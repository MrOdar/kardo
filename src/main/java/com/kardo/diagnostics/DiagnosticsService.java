package com.kardo.diagnostics;

import com.kardo.dto.DiagnosticsReportDto;
import com.kardo.dto.DiagnosticsReportDto.PoolDto;
import com.kardo.dto.DiagnosticsReportDto.SuspectDto;
import com.kardo.vision.GeminiVisionClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.search.Search;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Traduce las metricas a la unica pregunta que se hace en produccion.
 *
 * <p>Cuando alguien dice que "va lento", nadie va a leer un histograma: hay que
 * poder responder si el problema es Gemini, Supabase o el pool. Esto compara
 * los tres y lo dice en una frase.
 *
 * <p>Los umbrales son del proyecto, no universales: el escaneo de una guia son
 * segundos por naturaleza y una consulta al inventario son milisegundos, de modo
 * que un mismo numero no sirve para juzgar a los dos.
 */
@Service
public class DiagnosticsService {

    /** Por encima de esto, la vision esta peor de lo que ya es de por si. */
    private static final long VISION_SLOW_MS = 6_000;

    /** Supabase responde en decenas de milisegundos; cientos son ya una señal. */
    private static final long DATABASE_SLOW_MS = 300;

    /**
     * Esperar por una conexion es siempre sintoma: el pool tiene cinco y la
     * tienda no hace cinco cosas a la vez. Si hay espera, algo las retiene.
     */
    private static final long POOL_SLOW_MS = 50;

    private final MeterRegistry registry;

    public DiagnosticsService(MeterRegistry registry) {
        this.registry = registry;
    }

    public DiagnosticsReportDto report() {
        SuspectDto vision = suspect("Gemini (vision)", GeminiVisionClient.VISION_TIMER,
                "Cada escaneo de guia; incluye los reintentos con modelos de respaldo.");
        SuspectDto database = suspect("Supabase (SQL)", MeasuredDataSource.STATEMENT_TIMER,
                "Tiempo de ejecucion de la sentencia, sin la espera por conexion.");

        PoolDto pool = pool();
        long heapUsed = gauge("jvm.memory.used", "area", "heap") / (1024 * 1024);
        long heapMax = gauge("jvm.memory.max", "area", "heap") / (1024 * 1024);

        return new DiagnosticsReportDto(
                verdict(vision, database, pool),
                List.of(vision, database),
                pool,
                heapUsed,
                heapMax);
    }

    /**
     * El veredicto, en orden de lo que conviene mirar primero.
     *
     * <p>El pool va antes que la base aunque parezca menos probable: una espera
     * por conexion se <em>parece</em> a una base lenta desde fuera, y mirar
     * primero a Supabase manda a depurar al sitio equivocado.
     */
    private String verdict(SuspectDto vision, SuspectDto database, PoolDto pool) {
        // Se juzga por la espera TIPICA y no por el maximo: abrir la primera
        // conexion a Supabase cuesta cien milisegundos de TCP y TLS, y ese pico
        // de arranque acusaria al pool para siempre en un proceso que no se
        // reinicia. El maximo se muestra como contexto, no como prueba.
        if (pool.pendingThreads() > 0 || pool.acquireTypicalMillis() > POOL_SLOW_MS) {
            return "El pool: hay " + pool.pendingThreads() + " hilo(s) esperando conexion y la espera tipica es de "
                    + pool.acquireTypicalMillis() + " ms. Antes de mirar a Supabase, revisa si algo retiene conexiones.";
        }
        if (slowerThan(vision, VISION_SLOW_MS)) {
            return "Gemini: p95 de " + vision.p95Millis() + " ms por escaneo. "
                    + "Mira si el modelo primario esta cayendo a los respaldos.";
        }
        if (slowerThan(database, DATABASE_SLOW_MS)) {
            return "Supabase: p95 de " + database.p95Millis() + " ms por sentencia, con el pool holgado. "
                    + "Es la base o la red hasta ella, no la espera por conexion.";
        }
        if (vision.calls() == 0 && database.calls() == 0) {
            return "Sin muestras todavia: usa la aplicacion y vuelve a consultar.";
        }
        return "Nada destaca: el pool no espera, la base responde en " + describe(database)
                + " y de la vision " + describe(vision) + ".";
    }

    private static boolean slowerThan(SuspectDto suspect, long threshold) {
        return suspect.p95Millis() != null && suspect.p95Millis() > threshold;
    }

    private static String describe(SuspectDto suspect) {
        return suspect.p95Millis() == null
                ? "no hay muestras suficientes"
                : suspect.p95Millis() + " ms (p95)";
    }

    /**
     * Suma todas las series de un timer.
     *
     * <p>La vision esta etiquetada por modelo y resultado; aqui interesa el
     * conjunto, de modo que se agregan. El maximo es el de la peor serie, que es
     * lo que de verdad sufrio alguien.
     */
    private SuspectDto suspect(String name, String meter, String detail) {
        List<Timer> timers = new ArrayList<>(Search.in(registry).name(meter).timers());

        if (timers.isEmpty()) {
            return new SuspectDto(name, 0, null, null, 0, detail);
        }

        long calls = timers.stream().mapToLong(Timer::count).sum();
        long max = Math.round(timers.stream()
                .mapToDouble(timer -> timer.max(TimeUnit.MILLISECONDS)).max().orElse(0));

        return new SuspectDto(name, calls,
                percentile(timers, 0.5), percentile(timers, 0.95), max, detail);
    }

    /**
     * Percentil de la serie con mas muestras.
     *
     * <p>Promediar percentiles de series distintas no significa nada
     * estadisticamente; se toma el de la serie que domina el trafico, que es la
     * que describe lo que vive el usuario tipico.
     */
    private Long percentile(List<Timer> timers, double percentile) {
        return timers.stream()
                .max((a, b) -> Long.compare(a.count(), b.count()))
                .map(timer -> {
                    for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
                        if (Math.abs(value.percentile() - percentile) < 0.001) {
                            return Math.round(value.value(TimeUnit.MILLISECONDS));
                        }
                    }
                    return null;
                })
                .orElse(null);
    }

    /**
     * Estado del pool.
     *
     * <p>Lo publica Hikari por su cuenta en cuanto existe un registro de
     * metricas; no hay que instrumentar nada, solo saber donde mirar.
     */
    private PoolDto pool() {
        // La espera se mide en MeasuredDataSource y no se toma de Hikari porque
        // es la misma medicion que alimenta el reparto por peticion: un solo
        // numero para las dos vistas evita que digan cosas distintas.
        Timer acquire = Search.in(registry).name(MeasuredDataSource.CONNECTION_TIMER).timer();
        Long typical = acquire == null ? null : percentile(List.of(acquire), 0.5);

        return new PoolDto(
                (int) gauge("hikaricp.connections.active"),
                (int) gauge("hikaricp.connections.idle"),
                (int) gauge("hikaricp.connections.max"),
                (int) gauge("hikaricp.connections.pending"),
                typical == null ? 0 : typical,
                acquire == null ? 0 : Math.round(acquire.max(TimeUnit.MILLISECONDS)));
    }

    private double gauge(String name) {
        Gauge gauge = Search.in(registry).name(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private long gauge(String name, String tag, String value) {
        return Search.in(registry).name(name).tag(tag, value).gauges().stream()
                .mapToLong(gauge -> (long) gauge.value())
                .sum();
    }
}
