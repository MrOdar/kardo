package com.kardo.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fila cruda ya desacoplada de fastexcel: los valores son los tipos nativos que
 * entrego el lector ({@code String}, {@code BigDecimal}, {@code LocalDateTime}
 * o {@code null}).
 *
 * <p>Esta frontera permite probar el parseo y la validacion con mapas simples,
 * sin construir un XLSX, y mantiene la conversion de tipos en un solo lugar.
 *
 * @param rowNumber fila original en el Excel (1-based)
 * @param values    valor crudo por campo canonico
 */
public record RawRow(int rowNumber, Map<InventoryColumn, Object> values) {

    /** Excel cuenta los dias desde 1900 arrastrando el bug del anio bisiesto de Lotus 1-2-3. */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"));

    /** Texto tal como lo vio el operario; es lo que se devuelve en la cuarentena. */
    public String text(InventoryColumn column) {
        Object value = values.get(column);
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate().toString();
        }
        return String.valueOf(value).trim();
    }

    public boolean isBlank(InventoryColumn column) {
        return text(column).isEmpty();
    }

    /** @return vacio si el valor esta en blanco o no es numerico */
    public Optional<BigDecimal> number(InventoryColumn column) {
        Object value = values.get(column);
        if (value instanceof Number number) {
            return Optional.of(new BigDecimal(number.toString()));
        }
        String raw = text(column);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        // Tolera "S/ 12,50", "1.234,50" y "1,234.50": se descarta el simbolo de
        // moneda y se infiere el separador decimal por el ultimo delimitador.
        String digits = raw.replaceAll("[^0-9,.\\-]", "");
        int lastComma = digits.lastIndexOf(',');
        int lastDot = digits.lastIndexOf('.');
        String normalized = lastComma > lastDot
                ? digits.replace(".", "").replace(',', '.')
                : digits.replace(",", "");
        try {
            return Optional.of(new BigDecimal(normalized));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** @return vacio si el valor esta en blanco o la fecha no es interpretable */
    public Optional<LocalDate> date(InventoryColumn column) {
        Object value = values.get(column);
        if (value instanceof LocalDateTime dateTime) {
            return Optional.of(dateTime.toLocalDate());
        }
        if (value instanceof LocalDate date) {
            return Optional.of(date);
        }
        if (value instanceof Number serial) {
            return Optional.of(EXCEL_EPOCH.plusDays(serial.longValue()));
        }
        String raw = text(column);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(raw, format));
            } catch (java.time.format.DateTimeParseException ignored) {
                // Se prueba el siguiente formato conocido.
            }
        }
        return Optional.empty();
    }

    /** Valores crudos por nombre canonico, para devolverlos en la cuarentena. */
    public Map<String, String> asOriginalValues() {
        Map<String, String> original = new LinkedHashMap<>();
        for (InventoryColumn column : InventoryColumn.values()) {
            original.put(column.canonicalName(), text(column));
        }
        return original;
    }
}
