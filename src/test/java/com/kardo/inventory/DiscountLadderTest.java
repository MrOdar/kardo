package com.kardo.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La escalera, que es la regla de negocio mas discutible del proyecto y por eso
 * la que mas necesita estar fijada.
 */
class DiscountLadderTest {

    /** Leche: costo 3.80, lista 5.20. Hay margen de sobra para rebajar. */
    private static final BigDecimal COSTO = new BigDecimal("3.80");
    private static final BigDecimal LISTA = new BigDecimal("8.00");

    @ParameterizedTest(name = "a {0} dias sugiere {1}%")
    @CsvSource({
            "0, 50",
            "1, 40",
            "2, 30",
            "3, 20",
            "5, 20",
            "6, 10",
            "10, 10"
    })
    @DisplayName("Cuanto mas cerca el vencimiento, mas profundo el peldaño")
    void peldanos(int dias, int esperado) {
        var s = DiscountLadder.suggest(dias, 10, COSTO, LISTA);

        assertThat(s).isNotNull();
        assertThat(s.action()).isEqualTo(MarkdownAction.REBAJAR);
        assertThat(s.stepPercent()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("Mas alla del horizonte no hay nada que decidir")
    void fueraDelHorizonte() {
        assertThat(DiscountLadder.suggest(11, 10, COSTO, LISTA)).isNull();
        assertThat(DiscountLadder.suggest(365, 10, COSTO, LISTA)).isNull();
    }

    @Test
    @DisplayName("Un lote sin unidades vendibles no genera alerta")
    void sinUnidades() {
        assertThat(DiscountLadder.suggest(1, 0, COSTO, LISTA)).isNull();
    }

    @Test
    @DisplayName("El precio sugerido sale del precio de lista y el margen cedido de las unidades")
    void cuentasDeLaRebaja() {
        // 8.00 con 30% => 5.60; se ceden 2.40 por unidad en 10 unidades
        var s = DiscountLadder.suggest(2, 10, COSTO, LISTA);

        assertThat(s.suggestedPrice()).isEqualByComparingTo("5.60");
        assertThat(s.discountPercent()).isEqualTo(30);
        assertThat(s.marginSacrificed()).isEqualByComparingTo("24.00");
        assertThat(s.valueAtRisk()).isEqualByComparingTo("38.00");
        assertThat(s.rationale()).contains("Vence en 2 dias", "30%", "24.00", "38.00");
    }

    /**
     * El corazon del diseño: en la merma pierdes el costo una vez, pero
     * vendiendo por debajo del costo pierdes el costo y regalas la unidad.
     */
    @Test
    @DisplayName("El descuento nunca baja del costo del lote")
    void pisoEnElCosto() {
        // Lista 5.20, costo 3.80: el 50% nominal daria 2.60, por debajo del costo
        var s = DiscountLadder.suggest(0, 20, COSTO, new BigDecimal("5.20"));

        assertThat(s.suggestedPrice()).isEqualByComparingTo("3.80");
        assertThat(s.stepPercent()).isEqualTo(50);

        // Y el porcentaje que se muestra es el real, no el de la tabla
        assertThat(s.discountPercent()).isEqualTo(26);
        assertThat(s.marginSacrificed()).isEqualByComparingTo("28.00");
    }

    @Test
    @DisplayName("Sin margen entre costo y precio, la decision sube a quien fija precios")
    void sinMargen() {
        var s = DiscountLadder.suggest(1, 10, new BigDecimal("5.00"), new BigDecimal("5.00"));

        assertThat(s.action()).isEqualTo(MarkdownAction.REVISAR_PRECIO);
        assertThat(s.suggestedPrice()).isNull();
        assertThat(s.marginSacrificed()).isEqualByComparingTo("0.00");
        assertThat(s.valueAtRisk()).isEqualByComparingTo("50.00");
        assertThat(s.rationale()).contains("vender a perdida");
    }

    /** Ofrecer un descuento sobre mercaderia vencida seria sugerir una infraccion. */
    @Test
    @DisplayName("Lo vencido se retira, no se rebaja")
    void vencido() {
        var s = DiscountLadder.suggest(-3, 10, COSTO, LISTA);

        assertThat(s.action()).isEqualTo(MarkdownAction.RETIRAR);
        assertThat(s.discountPercent()).isZero();
        assertThat(s.suggestedPrice()).isNull();
        assertThat(s.rationale()).contains("Vencido hace 3 dias", "EXPIRATION");
    }

    /**
     * Lo vencido ocupa el peldaño mas profundo para que ninguna decision
     * anterior pueda silenciar la alerta mas urgente.
     */
    @Test
    @DisplayName("El peldaño de lo vencido supera cualquier decision previa")
    void elVencidoNoSePuedeSilenciarPorLoAnterior() {
        assertThat(DiscountLadder.suggest(-1, 5, COSTO, LISTA).stepPercent())
                .isEqualTo(DiscountLadder.EXPIRED_STEP)
                .isGreaterThan(DiscountLadder.suggest(0, 5, COSTO, LISTA).stepPercent());
    }

    @Test
    @DisplayName("El dia del vencimiento y el siguiente se dicen en palabras, no en numeros")
    void redaccion() {
        assertThat(DiscountLadder.suggest(0, 1, COSTO, LISTA).rationale()).startsWith("Vence hoy");
        assertThat(DiscountLadder.suggest(1, 1, COSTO, LISTA).rationale()).startsWith("Vence mañana");
    }
}
