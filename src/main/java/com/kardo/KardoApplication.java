package com.kardo;

import com.kardo.config.GeminiProperties;
import com.kardo.config.IngestProperties;
import com.kardo.config.KeepAliveProperties;
import com.kardo.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punto de entrada de Kardo.
 *
 * <p>El scheduler se habilita aqui para sostener el heartbeat diario contra
 * Supabase (ver {@link com.kardo.scheduler.DatabaseKeepAliveScheduler}).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        SecurityProperties.class,
        KeepAliveProperties.class,
        IngestProperties.class,
        GeminiProperties.class
})
public class KardoApplication {

    public static void main(String[] args) {
        SpringApplication.run(KardoApplication.class, args);
    }
}
