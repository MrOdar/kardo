package com.kardo.exception;

import java.time.Instant;

/** Cuerpo uniforme de error para el SPA. */
public record ApiError(Instant timestamp, int status, String error, String message) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message);
    }
}
