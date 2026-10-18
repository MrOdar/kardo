package com.kardo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.relational.core.dialect.Dialect;

/**
 * Fija el dialecto relacional en lugar de dejar que Spring Data JDBC lo deduzca.
 *
 * <p>La autoconfiguracion resuelve el dialecto abriendo una conexion durante el
 * refresco del contexto: si Supabase esta pausado o la red falla en el arranque,
 * el contenedor de Render no levanta y ni siquiera responde {@code /api/v1/ping},
 * que es justo el endpoint que debe mantenerlo vivo. El motor es siempre
 * PostgreSQL 15, asi que declararlo elimina ese viaje de red y hace el arranque
 * independiente de la disponibilidad de la base de datos.
 */
@Configuration
public class JdbcConfig {

    @Bean
    Dialect jdbcDialect() {
        return JdbcPostgresDialect.INSTANCE;
    }
}
