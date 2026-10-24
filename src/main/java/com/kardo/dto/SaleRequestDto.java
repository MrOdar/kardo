package com.kardo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Salida de mercaderia por venta.
 *
 * <p>No lleva tienda ni operario: ambos salen del token. El tope de unidades
 * evita que un dedo torpe en la caja dispare una salida absurda.
 */
public record SaleRequestDto(
        @NotBlank @Size(max = 50) String sku,
        @Min(1) @Max(10_000) int quantity) {
}
