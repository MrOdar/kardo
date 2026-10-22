package com.kardo.exception;

/**
 * Gemini respondio, pero su salida no es la guia en el JSON pactado.
 * Mapea a HTTP 502: el fallo es de un servicio aguas arriba, no del cliente.
 */
public class GeminiExtractionException extends RuntimeException {

    public GeminiExtractionException(String message) {
        super(message);
    }

    public GeminiExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
