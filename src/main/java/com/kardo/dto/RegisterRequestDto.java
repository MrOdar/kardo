package com.kardo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Alta de una empresa nueva, con su primera tienda y su primer gerente.
 *
 * <p>No lleva {@code storeId} ni {@code companyId} por el mismo motivo que el
 * resto de la API: nada que identifique a quien escribe puede venir del cliente.
 * Aqui no existen todavia, y los crea el servidor.
 *
 * @param taxId  RUC peruano: once digitos. Es la identidad de la empresa en los
 *               libros, asi que el sistema no admite dos empresas con el mismo.
 */
public record RegisterRequestDto(
        @NotBlank @Size(max = 150) String companyName,
        @NotBlank @Pattern(regexp = "[0-9]{11}", message = "El RUC son once digitos.") String taxId,
        @NotBlank @Size(max = 100) String storeName,
        @Size(max = 200) String storeAddress,
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 120) String email,
        // Diez caracteres y no ocho: es la unica credencial de una empresa real
        // y nadie va a teclearla mas de una vez al dia.
        @NotBlank @Size(min = 10, max = 100) String password) {
}
