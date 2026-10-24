package com.kardo.exception;

/** Salida mal formada, p. ej. cantidad no positiva. Mapea a HTTP 400. */
public class InvalidSaleException extends RuntimeException {

    public InvalidSaleException(String message) {
        super(message);
    }
}
