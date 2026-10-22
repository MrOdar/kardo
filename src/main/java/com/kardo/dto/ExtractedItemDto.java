package com.kardo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Linea de la guia leida por la IA, todavia sin cruzar contra el catalogo.
 *
 * @param descriptionRaw  texto literal del documento, p. ej. "CERV PILSEN 650"
 * @param batchCode       lote impreso, si figura en el documento
 * @param expirationDate  vencimiento impreso, si figura en el documento
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedItemDto(
        String descriptionRaw,
        BigDecimal invoicedQty,
        BigDecimal unitCost,
        String batchCode,
        LocalDate expirationDate) {
}
