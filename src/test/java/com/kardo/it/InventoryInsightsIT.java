package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.inventory.InventoryInsightsService;
import com.kardo.inventory.RiskHistoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lo que el analisis puede afirmar y lo que no.
 *
 * <p>La trampa de un panel de analitica es afirmar de mas: inventar un ritmo de
 * salida para un producto que no se ha vendido nunca, o repartir entre los
 * proveedores conocidos la mercaderia que entro sin guia. Las dos cosas
 * producen numeros que alguien usaria para comprar. Aqui se fija lo contrario:
 * sin datos, la respuesta es <em>no se sabe</em>, dicha como tal.
 */
class InventoryInsightsIT extends PostgresIntegrationTest {

    @Autowired
    private InventoryInsightsService insights;

    @Autowired
    private RiskHistoryService riskHistory;

    private Fixtures fixtures;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Analisis", "21600000030").store("TIENDA-INS");
        productId = fixtures.product(storeId, "SKU-INS-1", "Pan de molde integral", "2.00", "5.00");
    }

    // ------------------------------------------------------------------
    // Dias de cobertura
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sin ventas, la cobertura es desconocida y no cero")
    void sinVentasNoSeInventaUnRitmo() {
        fixtures.batch(storeId, productId, "LOT-INS-A", LocalDate.now().plusDays(20), 40, "2.00");

        Map<String, Object> fila = unaFila(insights.coverage(storeId));

        assertThat(fila.get("dias_cobertura")).isNull();
        assertThat(((Number) fila.get("unidades")).intValue()).isEqualTo(40);
        assertThat(((Number) fila.get("vendidas_ventana")).intValue()).isZero();
    }

    /**
     * El numero que paga esta funcion: 40 unidades saliendo a 4 al dia son diez
     * dias de anaquel, y el lote vence en cinco. Eso se decide al comprar.
     */
    @Test
    @DisplayName("Con ventas, dice cuantos dias de venta hay en el anaquel")
    void conVentasCalculaLaCobertura() {
        long batchId = fixtures.batch(storeId, productId, "LOT-INS-B", LocalDate.now().plusDays(5), 40, "2.00");
        vender(batchId, 120);   // 120 unidades en la ventana de 30 dias = 4 al dia

        Map<String, Object> fila = unaFila(insights.coverage(storeId));

        assertThat(new BigDecimal(fila.get("dias_cobertura").toString()))
                .isEqualByComparingTo("10.0");
        assertThat(((Number) fila.get("dias_hasta_vencer")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("Una venta antigua no cuenta como ritmo de hoy")
    void laVentanaDejaFueraLoViejo() {
        long batchId = fixtures.batch(storeId, productId, "LOT-INS-C", LocalDate.now().plusDays(30), 10, "2.00");
        venderHaceDias(batchId, 90, 60);

        assertThat(unaFila(insights.coverage(storeId)).get("dias_cobertura")).isNull();
    }

    // ------------------------------------------------------------------
    // Reparto por proveedor
    // ------------------------------------------------------------------

    /**
     * Los lotes cargados desde Excel no tienen guia y por tanto no tienen
     * proveedor. Repartirlos entre los conocidos daria un informe mas bonito y
     * una conclusion falsa.
     */
    @Test
    @DisplayName("Lo que entro sin guia se agrupa aparte, no se reparte")
    void loQueNoTieneGuiaSeDiceComoTal() {
        fixtures.batch(storeId, productId, "LOT-INS-D", LocalDate.now().plusDays(3), 10, "2.00");

        List<Map<String, Object>> filas = insights.bySupplier(storeId);

        assertThat(filas).hasSize(1);
        assertThat(filas.getFirst().get("proveedor").toString()).contains("sin guía");
        assertThat(filas.getFirst().get("con_guia")).isEqualTo(false);
        assertThat(new BigDecimal(filas.getFirst().get("en_riesgo").toString()))
                .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Varias bajas sobre un lote no multiplican su valor")
    void lasBajasNoDuplicanElLote() {
        long batchId = fixtures.batch(storeId, productId, "LOT-INS-E", LocalDate.now().plusDays(40), 10, "2.00");
        baja(batchId, 2);
        baja(batchId, 3);

        Map<String, Object> fila = insights.bySupplier(storeId).getFirst();

        // El valor del lote sigue siendo 10 x 2.00, no 40.00 por las dos bajas.
        assertThat(new BigDecimal(fila.get("valor").toString())).isEqualByComparingTo("20.00");
        assertThat(new BigDecimal(fila.get("merma").toString())).isEqualByComparingTo("10.00");
    }

    // ------------------------------------------------------------------
    // Serie del valor en riesgo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("La foto diaria se puede repetir sin duplicar el punto")
    void capturarDosVecesDejaUnSoloPunto() {
        fixtures.batch(storeId, productId, "LOT-INS-F", LocalDate.now().plusDays(2), 10, "2.00");

        riskHistory.capture();
        riskHistory.capture();

        List<Map<String, Object>> serie = riskHistory.history(storeId, 30);

        assertThat(serie).hasSize(1);
        assertThat(new BigDecimal(serie.getFirst().get("value_at_risk").toString()))
                .isEqualByComparingTo("20.00");
        assertThat(((Number) serie.getFirst().get("red_batches")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("Lo que vence con holgura entra en el inventario pero no en el riesgo")
    void laFotoSeparaRiesgoDeInventario() {
        fixtures.batch(storeId, productId, "LOT-INS-G", LocalDate.now().plusDays(2), 10, "2.00");
        fixtures.batch(storeId, productId, "LOT-INS-H", LocalDate.now().plusDays(90), 50, "2.00");

        riskHistory.capture();
        Map<String, Object> punto = riskHistory.history(storeId, 30).getFirst();

        assertThat(new BigDecimal(punto.get("value_at_risk").toString())).isEqualByComparingTo("20.00");
        assertThat(new BigDecimal(punto.get("inventory_value").toString())).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("Una tienda sin historia devuelve una serie vacia, no un cero inventado")
    void sinHistoriaNoHaySerie() {
        assertThat(riskHistory.history(storeId, 30)).isEmpty();
    }

    // ------------------------------------------------------------------

    private Map<String, Object> unaFila(List<Map<String, Object>> filas) {
        assertThat(filas).hasSize(1);
        return filas.getFirst();
    }

    private void vender(long batchId, int quantity) {
        venderHaceDias(batchId, quantity, 1);
    }

    private void venderHaceDias(long batchId, int quantity, int days) {
        jdbcTemplate.update("""
                INSERT INTO daily_sales (store_id, batch_id, product_id, sale_timestamp,
                                         quantity, unit_retail_price, lot_unit_cogs, operator_id)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP - make_interval(days => ?), ?, 5.00, 2.00, 'tester@kardo.pe')
                """, storeId, batchId, productId, days, quantity);
    }

    private void baja(long batchId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO waste_log (store_id, batch_id, product_id, quantity_discarded,
                                       unit_cost, waste_reason, approval_status, operator_id)
                VALUES (?, ?, ?, ?, 2.00, 'EXPIRATION', 'APPROVED', 'tester@kardo.pe')
                """, storeId, batchId, productId, quantity);
    }
}
