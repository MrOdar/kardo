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

    /**
     * Intento de cambiar a una tienda en la que no se trabaja.
     *
     * <p>Es lo unico que separa el cambio de tienda de una via para emitirse un
     * token de cualquier tienda: sin membresia, no hay token.
     */
    public static ForbiddenStoreException notAMember(long requestedStoreId, String email) {
        return new ForbiddenStoreException("El usuario " + email
                + " intento operar sobre la tienda " + requestedStoreId + " sin trabajar en ella.");
    }
}
