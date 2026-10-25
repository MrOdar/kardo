package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.SelfApprovalException;
import com.kardo.waste.WasteService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Segregación de funciones: quien declara una merma no puede resolverla.
 *
 * <p>El control por rol dejaba fuera el caso que importa en una tienda pequeña:
 * el gerente que declara una pérdida y se la aprueba a sí mismo. Esta regla es
 * lo que separa un registro de mermas de un control antifraude.
 */
class WasteSegregationIT extends PostgresIntegrationTest {

    private static final String OPERARIO = "operario@kardo.pe";
    private static final String GERENTE = "gerente@kardo.pe";

    @Autowired
    private WasteService wasteService;

    private long storeId;
    private long batchId;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Segregacion", "21100000011").store("TIENDA-S");
        long producto = fixtures.product(storeId, "SKU-SEG", "Producto", "2.00", "3.00");
        batchId = fixtures.batch(storeId, producto, "LOTE-SEG", LocalDate.now().plusDays(10), 20, "2.00");
    }

    @Test
    @DisplayName("El autor no puede aprobar su propia merma")
    void noPuedeAprobarLoQueDeclaro() {
        wasteService.report(storeId, batchId, 5, "DAMAGE", GERENTE);
        long merma = ultimaMerma();

        assertThatThrownBy(() -> wasteService.approve(merma, GERENTE))
                .isInstanceOf(SelfApprovalException.class)
                .hasMessageContaining(GERENTE);

        // La merma sigue pendiente y las unidades, inmovilizadas
        assertThat(estado()).isEqualTo("PENDING_APPROVAL");
        assertThat(cuarentena()).isEqualTo(5);
    }

    @Test
    @DisplayName("Tampoco puede rechazarla: devolver el stock sin revision es la otra mitad del fraude")
    void noPuedeRechazarLoQueDeclaro() {
        wasteService.report(storeId, batchId, 5, "DAMAGE", GERENTE);
        long merma = ultimaMerma();

        assertThatThrownBy(() -> wasteService.reject(merma, GERENTE))
                .isInstanceOf(SelfApprovalException.class);

        assertThat(estado()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("Otra persona si puede resolverla")
    void otraPersonaResuelve() {
        wasteService.report(storeId, batchId, 5, "EXPIRATION", OPERARIO);
        wasteService.approve(ultimaMerma(), GERENTE);

        assertThat(estado()).isEqualTo("APPROVED");
        assertThat(cuarentena()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT authorized_by FROM waste_log WHERE waste_id = ?", String.class, ultimaMerma()))
                .isEqualTo(GERENTE);
    }

    @Test
    @DisplayName("El rechazo por otra persona devuelve la mercaderia")
    void otraPersonaRechaza() {
        wasteService.report(storeId, batchId, 5, "EXPIRATION", OPERARIO);
        wasteService.reject(ultimaMerma(), GERENTE);

        assertThat(estado()).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_qty FROM inventory_batches WHERE batch_id = ?", Integer.class, batchId))
                .isEqualTo(20);
    }

    private long ultimaMerma() {
        return jdbcTemplate.queryForObject(
                "SELECT max(waste_id) FROM waste_log WHERE batch_id = ?", Long.class, batchId);
    }

    private String estado() {
        return jdbcTemplate.queryForObject(
                "SELECT approval_status FROM waste_log WHERE waste_id = ?", String.class, ultimaMerma());
    }

    private int cuarentena() {
        return jdbcTemplate.queryForObject(
                "SELECT quarantined_qty FROM inventory_batches WHERE batch_id = ?", Integer.class, batchId);
    }
}
