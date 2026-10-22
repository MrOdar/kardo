package com.kardo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Linea de la guia ya cruzada contra el catalogo.
 *
 * @param ocrLineId      indice estable de la linea; el SPA lo devuelve en la
 *                       confirmacion para no depender del orden
 * @param productId      SKU sugerido, o {@code null} si no hubo match por
 *                       encima del umbral: entonces el operario lo asigna o
 *                       crea un producto nuevo
 * @param matchedName    nombre del producto sugerido, para mostrarlo al lado
 *                       del texto del documento
 * @param matchScore     {@code word_similarity} entre 0 y 1; es el nivel de
 *                       confianza que se pinta en la interfaz
 */
public record ConciliationLineDto(
        int ocrLineId,
        String descriptionRaw,
        BigDecimal invoicedQty,
        BigDecimal unitCost,
        String batchCode,
        LocalDate expirationDate,
        Long productId,
        String matchedName,
        Double matchScore) {
}
