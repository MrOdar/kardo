package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.BatchNotFoundException;
import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.WasteAlreadyResolvedException;
import com.kardo.sales.FefoSaleService;
import com.kardo.waste.WasteService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Circuito de mermas y el invariante de masa del Reto 1.
 *
 * <p>Lo que se verifica no es que los procedimientos "funcionen", sino que el
 * invariante {@code current_qty + quarantined_qty <= initial_qty} se sostenga en
 * cada paso: es la pieza que impide que un doble rechazo infle el inventario.
 */
class WasteWorkflowIT extends PostgresIntegrationTest {

    private static final String SKU = "SKU-YOG-FRESA";

    @Autowired
    private WasteService wasteService;

    @Autowired
    private FefoSaleService fefoSaleService;

    private long storeId;
    private long batchId;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Mermas", "20300000003").store("TIENDA-M");
        long yogurt = fixtures.product(storeId, SKU, "Yogurt Fresa 1L", "4.50", "6.80");
        batchId = fixtures.batch(storeId, yogurt, "LOTE-MERMA", LocalDate.now().plusDays(5), 30, "4.50");
    }

    @Test
    @DisplayName("Declarar una merma inmoviliza las unidades sin romper el balance de masa")
    void declararReservaEnCuarentena() {
        wasteService.report(storeId, batchId, 6, "EXPIRATION", "operario@kardo.pe");

        assertThat(masa()).isEqualTo("24/6/30");
        assertThat(estadoMerma()).isEqualTo("PENDING_APPROVAL");
        assertThat(perdida()).isEqualByComparingTo("27.00");
    }

    @Test
    @DisplayName("El motor FEFO no puede vender lo que esta en cuarentena")
    void laCuarentenaNoSeVende() {
        wasteService.report(storeId, batchId, 25, "DAMAGE", "operario@kardo.pe");

        // Quedan 5 disponibles de 30: pedir 6 ya no alcanza
        assertThatThrownBy(() -> fefoSaleService.sell(storeId, SKU, 6, "operario@kardo.pe"))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(masa()).isEqualTo("5/25/30");
    }

    /**
     * El historial de la interfaz pide la lista <b>sin filtro</b>, y ese camino
     * nunca se habia ejercitado: la pantalla solo consultaba las pendientes. Un
     * {@code ?} suelto dentro de un {@code IS NULL} hacia que PostgreSQL
     * rechazara la sentencia entera, de modo que el endpoint respondia 500 a su
     * propio caso por defecto.
     */
    @Test
    @DisplayName("Listar sin filtro devuelve todas las bajas, resueltas o no")
    void listarSinFiltro() {
        wasteService.report(storeId, batchId, 4, "EXPIRATION", "operario@kardo.pe");
        wasteService.approve(ultimaMerma(), "gerente@kardo.pe");
        wasteService.report(storeId, batchId, 3, "DAMAGE", "operario@kardo.pe");

        assertThat(wasteService.list(storeId, null, 50))
                .hasSize(2)
                .extracting("status")
                .containsExactlyInAnyOrder("APPROVED", "PENDING_APPROVAL");

        // Y el filtro sigue acotando cuando se usa
        assertThat(wasteService.list(storeId, "APPROVED", 50)).hasSize(1);
    }

    @Test
    @DisplayName("Aprobar consolida la perdida: las unidades no vuelven")
    void aprobarConsolida() {
        wasteService.report(storeId, batchId, 6, "EXPIRATION", "operario@kardo.pe");
        wasteService.approve(ultimaMerma(), "gerente@kardo.pe");

        assertThat(masa()).isEqualTo("24/0/30");
        assertThat(estadoMerma()).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT authorized_by FROM waste_log WHERE waste_id = ?", String.class, ultimaMerma()))
                .isEqualTo("gerente@kardo.pe");
    }

    @Test
    @DisplayName("Rechazar devuelve la mercaderia al stock vendible")
    void rechazarDevuelveElStock() {
        wasteService.report(storeId, batchId, 6, "COLD_CHAIN_FAILURE", "operario@kardo.pe");
        wasteService.reject(ultimaMerma(), "gerente@kardo.pe");

        assertThat(masa()).isEqualTo("30/0/30");
        assertThat(estadoMerma()).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT batch_status FROM inventory_batches WHERE batch_id = ?", String.class, batchId))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Resolver dos veces la misma merma no la aplica dos veces")
    void resolucionIdempotente() {
        wasteService.report(storeId, batchId, 6, "EXPIRATION", "operario@kardo.pe");
        long merma = ultimaMerma();
        wasteService.approve(merma, "gerente@kardo.pe");

        assertThatThrownBy(() -> wasteService.approve(merma, "gerente@kardo.pe"))
                .isInstanceOf(WasteAlreadyResolvedException.class);
        assertThatThrownBy(() -> wasteService.reject(merma, "gerente@kardo.pe"))
                .isInstanceOf(WasteAlreadyResolvedException.class);

        // El lote quedo como lo dejo la aprobacion, sin doble descuento
        assertThat(masa()).isEqualTo("24/0/30");
    }

    @Test
    @DisplayName("No se puede dar de baja mas de lo disponible")
    void masDeLoDisponible() {
        assertThatThrownBy(() -> wasteService.report(storeId, batchId, 31, "DAMAGE", "operario@kardo.pe"))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(masa()).isEqualTo("30/0/30");
    }

    @Test
    @DisplayName("Un lote de otra tienda no existe para esta")
    void loteDeOtraTienda() {
        Fixtures otra = new Fixtures(jdbcTemplate);
        long otraTienda = otra.company("Ajena", "20400000004").store("TIENDA-A");
        long producto = otra.product(otraTienda, "SKU-AJENO", "Ajeno", "1.00", "2.00");
        long loteAjeno = otra.batch(otraTienda, producto, "LOTE-AJENO", LocalDate.now().plusDays(10), 10, "1.00");

        assertThatThrownBy(() -> wasteService.report(storeId, loteAjeno, 1, "DAMAGE", "operario@kardo.pe"))
                .isInstanceOf(BatchNotFoundException.class);
    }

    @Test
    @DisplayName("Dar de baja todo el lote lo deja agotado, no negativo")
    void bajaTotal() {
        wasteService.report(storeId, batchId, 30, "RECALL", "operario@kardo.pe");

        assertThat(masa()).isEqualTo("0/30/30");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT batch_status FROM inventory_batches WHERE batch_id = ?", String.class, batchId))
                .isEqualTo("DEPLETED");
    }

    /** current/cuarentena/inicial, para leer el invariante de un vistazo. */
    private String masa() {
        return jdbcTemplate.queryForObject("""
                SELECT current_qty || '/' || quarantined_qty || '/' || initial_qty
                FROM inventory_batches WHERE batch_id = ?
                """, String.class, batchId);
    }

    private long ultimaMerma() {
        return jdbcTemplate.queryForObject(
                "SELECT max(waste_id) FROM waste_log WHERE batch_id = ?", Long.class, batchId);
    }

    private String estadoMerma() {
        return jdbcTemplate.queryForObject(
                "SELECT approval_status FROM waste_log WHERE waste_id = ?", String.class, ultimaMerma());
    }

    private java.math.BigDecimal perdida() {
        return jdbcTemplate.queryForObject(
                "SELECT total_loss_amount FROM waste_log WHERE waste_id = ?",
                java.math.BigDecimal.class, ultimaMerma());
    }
}
