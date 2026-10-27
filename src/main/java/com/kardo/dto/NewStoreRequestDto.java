package com.kardo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Tienda nueva dentro de la empresa de quien la pide.
 *
 * <p>La empresa sale del token, no del cuerpo: un gerente no puede abrir una
 * tienda en una empresa ajena ni aunque conozca su identificador.
 */
public record NewStoreRequestDto(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 200) String address) {
}
