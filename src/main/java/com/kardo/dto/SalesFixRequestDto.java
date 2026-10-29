package com.kardo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Reenvio de las ventas que el operario corrigio en el navegador.
 *
 * <p><b>Por que no se reenvia el archivo corregido.</b> Un archivo con una fila
 * arreglada tiene otra huella, de modo que pasaria la guarda de duplicados y
 * registraria POR SEGUNDA VEZ las lineas que ya entraron bien. En inventario eso
 * se revierte; una venta no. Por eso lo que vuelve son las filas sueltas, nunca
 * el archivo.
 */
public record SalesFixRequestDto(
        @NotNull Long storeId,
        @NotEmpty @Size(max = 100) List<@Valid SalesFixRowDto> rows) {
}
