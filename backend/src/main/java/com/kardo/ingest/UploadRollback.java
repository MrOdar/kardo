package com.kardo.ingest;

import java.util.List;

/**
 * Regla que decide si una carga todavia puede deshacerse.
 *
 * <p>Revertir no es borrar lo que la carga escribio: es borrarlo solo si nadie
 * lo ha usado todavia. Un lote del que ya se vendio, del que se declaro una
 * merma o que quedo parcialmente consumido es historia real del almacen, y
 * eliminarlo dejaria ventas apuntando a un lote inexistente y descuadraria el
 * inventario fisico contra el sistema.
 *
 * <p>Por eso la reversion es todo o nada: si un solo lote fue tocado, se rechaza
 * entera y se nombra cual, en lugar de deshacer "lo que se pueda".
 */
final class UploadRollback {

    private UploadRollback() {
    }

    /**
     * Estado de un lote creado por la carga.
     *
     * @param hasMovements existe alguna venta o merma que lo referencie
     */
    record BatchState(long batchId, String batchCode, String productName,
                      int initialQty, int currentQty, int quarantinedQty, boolean hasMovements) {

        /** Intacto: sigue entero, sin nada reservado y sin movimientos. */
        boolean isUntouched() {
            return currentQty == initialQty && quarantinedQty == 0 && !hasMovements;
        }

        String describe() {
            if (hasMovements) {
                return batchCode + " (" + productName + "): ya tiene ventas o mermas registradas";
            }
            if (quarantinedQty > 0) {
                return batchCode + " (" + productName + "): tiene " + quarantinedQty + " unidades en cuarentena";
            }
            return batchCode + " (" + productName + "): quedan " + currentQty
                    + " de " + initialQty + " unidades";
        }
    }

    /** @return los lotes que impiden revertir; vacio si la carga puede deshacerse */
    static List<BatchState> blockers(List<BatchState> batches) {
        return batches.stream().filter(batch -> !batch.isUntouched()).toList();
    }
}
