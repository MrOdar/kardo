package com.kardo.exception;

import java.time.Duration;

/**
 * Demasiados intentos. Mapea a HTTP 429 con cabecera {@code Retry-After}.
 *
 * <p>El mensaje nunca dice si la cuenta existe ni si la contraseña era correcta:
 * un freno que respondiera distinto a un correo real y a uno inventado seria un
 * buscador de usuarios dados de alta.
 */
public class TooManyAttemptsException extends RuntimeException {

    private final transient Duration retryAfter;

    public TooManyAttemptsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** Segundos para la cabecera, redondeados hacia arriba y nunca cero. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
