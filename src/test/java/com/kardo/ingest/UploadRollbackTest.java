package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.ingest.UploadRollback.BatchState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La regla que decide si una carga puede deshacerse. Es el corazon del rollback:
 * borrar un lote del que ya se vendio dejaria ventas apuntando a un lote
 * inexistente.
 */
class UploadRollbackTest {

    @Test
    @DisplayName("Una carga cuyos lotes nadie toco se puede revertir")
    void lotesIntactos() {
        List<BatchState> batches = List.of(
                batch(1, "L-A", 24, 24, 0, false),
                batch(2, "L-B", 48, 48, 0, false));

        assertThat(UploadRollback.blockers(batches)).isEmpty();
    }

    @Test
    @DisplayName("Un lote parcialmente vendido bloquea la reversion entera")
    void loteConsumido() {
        List<BatchState> batches = List.of(
                batch(1, "L-A", 24, 24, 0, false),
                batch(2, "L-B", 48, 40, 0, true));

        var blockers = UploadRollback.blockers(batches);

        // Todo o nada: no se revierte "lo que se pueda"
        assertThat(blockers).hasSize(1);
        assertThat(blockers.get(0).describe()).contains("L-B").contains("ventas o mermas");
    }

    @Test
    @DisplayName("Un lote con unidades en cuarentena tambien bloquea")
    void loteEnCuarentena() {
        var blockers = UploadRollback.blockers(List.of(batch(3, "L-C", 30, 24, 6, false)));

        assertThat(blockers).singleElement()
                .satisfies(blocker -> assertThat(blocker.describe())
                        .contains("6 unidades en cuarentena"));
    }

    @Test
    @DisplayName("Un lote entero pero con movimientos registrados no es intacto")
    void loteConHistoria() {
        // Cantidad igual a la inicial, pero hubo una merma rechazada: hay historia
        var blockers = UploadRollback.blockers(List.of(batch(4, "L-D", 10, 10, 0, true)));

        assertThat(blockers).hasSize(1);
    }

    @Test
    @DisplayName("Un lote consumido sin movimientos explica cuanto queda")
    void loteMermado() {
        var blockers = UploadRollback.blockers(List.of(batch(5, "L-E", 20, 12, 0, false)));

        assertThat(blockers).singleElement()
                .satisfies(blocker -> assertThat(blocker.describe()).contains("quedan 12 de 20"));
    }

    private static BatchState batch(long id, String code, int initial, int current,
                                    int quarantined, boolean movements) {
        return new BatchState(id, code, "Producto " + code, initial, current, quarantined, movements);
    }
}
