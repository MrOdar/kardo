package com.kardo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Merma registrada.
 *
 * @param status         PENDING_APPROVAL, APPROVED o REJECTED
 * @param totalLoss      perdida monetaria, calculada por la propia base
 * @param authorizedBy   gerente que la resolvio; nulo mientras esta pendiente
 */
public record WasteEntryDto(
        long wasteId,
        long batchId,
        String batchCode,
        String sku,
        String productName,
        LocalDate expirationDate,
        int quantity,
        BigDecimal unitCost,
        BigDecimal totalLoss,
        String reason,
        String status,
        String operatorId,
        String authorizedBy,
        Instant loggedAt) {
}
