package com.kardo.ingest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exportacion del inventario fisico valorizado a Excel.
 *
 * <p>Se escribe fila a fila directamente sobre el flujo de la respuesta: el
 * archivo no se construye en memoria ni en disco. Es la misma disciplina que la
 * ingesta, por el mismo motivo —512 MB de contenedor— y ademas el navegador
 * empieza a recibir el archivo de inmediato.
 */
@Service
public class InventoryExportService {

    private static final Logger log = LoggerFactory.getLogger(InventoryExportService.class);

    /** Se traen de a 500 filas para no acumular el inventario entero en el driver. */
    private static final int FETCH_SIZE = 500;

    private static final String INVENTORY = """
            SELECT p.sku,
                   p.name,
                   p.category,
                   p.unit_of_measure,
                   b.batch_code,
                   b.manufacturing_date,
                   b.expiration_date,
                   (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                   b.batch_status,
                   b.current_qty,
                   b.quarantined_qty,
                   b.unit_cost,
                   (b.current_qty * b.unit_cost) AS valor
            FROM inventory_batches b
            JOIN products p USING (product_id)
            WHERE b.store_id = ?
              AND (b.current_qty > 0 OR b.quarantined_qty > 0)
            ORDER BY b.expiration_date ASC, p.name
            """;

    private static final String[] HEADERS = {
            "SKU", "Producto", "Categoria", "Unidad", "Lote", "Fabricacion", "Vencimiento",
            "Dias restantes", "Estado", "Cantidad", "En cuarentena", "Costo unitario", "Valor"
    };

    private final JdbcTemplate jdbcTemplate;

    public InventoryExportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Escribe el inventario de la tienda en el flujo recibido.
     *
     * <p>Transaccional de solo lectura para que el cursor del servidor mantenga
     * una vista coherente mientras se vuelca: una venta a mitad de la exportacion
     * no puede producir un archivo que no cuadre consigo mismo.
     */
    @Transactional(readOnly = true)
    public void writeTo(long storeId, String storeName, OutputStream output) {
        try {
            Workbook workbook = new Workbook(output, "Kardo", "1.0");
            Worksheet sheet = workbook.newWorksheet("Inventario valorizado");

            sheet.value(0, 0, "Inventario valorizado · " + storeName);
            sheet.value(1, 0, "Generado el " + LocalDate.now());
            sheet.range(0, 0, 0, HEADERS.length - 1).style().bold().set();

            for (int column = 0; column < HEADERS.length; column++) {
                sheet.value(3, column, HEADERS[column]);
            }
            sheet.range(3, 0, 3, HEADERS.length - 1).style().bold().set();

            Counters counters = new Counters();
            int[] row = {4};

            jdbcTemplate.query(connection -> {
                var statement = connection.prepareStatement(INVENTORY);
                statement.setFetchSize(FETCH_SIZE);
                statement.setLong(1, storeId);
                return statement;
            }, rs -> {
                int current = row[0]++;
                int quantity = rs.getInt("current_qty");
                BigDecimal unitCost = rs.getBigDecimal("unit_cost");
                BigDecimal value = rs.getBigDecimal("valor");

                sheet.value(current, 0, rs.getString("sku"));
                sheet.value(current, 1, rs.getString("name"));
                sheet.value(current, 2, rs.getString("category"));
                sheet.value(current, 3, rs.getString("unit_of_measure"));
                sheet.value(current, 4, rs.getString("batch_code"));
                sheet.value(current, 5, text(rs.getObject("manufacturing_date", LocalDate.class)));
                sheet.value(current, 6, text(rs.getObject("expiration_date", LocalDate.class)));
                sheet.value(current, 7, rs.getInt("dias_restantes"));
                sheet.value(current, 8, rs.getString("batch_status"));
                sheet.value(current, 9, quantity);
                sheet.value(current, 10, rs.getInt("quarantined_qty"));
                sheet.value(current, 11, unitCost);
                sheet.value(current, 12, value);

                counters.batches++;
                counters.units += quantity;
                counters.value = counters.value.add(value);
            });

            int totals = row[0] + 1;
            sheet.value(totals, 8, "Totales");
            sheet.value(totals, 9, counters.units);
            sheet.value(totals, 12, counters.value);
            sheet.range(totals, 8, totals, 12).style().bold().set();

            workbook.finish();

            log.info("Inventario exportado: tienda {}, {} lotes, {} unidades, valor {}",
                    storeId, counters.batches, counters.units, counters.value);

        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String text(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /** Acumuladores de la fila de totales. */
    private static final class Counters {
        private int batches;
        private int units;
        private BigDecimal value = BigDecimal.ZERO;
    }
}
