package com.kardo.ingest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/** Genera archivos XLSX reales en memoria para ejercitar el lector de fastexcel. */
final class TestWorkbooks {

    private TestWorkbooks() {
    }

    /**
     * @param headers fila 1 del archivo
     * @param rows    filas de datos; cada celda se escribe como texto o numero
     *                segun su tipo, igual que lo haria un export real
     */
    static InputStream xlsx(List<String> headers, List<List<Object>> rows) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Workbook workbook = new Workbook(output, "kardo-test", "1.0");
        Worksheet sheet = workbook.newWorksheet("Inventario");

        for (int column = 0; column < headers.size(); column++) {
            sheet.value(0, column, headers.get(column));
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Object> row = rows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                Object value = row.get(column);
                if (value == null) {
                    continue;
                }
                if (value instanceof Number number) {
                    sheet.value(rowIndex + 1, column, number);
                } else {
                    sheet.value(rowIndex + 1, column, String.valueOf(value));
                }
            }
        }

        workbook.finish();
        return new ByteArrayInputStream(output.toByteArray());
    }
}
