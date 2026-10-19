package com.kardo.dto;

import java.util.Map;

/**
 * Fila rechazada por la compuerta de calidad.
 *
 * <p>Se devuelve al SPA con los valores crudos tal como venian en el archivo:
 * el operario los corrige en la memoria de su navegador (maximo 100 filas) y
 * reenvia unicamente lo modificado a {@code /bulk-fix}. Asi el servidor guarda
 * 0 bytes de estado entre peticiones.
 *
 * @param rowNumber      fila original en el Excel (1-based)
 * @param originalValues valores leidos, indexados por nombre de columna canonico
 * @param errorCode      codigo estable para que la UI decida como resaltar el campo
 * @param message        explicacion en lenguaje natural para el operario
 */
public record QuarantineRowDto(
        int rowNumber,
        Map<String, String> originalValues,
        String errorCode,
        String message) {
}
