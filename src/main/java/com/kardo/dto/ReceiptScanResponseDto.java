package com.kardo.dto;

import java.util.List;

/**
 * Respuesta de {@code /receipts/scan}: la guia extraida mas el cruce contra el
 * catalogo, lista para que el operario la revise en pantalla.
 *
 * @param modelUsed       modelo de Gemini que respondio (puede ser un respaldo
 *                        si el primario devolvio 429 o 503)
 * @param unmatchedLines  cuantas lineas requieren decision humana
 */
public record ReceiptScanResponseDto(
        String supplierTaxId,
        String supplierName,
        String documentNumber,
        java.time.LocalDate issueDate,
        String modelUsed,
        int unmatchedLines,
        List<ConciliationLineDto> lines) {
}
