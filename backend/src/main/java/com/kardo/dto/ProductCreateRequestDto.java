package com.kardo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta manual de un producto, con su primer lote si se quiere.
 *
 * <p><b>Por que el lote va aqui y no en una segunda pantalla.</b> Un producto
 * sin lote no tiene existencias: no sale en el semaforo, no se puede vender y
 * no aparece en el analisis. Darlo de alta solo dejaria al operario con la
 * sensacion de que no paso nada. Si trae cantidad, se crea tambien el lote.
 *
 * <p>No lleva tienda: sale del token.
 *
 * @param initialQty 0 o nulo da de alta solo el catalogo, sin existencias
 * @param expirationDate obligatoria si hay cantidad; es lo que hace funcionar
 *                       el FEFO, y un lote sin ella seria invisible al motor
 */
public record ProductCreateRequestDto(
        @NotBlank @Size(max = 50) String sku,
        @Size(max = 50) String barcode,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 80) String category,
        @Size(max = 20) String unitOfMeasure,
        @DecimalMin("0.00") BigDecimal unitCost,
        @DecimalMin("0.00") BigDecimal retailPrice,

        @Size(max = 50) String batchCode,
        @Min(0) @Max(1_000_000) Integer initialQty,
        LocalDate expirationDate) {
}
