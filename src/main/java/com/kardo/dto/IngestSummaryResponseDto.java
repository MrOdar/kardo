package com.kardo.dto;

import java.util.List;

/**
 * Resultado de una ingesta.
 *
 * @param totalRowsProcessed filas de datos leidas (excluye la cabecera)
 * @param totalInserted      filas persistidas en {@code products} e {@code inventory_batches}
 * @param totalQuarantined   filas rechazadas por la compuerta de calidad
 * @param quarantinedRows    detalle de las filas rechazadas, para corregir en el navegador
 */
public record IngestSummaryResponseDto(
        int totalRowsProcessed,
        int totalInserted,
        int totalQuarantined,
        List<QuarantineRowDto> quarantinedRows) {
}
