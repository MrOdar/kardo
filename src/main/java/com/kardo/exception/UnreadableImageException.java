package com.kardo.exception;

/** La imagen de la guia no se pudo decodificar. Mapea a HTTP 400. */
public class UnreadableImageException extends RuntimeException {

    public UnreadableImageException(String message) {
        super(message);
    }

    public UnreadableImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
