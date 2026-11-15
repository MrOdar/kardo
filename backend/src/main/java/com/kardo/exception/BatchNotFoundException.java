package com.kardo.exception;

/** El lote no existe o no pertenece a la tienda del usuario. Mapea a HTTP 404. */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(long batchId) {
        super("El lote " + batchId + " no existe en esta tienda.");
    }
}
