package com.kardo.dto;

import java.math.BigDecimal;

/**
 * Metricas del semaforo FEFO de la tienda demo.
 *
 * @param activeBatches        lotes activos con stock
 * @param redAlertBatches      vencen dentro de 48 horas
 * @param yellowAlertBatches   vencen entre 3 y 5 dias
 * @param expiredBatches       ya vencidos y aun en estanteria
 * @param valueAtRisk          dinero inmovilizado en lotes en alerta
 * @param inventoryValue       valor total del inventario activo
 * @param linesWithDiscrepancy lineas de guia donde lo recibido no cuadra con lo facturado
 */
public record DemoDashboardDto(
        long storeId,
        int activeBatches,
        int redAlertBatches,
        int yellowAlertBatches,
        int expiredBatches,
        BigDecimal valueAtRisk,
        BigDecimal inventoryValue,
        int linesWithDiscrepancy) {
}
