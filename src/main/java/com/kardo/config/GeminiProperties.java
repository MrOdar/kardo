package com.kardo.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cliente de Gemini 2.0 Flash (prefijo {@code kardo.gemini}).
 *
 * @param model          modelo primario
 * @param fallbackModels modelos de respaldo ante 429 (cuota) o 503 (sobrecarga)
 *                       del tier gratuito, en orden de preferencia
 * @param maxAttempts    intentos totales, repartidos entre los modelos disponibles
 */
@ConfigurationProperties(prefix = "kardo.gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String model,
        List<String> fallbackModels,
        int maxAttempts,
        Duration connectTimeout,
        Duration readTimeout) {

    /** Modelo primario seguido de los respaldos, sin duplicados. */
    public List<String> modelChain() {
        List<String> chain = new ArrayList<>();
        chain.add(model);
        if (fallbackModels != null) {
            fallbackModels.stream()
                    .filter(candidate -> candidate != null && !candidate.isBlank())
                    .map(String::trim)
                    .filter(candidate -> !chain.contains(candidate))
                    .forEach(chain::add);
        }
        return List.copyOf(chain);
    }
}
