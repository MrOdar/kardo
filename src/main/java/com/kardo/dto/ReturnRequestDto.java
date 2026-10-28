package com.kardo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Peticion de canje.
 *
 * <p>No lleva proveedor: se deduce de la trazabilidad del lote. Pedirselo al
 * cliente permitiria reclamarle a quien no trajo la mercaderia.
 */
public record ReturnRequestDto(
        @NotNull Long batchId,
        @NotNull @Min(1) Integer quantity) {
}
