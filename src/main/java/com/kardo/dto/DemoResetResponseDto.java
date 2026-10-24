package com.kardo.dto;

/**
 * Respuesta del reinicio de la demo.
 *
 * @param session   sesion del usuario demo, ya lista para el resto de llamadas
 * @param dashboard estado del semaforo FEFO justo despues de resembrar
 */
public record DemoResetResponseDto(
        String message,
        long storeId,
        SessionDto session,
        DemoDashboardDto dashboard) {
}
