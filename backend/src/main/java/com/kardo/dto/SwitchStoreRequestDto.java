package com.kardo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Tienda a la que se quiere pasar.
 *
 * <p>Aqui si viaja un identificador de tienda en el cuerpo, y es la excepcion
 * que confirma la regla: no dice sobre que tienda escribir -eso lo seguira
 * diciendo el token-, sino que token pedir. El servidor solo lo concede si
 * quien lo pide trabaja en esa tienda.
 */
public record SwitchStoreRequestDto(@NotNull Long storeId) {
}
