package com.kardo.exception;

/**
 * El archivo trae mas filas de datos de las que la ingesta acepta.
 *
 * <p>No es lo mismo que {@link CorruptSpreadsheetException}: ahi el archivo
 * esta mal y por eso se rechaza; aqui esta bien y lo que no cabe es el trabajo.
 * El tope existe porque la ingesta corre en una sola transaccion sobre un pool
 * de cinco conexiones: un .xlsx de 10 MB -lo que admite el multipart- cabe
 * comprimido y puede traer millones de filas, y mientras se procesan, el resto
 * de las tiendas no puede vender. Mapea a HTTP 413.
 */
public class SpreadsheetTooLargeException extends RuntimeException {

    private final int maxRows;

    public SpreadsheetTooLargeException(int maxRows) {
        super("El archivo supera las " + maxRows
                + " filas que admite una sola carga. Dividelo en varias.");
        this.maxRows = maxRows;
    }

    public int maxRows() {
        return maxRows;
    }
}
