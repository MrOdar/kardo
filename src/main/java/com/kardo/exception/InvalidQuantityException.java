package com.kardo.exception;

/**
 * Cantidad no valida en una salida de inventario (venta o merma).
 * Mapea a HTTP 400.
 */
public class InvalidQuantityException extends RuntimeException {

    public InvalidQuantityException(String message) {
        super(message);
    }
}
