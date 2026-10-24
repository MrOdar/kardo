package com.kardo.exception;

/**
 * La merma ya fue aprobada o rechazada. Mapea a HTTP 409.
 *
 * <p>Es la respuesta a un segundo clic del gerente: la resolucion es idempotente
 * y no se aplica dos veces sobre el lote.
 */
public class WasteAlreadyResolvedException extends RuntimeException {

    public WasteAlreadyResolvedException(long wasteId) {
        super("La merma " + wasteId + " ya fue resuelta.");
    }
}
