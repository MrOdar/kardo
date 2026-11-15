package com.kardo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Lo que el operario decidio sobre una alerta.
 *
 * <p>No lleva el peldaño ni el precio: ambos los calcula el servidor con los
 * dias que le quedan al lote en este momento. Si vinieran del cliente, bastaria
 * declarar un 100% para silenciar un lote hasta que venza.
 */
public record MarkdownDecisionRequestDto(

        @NotBlank
        @Pattern(regexp = "APPLIED|DISMISSED",
                 message = "debe ser APPLIED (rebaja puesta en el anaquel) o DISMISSED (descartada)")
        String decision) {
}
