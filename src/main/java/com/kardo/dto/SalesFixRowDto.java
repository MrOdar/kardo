package com.kardo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Una venta que el operario corrigio en el navegador.
 *
 * <p>La fecha llega como texto y no como {@code LocalDate} a proposito: una
 * fecha que el operario acaba de teclear puede no ser una fecha, y queremos
 * decirselo con el numero de fila en lugar de devolverle un 400 de
 * deserializacion sin contexto.
 *
 * @param rowNumber fila del Excel original, para poder señalarla de vuelta
 * @param saleDate  vacia o nula significa "ahora"
 */
public record SalesFixRowDto(
        @NotNull Integer rowNumber,
        @NotBlank @Size(max = 50) String sku,
        @NotNull @Min(1) @Max(10_000) Integer quantity,
        @Size(max = 10) String saleDate) {
}
