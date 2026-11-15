package com.kardo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

/**
 * Cabecera de la guia de remision tal como la extrajo la vision multimodal.
 *
 * <p>Es el contrato exacto que se le exige a Gemini en el prompt. Se ignoran
 * campos desconocidos porque el modelo puede añadir claves no solicitadas y eso
 * no debe romper la deserializacion.
 *
 * @param issueDate puede venir nula si el documento esta borroso en esa zona
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedReceiptDto(
        String supplierTaxId,
        String supplierName,
        String documentNumber,
        LocalDate issueDate,
        List<ExtractedItemDto> items) {

    public List<ExtractedItemDto> items() {
        return items == null ? List.of() : items;
    }
}
