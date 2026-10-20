package com.kardo.ingest;

/** Codigos de rechazo que el SPA usa para resaltar el campo exacto a corregir. */
public enum IngestErrorCode {

    MISSING_SKU("El SKU es obligatorio."),
    MISSING_NAME("El nombre del producto es obligatorio."),
    MISSING_CATEGORY("La categoria es obligatoria."),
    INVALID_COST("El costo unitario debe ser un numero mayor o igual a 0."),
    INVALID_PRICE("El precio de venta debe ser un numero mayor o igual a 0."),
    INVALID_QUANTITY("La cantidad inicial debe ser un numero entero mayor que 0."),
    MISSING_EXPIRATION_DATE("La fecha de vencimiento es obligatoria."),
    INVALID_DATE_FORMAT("Formato de fecha no reconocido (use dd/MM/yyyy o yyyy-MM-dd)."),
    EXPIRATION_BEFORE_MANUFACTURING("La fecha de vencimiento no puede ser anterior a la de fabricacion."),
    VALUE_TOO_LONG("Un valor excede la longitud maxima permitida por el catalogo.");

    private final String message;

    IngestErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
