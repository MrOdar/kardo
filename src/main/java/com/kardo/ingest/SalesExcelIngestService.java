package com.kardo.ingest;

import com.kardo.config.IngestProperties;
import com.kardo.dto.QuarantineRowDto;
import com.kardo.dto.SalesFixRowDto;
import com.kardo.dto.SalesIngestSummaryDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.exception.SpreadsheetTooLargeException;
import com.kardo.sales.BulkSaleRegistrar;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.dhatim.fastexcel.reader.ExcelReaderException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro masivo de ventas desde la hoja "Ventas" del Excel.
 *
 * <p>Hermana de {@link ExcelStreamingIngestService}, y con la misma forma: se
 * recorre en streaming, cada fila pasa una compuerta de calidad, y lo que no
 * pasa va a cuarentena para que el operario lo corrija en pantalla en vez de
 * perder el archivo entero. Las diferencias son tres, y las tres vienen de que
 * una venta no es un apunte sino un movimiento de stock.
 *
 * <p><b>1. La hoja se busca por nombre, no por posicion.</b> La de inventario
 * usa {@code getFirstSheet()}; aqui se pide "Ventas". Asi el mismo archivo sirve
 * para las dos cargas y añadir una hoja no cambia el significado de la primera.
 *
 * <p><b>2. Cada fila va en su propio savepoint.</b> Es la decision que sostiene
 * la cuarentena. En PostgreSQL un error aborta la transaccion entera: sin
 * savepoint, la primera fila sin stock dejaria la transaccion inservible y las
 * 199 filas buenas que vinieran detras fallarian todas con "current transaction
 * is aborted". Con savepoint por fila, el error se deshace solo hasta esa fila y
 * el recorrido sigue.
 *
 * <p><b>3. Las filas aceptadas no se escriben desde Java.</b> Quien conoce FEFO
 * -que lote toca, a que precio estaba, cuanto costo- es
 * {@code sp_process_fefo_sale}, y partirlo en consultas abriria ventanas de
 * carrera entre leer el stock y descontarlo. Aqui solo se le pasa la fila.
 */
@Service
public class SalesExcelIngestService {

    private static final Logger log = LoggerFactory.getLogger(SalesExcelIngestService.class);

    /** El nombre que lleva la hoja en la plantilla de Kardo. */
    static final String HOJA = "Ventas";

    private final BulkSaleRegistrar registrar;
    private final IngestProperties properties;

    public SalesExcelIngestService(BulkSaleRegistrar registrar, IngestProperties properties) {
        this.registrar = registrar;
        this.properties = properties;
    }

    /**
     * Recorre la hoja de ventas y registra cada linea por el motor FEFO.
     *
     * @param operatorId queda en {@code daily_sales} como autor de cada salida
     * @return resumen con las ventas registradas, las unidades y lo facturado
     * @throws CorruptSpreadsheetException si el libro no trae hoja "Ventas", le
     *                                     faltan columnas, o se pasa del umbral
     *                                     de filas rechazadas
     */
    @Transactional
    public SalesIngestSummaryDto ingest(InputStream inputStream, long storeId, String operatorId) {
        int processed = 0;
        int registered = 0;
        int units = 0;
        List<QuarantineRowDto> quarantined = new ArrayList<>();

        // Corte para saber luego que filas de daily_sales son de esta carga.
        // Dentro de la misma transaccion nadie mas inserta filas visibles, asi
        // que el corte es exacto.
        long corte = registrar.lastSaleId(storeId);

        try (ReadableWorkbook workbook = new ReadableWorkbook(inputStream)) {
            Sheet sheet = workbook.findSheet(HOJA).orElseThrow(() -> new CorruptSpreadsheetException(
                    "El archivo no tiene una hoja llamada '" + HOJA + "'. "
                            + "Descarga la plantilla de Kardo: trae la hoja de inventario y la de ventas.", 0));

            try (var rows = sheet.openStream()) {
                Iterator<Row> iterator = rows.iterator();
                Map<SalesColumn, Integer> mapping = readHeader(iterator);

                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    if (isEmpty(row)) {
                        continue;
                    }
                    processed++;

                    if (processed > properties.maxRows()) {
                        throw new SpreadsheetTooLargeException(properties.maxRows());
                    }

                    Object sku = cellValue(row, mapping.get(SalesColumn.SKU));
                    Object cantidad = cellValue(row, mapping.get(SalesColumn.QUANTITY));
                    Object fecha = cellValue(row, mapping.get(SalesColumn.SALE_DATE));

                    switch (SalesRowGate.check(row.getRowNum(), sku, cantidad, fecha)) {
                        case SalesRowGate.Result.Valid valid -> {
                            var fila = valid.row();
                            var rechazo = registrar.register(
                                    storeId, fila.sku(), fila.quantity(), fila.saleDate(), operatorId);
                            if (rechazo.isEmpty()) {
                                registered++;
                                units += fila.quantity();
                            } else {
                                // El procedimiento la rechazo: sin stock, SKU
                                // inexistente, lote ya vencido. Es informacion
                                // que el operario puede arreglar, no un fallo.
                                quarantined.add(new QuarantineRowDto(
                                        fila.rowNumber(),
                                        SalesRowGate.originalValues(sku, cantidad, fecha),
                                        rechazo.get().code(),
                                        rechazo.get().message()));
                                tripBreakerIfExceeded(quarantined.size());
                            }
                        }
                        case SalesRowGate.Result.Rejected rejected -> {
                            quarantined.add(new QuarantineRowDto(
                                    row.getRowNum(),
                                    SalesRowGate.originalValues(sku, cantidad, fecha),
                                    rejected.code(),
                                    rejected.message()));
                            tripBreakerIfExceeded(quarantined.size());
                        }
                    }
                }
            }

        } catch (CorruptSpreadsheetException | SpreadsheetTooLargeException breaker) {
            throw breaker;
        } catch (ExcelReaderException | UncheckedIOException ex) {
            throw new CorruptSpreadsheetException(
                    "Estructura de archivo corrupta o plantilla no compatible: " + ex.getMessage(), 0);
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException(
                    "El archivo no es un Excel (.xlsx) legible: " + ex.getMessage(), 0);
        }

