package com.kardo.exception;

/** El SKU no existe en el catalogo de la compania. Mapea a HTTP 404. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String sku) {
        super("El producto " + sku + " no existe en el catalogo de esta tienda.");
    }
}
