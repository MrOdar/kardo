package com.kardo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Canje al proveedor.
 *
 * @param status    PENDING, CREDITED o REFUSED
 * @param recovered costo que se recupera si el proveedor abona; lo calcula la
 *                  propia base, igual que la perdida de una merma, para que no
 *                  pueda escribirse una cifra que no cuadre con las unidades
 */
public record SupplierReturnDto(
        long returnId,
        long batchId,
        String batchCode,
        String sku,
        String productName,
        LocalDate expirationDate,
        String supplierName,
        int quantity,
        BigDecimal unitCost,
        BigDecimal recovered,
        String status,
        String declaredBy,
        String resolvedBy,
        Instant declaredAt) {
}
