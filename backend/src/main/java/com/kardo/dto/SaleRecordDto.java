package com.kardo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una venta registrada, tal como se puede mirar despues.
 *
 * <p>Lleva el lote y su vencimiento porque es lo unico que hace visible el
 * FEFO: sin esa columna, una venta es "salieron 4 unidades" y no se puede
 * comprobar que salieron del lote que vencia antes.
 *
 * @param price      lo que cobro la caja: el precio del lote, con su rebaja si
 *                   la tenia, no el del catalogo
 * @param unitCost   costo del lote consumido, no un promedio del producto
 * @param margin     importe menos costo, de ESTA venta
 * @param discounted el precio cobrado estuvo por debajo del de catalogo
 */
public record SaleRecordDto(
        long saleId,
        Instant soldAt,
        String sku,
        String productName,
        String category,
        String batchCode,
        LocalDate expiresOn,
        int quantity,
        BigDecimal price,
        BigDecimal unitCost,
        BigDecimal amount,
        BigDecimal margin,
        boolean discounted,
        String operator) {
}
