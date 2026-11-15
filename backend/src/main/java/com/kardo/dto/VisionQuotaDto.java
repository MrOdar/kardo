package com.kardo.dto;

import java.time.LocalDate;

/**
 * Cuanto escaneo le queda a la tienda hoy.
 *
 * <p>Se expone el tope del despliegue ademas del de la tienda porque explican
 * cosas distintas cuando el boton deja de funcionar: con el propio agotado se
 * puede pedir mas cupo, con el global no hay nada que hacer hasta mañana.
 *
 * @param day dia de Lima al que corresponde el consumo, no el del servidor
 */
public record VisionQuotaDto(
        LocalDate day,
        int usedToday,
        int perStoreLimit,
        int remainingToday,
        int globalUsedToday,
        int globalLimit) {
}
