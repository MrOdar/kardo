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
