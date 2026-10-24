package com.kardo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Declaracion de merma por parte del operario.
 *
 * <p>No la confirma: deja las unidades en cuarentena a la espera del gerente.
 *
 * @param reason uno de los motivos que admite el CHECK de {@code waste_log}
 */
public record WasteReportRequestDto(
        @NotNull Long batchId,

        @Min(1) @Max(100_000) int quantity,

        @Pattern(regexp = "EXPIRATION|DAMAGE|COLD_CHAIN_FAILURE|RECALL",
                 message = "debe ser EXPIRATION, DAMAGE, COLD_CHAIN_FAILURE o RECALL")
        String reason) {
}
