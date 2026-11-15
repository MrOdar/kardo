package com.kardo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * El panel de alertas completo.
 *
 * <p>Los totales van calculados aqui y no en el navegador porque son la frase
 * que resume el panel ("S/ X en riesgo, S/ Y de margen en juego") y debe decir
 * lo mismo en la pantalla, en una exportacion y en un correo.
 *
 * @param valueAtRisk      costo que se pierde si no se hace nada
 * @param marginSacrificed margen que cuesta aplicar todas las rebajas sugeridas
 * @param silenced         alertas ya decididas en este mismo peldaño, que no se
 *                         muestran; se cuentan para que el panel vacio pueda
 *                         distinguir "no hay nada" de "ya lo resolviste todo"
 */
public record ExpiryAlertBoardDto(
        BigDecimal valueAtRisk,
        BigDecimal marginSacrificed,
        int silenced,
        List<ExpiryAlertDto> alerts) {
}
