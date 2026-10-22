package com.kardo.dto;

import java.util.List;

/**
 * Resultado de persistir la recepcion.
 *
 * @param receiptId             cabecera creada en {@code goods_receipt_headers}
 * @param itemsPersisted        lineas en {@code goods_receipt_items}
 * @param batchesCreated        lotes en {@code inventory_batches} (solo las
 *                              lineas con cantidad recibida mayor que 0)
 * @param discrepancyLineIds    lineas donde lo recibido no coincide con lo
 *                              facturado: base del reclamo al proveedor
 */
public record ReceiptConfirmationResponseDto(
        long receiptId,
        int itemsPersisted,
        int batchesCreated,
        List<Integer> discrepancyLineIds) {
}
