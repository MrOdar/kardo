package com.kardo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Las ventas de una ventana, con su resumen.
 *
 * <p>Los totales NO se deducen de {@code sales}: esa lista viene acotada por un
 * {@code LIMIT}, de modo que sumarla daria el total de lo que cabe en pantalla
 * y no el de la ventana. Si las dos cifras pueden discrepar, hay que calcular la
 * buena aparte.
 *
 * @param windowDays ventana hacia atras sobre la que se cuenta
 * @param from       dia de la venta mas antigua de la ventana; nulo si no hay
 * @param sales      las mas recientes primero, acotadas
 */
public record SalesLedgerDto(
        int windowDays,
        int totalSales,
        int totalUnits,
        BigDecimal totalAmount,
        BigDecimal totalMargin,
        LocalDate from,
        LocalDate to,
        List<SaleRecordDto> sales) {
}
