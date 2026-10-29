package com.kardo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de registrar ventas desde un Excel.
 *
 * <p>Lleva unidades y facturacion ademas del recuento de filas porque es lo que
 * el operario compara contra la cinta de la caja: "registre 14 lineas, 120
 * unidades, S/ 487.50". Un "14 filas procesadas" no se puede cuadrar con nada.
 *
 * @param totalRowsProcessed filas con contenido que trae la hoja
 * @param totalRegistered    ventas que de verdad descontaron stock
 * @param totalQuarantined   filas devueltas para corregir
 * @param totalUnits         unidades que salieron del inventario
 * @param revenue            a precio de cada lote, con su rebaja si la tenia
 * @param quarantinedRows    el detalle de lo que no entro, y por que
 * @param uploadId           asiento de auditoria de esta carga
 */
public record SalesIngestSummaryDto(
        int totalRowsProcessed,
        int totalRegistered,
        int totalQuarantined,
        int totalUnits,
        BigDecimal revenue,
        List<QuarantineRowDto> quarantinedRows,
        Long uploadId) {

    /** Antes de que exista el asiento: el servicio de carga lo añade al final. */
    public SalesIngestSummaryDto(int totalRowsProcessed, int totalRegistered, int totalQuarantined,
                                 int totalUnits, BigDecimal revenue, List<QuarantineRowDto> quarantinedRows) {
        this(totalRowsProcessed, totalRegistered, totalQuarantined, totalUnits, revenue, quarantinedRows, null);
    }
}
