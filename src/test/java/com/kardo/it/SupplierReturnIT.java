package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.NotReturnableException;
import com.kardo.exception.ReturnAlreadyResolvedException;
import com.kardo.inventory.InventoryInsightsService;
import com.kardo.returns.SupplierReturnService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Canje al proveedor: lo que lo separa de una merma.
 *
 * <p>El circuito se parece -se declara, se inmoviliza, alguien resuelve- pero
 * el dinero va al reves. Lo que se fija aqui es justo eso: que el costo se
 * recupere y no se cuente nunca como perdida, que las unidades no se puedan
 * vender mientras esperan al camion, y que un rechazo del proveedor devuelva la
 * mercaderia al circuito de mermas en vez de inventarse una contabilidad
 * propia.
 */
class SupplierReturnIT extends PostgresIntegrationTest {

    private static final String OPERARIO = "operario@kardo.pe";
    private static final String GERENTE = "gerente@kardo.pe";

    @Autowired
    private SupplierReturnService returns;

    @Autowired
    private InventoryInsightsService insights;

    private Fixtures fixtures;
    private long storeId;
    private long productId;
    private long supplierId;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Canje", "21800000050").store("TIENDA-CJE");
        productId = fixtures.product(storeId, "SKU-CJE-1", "Leche evaporada 400g", "3.00", "5.00");
        supplierId = proveedor("Distribuidora Lacteos Canje");
    }

    // ------------------------------------------------------------------
    // El circuito
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Declarar el canje inmoviliza las unidades sin perderlas")
    void declararApartaLaMercaderia() {
        long batchId = loteConGuia("LOT-CJE-A", 20);

        returns.declare(storeId, batchId, 12, OPERARIO);

        Map<String, Object> lote = lote(batchId);
        assertThat(lote.get("current_qty")).isEqualTo(8);
        assertThat(lote.get("quarantined_qty")).isEqualTo(12);

        var canje = returns.list(storeId, null, 10).getFirst();
        assertThat(canje.status()).isEqualTo("PENDING");
        assertThat(canje.supplierName()).isEqualTo("Distribuidora Lacteos Canje");
        assertThat(canje.recovered()).isEqualByComparingTo("36.00");   // 12 x 3.00
        assertThat(canje.resolvedBy()).isNull();
    }

    /**
     * La diferencia que justifica la tabla aparte: el dinero vuelve y no entra
     * en ninguna cuenta de perdidas.
     */
    @Test
    @DisplayName("Abonado: las unidades salen y el costo se recupera, no se pierde")
    void elAbonoRecuperaElCosto() {
        long batchId = loteConGuia("LOT-CJE-B", 20);
        returns.declare(storeId, batchId, 12, OPERARIO);

        returns.credit(idDelUnicoCanje(), GERENTE);

        Map<String, Object> lote = lote(batchId);
        assertThat(lote.get("current_qty")).isEqualTo(8);
        assertThat(lote.get("quarantined_qty")).isEqualTo(0);

        var canje = returns.list(storeId, "CREDITED", 10).getFirst();
        assertThat(canje.recovered()).isEqualByComparingTo("36.00");
        assertThat(canje.resolvedBy()).isEqualTo(GERENTE);

        // Y lo que de verdad importa: no hay ni una merma por ningun lado.
        Integer mermas = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM waste_log WHERE store_id = ?", Integer.class, storeId);
        assertThat(mermas).isZero();
    }

    /**
     * Si el proveedor no lo acepta, este flujo no inventa una perdida: devuelve
     * la mercaderia al stock y deja que la vea el semaforo, que ya sabe
     * empujarla al circuito de mermas.
     */
    @Test
    @DisplayName("Rechazado: la mercaderia vuelve al stock")
    void elRechazoDevuelveLaMercaderia() {
        long batchId = loteConGuia("LOT-CJE-C", 20);
        returns.declare(storeId, batchId, 12, OPERARIO);

        returns.refuse(idDelUnicoCanje(), GERENTE);

        Map<String, Object> lote = lote(batchId);
        assertThat(lote.get("current_qty")).isEqualTo(20);
        assertThat(lote.get("quarantined_qty")).isEqualTo(0);
        assertThat(returns.list(storeId, "REFUSED", 10)).hasSize(1);
    }

    @Test
    @DisplayName("Un lote ya vencido vuelve del rechazo marcado como vencido")
    void elRechazoDeLoVencidoLoDejaEnEstadoVencido() {
        long batchId = loteConGuia("LOT-CJE-D", LocalDate.now().minusDays(1), 10);
        returns.declare(storeId, batchId, 10, OPERARIO);

        returns.refuse(idDelUnicoCanje(), GERENTE);

        assertThat(lote(batchId).get("batch_status")).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------
    // Lo que no se puede hacer
    // ------------------------------------------------------------------

    /**
     * El caso que descubri construyendo el reparto por proveedor: un lote
     * cargado desde Excel no tiene guia, y sin guia no hay a quien reclamarle.
     */
    @Test
    @DisplayName("Un lote sin guia de remision no se puede canjear")
    void sinGuiaNoHayCanje() {
        long batchId = fixtures.batch(storeId, productId, "LOT-CJE-E",
                LocalDate.now().plusDays(3), 10, "3.00");

        assertThatThrownBy(() -> returns.declare(storeId, batchId, 5, OPERARIO))
                .isInstanceOf(NotReturnableException.class)
                .hasMessageContaining("no vino por una guia");

        // Y la mercaderia no se movio: sigue entera y vendible.
        assertThat(lote(batchId).get("current_qty")).isEqualTo(10);
    }

    @Test
    @DisplayName("No se pueden canjear mas unidades de las que hay")
    void noSeCanjeaMasDeLoQueHay() {
        long batchId = loteConGuia("LOT-CJE-F", 5);

        assertThatThrownBy(() -> returns.declare(storeId, batchId, 9, OPERARIO))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(lote(batchId).get("current_qty")).isEqualTo(5);
    }

    @Test
    @DisplayName("La cantidad tiene que ser positiva")
    void cantidadInvalida() {
        long batchId = loteConGuia("LOT-CJE-G", 5);

        assertThatThrownBy(() -> returns.declare(storeId, batchId, 0, OPERARIO))
                .isInstanceOf(InvalidQuantityException.class);
    }

    /** Dos clics del gerente no pueden abonar el mismo canje dos veces. */
    @Test
    @DisplayName("Un canje resuelto no se vuelve a resolver")
    void laResolucionEsIdempotente() {
        long batchId = loteConGuia("LOT-CJE-H", 10);
        returns.declare(storeId, batchId, 4, OPERARIO);
        long returnId = idDelUnicoCanje();

        returns.credit(returnId, GERENTE);

        assertThatThrownBy(() -> returns.credit(returnId, GERENTE))
                .isInstanceOf(ReturnAlreadyResolvedException.class);
        assertThatThrownBy(() -> returns.refuse(returnId, GERENTE))
                .isInstanceOf(ReturnAlreadyResolvedException.class);
    }

    /**
     * Lo inmovilizado no se vende: es lo que impide prometerle al proveedor
     * unas unidades que mientras tanto pasaron por caja.
     */
    @Test
    @DisplayName("Lo apartado para el canje ya no se puede dar de baja")
    void loApartadoNoSeToca() {
        long batchId = loteConGuia("LOT-CJE-I", 10);
        returns.declare(storeId, batchId, 10, OPERARIO);

        assertThatThrownBy(() -> returns.declare(storeId, batchId, 1, OPERARIO))
                .isInstanceOf(InsufficientStockException.class);
    }

    // ------------------------------------------------------------------
    // Que no contamine el informe de mermas
    // ------------------------------------------------------------------

    /**
     * La razon de no meter el canje en {@code waste_log}: el reparto por
     * proveedor tiene que enseñar al que acepta devoluciones como el que mejor
     * se porta, no como el que mas perdidas causa.
     */
    @Test
    @DisplayName("Lo canjeado se cuenta aparte de lo perdido")
    void elCanjeNoSeCuentaComoMerma() {
        long canjeado = loteConGuia("LOT-CJE-J", 10);
        returns.declare(storeId, canjeado, 10, OPERARIO);
        returns.credit(idDelUnicoCanje(), GERENTE);

        long perdido = loteConGuia("LOT-CJE-K", 10);
        jdbcTemplate.update("""
                INSERT INTO waste_log (store_id, batch_id, product_id, quantity_discarded,
                                       unit_cost, waste_reason, approval_status, operator_id)
                VALUES (?, ?, ?, 10, 3.00, 'EXPIRATION', 'APPROVED', ?)
                """, storeId, perdido, productId, OPERARIO);

        Map<String, Object> proveedor = insights.bySupplier(storeId).getFirst();

        assertThat(new BigDecimal(proveedor.get("canjeado").toString()))
                .isEqualByComparingTo("30.00");
        assertThat(new BigDecimal(proveedor.get("merma").toString()))
                .isEqualByComparingTo("30.00");
        assertThat(proveedor.get("proveedor")).isEqualTo("Distribuidora Lacteos Canje");
    }

    /**
     * El canje bloquea el borrado de su lote ({@code ON DELETE RESTRICT}), que
     * es lo correcto -no se borra mercaderia prometida a un proveedor- pero
     * choca de frente con el reinicio de la demo, que borra los lotes de la
     * tienda 999. Sin la limpieza previa, el primer evaluador que declarase un
     * canje dejaria la demo sin poder restaurarse nunca mas.
     */
    @Test
    @DisplayName("La demo se puede restaurar aunque haya canjes declarados")
    void elCanjeNoBloqueaElReinicioDeLaDemo() {
        long batchId = loteConGuia("LOT-CJE-L", 10);
        returns.declare(storeId, batchId, 5, OPERARIO);

        // El borrado de lotes es lo que haria el procedimiento de la demo.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM inventory_batches WHERE batch_id = ?", batchId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Con la limpieza previa que hace DemoRepository, el borrado pasa.
        jdbcTemplate.update("DELETE FROM supplier_returns WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE batch_id = ?", batchId);

        assertThat(returns.list(storeId, null, 10)).isEmpty();
    }

    // ------------------------------------------------------------------

    private long proveedor(String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO suppliers (company_id, name, tax_id)
                SELECT s.company_id, ?, ?
                  FROM stores s WHERE s.store_id = ?
                RETURNING supplier_id
                """, Long.class, name,
                "20" + String.format("%09d", Math.abs(name.hashCode()) % 1_000_000_000), storeId);
    }

    private long loteConGuia(String code, int quantity) {
        return loteConGuia(code, LocalDate.now().plusDays(3), quantity);
    }

    /** Lote con trazabilidad completa: guia -> linea -> lote, como una recepcion real. */
    private long loteConGuia(String code, LocalDate expiration, int quantity) {
        Long receiptId = jdbcTemplate.queryForObject("""
                INSERT INTO goods_receipt_headers (store_id, supplier_id, grn_number, issue_date)
                VALUES (?, ?, ?, CURRENT_DATE) RETURNING receipt_id
                """, Long.class, storeId, supplierId, "GRN-" + code);

        Long itemId = jdbcTemplate.queryForObject("""
                INSERT INTO goods_receipt_items (receipt_id, product_id, invoiced_qty, received_qty, unit_cost)
                VALUES (?, ?, ?, ?, 3.00) RETURNING item_id
                """, Long.class, receiptId, productId, quantity, quantity);

        return jdbcTemplate.queryForObject("""
                INSERT INTO inventory_batches (store_id, product_id, receipt_item_id, batch_code,
                                               expiration_date, initial_qty, current_qty, unit_cost, batch_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 3.00, 'ACTIVE')
                RETURNING batch_id
                """, Long.class, storeId, productId, itemId, code, expiration, quantity, quantity);
    }

    private long idDelUnicoCanje() {
        return returns.list(storeId, "PENDING", 10).getFirst().returnId();
    }

    private Map<String, Object> lote(long batchId) {
        return jdbcTemplate.queryForMap(
                "SELECT current_qty, quarantined_qty, batch_status FROM inventory_batches WHERE batch_id = ?",
                batchId);
    }
}
