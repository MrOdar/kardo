package com.kardo.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.vision.GeminiVisionClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El veredicto: a quien hay que mirar cuando alguien dice que va lento.
 *
 * <p>Es la parte del diagnostico que de verdad se usa, porque nadie va a leer
 * un histograma durante una incidencia.
 */
class DiagnosticsServiceTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final DiagnosticsService service = new DiagnosticsService(registry);

    @Test
    @DisplayName("Sin muestras lo dice, en vez de dar a entender que todo vuela")
    void sinMuestras() {
        assertThat(service.report().verdict()).contains("Sin muestras");
        assertThat(service.report().suspects())
                .extracting("p95Millis")
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("Una vision lenta se señala a ella, no a la base")
    void visionLenta() {
        record(GeminiVisionClient.VISION_TIMER, Duration.ofSeconds(9), 30);
        record(MeasuredDataSource.STATEMENT_TIMER, Duration.ofMillis(20), 30);

        var report = service.report();

        assertThat(report.verdict()).startsWith("Gemini");
        assertThat(report.verdict()).contains("respaldos");
    }

    @Test
    @DisplayName("Una base lenta con el pool holgado se señala a Supabase")
    void baseLenta() {
        record(MeasuredDataSource.STATEMENT_TIMER, Duration.ofMillis(900), 40);

        var report = service.report();

        assertThat(report.verdict()).startsWith("Supabase");
        assertThat(report.verdict()).contains("no la espera por conexion");
    }

    /**
     * Una espera por conexion se parece a una base lenta desde fuera: si el
     * veredicto mandara a mirar Supabase, se depuraria el sitio equivocado.
     */
    @Test
    @DisplayName("Con hilos esperando conexion, el culpable es el pool aunque la base tambien vaya lenta")
    void elPoolVaPrimero() {
        record(MeasuredDataSource.STATEMENT_TIMER, Duration.ofMillis(900), 40);
        registry.gauge("hikaricp.connections.pending", new AtomicInteger(3));

        assertThat(service.report().verdict()).startsWith("El pool");
    }

    /**
     * Abrir la primera conexion contra Supabase cuesta cien milisegundos de TCP
     * y TLS. Si el veredicto mirase el maximo, ese pico de arranque acusaria al
     * pool para siempre en un proceso que no se reinicia.
     */
    @Test
    @DisplayName("El coste de abrir la primera conexion no condena al pool")
    void elPicoDeArranqueNoCuenta() {
        Timer acquire = Timer.builder(MeasuredDataSource.CONNECTION_TIMER)
                .publishPercentiles(0.5, 0.95).register(registry);
        acquire.record(Duration.ofMillis(103));
        for (int i = 0; i < 200; i++) {
            acquire.record(Duration.ofMillis(1));
        }
        record(MeasuredDataSource.STATEMENT_TIMER, Duration.ofMillis(25), 50);

        var report = service.report();

        assertThat(report.pool().acquireMaxMillis()).isGreaterThanOrEqualTo(100);
        assertThat(report.pool().acquireTypicalMillis()).isLessThan(20);
        assertThat(report.verdict()).doesNotContain("El pool");
    }

    @Test
    @DisplayName("Cuando nada destaca, lo dice con los numeros que lo sostienen")
    void nadaDestaca() {
        record(MeasuredDataSource.STATEMENT_TIMER, Duration.ofMillis(25), 50);
        record(GeminiVisionClient.VISION_TIMER, Duration.ofSeconds(2), 5);

        var report = service.report();

        assertThat(report.verdict()).startsWith("Nada destaca");
        assertThat(report.suspects()).hasSize(2);
        assertThat(report.suspects().get(0).calls()).isEqualTo(5);
        assertThat(report.suspects().get(1).calls()).isEqualTo(50);
    }

    /** La vision se etiqueta por modelo; el panel debe sumar la cadena entera. */
    @Test
    @DisplayName("Los intentos con modelos de respaldo se suman al mismo sospechoso")
    void sumaLaCadenaDeModelos() {
        Timer.builder(GeminiVisionClient.VISION_TIMER)
                .tag("model", "gemini-3.8-flash").tag("outcome", "429")
                .publishPercentiles(0.5, 0.95).register(registry)
                .record(Duration.ofSeconds(1));
        Timer.builder(GeminiVisionClient.VISION_TIMER)
                .tag("model", "gemini-3.5-flash").tag("outcome", "ok")
                .publishPercentiles(0.5, 0.95).register(registry)
                .record(Duration.ofSeconds(4));

        assertThat(service.report().suspects().get(0).calls()).isEqualTo(2);
        assertThat(service.report().suspects().get(0).maxMillis()).isGreaterThanOrEqualTo(4000);
    }

    @Test
    @DisplayName("La memoria del contenedor viaja en el informe: en 512 MB es sospechosa habitual")
    void memoria() {
        new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(registry);

        assertThat(service.report().heapUsedMb()).isPositive();
    }

    private void record(String meter, Duration each, int times) {
        Timer timer = Timer.builder(meter).publishPercentiles(0.5, 0.95).register(registry);
        for (int i = 0; i < times; i++) {
            timer.record(each);
        }
    }
}
