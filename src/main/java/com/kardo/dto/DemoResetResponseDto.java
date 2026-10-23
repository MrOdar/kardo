package com.kardo.dto;

/**
 * Respuesta del reinicio de la demo.
 *
 * @param token token JWT de la tienda demo, listo para las demas llamadas
 * @param expiresInSeconds vigencia del token
 */
public record DemoResetResponseDto(
        String message,
        long storeId,
        String token,
        long expiresInSeconds,
        DemoDashboardDto dashboard) {
}
