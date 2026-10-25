package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.BatchNotFoundException;
import com.kardo.inventory.ExpiryAlertService;
import com.kardo.inventory.MarkdownAction;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El panel de alertas contra PostgreSQL real.
 *
 * <p>Lo que se verifica aqui no es la escalera -eso ya lo fija
 * {@code DiscountLadderTest} sin base de datos- sino lo que solo puede fallar
 * con datos de verdad: que el panel se pueda callar, que vuelva a hablar cuando
 * la recomendacion cambia, y que no asome mercaderia de otra tienda.
 */
class ExpiryAlertIT extends PostgresIntegrationTest {

    @Autowired
    private ExpiryAlertService alertService;

    private Fixtures fixtures;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Alertas", "21300000013").store("TIENDA-AL");
        // Costo 4.00, lista 10.00: margen amplio, el piso del costo no interfiere
        productId = fixtures.product(storeId, "SKU-ALERTA", "Queso fresco 500g", "4.00", "10.00");
    }

    @Test
    @DisplayName("Solo entra al panel lo que esta dentro del horizonte")
    void horizonte() {
        fixtures.batch(storeId, productId, "LOT-2D", LocalDate.now().plusDays(2), 10, "4.00");
        fixtures.batch(storeId, productId, "LOT-60D", LocalDate.now().plusDays(60), 10, "4.00");

        var board = alertService.board(storeId);

        assertThat(board.alerts()).hasSize(1);
        assertThat(board.alerts().get(0).batchCode()).isEqualTo("LOT-2D");
        assertThat(board.alerts().get(0).discountPercent()).isEqualTo(30);
        assertThat(board.valueAtRisk()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Decidir una alerta la calla, y el riesgo sigue contandose")
    void decidirCalla() {
        long batchId = fixtures.batch(storeId, productId, "LOT-3D", LocalDate.now().plusDays(3), 10, "4.00");

        var board = alertService.decide(storeId, batchId, "APPLIED", "operario@kardo.pe");

        assertThat(board.alerts()).isEmpty();
        assertThat(board.silenced()).isEqualTo(1);
        // El dinero sigue en el anaquel: callar la alerta no borra el riesgo
        assertThat(board.valueAtRisk()).isEqualByComparingTo("40.00");
        // Y nada que aplicar significa nada de margen en juego
        assertThat(board.marginSacrificed()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("La decision queda firmada con su autor y el precio de ese momento")
    void decisionAuditada() {
        long batchId = fixtures.batch(storeId, productId, "LOT-1D", LocalDate.now().plusDays(1), 5, "4.00");

        alertService.decide(storeId, batchId, "APPLIED", "operario@kardo.pe");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT decision || '|' || step_percent || '|' || suggested_price || '|' || decided_by
                FROM markdown_decisions WHERE batch_id = ?
                """, String.class, batchId))
                .isEqualTo("APPLIED|40|6.00|operario@kardo.pe");

        assertThat(alertService.history(storeId, 10)).hasSize(1);
    }

    /**
     * La regla que hace que el panel siga siendo creible: lo que descartaste
     * vale para la recomendacion que viste, no para la siguiente.
     */
    @Test
    @DisplayName("Al bajar un peldaño la alerta vuelve a hablar")
    void alBajarPeldanoVuelve() {
        long batchId = fixtures.batch(storeId, productId, "LOT-MOV", LocalDate.now().plusDays(4), 10, "4.00");

        alertService.decide(storeId, batchId, "DISMISSED", "operario@kardo.pe");
        assertThat(alertService.board(storeId).alerts()).isEmpty();

        // El lote se acerca al vencimiento: 20% se queda corto, ahora toca 40%
        jdbcTemplate.update("UPDATE inventory_batches SET expiration_date = CURRENT_DATE + 1 WHERE batch_id = ?",
                batchId);

        var board = alertService.board(storeId);

        assertThat(board.alerts()).hasSize(1);
        var alerta = board.alerts().get(0);
        assertThat(alerta.stepPercent()).isEqualTo(40);
        // Y la interfaz puede decir que ya se habia descartado al 20%
        assertThat(alerta.previousDecision()).isEqualTo("DISMISSED");
        assertThat(alerta.decidedStep()).isEqualTo(20);
    }

    @Test
    @DisplayName("Lo vencido se retira y ninguna decision previa lo silencia")
    void vencido() {
        long batchId = fixtures.batch(storeId, productId, "LOT-VENC", LocalDate.now().plusDays(2), 8, "4.00");
        alertService.decide(storeId, batchId, "APPLIED", "operario@kardo.pe");

        jdbcTemplate.update("""
                UPDATE inventory_batches SET expiration_date = CURRENT_DATE - 2, batch_status = 'EXPIRED'
                WHERE batch_id = ?
                """, batchId);

        var board = alertService.board(storeId);

        assertThat(board.alerts()).hasSize(1);
        assertThat(board.alerts().get(0).action()).isEqualTo(MarkdownAction.RETIRAR);
        assertThat(board.alerts().get(0).suggestedPrice()).isNull();
    }

    @Test
    @DisplayName("Las unidades en cuarentena no se rebajan")
    void cuarentenaNoSeRebaja() {
        long batchId = fixtures.batch(storeId, productId, "LOT-CUAR", LocalDate.now().plusDays(2), 10, "4.00");

        // Toda la existencia pasa a cuarentena por una merma sin resolver
        jdbcTemplate.update("""
                UPDATE inventory_batches SET current_qty = 0, quarantined_qty = 10 WHERE batch_id = ?
                """, batchId);

        assertThat(alertService.board(storeId).alerts()).isEmpty();
    }

    @Test
    @DisplayName("El panel de una tienda no muestra lotes de otra")
    void aislamientoPorTienda() {
        long otraTienda = fixtures.company("Minimarket Vecino", "21300000014").store("TIENDA-VE");
        long otroProducto = fixtures.product(otraTienda, "SKU-VECINO", "Queso vecino", "4.00", "10.00");
        fixtures.batch(otraTienda, otroProducto, "LOT-VECINO", LocalDate.now().plusDays(1), 10, "4.00");

        fixtures.batch(storeId, productId, "LOT-PROPIO", LocalDate.now().plusDays(1), 10, "4.00");

        assertThat(alertService.board(storeId).alerts())
                .extracting("batchCode")
                .containsExactly("LOT-PROPIO");
    }

    @Test
    @DisplayName("No se puede decidir sobre un lote de otra tienda")
    void decidirLoteAjeno() {
        long otraTienda = fixtures.company("Minimarket Ajeno", "21300000015").store("TIENDA-AJ");
        long otroProducto = fixtures.product(otraTienda, "SKU-AJENO", "Queso ajeno", "4.00", "10.00");
        long ajeno = fixtures.batch(otraTienda, otroProducto, "LOT-AJENO", LocalDate.now().plusDays(1), 10, "4.00");

        assertThatThrownBy(() -> alertService.decide(storeId, ajeno, "APPLIED", "intruso@kardo.pe"))
                .isInstanceOf(BatchNotFoundException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM markdown_decisions WHERE batch_id = ?", Integer.class, ajeno))
                .isZero();
    }

    /**
     * Revertir una carga borra sus lotes; una rebaja decidida sobre uno de ellos
     * no puede ser lo que bloquee esa reversion.
     */
    @Test
    @DisplayName("Borrar el lote se lleva su decision y no bloquea la reversion")
    void laDecisionNoBloqueaElBorrado() {
        long batchId = fixtures.batch(storeId, productId, "LOT-BORRA", LocalDate.now().plusDays(2), 10, "4.00");
        alertService.decide(storeId, batchId, "APPLIED", "operario@kardo.pe");

        jdbcTemplate.update("DELETE FROM inventory_batches WHERE batch_id = ?", batchId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM markdown_decisions WHERE batch_id = ?", Integer.class, batchId))
                .isZero();
    }
}
