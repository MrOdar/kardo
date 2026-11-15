package com.kardo.dto;

import java.util.List;

/**
 * Resultado de una ingesta.
 *
 * @param totalRowsProcessed filas de datos leidas (excluye la cabecera)
 * @param totalInserted      filas persistidas en {@code products} e {@code inventory_batches}
 * @param totalQuarantined   filas rechazadas por la compuerta de calidad
 * @param quarantinedRows    detalle de las filas rechazadas, para corregir en el navegador
 * @param uploadId           asiento de auditoria de la carga; nulo cuando no hubo
 *                           archivo, como en el reenvio de filas corregidas
 */
public record IngestSummaryResponseDto(
        int totalRowsProcessed,
        int totalInserted,
        int totalQuarantined,
        List<QuarantineRowDto> quarantinedRows,
        Long uploadId) {

    /** Resultado sin archivo detras: correcciones reenviadas desde el navegador. */
    public IngestSummaryResponseDto(int totalRowsProcessed, int totalInserted,
                                    int totalQuarantined, List<QuarantineRowDto> quarantinedRows) {
        this(totalRowsProcessed, totalInserted, totalQuarantined, quarantinedRows, null);
    }
}