        BigDecimal revenue = registrar.revenueSince(storeId, corte);

        log.info("Ventas de la tienda {}: {} filas leidas, {} registradas ({} unidades, {}), {} en cuarentena",
                storeId, processed, registered, units, revenue, quarantined.size());

        return new SalesIngestSummaryDto(processed, registered, quarantined.size(),
                units, revenue, quarantined);
    }

    /**
     * Registra solo las ventas que el operario corrigio en su navegador.
     *
     * <p>Se revalidan en servidor: el cliente no es fuente de confianza, y una
     * cantidad corregida a mano puede seguir pasandose del stock. Las que
     * vuelvan a fallar vuelven a salir en cuarentena, con su motivo.
     *
     * <p>No hay huella que comprobar y es a proposito: estas filas no son un
     * archivo, son el resto de uno que ya se registro. La guarda que importa
     * -no registrar dos veces lo mismo- la dio la carga original al rechazar
     * estas lineas; aqui lo que entra es lo que entonces no entro.
     */
    @Transactional
    public SalesIngestSummaryDto registerFixedRows(long storeId, List<SalesFixRowDto> rows, String operatorId) {
        int registered = 0;
        int units = 0;
        List<QuarantineRowDto> quarantined = new ArrayList<>();

        long corte = registrar.lastSaleId(storeId);

        for (SalesFixRowDto row : rows) {
            switch (SalesRowGate.check(row.rowNumber(), row.sku(), row.quantity(), row.saleDate())) {
                case SalesRowGate.Result.Valid valid -> {
                    var fila = valid.row();
                    var rechazo = registrar.register(
                            storeId, fila.sku(), fila.quantity(), fila.saleDate(), operatorId);
                    if (rechazo.isEmpty()) {
                        registered++;
                        units += fila.quantity();
                    } else {
                        quarantined.add(new QuarantineRowDto(
                                fila.rowNumber(),
                                SalesRowGate.originalValues(row.sku(), row.quantity(), row.saleDate()),
                                rechazo.get().code(),
                                rechazo.get().message()));
                    }
                }
                case SalesRowGate.Result.Rejected rejected -> quarantined.add(new QuarantineRowDto(
                        row.rowNumber(),
                        SalesRowGate.originalValues(row.sku(), row.quantity(), row.saleDate()),
                        rejected.code(),
                        rejected.message()));
            }
        }

        BigDecimal revenue = registrar.revenueSince(storeId, corte);

        log.info("Correcciones de ventas en la tienda {}: {} de {} registradas ({} unidades, {})",
                storeId, registered, rows.size(), units, revenue);

        return new SalesIngestSummaryDto(rows.size(), registered, quarantined.size(),
                units, revenue, quarantined);
    }

    private static Map<SalesColumn, Integer> readHeader(Iterator<Row> iterator) {
        while (iterator.hasNext()) {
            Row row = iterator.next();
            if (isEmpty(row)) {
                continue;
            }
            List<String> headerCells = new ArrayList<>(row.getCellCount());
            for (int index = 0; index < row.getCellCount(); index++) {
                headerCells.add(cellText(row, index));
            }
            return SalesHeaderMapper.map(headerCells);
        }
        throw new CorruptSpreadsheetException(
                "La hoja '" + HOJA + "' esta vacia: falta la cabecera.", 0);
    }

    /**
     * Mismo umbral que la ingesta de inventario: por encima, lo que hay no es un
     * archivo con errores sino un archivo que no es este.
     */
    private void tripBreakerIfExceeded(int rejectedRows) {
        if (rejectedRows > properties.maxCorruptRows()) {
            throw new CorruptSpreadsheetException(
                    "Demasiadas filas rechazadas en la hoja '" + HOJA
                            + "': revisa que los SKU y las cantidades sean los de esta tienda", rejectedRows);
        }
    }

    /** @param index nulo cuando la columna es opcional y el archivo no la trae */
    private static Object cellValue(Row row, Integer index) {
        if (index == null || index >= row.getCellCount()) {
            return null;
        }
        var cell = row.getCell(index);
        return cell == null ? null : cell.getValue();
    }

    private static String cellText(Row row, int index) {
        Object value = cellValue(row, index);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean isEmpty(Row row) {
        for (int index = 0; index < row.getCellCount(); index++) {
            if (!cellText(row, index).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
