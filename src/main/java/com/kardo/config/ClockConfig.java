package com.kardo.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * El reloj como dependencia y no como llamada estatica.
 *
 * <p>Los frenos se miden en ventanas de tiempo, y una prueba honesta de un
 * freno tiene que poder adelantar el reloj. Con {@code Instant.now()} incrustado
 * solo quedaria dormir el hilo de prueba durante la ventana entera, que es como
 * una suite pasa de segundos a minutos.
 */
@Configuration
public class ClockConfig {

    @Bean
    @Primary
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
