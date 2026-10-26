package com.kardo.config;

import com.kardo.diagnostics.MeasuredDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

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

    /**
     * Envuelve el {@code DataSource} para cronometrar JDBC.
     *
     * <p>Se hace con un {@code BeanPostProcessor} y no declarando el bean para
     * no perder la autoconfiguracion de Hikari, que lee una docena de
     * propiedades. Aqui solo se envuelve lo que Spring Boot ya construyo.
     *
     * <p>{@code MeasuredDataSource} extiende {@code DelegatingDataSource}, que
     * es lo que permite que las metricas del pool sigan funcionando: Boot
     * desenvuelve hasta encontrar el {@code HikariDataSource} de dentro.
     */
    @Bean
    static BeanPostProcessor dataSourceInstrumentation(ObjectProvider<MeterRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof MeasuredDataSource)) {
                    MeterRegistry meterRegistry = registry.getIfAvailable();
                    if (meterRegistry != null) {
                        return new MeasuredDataSource(dataSource, meterRegistry);
                    }
                }
                return bean;
            }
        };
    }
}
