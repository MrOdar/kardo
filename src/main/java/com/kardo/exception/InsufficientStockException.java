package com.kardo.exception;

/** Se lanza cuando {@code sp_process_fefo_sale} no encuentra stock suficiente. Mapea a HTTP 409. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
