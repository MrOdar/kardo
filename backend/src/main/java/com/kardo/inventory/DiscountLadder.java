package com.kardo.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Que hacer con un lote segun los dias que le queden.
 *
 * <p>El semaforo FEFO decia cuanto falta; esto dice que hacer. La escalera es
 * deliberadamente simple y esta toda aqui, en un solo sitio sin estado, porque
 * es la regla que mas se va a discutir con el negocio: cambiarla no debe obligar
 * a tocar SQL ni controladores.
 *
 * <p>La decision de diseño que importa es el <b>piso en el costo del lote</b>.
 * Una escalera suelta acabaria recomendando vender a perdida, que es peor
 * negocio que la merma: en la merma pierdes el costo una vez; vendiendo por
 * debajo del costo pierdes el costo y ademas regalas la unidad. Por eso el
 * precio sugerido nunca baja del {@code unitCost} del lote, y el porcentaje que
 * se muestra es el que de verdad resulta tras aplicar ese piso.
 */
public final class DiscountLadder {

    /**
     * Mas alla de este horizonte no hay nada que decidir todavia: una alerta a
     * veinte dias solo enseña al operario a ignorar el panel.
     */
    public static final int HORIZON_DAYS = 10;

    /**
     * Peldaño que se asigna a un lote vencido. Al ser el mas profundo posible,
     * ninguna decision anterior puede silenciar la alerta mas urgente de todas.
     */
    public static final int EXPIRED_STEP = 100;

    private DiscountLadder() {
    }

    /** Peldaños de la escalera, del mas urgente al mas holgado. */
    private record Step(int maxDays, int percent) {
    }

    private static final Step[] STEPS = {
            new Step(0, 50),   // vence hoy
            new Step(1, 40),
            new Step(2, 30),
            new Step(5, 20),
            new Step(HORIZON_DAYS, 10)
    };

    /**
     * Recomendacion para un lote.
     *
     * @param daysToExpiry negativo si ya vencio
     * @param sellableQty  unidades vendibles: las que estan en cuarentena ya van
     *                     camino de la merma y no se rebajan
     * @param unitCost     costo de ESTE lote, no del catalogo: es el piso
     * @param retailPrice  precio de lista vigente del producto
     * @return la recomendacion, o {@code null} si el lote no necesita ninguna
     */
    public static MarkdownSuggestion suggest(int daysToExpiry, int sellableQty,
                                             BigDecimal unitCost, BigDecimal retailPrice) {

        if (sellableQty <= 0 || daysToExpiry > HORIZON_DAYS) {
            return null;
        }

        BigDecimal valueAtRisk = unitCost.multiply(BigDecimal.valueOf(sellableQty))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal nada = BigDecimal.ZERO.setScale(2);

        // Vencido: no se rebaja, se retira. Venderlo no es una opcion legal y
        // ofrecer un descuento aqui seria sugerir una infraccion sanitaria.
        if (daysToExpiry < 0) {
            int dias = Math.abs(daysToExpiry);
            return new MarkdownSuggestion(MarkdownAction.RETIRAR, EXPIRED_STEP, 0, null, nada, valueAtRisk,
                    "Vencido hace " + dias + (dias == 1 ? " dia" : " dias")
                            + ": retirar del anaquel y declarar la merma por EXPIRATION.");
        }

        int step = nominalPercent(daysToExpiry);

        // Sin margen que ceder: el lote entro a un costo que iguala o supera el
        // precio de lista. Rebajar seria vender a perdida, asi que la decision
        // sube a quien pone los precios en lugar de disfrazarse de oferta.
        if (unitCost.compareTo(retailPrice) >= 0) {
            return new MarkdownSuggestion(MarkdownAction.REVISAR_PRECIO, step, 0, null, nada, valueAtRisk,
                    "El costo del lote (S/ " + unitCost.setScale(2, RoundingMode.HALF_UP)
                            + ") iguala o supera el precio de lista: rebajar seria vender a perdida.");
        }

        BigDecimal nominalPrice = retailPrice
                .multiply(BigDecimal.valueOf(100 - step))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // El piso: por debajo del costo del lote no se sugiere nada.
        BigDecimal price = nominalPrice.max(unitCost.setScale(2, RoundingMode.HALF_UP));

        // Si el piso mordio, el porcentaje que se muestra es el real y no el de
        // la tabla: anunciar un 50% y que el precio baje un 34% es como se
        // pierde la confianza en una recomendacion.
        int effective = retailPrice.subtract(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(retailPrice, 0, RoundingMode.DOWN)
                .intValue();

        BigDecimal marginSacrificed = retailPrice.subtract(price)
                .multiply(BigDecimal.valueOf(sellableQty))
                .setScale(2, RoundingMode.HALF_UP);

        return new MarkdownSuggestion(MarkdownAction.REBAJAR, step, effective, price,
                marginSacrificed, valueAtRisk,
                rationale(daysToExpiry, effective, marginSacrificed, valueAtRisk));
    }

    private static int nominalPercent(int daysToExpiry) {
        for (Step step : STEPS) {
            if (daysToExpiry <= step.maxDays()) {
                return step.percent();
            }
        }
        return 0;
    }

    /**
     * La frase que justifica la rebaja en los terminos en que se decide en una
     * tienda: cuanto margen cedes para no comerte cuanto costo.
     */
    private static String rationale(int daysToExpiry, int percent,
                                    BigDecimal marginSacrificed, BigDecimal valueAtRisk) {
        String cuando = switch (daysToExpiry) {
            case 0 -> "Vence hoy";
            case 1 -> "Vence mañana";
            default -> "Vence en " + daysToExpiry + " dias";
        };

        return cuando + ": rebajar " + percent + "% cede S/ " + marginSacrificed
                + " de margen para no perder S/ " + valueAtRisk + " de costo.";
    }
}
