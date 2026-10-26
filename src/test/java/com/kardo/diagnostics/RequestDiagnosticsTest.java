package com.kardo.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El reparto del tiempo de una peticion entre sus sospechosos.
 *
 * <p>Si este reparto miente, la linea del log manda a depurar al sitio
 * equivocado, que es peor que no tener la linea.
 */
class RequestDiagnosticsTest {

    @AfterEach
    void cleanUp() {
        RequestDiagnostics.end();
    }

    @Test
    @DisplayName("Fuera de una peticion no se acumula nada y nadie falla")
    void fueraDePeticion() {
        // El heartbeat diario consulta la base sin peticion detras
        RequestDiagnostics.recordDatabase(millis(40));
        RequestDiagnostics.recordConnectionWait(millis(2));

        assertThat(RequestDiagnostics.current()).isNull();
    }

    @Test
    @DisplayName("Suma consultas y escaneos por separado")
    void reparte() {
        var diagnostics = RequestDiagnostics.begin("abc123");

        RequestDiagnostics.recordDatabase(millis(30));
        RequestDiagnostics.recordDatabase(millis(12));
        RequestDiagnostics.recordVision(millis(4200));

        assertThat(diagnostics.requestId()).isEqualTo("abc123");
        assertThat(diagnostics.databaseCalls()).isEqualTo(2);
        assertThat(diagnostics.databaseMillis()).isEqualTo(42);
        assertThat(diagnostics.visionCalls()).isEqualTo(1);
        assertThat(diagnostics.visionMillis()).isEqualTo(4200);
    }

    /**
     * Es la distincion que da sentido a todo esto: desde fuera, esperar una
     * conexion y una base lenta se ven igual, pero una acusa al pool y la otra
     * a Supabase.
     */
    @Test
    @DisplayName("La espera por conexion no se suma a la base")
    void elPoolNoEsLaBase() {
        var diagnostics = RequestDiagnostics.begin("abc123");

        RequestDiagnostics.recordConnectionWait(millis(300));
        RequestDiagnostics.recordDatabase(millis(10));

        assertThat(diagnostics.poolMillis()).isEqualTo(300);
        assertThat(diagnostics.databaseMillis()).isEqualTo(10);
        assertThat(diagnostics.breakdown()).contains("pool 300 ms").contains("bd 10 ms");
    }

    /** El "resto" es la pista de que el problema no es ninguno de los tres. */
    @Test
    @DisplayName("La linea del log nombra lo que no se midio")
    void laLineaNombraElResto() {
        RequestDiagnostics.begin("abc123");
        RequestDiagnostics.recordDatabase(millis(5));

        assertThat(RequestDiagnostics.current().breakdown())
                .contains("bd 5 ms en 1 consulta")
                .contains("resto");
    }

    @Test
    @DisplayName("Sin consultas ni escaneos, la linea no inventa cero milisegundos de nada")
    void sinSospechosos() {
        RequestDiagnostics.begin("abc123");

        assertThat(RequestDiagnostics.current().breakdown())
                .doesNotContain("bd")
                .doesNotContain("vision")
                .doesNotContain("pool")
                .contains("resto");
    }

    /** Un pool sano no gasta nada; nombrarlo siempre seria ruido. */
    @Test
    @DisplayName("Una espera de cero no ensucia la linea")
    void elPoolSanoNoSeNombra() {
        RequestDiagnostics.begin("abc123");
        RequestDiagnostics.recordConnectionWait(1000);
        RequestDiagnostics.recordDatabase(millis(8));

        assertThat(RequestDiagnostics.current().breakdown()).doesNotContain("pool");
    }

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }
}
