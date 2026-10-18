package com.kardo.exception;

/**
 * Circuit Breaker de calidad: el archivo supero el umbral de filas corruptas y
 * la ingesta se aborto sin seguir consumiendo memoria. Mapea a HTTP 422.
 */
public class CorruptSpreadsheetException extends RuntimeException {

    private final int corruptRows;

    public CorruptSpreadsheetException(String message, int corruptRows) {
        super(message);
        this.corruptRows = corruptRows;
    }

    public int getCorruptRows() {
        return corruptRows;
    }
}
