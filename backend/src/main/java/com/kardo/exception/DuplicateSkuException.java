package com.kardo.exception;

/**
 * Ese SKU ya existe en el catalogo de la compania. Mapea a HTTP 409.
 *
 * <p>La ingesta por Excel hace {@code ON CONFLICT DO UPDATE} con el mismo SKU, y
 * ahi es lo correcto: un archivo del proveedor trae el catalogo entero y se
 * espera que actualice precios. Un alta manual es lo contrario -alguien teclea
 * un producto que cree nuevo- y pisar en silencio el precio de uno que ya
 * existia seria una sorpresa cara. Mejor negarse y decir cual es.
 */
public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku, String existingName) {
        super("El SKU " + sku + " ya existe en el catalogo: \"" + existingName
                + "\". Usa otro codigo, o corrige el producto desde una carga de Excel.");
    }
}
