package com.kardo.ingest;

import com.kardo.config.IngestProperties;
import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.QuarantineRowDto;
import com.kardo.dto.SpreadsheetRowDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.repository.InventoryIngestRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * Ingesta de inventario desde Excel en streaming continuo.
 *
 * <p>El archivo nunca se materializa en el heap: Tomcat lo vuelca a disco
 * (<code>file-size-threshold=64KB</code>), fastexcel recorre la hoja fila por
 * fila y solo se retiene el micro-lote en curso mas, como maximo, las filas en
 * cuarentena acotadas por el Circuit Breaker. El consumo de memoria es por tanto
 * constante y no depende del tamanio del archivo.
 */
@Service
public class ExcelStreamingIngestService {

    private static final Logger log = LoggerFactory.getLogger(ExcelStreamingIngestService.class);

    /** Fila 1: cabeceras. Los datos empiezan en la fila 2. */
    private static final int HEADER_ROW_NUMBER = 1;

    private final InventoryIngestRepository repository;
    private final IngestProperties properties;

    public ExcelStreamingIngestService(InventoryIngestRepository repository, IngestProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Recorre la hoja, valida cada fila y persiste las validas en micro-lotes.
     *
     * @param inputStream flujo del multipart, leido directamente sin copiarlo
     * @param storeId     tienda destino de los lotes
     * @return resumen con las filas insertadas y las que quedaron en cuarentena
     * @throws CorruptSpreadsheetException si falta una columna requerida o se
     *                                     supera el umbral de filas corruptas
     */
    @Transactional
    public IngestSummaryResponseDto ingest(InputStream inputStream, long storeId) {
        long companyId = repository.findCompanyIdByStore(storeId);

        int processed = 0;
        int inserted = 0;
        List<QuarantineRowDto> quarantined = new ArrayList<>();
        List<SpreadsheetRowDto> microBatch = new ArrayList<>(properties.batchSize());

        try (ReadableWorkbook workbook = new ReadableWorkbook(inputStream)) {
            Sheet sheet = workbook.getFirstSheet();

            try (var rows = sheet.openStream()) {
                Iterator<Row> iterator = rows.iterator();
                Map<InventoryColumn, Integer> mapping = readHeader(iterator);

                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    if (isEmpty(row)) {
                        continue;
                    }
                    processed++;

                    RawRow rawRow = toRawRow(row, mapping);
                    switch (RowQualityGate.check(rawRow)) {
                        case RowValidationResult.Valid valid -> {
                            microBatch.add(valid.row());
                            if (microBatch.size() >= properties.batchSize()) {
                                inserted += repository.persistMicroBatch(storeId, companyId, microBatch);
                                // Se reemplaza la lista en vez de limpiarla: el lote ya
                                // entregado no debe mutar bajo los pies del repositorio,
                                // y el anterior queda elegible para el recolector.
                                microBatch = new ArrayList<>(properties.batchSize());
                            }
                        }
                        case RowValidationResult.Rejected rejected -> {
                            quarantined.add(new QuarantineRowDto(
                                    rawRow.rowNumber(),
                                    rawRow.asOriginalValues(),
                                    rejected.code().name(),
                                    rejected.code().message()));
                            tripBreakerIfExceeded(quarantined.size());
                        }
                    }
                }
            }

            inserted += repository.persistMicroBatch(storeId, companyId, microBatch);

        } catch (CorruptSpreadsheetException breaker) {
            // El Circuit Breaker ya trae su mensaje y su contador: se propaga tal cual.
            throw breaker;
        } catch (ExcelReaderException | UncheckedIOException ex) {
            // fastexcel envuelve el XML malformado en excepciones de runtime propias.
            throw new CorruptSpreadsheetException(
                    "Estructura de archivo corrupta o plantilla no compatible: " + ex.getMessage(), 0);
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException(
                    "El archivo no es un Excel (.xlsx) legible: " + ex.getMessage(), 0);
        }

        log.info("Ingesta de tienda {}: {} filas leidas, {} lotes insertados, {} en cuarentena",
                storeId, processed, inserted, quarantined.size());

        return new IngestSummaryResponseDto(processed, inserted, quarantined.size(), quarantined);
    }

