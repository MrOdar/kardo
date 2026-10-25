package com.kardo.dto;

import com.kardo.inventory.MarkdownAction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una alerta accionable: el lote, lo que esta en juego y que hacer.
 *
 * @param decidedStep    peldaño sobre el que ya se decidio antes, o nulo si
 *                       nadie habia mirado este lote; viaja a la interfaz para
 *                       poder decir "ya lo rebajaste al 20%, ahora toca 40%"
 */
public record ExpiryAlertDto(
        long batchId,
        String sku,
        String productName,
        String batchCode,
        LocalDate expirationDate,
        int daysToExpiry,
        int sellableQty,
        BigDecimal unitCost,
        BigDecimal retailPrice,

        MarkdownAction action,
        int stepPercent,
        int discountPercent,
        BigDecimal suggestedPrice,
        BigDecimal marginSacrificed,
        BigDecimal valueAtRisk,
        String rationale,

        String previousDecision,
        Integer decidedStep,
        String decidedBy,
        Instant decidedAt) {
}
