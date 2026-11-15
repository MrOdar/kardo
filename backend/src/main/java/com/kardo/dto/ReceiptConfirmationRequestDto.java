package com.kardo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Conciliacion confirmada por el operario.
 *
 * <p>Llega con las cantidades fisicas realmente recibidas y con el SKU final de
 * cada linea, ya resueltas las que la IA no pudo mapear.
 */
public record ReceiptConfirmationRequestDto(
        @NotNull Long storeId,

        @NotBlank @Size(max = 20) String supplierTaxId,

        @NotBlank @Size(max = 150) String supplierName,

        /** Numero de guia de remision o factura; unico por tienda en el DDL. */
        @NotBlank @Size(max = 50) String documentNumber,

        @NotNull LocalDate issueDate,

        @NotEmpty List<@Valid ConfirmedLineDto> lines) {
}
