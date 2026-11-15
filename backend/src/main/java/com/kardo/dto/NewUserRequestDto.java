package com.kardo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Alta de un companiero de trabajo.
 *
 * <p>{@code storeId} si viaja en el cuerpo, porque una empresa puede tener
 * varias tiendas y hay que decir a cual entra. Lo que el servidor comprueba es
 * que esa tienda sea de la empresa de quien firma la peticion; cualquier otra
 * se rechaza antes de escribir.
 */
public record NewUserRequestDto(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(min = 10, max = 100) String password,
        @NotBlank @Pattern(regexp = "OPERATOR|MANAGER") String role,
        @NotNull Long storeId) {
}
