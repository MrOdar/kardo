package com.kardo.inventory;

import java.math.BigDecimal;

/**
 * Recomendacion de la escalera para un lote.
 *
 * @param stepPercent      peldaño nominal, la clave con la que se recuerda que
 *                         esta recomendacion ya se vio; crece al acercarse el
 *                         vencimiento, y por eso una decision vieja deja de
 *                         silenciar la alerta cuando el lote baja un peldaño
 * @param discountPercent  descuento real tras aplicar el piso del costo; es el
 *                         que ve el operario y puede ser menor que el nominal
 * @param suggestedPrice   precio de anaquel sugerido; nulo cuando no se rebaja
 * @param marginSacrificed margen que se cede si se aplica
 * @param valueAtRisk      costo que se pierde si el lote vence sin venderse
 * @param rationale        la frase que explica el porque, en terminos de tienda
 */
public record MarkdownSuggestion(
        MarkdownAction action,
        int stepPercent,
        int discountPercent,
        BigDecimal suggestedPrice,
        BigDecimal marginSacrificed,
        BigDecimal valueAtRisk,
        String rationale) {
}
