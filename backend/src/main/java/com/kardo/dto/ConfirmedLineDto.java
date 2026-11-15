package com.kardo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Linea confirmada por el operario.
 *
 * <p>{@code productId} ya no admite nulos: si la IA no mapeo la linea, el
 * operario asigna el SKU o crea el producto antes de confirmar.
 *
 * <p>{@code receivedQty} puede ser 0 cuando el proveedor factura algo que no
 * llego: la linea se registra para el reclamo (la columna generada
 * {@code discrepancy_qty} lo deja en evidencia) pero no se crea lote.
 */
public record ConfirmedLineDto(
        int ocrLineId,

        @NotNull Long productId,

        @NotNull @DecimalMin("0.00") BigDecimal invoicedQty,

        @NotNull @DecimalMin("0.00") BigDecimal receivedQty,

        @NotNull @DecimalMin("0.00") BigDecimal unitCost,

        @Size(max = 80) String batchCode,

        LocalDate manufacturingDate,

        /** Obligatoria cuando {@code receivedQty > 0}: el lote la exige NOT NULL. */
        LocalDate expirationDate) {
}
