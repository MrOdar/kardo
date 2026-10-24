package com.kardo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credenciales de acceso. */
public record LoginRequestDto(
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(max = 100) String password) {
}
