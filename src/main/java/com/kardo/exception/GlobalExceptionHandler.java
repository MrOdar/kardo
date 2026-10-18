package com.kardo.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Manejo centralizado de excepciones con los codigos acordados en la arquitectura. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Carga rechazada por exceder el tamano maximo: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "El archivo excede el tamano maximo permitido de 10MB.");
    }

    @ExceptionHandler(CorruptSpreadsheetException.class)
    public ResponseEntity<ApiError> handleCorruptSpreadsheet(CorruptSpreadsheetException ex) {
        log.warn("Ingesta abortada tras {} filas corruptas", ex.getCorruptRows());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message));
    }
}
