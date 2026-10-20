package com.kardo.exception;

/** La tienda indicada no existe. Mapea a HTTP 404. */
public class StoreNotFoundException extends RuntimeException {

    public StoreNotFoundException(long storeId) {
        super("La tienda " + storeId + " no existe.");
    }
}
