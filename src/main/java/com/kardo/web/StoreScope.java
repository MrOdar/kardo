package com.kardo.web;

import com.kardo.exception.ForbiddenStoreException;
import com.kardo.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolucion de la tienda sobre la que actua una peticion.
 *
 * <p>La respuesta siempre sale del token. Cuando el cuerpo de la peticion trae
 * tambien un {@code storeId} (porque el contrato con la SPA lo incluye), se
 * comprueba que coincida y se rechaza si no: callarlo convertiria un intento de
 * salto entre tiendas en una escritura silenciosa sobre la tienda equivocada.
 */
final class StoreScope {

    private static final Logger log = LoggerFactory.getLogger(StoreScope.class);

    private StoreScope() {
    }

    /** Tienda del usuario autenticado. */
    static long current() {
        return CurrentUser.storeId();
    }

    /**
     * @param requestedStoreId tienda que vino en el cuerpo de la peticion
     * @return la tienda del token
     * @throws ForbiddenStoreException si el cliente pidio otra
     */
    static long requireMatching(Long requestedStoreId) {
        long allowed = current();
        if (requestedStoreId != null && requestedStoreId != allowed) {
            ForbiddenStoreException ex = new ForbiddenStoreException(requestedStoreId, allowed);
            log.warn("{} (usuario {})", ex.getMessage(), CurrentUser.require().email());
            throw ex;
        }
        return allowed;
    }
}
