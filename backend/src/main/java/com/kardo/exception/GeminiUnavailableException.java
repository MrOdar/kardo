package com.kardo.exception;

/** Toda la cadena de modelos de Gemini esta saturada. Mapea a HTTP 503. */
public class GeminiUnavailableException extends RuntimeException {

    public GeminiUnavailableException(String message) {
        super(message);
    }
}