    /**
     * Reinserta las filas que el operario corrigio en el navegador.
     *
     * <p>Se revalidan en servidor: el cliente no es una fuente de confianza y una
     * fila corregida a mano puede seguir violando los CHECK del DDL.
     */
    @Transactional
    public IngestSummaryResponseDto ingestFixedRows(long storeId, List<SpreadsheetRowDto> rows) {
        long companyId = repository.findCompanyIdByStore(storeId);

        List<SpreadsheetRowDto> accepted = new ArrayList<>(rows.size());
        List<QuarantineRowDto> quarantined = new ArrayList<>();

        for (SpreadsheetRowDto row : rows) {
            switch (RowQualityGate.check(toRawRow(row))) {
                case RowValidationResult.Valid valid -> accepted.add(valid.row());
                case RowValidationResult.Rejected rejected -> quarantined.add(new QuarantineRowDto(
                        row.rowNumber(),
                        Map.of("sku", String.valueOf(row.sku())),
                        rejected.code().name(),
                        rejected.code().message()));
            }
        }

        int inserted = 0;
        for (int start = 0; start < accepted.size(); start += properties.batchSize()) {
            int end = Math.min(start + properties.batchSize(), accepted.size());
            inserted += repository.persistMicroBatch(storeId, companyId, accepted.subList(start, end));
        }

        return new IngestSummaryResponseDto(rows.size(), inserted, quarantined.size(), quarantined);
    }

    private Map<InventoryColumn, Integer> readHeader(Iterator<Row> iterator) {
        while (iterator.hasNext()) {
            Row row = iterator.next();
            if (isEmpty(row)) {
                continue;
            }
            // Se tolera que el archivo arranque con filas vacias: la primera fila
            // con contenido es la de encabezados.
            if (row.getRowNum() != HEADER_ROW_NUMBER) {
                log.debug("Cabecera detectada en la fila {} en lugar de la 1", row.getRowNum());
            }
            List<String> headerCells = new ArrayList<>(row.getCellCount());
            for (int index = 0; index < row.getCellCount(); index++) {
                headerCells.add(cellText(row, index));
            }
            return HeaderMapper.map(headerCells);
        }
        throw new CorruptSpreadsheetException("El archivo no contiene filas: falta la cabecera.", 0);
    }

    /**
     * Circuit Breaker de calidad: por encima del umbral el archivo se considera
     * una plantilla incompatible y se corta la lectura sin seguir acumulando
     * cuarentena en memoria.
     */
    private void tripBreakerIfExceeded(int corruptRows) {
        if (corruptRows > properties.maxCorruptRows()) {
            throw new CorruptSpreadsheetException(
                    "Estructura de archivo corrupta o plantilla no compatible", corruptRows);
        }
    }

    private static RawRow toRawRow(Row row, Map<InventoryColumn, Integer> mapping) {
        Map<InventoryColumn, Object> values = new EnumMap<>(InventoryColumn.class);
        mapping.forEach((column, index) -> values.put(column, cellValue(row, index)));
        return new RawRow(row.getRowNum(), values);
    }

    /** Permite reutilizar la compuerta de calidad con filas que llegan ya tipadas. */
    private static RawRow toRawRow(SpreadsheetRowDto row) {
        Map<InventoryColumn, Object> values = new EnumMap<>(InventoryColumn.class);
        values.put(InventoryColumn.SKU, row.sku());
        values.put(InventoryColumn.BARCODE, row.barcode());
        values.put(InventoryColumn.NAME, row.name());
        values.put(InventoryColumn.CATEGORY, row.category());
        values.put(InventoryColumn.UNIT_OF_MEASURE, row.unitOfMeasure());
        values.put(InventoryColumn.UNIT_COST, row.unitCost());
        values.put(InventoryColumn.RETAIL_PRICE, row.retailPrice());
        values.put(InventoryColumn.BATCH_CODE, row.batchCode());
        values.put(InventoryColumn.MANUFACTURING_DATE, row.manufacturingDate());
        values.put(InventoryColumn.EXPIRATION_DATE, row.expirationDate());
        values.put(InventoryColumn.INITIAL_QTY, row.initialQty());
        return new RawRow(row.rowNumber(), values);
    }

    private static Object cellValue(Row row, int index) {
        if (index >= row.getCellCount()) {
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
