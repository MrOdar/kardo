package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingesta en streaming de Excel (prefijo {@code kardo.ingest}).
 *
 * @param batchSize      filas por micro-lote de {@code JdbcTemplate.batchUpdate}
 * @param maxCorruptRows umbral del Circuit Breaker de calidad (HTTP 422)
 * @param maxRows        tope de filas de datos por archivo. No lo pide la
 *                       memoria -la ingesta es constante- sino el tiempo: un
 *                       .xlsx de 10 MB, que es lo que admite el multipart,
 *                       cabe comprimido y puede traer millones de filas, y
 *                       cada una son escrituras en PostgreSQL dentro de una
 *                       sola transaccion. Sin tope, una carga ocupa una de las
 *                       cinco conexiones del pool y un candado durante minutos,
 *                       y con tres a la vez el resto de las tiendas deja de
 *                       poder vender. 200.000 esta un orden de magnitud por
 *                       encima del inventario de un minimarket.
 */
@ConfigurationProperties(prefix = "kardo.ingest")
public record IngestProperties(int batchSize, int maxCorruptRows, int maxRows) {
}
