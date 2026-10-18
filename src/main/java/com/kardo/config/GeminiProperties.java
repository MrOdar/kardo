package com.kardo.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cliente de Gemini 2.0 Flash (prefijo {@code kardo.gemini}), usado en el Sprint 3. */
@ConfigurationProperties(prefix = "kardo.gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String model,
        Duration connectTimeout,
        Duration readTimeout) {
}
