package com.kardo;

import com.kardo.config.GeminiProperties;
import com.kardo.config.IngestProperties;
import com.kardo.config.KeepAliveProperties;
import com.kardo.config.MatchingProperties;
import com.kardo.config.SecurityProperties;
import com.kardo.config.ThrottleProperties;
import com.kardo.config.VisionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punto de entrada de Kardo.
 *
 * <p>El scheduler se habilita aqui para sostener el heartbeat diario contra
 * Supabase (ver {@link com.kardo.scheduler.DatabaseKeepAliveScheduler}).
 */
/*
 * Se excluye UserDetailsServiceAutoConfiguration porque, al no haber ningun
 * UserDetailsService propio, Boot sembraba un usuario 'user' en memoria con
 * contraseña aleatoria y la imprimia en el arranque. Hoy no es explotable
 * (httpBasic y formLogin estan deshabilitados y la identidad sale del JWT),
 * pero es una credencial que nadie creo a proposito: si manana alguien
 * habilitase basic auth, entraria con ella.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({
        SecurityProperties.class,
        VisionProperties.class,
        MatchingProperties.class,
        KeepAliveProperties.class,
        IngestProperties.class,
        GeminiProperties.class,
        ThrottleProperties.class
})
public class KardoApplication {

    public static void main(String[] args) {
        SpringApplication.run(KardoApplication.class, args);
    }
}
