package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingesta en streaming de Excel (prefijo {@code kardo.ingest}).
 *
 * @param batchSize      filas por micro-lote de {@code JdbcTemplate.batchUpdate}
 * @param maxCorruptRows umbral del Circuit Breaker de calidad (HTTP 422)
 */
@ConfigurationProperties(prefix = "kardo.ingest")
public record IngestProperties(int batchSize, int maxCorruptRows) {
}
