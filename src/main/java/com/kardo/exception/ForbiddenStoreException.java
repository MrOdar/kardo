package com.kardo.exception;

/**
 * El usuario intento operar sobre una tienda que no es la suya. Mapea a HTTP 403.
 *
 * <p>No es un error de validacion sino un intento de salto entre inquilinos, asi
 * que se registra con el detalle y se responde sin el.
 */
public class ForbiddenStoreException extends RuntimeException {

    public ForbiddenStoreException(long requestedStoreId, long allowedStoreId) {
        super("Intento de operar sobre la tienda " + requestedStoreId
                + " con una sesion de la tienda " + allowedStoreId + ".");
    }
}
