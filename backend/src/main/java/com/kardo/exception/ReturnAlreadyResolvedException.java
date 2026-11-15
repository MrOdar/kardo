package com.kardo.exception;

/**
 * El canje ya fue abonado o rechazado. Mapea a HTTP 409.
 *
 * <p>Igual que en las mermas, es la respuesta al segundo clic: la resolucion no
 * se aplica dos veces sobre el lote.
 */
public class ReturnAlreadyResolvedException extends RuntimeException {

    public ReturnAlreadyResolvedException(long returnId) {
        super("El canje " + returnId + " ya fue resuelto.");
    }
}
