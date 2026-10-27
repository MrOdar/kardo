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

    private ForbiddenStoreException(String message) {
        super(message);
    }

    /**
     * Alta de un usuario en una tienda ajena.
     *
     * <p>El limite aqui no es la tienda del token sino la <b>empresa</b>: un
     * gerente puede dar de alta en cualquiera de sus tiendas, y en ninguna mas.
     */
    public static ForbiddenStoreException outsideCompany(long requestedStoreId, long companyId) {
        return new ForbiddenStoreException("Intento de dar de alta un usuario en la tienda "
                + requestedStoreId + ", que no pertenece a la empresa " + companyId + ".");
    }
}
