package com.kardo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Correccion de un producto del catalogo.
 *
 * <p><b>El SKU no esta aqui, y es deliberado.</b> Es la clave con la que la
 * ingesta por Excel reconoce el producto: cambiarlo haria que la proxima carga
 * del proveedor no encontrara el existente y creara uno nuevo al lado, con lo
 * que el historico de ventas quedaria repartido entre dos fichas sin que nadie
 * lo note. Un SKU equivocado se arregla dando de alta el correcto, no mutando
 * el viejo.
 *
 * <p>Tampoco esta la tienda: sale del token.
 */
public record ProductUpdateRequestDto(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 80) String category,
        @Size(max = 50) String barcode,
        @Size(max = 20) String unitOfMeasure,
        @NotNull @DecimalMin("0.00") BigDecimal unitCost,
        @NotNull @DecimalMin("0.00") BigDecimal retailPrice) {
}
