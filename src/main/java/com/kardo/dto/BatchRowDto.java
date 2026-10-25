package com.kardo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lote tal como se muestra en la pantalla de inventario.
 *
 * @param daysToExpiry negativo si ya vencio; es lo que pinta el semaforo
 * @param quarantinedQty unidades inmovilizadas por una merma sin resolver
 */
public record BatchRowDto(
        long batchId,
        String sku,
        String productName,
        String category,
        String batchCode,
        LocalDate expirationDate,
        int daysToExpiry,
        int currentQty,
        int quarantinedQty,
        BigDecimal unitCost,
        BigDecimal value,
        String status) {
}
