package com.kardo.exception;

/** Una linea confirmada es internamente incoherente. Mapea a HTTP 400. */
public class InvalidReceiptLineException extends RuntimeException {

    public InvalidReceiptLineException(int ocrLineId, String detail) {
        super("Linea " + ocrLineId + ": " + detail);
    }
}
