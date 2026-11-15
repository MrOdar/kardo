package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Entity matching de SKUs (prefijo {@code kardo.matching}).
 *
 * @param minScore umbral de {@code word_similarity}; por debajo se devuelve
 *                 {@code product_id = null} para que el operario decida si es
 *                 un producto nuevo o a que SKU corresponde
 */
@ConfigurationProperties(prefix = "kardo.matching")
public record MatchingProperties(double minScore) {
}
