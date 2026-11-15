package com.kardo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de una salida FEFO.
 *
 * @param consumedBatches lotes tocados, del mas proximo a vencer al mas lejano
 * @param margin          ingreso menos costo real de los lotes consumidos
 */
public record SaleResponseDto(
        String sku,
        String productName,
        int quantity,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal margin,
        List<SaleLineDto> consumedBatches) {
}
