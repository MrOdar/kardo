package com.kardo.ingest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compuerta de calidad de una fila de ventas.
 *
 * <p>Comprueba solo lo que se puede saber sin tocar la base: que haya SKU, que
 * la cantidad sea un entero positivo y que la fecha, si viene, sea una fecha
 * pasada o de hoy. Lo demas -que el SKU exista, que haya stock- lo sabe
 * {@code sp_process_fefo_sale} y no se adivina aqui: duplicar esas reglas en
 * Java las dejaria desincronizadas el dia que cambien en el procedimiento, que
 * es donde viven.
 */
final class SalesRowGate {

    /** Lima. La fecha de una venta es la del local, no la del servidor en Virginia. */
    private static final ZoneId TIENDA = ZoneId.of("America/Lima");

    /**
     * Mismo tope que una venta por la caja ({@code SaleRequestDto}): mantiene la
     * misma defensa contra el dedazo por las dos vias de entrada.
     */
    private static final int MAX_UNIDADES = 10_000;

    private SalesRowGate() {
    }

    sealed interface Result {
        record Valid(SalesRow row) implements Result {
        }

        record Rejected(String code, String message) implements Result {
        }
    }

    static Result check(int rowNumber, Object sku, Object quantity, Object saleDate) {
        String skuText = text(sku);
        if (skuText.isEmpty()) {
            return new Result.Rejected("SKU_VACIO", "La fila no trae SKU.");
        }
        if (skuText.length() > 50) {
            return new Result.Rejected("SKU_LARGO",
                    "El SKU no puede pasar de 50 caracteres (tiene " + skuText.length() + ").");
        }

        Integer unidades = asInteger(quantity);
        if (unidades == null) {
            return new Result.Rejected("CANTIDAD_INVALIDA",
                    "La cantidad '" + text(quantity) + "' no es un numero entero.");
        }
        if (unidades <= 0) {
            return new Result.Rejected("CANTIDAD_NO_POSITIVA",
                    "La cantidad debe ser mayor que 0 (recibido: " + unidades + ").");
        }
        if (unidades > MAX_UNIDADES) {
            return new Result.Rejected("CANTIDAD_EXCESIVA",
                    "La cantidad " + unidades + " pasa del tope de " + MAX_UNIDADES
                            + " unidades en una sola linea.");
        }

        LocalDate fecha;
        try {
            fecha = asDate(saleDate);
        } catch (DateTimeParseException ex) {
            return new Result.Rejected("FECHA_INVALIDA",
                    "La fecha '" + text(saleDate) + "' no se entiende. Usa el formato AAAA-MM-DD.");
        }

        // El procedimiento tambien lo rechaza (P0010), pero cortar aqui ahorra
        // el viaje a la base y, sobre todo, da el numero de fila: el error del
        // procedimiento no sabe de que linea del Excel viene.
        if (fecha != null && fecha.isAfter(LocalDate.now(TIENDA))) {
            return new Result.Rejected("FECHA_FUTURA",
                    "La fecha " + fecha + " esta en el futuro.");
        }

        return new Result.Valid(new SalesRow(rowNumber, skuText, unidades, fecha));
    }

    /** Los valores crudos, para que el operario vea en pantalla lo que escribio. */
    static Map<String, String> originalValues(Object sku, Object quantity, Object saleDate) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sku", text(sku));
        values.put("quantity", text(quantity));
        values.put("saleDate", text(saleDate));
        return values;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * Excel entrega los numeros como {@code BigDecimal}, asi que "12" llega
     * como 12.0 y hay que aceptarlo; "12.5" unidades, en cambio, no es una
     * cantidad de un minimarket y se rechaza en vez de truncarse en silencio.
     */
    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return d == Math.rint(d) ? (int) d : null;
        }
        String raw = text(value);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(raw.replace(',', '.')).intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /**
     * @return null cuando la celda viene vacia, que significa "ahora"
     */
    private static LocalDate asDate(Object value) {
        if (value == null) {
            return null;
        }
        // Una celda con formato de fecha llega ya tipada desde fastexcel.
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        String raw = text(value);
        if (raw.isEmpty()) {
            return null;
        }
        // Una celda de texto con la fecha dentro, que es como la escribe quien
        // rellena la plantilla a mano.
        return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
    }
}
