package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.diagnostics.DiagnosticsService;
import com.kardo.diagnostics.MeasuredDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Que la instrumentacion este de verdad puesta en el contexto real.
 *
 * <p>Es el riesgo concreto de medir sustituyendo un bean de la
 * autoconfiguracion: si Spring Boot ganase la partida y creara su propio
 * {@code JdbcTemplate}, todo seguiria funcionando y el panel de diagnostico
 * diria tranquilamente que no hay consultas. Un fallo silencioso que solo se
 * descubre el dia que hace falta.
 */
class DiagnosticsIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private DiagnosticsService diagnosticsService;

    @Test
    @DisplayName("El DataSource del contexto es el que mide")
    void elDataSourceMedidoGana() {
        assertThat(jdbcTemplate.getDataSource()).isInstanceOf(MeasuredDataSource.class);
    }

    @Test
    @DisplayName("Cada sentencia suma al cronometro de Supabase")
    void cadaSentenciaCuenta() {
        long antes = count();

        jdbcTemplate.queryForObject("SELECT count(*) FROM products", Integer.class);
        jdbcTemplate.update("UPDATE products SET name = name WHERE 1 = 0");

        assertThat(count()).isEqualTo(antes + 2);
    }

    /** Un CALL pasa por otro embudo distinto al de una consulta normal. */
    @Test
    @DisplayName("Los procedimientos tambien se miden")
    void losProcedimientosCuentan() {
        long antes = count();

        jdbcTemplate.execute("CALL sp_reset_demo_environment()");

        assertThat(count()).isGreaterThan(antes);
    }

    @Test
    @DisplayName("El pool se publica solo; el informe lo lee sin instrumentar nada")
    void elPoolSePublicaSolo() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        var report = diagnosticsService.report();

        assertThat(report.pool().max()).isPositive();
        assertThat(report.pool().pendingThreads()).isZero();
        assertThat(report.heapUsedMb()).isPositive();
    }

    @Test
    @DisplayName("Con la base respondiendo rapido, el veredicto no culpa a nadie")
    void veredictoSinCulpables() {
        for (int i = 0; i < 20; i++) {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        }

        assertThat(diagnosticsService.report().verdict())
                .doesNotContain("El pool")
                .doesNotContain("Supabase:");
    }

    private long count() {
        var timer = Search.in(registry).name(MeasuredDataSource.STATEMENT_TIMER).timer();
        return timer == null ? 0 : timer.count();
    }
}
