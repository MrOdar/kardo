package com.kardo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lote consumido por la venta, en el orden en que FEFO lo tomo.
 *
 * @param daysToExpiry dias que le quedaban al lote; es lo que hace visible que
 *                     el motor saco primero lo que estaba por vencer
 * @param lotUnitCogs  costo real del lote consumido, no un promedio
 */
public record SaleLineDto(
        long batchId,
        String batchCode,
        LocalDate expirationDate,
        int daysToExpiry,
        int quantity,
        BigDecimal unitRetailPrice,
        BigDecimal lotUnitCogs) {
}
