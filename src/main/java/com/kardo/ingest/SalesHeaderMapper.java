package com.kardo.ingest;

import com.kardo.exception.CorruptSpreadsheetException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resuelve que columna de la hoja de ventas es cual, por alias normalizado.
 *
 * <p>Gemelo de {@link HeaderMapper} para la otra hoja. No se unifican en un
 * generico: serian cuatro lineas compartidas a cambio de un tipo con comodin
 * que ninguno de los dos lados necesita, y los mensajes de error -que es lo que
 * de verdad aporta esta clase- tienen que nombrar la hoja concreta.
 */
final class SalesHeaderMapper {

    private SalesHeaderMapper() {
    }

    static Map<SalesColumn, Integer> map(List<String> headerCells) {
        Map<SalesColumn, Integer> mapping = new EnumMap<>(SalesColumn.class);

        for (int index = 0; index < headerCells.size(); index++) {
            String normalized = HeaderNormalizer.normalize(headerCells.get(index));
            if (normalized.isEmpty()) {
                continue;
            }
            for (SalesColumn column : SalesColumn.values()) {
                if (!mapping.containsKey(column) && column.aliases().contains(normalized)) {
                    mapping.put(column, index);
                    break;
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (SalesColumn column : SalesColumn.values()) {
            if (column.required() && !mapping.containsKey(column)) {
                missing.add(column.name());
            }
        }
        if (!missing.isEmpty()) {
            // 422 de inmediato en vez de recorrer el archivo entero dejando
            // cada fila en cuarentena por el mismo motivo.
            throw new CorruptSpreadsheetException(
                    "La hoja 'Ventas' no tiene las columnas requeridas: " + String.join(", ", missing)
                            + ". Se esperan al menos SKU y Cantidad.", 0);
        }
        return mapping;
    }
}
