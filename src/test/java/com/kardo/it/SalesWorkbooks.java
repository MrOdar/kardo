package com.kardo.it;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Libros XLSX de verdad para ejercitar la lectura de la hoja de ventas.
 *
 * <p>Se generan con las dos hojas, igual que la plantilla que se le entrega al
 * usuario, porque parte de lo que hay que comprobar es justo eso: que la
 * ingesta de ventas encuentre su hoja aunque no sea la primera, y que la de
 * inventario siga leyendo la primera aunque ahora haya dos.
 */
final class SalesWorkbooks {

    private SalesWorkbooks() {
    }

    /** Las dos hojas en el orden de la plantilla: Inventario, luego Ventas. */
    static byte[] conAmbasHojas(List<List<Object>> ventas) throws IOException {
        return libro(true, List.of("SKU", "Cantidad", "Fecha"), ventas, "Ventas");
    }

    /** Solo la hoja de ventas, para lo que no necesita inventario delante. */
    static byte[] soloVentas(List<List<Object>> ventas) throws IOException {
        return libro(false, List.of("SKU", "Cantidad", "Fecha"), ventas, "Ventas");
    }

    /** Con la cabecera que se le pase, para probar alias y columnas que faltan. */
    static byte[] conCabecera(List<String> cabecera, List<List<Object>> ventas) throws IOException {
        return libro(false, cabecera, ventas, "Ventas");
    }

    /** Con otro nombre de hoja, para probar que se exige "Ventas". */
    static byte[] conHoja(String nombre, List<List<Object>> ventas) throws IOException {
        return libro(false, List.of("SKU", "Cantidad", "Fecha"), ventas, nombre);
    }

    static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] libro(boolean conInventario, List<String> cabecera,
                                List<List<Object>> filas, String nombreHoja) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Workbook workbook = new Workbook(output, "kardo-test", "1.0");

        if (conInventario) {
            Worksheet inventario = workbook.newWorksheet("Inventario");
            List<String> cabeceraInventario = List.of(
                    "SKU", "Producto", "Categoria", "Costo", "Precio", "Vencimiento", "Cantidad");
            for (int col = 0; col < cabeceraInventario.size(); col++) {
                inventario.value(0, col, cabeceraInventario.get(col));
            }
        }

        Worksheet hoja = workbook.newWorksheet(nombreHoja);
        for (int col = 0; col < cabecera.size(); col++) {
            hoja.value(0, col, cabecera.get(col));
        }
        for (int fila = 0; fila < filas.size(); fila++) {
            List<Object> valores = filas.get(fila);
            for (int col = 0; col < valores.size(); col++) {
                Object valor = valores.get(col);
                if (valor == null) {
                    continue;
                }
                if (valor instanceof Number numero) {
                    hoja.value(fila + 1, col, numero);
                } else {
                    hoja.value(fila + 1, col, String.valueOf(valor));
                }
            }
        }

        workbook.finish();
        return output.toByteArray();
    }
}
