package com.kardo.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parametros de JWT y CORS (prefijo {@code kardo.security}). */
@ConfigurationProperties(prefix = "kardo.security")
public record SecurityProperties(Jwt jwt, Cors cors) {

    public record Jwt(String secret, String issuer, Duration expiration) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
