package com.kardo.ingest;

import com.kardo.exception.CorruptSpreadsheetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resuelve que indice de columna corresponde a cada campo canonico leyendo la
 * fila 1 del archivo.
 *
 * <p>Si falta alguna columna requerida se aborta de inmediato con HTTP 422 en
 * lugar de recorrer el archivo entero generando miles de filas en cuarentena.
 */
public final class HeaderMapper {

    private HeaderMapper() {
    }

    /**
     * @param headerCells textos de la fila de cabecera, en orden de columna
     * @return indice de columna por campo canonico
     * @throws CorruptSpreadsheetException si la plantilla no es compatible
     */
    public static Map<InventoryColumn, Integer> map(List<String> headerCells) {
        Map<InventoryColumn, Integer> mapping = new EnumMap<>(InventoryColumn.class);

        for (int index = 0; index < headerCells.size(); index++) {
            String normalized = HeaderNormalizer.normalize(headerCells.get(index));
            if (normalized.isEmpty()) {
                continue;
            }
            for (InventoryColumn column : InventoryColumn.values()) {
                // El primer encabezado que coincide gana: columnas duplicadas
                // ("Precio" repetido) no desplazan el mapeo ya resuelto.
                if (!mapping.containsKey(column) && column.aliases().contains(normalized)) {
                    mapping.put(column, index);
                    break;
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (InventoryColumn column : InventoryColumn.values()) {
            if (column.isRequired() && !mapping.containsKey(column)) {
                missing.add(column.canonicalName());
            }
        }
        if (!missing.isEmpty()) {
            throw new CorruptSpreadsheetException(
                    "Estructura de archivo corrupta o plantilla no compatible: faltan las columnas " + missing, 0);
        }

        return Collections.unmodifiableMap(mapping);
    }
}
