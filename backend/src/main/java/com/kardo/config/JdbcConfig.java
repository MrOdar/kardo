package com.kardo.config;

import com.kardo.diagnostics.MeasuredDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Instrumentacion del acceso a datos.
 *
 * <p><b>El dialecto ya no se declara aqui.</b> Lo hacia un bean propio llamado
 * `jdbcDialect`, por la misma razon que hoy se fija con
 * `spring.data.jdbc.dialect`: la autoconfiguracion lo resolvia abriendo una
 * conexion durante el refresco del contexto, de modo que una base pausada
 * impedia que el contenedor levantara y, con el, que respondiera
 * {@code /api/v1/ping}, que es justo el endpoint encargado de mantenerlo vivo.
 *
 * <p>Spring Boot 4 registra su propio bean con ese mismo nombre, asi que el
 * nuestro dejo de ganar y paso a chocar con el
 * ({@code BeanDefinitionOverrideException}). La propiedad hace lo mismo y esta
 * sostenida por el framework, que es mejor sitio para esto que un bean nuestro.
 */
@Configuration
public class JdbcConfig {

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
