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

    // ------------------------------------------------------------------
    // El reparto por familia
    // ------------------------------------------------------------------

    /**
     * La columna que paga la consulta no es el valor, es {@code sin_salida}.
     *
     * <p>Agrupar por familia para contar unidades no lleva a ninguna decision:
     * el dueño ya sabe que tiene mas gaseosas que embutidos. Lo que no sabe es
     * cual se le esta muriendo en el anaquel, y eso solo aparece cruzando lo
     * que le queda al lote con lo que tarda en venderse.
     */
    @Test
    @DisplayName("Separa el dinero que no alcanza a venderse del que si")
    void repartePorFamiliaLoQueNoDaTiempo() {
        long pan = fixtures.product(storeId, "SKU-PAN", "Pan de molde", "PANADERIA", "2.00", "5.00");
        long arroz = fixtures.product(storeId, "SKU-ARZ", "Arroz extra", "ABARROTES", "3.00", "6.00");

        // 40 unidades a 4 al dia son 10 dias de anaquel, y vence en 5: no da
        // tiempo, y los S/ 80 enteros son plata que se va a perder.
        long lotePan = fixtures.batch(storeId, pan, "LOT-PAN", LocalDate.now().plusDays(5), 40, "2.00");
        venderDe(lotePan, pan, 120);

        // 100 unidades a 10 al dia son 10 dias, y vence dentro de un año.
        long loteArroz = fixtures.batch(storeId, arroz, "LOT-ARZ", LocalDate.now().plusDays(400), 100, "3.00");
        venderDe(loteArroz, arroz, 300);

        List<Map<String, Object>> familias = insights.byCategory(storeId);

        Map<String, Object> panaderia = familia(familias, "PANADERIA");
        assertThat(new BigDecimal(panaderia.get("valor").toString())).isEqualByComparingTo("80.00");
        assertThat(new BigDecimal(panaderia.get("sin_salida").toString())).isEqualByComparingTo("80.00");
        assertThat(((Number) panaderia.get("productos_sin_salida")).intValue()).isEqualTo(1);
        // Vence dentro de los 5 dias del semaforo, asi que tambien esta en riesgo.
        assertThat(new BigDecimal(panaderia.get("en_riesgo").toString())).isEqualByComparingTo("80.00");

        Map<String, Object> abarrotes = familia(familias, "ABARROTES");
        assertThat(new BigDecimal(abarrotes.get("valor").toString())).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(abarrotes.get("sin_salida").toString())).isEqualByComparingTo("0.00");
        assertThat(((Number) abarrotes.get("productos_sin_salida")).intValue()).isZero();
    }

    /**
     * Lo que el grafico atrapa y el semaforo no.
     *
     * <p>El semaforo mira cinco dias. Un lote que vence en nueve y tarda doce
     * en venderse queda fuera de esa ventana -{@code en_riesgo} dice cero- y se
     * pierde igual. Esa es la razon de existir de esta consulta.
     */
    @Test
    @DisplayName("Atrapa lo que se pierde fuera de la ventana de cinco dias")
    void veMasAlláDelSemaforo() {
        long jamon = fixtures.product(storeId, "SKU-JAM", "Jamonada", "EMBUTIDOS", "4.00", "9.00");
        // 60 unidades a 5 al dia son 12 dias de anaquel, y vence en 9.
        long lote = fixtures.batch(storeId, jamon, "LOT-JAM", LocalDate.now().plusDays(9), 60, "4.00");
        venderDe(lote, jamon, 150);

        Map<String, Object> fila = familia(insights.byCategory(storeId), "EMBUTIDOS");

        assertThat(new BigDecimal(fila.get("en_riesgo").toString()))
                .describedAs("vence en 9 dias: el semaforo de 5 no lo ve")
                .isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(fila.get("sin_salida").toString()))
                .describedAs("y se pierde igual, porque tarda 12 en venderse")
                .isEqualByComparingTo("240.00");
    }

    /**
     * La misma regla que ya rige la cobertura, sostenida aqui.
     *
     * <p>A un producto sin ventas no se le puede suponer el peor caso: inflaria
     * la cifra justo en lo que acaba de llegar, que es cuando menos historial
     * hay y mas facil es asustarse.
     */
    @Test
    @DisplayName("Un producto sin ventas no cuenta como perdido, aunque venza mañana")
    void sinVentasNoSeDaPorPerdido() {
        long nuevo = fixtures.product(storeId, "SKU-NUE", "Queso recien llegado", "LACTEOS", "7.00", "12.00");
        fixtures.batch(storeId, nuevo, "LOT-NUE", LocalDate.now().plusDays(1), 20, "7.00");

        Map<String, Object> fila = familia(insights.byCategory(storeId), "LACTEOS");

        assertThat(new BigDecimal(fila.get("valor").toString())).isEqualByComparingTo("140.00");
        assertThat(((Number) fila.get("productos_sin_salida")).intValue())
                .describedAs("no se sabe a que ritmo sale, asi que no se afirma que se pierda")
                .isZero();
        assertThat(fila.get("cobertura_media")).isNull();
        // Que venza mañana si se sabe, y eso el semaforo lo dice igual.
        assertThat(new BigDecimal(fila.get("en_riesgo").toString())).isEqualByComparingTo("140.00");
    }

    /**
     * El orden es parte del mensaje: arriba lo que se pierde, no lo que vale.
     *
     * <p>Ordenado por valor, la familia pequeña que se esta muriendo aparece la
     * ultima y nadie la ve. Es justo la que hay que mirar.
     */
    @Test
    @DisplayName("Ordena por lo que se pierde, no por lo que vale")
    void mandaLoQueSePierde() {
        long pan = fixtures.product(storeId, "SKU-PAN2", "Pan chico", "PANADERIA", "2.00", "5.00");
        long arroz = fixtures.product(storeId, "SKU-ARZ2", "Arroz caro", "ABARROTES", "3.00", "6.00");

        long lotePan = fixtures.batch(storeId, pan, "LOT-PAN2", LocalDate.now().plusDays(2), 10, "2.00");
        venderDe(lotePan, pan, 30);    // 1 al dia, 10 dias de anaquel, vence en 2

        long loteArroz = fixtures.batch(storeId, arroz, "LOT-ARZ2", LocalDate.now().plusDays(400), 500, "3.00");
        venderDe(loteArroz, arroz, 3000);

        List<Map<String, Object>> familias = insights.byCategory(storeId);

        // Abarrotes vale S/ 1.500 y panaderia S/ 20, pero la que sangra va arriba.
        assertThat(familias.getFirst().get("categoria")).isEqualTo("PANADERIA");
        assertThat(new BigDecimal(familias.getFirst().get("sin_salida").toString()))
                .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Una familia sin existencias no ocupa una barra vacia")
    void soloLoQueTieneStock() {
        long pan = fixtures.product(storeId, "SKU-PAN3", "Pan", "PANADERIA", "2.00", "5.00");
        fixtures.batch(storeId, pan, "LOT-PAN3", LocalDate.now().plusDays(10), 10, "2.00");
        // Un producto de otra familia, pero sin un solo lote con existencias.
        fixtures.product(storeId, "SKU-VAC", "Descatalogado", "LICORES", "9.00", "20.00");

        assertThat(insights.byCategory(storeId))
                .extracting(fila -> fila.get("categoria"))
                .containsExactly("PANADERIA");
    }

    private Map<String, Object> familia(List<Map<String, Object>> filas, String categoria) {
        return filas.stream()
                .filter(fila -> categoria.equals(fila.get("categoria")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No hay fila para " + categoria + " en " + filas));
    }

    private void venderDe(long batchId, long productId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO daily_sales (store_id, batch_id, product_id, sale_timestamp,
                                         quantity, unit_retail_price, lot_unit_cogs, operator_id)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP - make_interval(days => 1), ?, 5.00, 2.00, 'tester@kardo.pe')
                """, storeId, batchId, productId, quantity);
    }

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
