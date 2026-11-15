package com.kardo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fila de inventario normalizada a partir de la hoja de calculo.
 *
 * <p>Es un objeto efimero: vive solo mientras se acumula el micro-lote que
 * {@code JdbcTemplate.batchUpdate} envia a PostgreSQL, de modo que el heap del
 * servidor nunca retiene el archivo completo.
 *
 * <p>Las anotaciones de validacion cubren la reentrada por {@code /bulk-fix},
 * donde el SPA envia filas ya corregidas por el operario.
 *
 * @param rowNumber         fila original en el Excel (1-based), para que el
 *                          operario ubique el dato en su archivo
 * @param manufacturingDate opcional: el DDL la admite nula
 */
public record SpreadsheetRowDto(
        @Min(1) int rowNumber,

        @NotBlank @Size(max = 50) String sku,

        @Size(max = 50) String barcode,

        @NotBlank @Size(max = 200) String name,

        @NotBlank @Size(max = 80) String category,

        @NotBlank @Size(max = 20) String unitOfMeasure,

        @NotNull @DecimalMin("0.00") BigDecimal unitCost,

        @NotNull @DecimalMin("0.00") BigDecimal retailPrice,

        @NotBlank @Size(max = 80) String batchCode,

        LocalDate manufacturingDate,

        @NotNull LocalDate expirationDate,

        @NotNull @Min(1) Integer initialQty) {
}
