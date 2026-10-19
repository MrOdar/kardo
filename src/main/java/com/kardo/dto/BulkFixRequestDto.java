package com.kardo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Reenvio de las filas que el operario corrigio en el navegador.
 *
 * <p>El tope de {@code 100} filas refleja el limite de la cuarentena en memoria
 * del cliente: mas alla de eso la ingesta se habria abortado con HTTP 422.
 */
public record BulkFixRequestDto(
        @NotNull Long storeId,
        @NotEmpty @Size(max = 100) List<@Valid SpreadsheetRowDto> rows) {
}
