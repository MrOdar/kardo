package com.kardo.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de las pruebas de integracion contra un PostgreSQL real.
 *
 * <p>El contenedor ejecuta **los mismos scripts de `sql/`** que se aplican en
 * Supabase, no un esquema paralelo escrito para los tests. Esa es toda la
 * gracia: si alguien edita un procedimiento o rompe un invariante, estas pruebas
 * lo descubren en el build en lugar de descubrirlo el operario en la tienda.
 *
 * <p>El contenedor es uno solo para toda la suite (patron singleton): arrancar
 * PostgreSQL por clase multiplicaria el tiempo de build sin aportar aislamiento,
 * que aqui se consigue dando a cada prueba sus propios datos.
 */
@SpringBootTest
@ActiveProfiles("it")
@DockerAvailable
public abstract class PostgresIntegrationTest {

    /** La misma version mayor que sirve Supabase hoy. */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("kardo")
                    .withUsername("kardo")
                    .withPassword("kardo");

    /** Orden real de aplicacion; el 01 es destructivo y aqui parte de una base vacia. */
    private static final List<String> SCRIPTS = List.of(
            "sql/01_schema_ddl_3nf.sql",
            "sql/02_procedures_and_triggers.sql",
            "sql/03_users_and_auth.sql",
            "sql/04_fefo_sale_hardening.sql",
            "sql/05_waste_workflow.sql",
            "sql/06_upload_audit.sql",
            "sql/07_upload_rollback.sql",
            "sql/08_waste_segregation.sql",
            "sql/09_expiry_alerts.sql",
            "sql/10_vision_quota.sql",
            "sql/11_batch_markdown.sql",
            "sql/12_risk_history.sql",
            "sql/13_user_stores.sql",
            "sql/14_supplier_returns.sql",
            "sql/15_tenant_scoped_resolution.sql");

    static {
        if (DockerEnvironment.isPresent()) {
            POSTGRES.start();
            applySchema();
        }
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Aplica los scripts antes de que Spring levante el contexto.
     *
     * <p>PostgreSQL acepta varias sentencias en un solo {@code execute}, incluidos
     * los bloques {@code $$} de los procedimientos, de modo que no hace falta
     * trocear los archivos y arriesgarse a partir un cuerpo PL/pgSQL por la mitad.
     */
    private static void applySchema() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            for (String script : SCRIPTS) {
                String sql = Files.readString(Path.of(script), StandardCharsets.UTF_8);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo aplicar el esquema de pruebas: " + ex.getMessage(), ex);
        }
    }
}
