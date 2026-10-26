package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.dto.SaleResponseDto;
import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.ProductNotFoundException;
import com.kardo.sales.FefoSaleService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El motor FEFO contra PostgreSQL real.
 *
 * <p>Cubre el fallo mas grave que encontramos: {@code sp_process_fefo_sale}
 * resolvia el producto sin filtrar por compania, de modo que con dos clientes
 * usando el mismo SKU descontaba del inventario equivocado.
 */
class FefoSaleIT extends PostgresIntegrationTest {

    private static final String SKU = "SKU-LECHE-UHT-1L";

    @Autowired
    private FefoSaleService fefoSaleService;

    private long storeId;
    private long rivalStoreId;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Uno", "20100000001").store("TIENDA-1");
        rivalStoreId = fixtures.company("Competencia S.A.", "20200000002").store("TIENDA-2");

        long leche = fixtures.product(storeId, SKU, "Leche Entera UHT 1L", "3.80", "5.20");
        // Mismo SKU, otra compania: es el escenario que el bug confundia
        long lecheRival = fixtures.product(rivalStoreId, SKU, "Leche de la competencia", "1.00", "2.00");

        fixtures.batch(storeId, leche, "LOTE-VENCE-PRONTO", LocalDate.now().plusDays(2), 20, "3.80");
        fixtures.batch(storeId, leche, "LOTE-FRESCO", LocalDate.now().plusDays(30), 50, "3.80");
        fixtures.batch(rivalStoreId, lecheRival, "LOTE-RIVAL", LocalDate.now().plusDays(60), 100, "1.00");
    }

    @Test
    @DisplayName("Vende primero el lote que vence antes y pasa al siguiente")
    void respetaElOrdenFefo() {
        SaleResponseDto sale = fefoSaleService.sell(storeId, SKU, 25, "operario@kardo.pe");

        assertThat(sale.consumedBatches())
                .extracting(line -> line.batchCode() + ":" + line.quantity())
                .containsExactly("LOTE-VENCE-PRONTO:20", "LOTE-FRESCO:5");

        assertThat(estado("LOTE-VENCE-PRONTO")).isEqualTo("0/DEPLETED");
        assertThat(estado("LOTE-FRESCO")).isEqualTo("45/ACTIVE");

        // El costo que se guarda es el del lote consumido, no un promedio
        assertThat(sale.cogs()).isEqualByComparingTo("95.00");
        assertThat(sale.margin()).isEqualByComparingTo("35.00");
    }

    /**
     * El agujero que cerro {@code sql/11}: la escalera recomendaba un precio y
     * guardaba quien lo decidio, pero la venta seguia cobrando el de lista. El
     * margen quedaba mal justo en los lotes que el propio sistema mandaba
     * rebajar, y la rebaja no se podia comprobar despues en los libros.
     */
    @Test
    @DisplayName("La venta cobra el precio rebajado del lote, no el de lista")
    void laVentaRespetaLaRebaja() {
        rebajar("LOTE-VENCE-PRONTO", "4.00");

        SaleResponseDto sale = fefoSaleService.sell(storeId, SKU, 10, "operario@kardo.pe");

        assertThat(sale.consumedBatches()).singleElement()
                .extracting(line -> line.unitRetailPrice().stripTrailingZeros())
                .isEqualTo(new java.math.BigDecimal("4"));

        // 10 x 4.00 y no 10 x 5.20
        assertThat(sale.revenue()).isEqualByComparingTo("40.00");
        assertThat(sale.cogs()).isEqualByComparingTo("38.00");
        assertThat(sale.margin()).isEqualByComparingTo("2.00");
    }

    /**
     * Es el caso que de verdad distingue "precio del lote" de "precio del
     * producto": la misma venta cruza un lote en oferta y otro que no, y cada
     * asiento debe salir con el precio que ese lote tenia en el anaquel.
     */
    @Test
    @DisplayName("Una venta que cruza dos lotes cobra el precio de cada uno")
    void cadaLoteConSuPrecio() {
        rebajar("LOTE-VENCE-PRONTO", "4.00");

        SaleResponseDto sale = fefoSaleService.sell(storeId, SKU, 25, "operario@kardo.pe");

        assertThat(sale.consumedBatches())
                .extracting(line -> line.batchCode() + "@" + line.unitRetailPrice().stripTrailingZeros())
                .containsExactly("LOTE-VENCE-PRONTO@4", "LOTE-FRESCO@5.2");

        // 20 x 4.00 en oferta + 5 x 5.20 a precio de lista
        assertThat(sale.revenue()).isEqualByComparingTo("106.00");
    }

    @Test
    @DisplayName("Sin rebaja vigente se sigue cobrando el precio de lista")
    void sinRebajaElPrecioEsElDeLista() {
        SaleResponseDto sale = fefoSaleService.sell(storeId, SKU, 10, "operario@kardo.pe");

        assertThat(sale.revenue()).isEqualByComparingTo("52.00");
    }

    /**
     * El piso del costo deja de depender de que todos pasen por la escalera:
     * lo impide la base, que es donde no se puede esquivar.
     */
    @Test
    @DisplayName("La base rechaza una oferta por debajo del costo del lote")
    void laBaseNoAdmiteVenderAPerdida() {
        assertThatThrownBy(() -> rebajar("LOTE-VENCE-PRONTO", "3.00"))
                .hasMessageContaining("chk_markdown_not_below_cost");

        // Justo en el costo si: ahi se recupera lo pagado, que es el limite
        rebajar("LOTE-VENCE-PRONTO", "3.80");
        assertThat(fefoSaleService.sell(storeId, SKU, 5, "operario@kardo.pe").revenue())
                .isEqualByComparingTo("19.00");
    }

    private void rebajar(String batchCode, String price) {
        jdbcTemplate.update("UPDATE inventory_batches SET markdown_price = ? WHERE batch_code = ?",
                new java.math.BigDecimal(price), batchCode);
    }

    @Test
    @DisplayName("No toca el inventario de otra compania con el mismo SKU")
    void aislamientoEntreCompanias() {
        fefoSaleService.sell(storeId, SKU, 25, "operario@kardo.pe");

        assertThat(estado(rivalStoreId, "LOTE-RIVAL")).isEqualTo("100/ACTIVE");
    }

    @Test
    @DisplayName("Cada tienda vende de su propio stock")
    void cadaTiendaVendeLoSuyo() {
        fefoSaleService.sell(rivalStoreId, SKU, 10, "otro@rival.pe");

        assertThat(estado(rivalStoreId, "LOTE-RIVAL")).isEqualTo("90/ACTIVE");
        assertThat(estado(storeId, "LOTE-VENCE-PRONTO")).isEqualTo("20/ACTIVE");
    }

    @Test
    @DisplayName("Sin stock suficiente no vende nada: la transaccion se deshace entera")
    void stockInsuficienteNoDejaRastro() {
        assertThatThrownBy(() -> fefoSaleService.sell(storeId, SKU, 999, "operario@kardo.pe"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Faltaron");

        assertThat(estado("LOTE-VENCE-PRONTO")).isEqualTo("20/ACTIVE");
        assertThat(estado("LOTE-FRESCO")).isEqualTo("50/ACTIVE");
        assertThat(ventasRegistradas()).isZero();
    }

    @Test
    @DisplayName("Un SKU ajeno al catalogo de la tienda no existe para ella")
    void skuFueraDelCatalogo() {
        assertThatThrownBy(() -> fefoSaleService.sell(storeId, "SKU-FANTASMA", 1, "operario@kardo.pe"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("Vender cero unidades es un error, no una venta vacia")
    void cantidadNoPositiva() {
        assertThatThrownBy(() -> fefoSaleService.sell(storeId, SKU, 0, "operario@kardo.pe"))
                .isInstanceOf(InvalidQuantityException.class);

        assertThat(ventasRegistradas()).isZero();
    }

    @Test
    @DisplayName("Un lote vencido no se vende aunque tenga stock")
    void loteVencidoNoSeVende() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        long yogurt = fixtures.product(storeId, "SKU-YOG", "Yogurt", "4.50", "6.80");
        fixtures.batch(storeId, yogurt, "LOTE-VENCIDO", LocalDate.now().minusDays(1), 30, "4.50");

        assertThatThrownBy(() -> fefoSaleService.sell(storeId, "SKU-YOG", 1, "operario@kardo.pe"))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(estado("LOTE-VENCIDO")).isEqualTo("30/ACTIVE");
    }

    private String estado(long store, String batchCode) {
        return jdbcTemplate.queryForObject(
                "SELECT current_qty || '/' || batch_status FROM inventory_batches WHERE store_id = ? AND batch_code = ?",
                String.class, store, batchCode);
    }

    private String estado(String batchCode) {
        return estado(storeId, batchCode);
    }

    private int ventasRegistradas() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE store_id = ?", Integer.class, storeId);
    }
}
