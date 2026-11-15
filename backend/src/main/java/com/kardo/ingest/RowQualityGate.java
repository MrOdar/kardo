package com.kardo.ingest;

import com.kardo.dto.SpreadsheetRowDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Compuerta de calidad por fila.
 *
 * <p>Replica en Java las restricciones del DDL ({@code unit_cost >= 0},
 * {@code retail_price >= 0}, {@code initial_qty > 0}, {@code chk_batch_dates})
 * para rechazar la fila con un mensaje util al operario en vez de dejar que
 * PostgreSQL reviente el micro-lote completo: un solo {@code CHECK} violado
 * abortaria las 500 filas del {@code batchUpdate}.
 */
public final class RowQualityGate {

    /** El DDL declara NUMERIC(10,2): 8 digitos enteros mas 2 decimales. */
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999.99");

    private static final String DEFAULT_UNIT_OF_MEASURE = "UNIDAD";

    /** El lote es NOT NULL en el DDL; un Excel sin columna de lote recibe este marcador. */
    private static final String DEFAULT_BATCH_CODE = "SIN-LOTE";

    private RowQualityGate() {
    }

    public static RowValidationResult check(RawRow row) {
        String sku = row.text(InventoryColumn.SKU);
        if (sku.isEmpty()) {
            return new RowValidationResult.Rejected(IngestErrorCode.MISSING_SKU);
        }
        String name = row.text(InventoryColumn.NAME);
        if (name.isEmpty()) {
            return new RowValidationResult.Rejected(IngestErrorCode.MISSING_NAME);
        }
        String category = row.text(InventoryColumn.CATEGORY);
        if (category.isEmpty()) {
            return new RowValidationResult.Rejected(IngestErrorCode.MISSING_CATEGORY);
        }

        Optional<BigDecimal> unitCost = row.number(InventoryColumn.UNIT_COST);
        if (unitCost.isEmpty() || unitCost.get().signum() < 0) {
            return new RowValidationResult.Rejected(IngestErrorCode.INVALID_COST);
        }
        Optional<BigDecimal> retailPrice = row.number(InventoryColumn.RETAIL_PRICE);
        if (retailPrice.isEmpty() || retailPrice.get().signum() < 0) {
            return new RowValidationResult.Rejected(IngestErrorCode.INVALID_PRICE);
        }

        Optional<BigDecimal> quantity = row.number(InventoryColumn.INITIAL_QTY);
        if (quantity.isEmpty() || quantity.get().signum() <= 0
                || quantity.get().stripTrailingZeros().scale() > 0) {
            return new RowValidationResult.Rejected(IngestErrorCode.INVALID_QUANTITY);
        }

        if (row.isBlank(InventoryColumn.EXPIRATION_DATE)) {
            return new RowValidationResult.Rejected(IngestErrorCode.MISSING_EXPIRATION_DATE);
        }
        Optional<LocalDate> expirationDate = row.date(InventoryColumn.EXPIRATION_DATE);
        if (expirationDate.isEmpty()) {
            return new RowValidationResult.Rejected(IngestErrorCode.INVALID_DATE_FORMAT);
        }

        LocalDate manufacturingDate = null;
        if (!row.isBlank(InventoryColumn.MANUFACTURING_DATE)) {
            Optional<LocalDate> parsed = row.date(InventoryColumn.MANUFACTURING_DATE);
            if (parsed.isEmpty()) {
                return new RowValidationResult.Rejected(IngestErrorCode.INVALID_DATE_FORMAT);
            }
            manufacturingDate = parsed.get();
            if (expirationDate.get().isBefore(manufacturingDate)) {
                return new RowValidationResult.Rejected(IngestErrorCode.EXPIRATION_BEFORE_MANUFACTURING);
            }
        }

        BigDecimal cost = unitCost.get().setScale(2, RoundingMode.HALF_UP);
        BigDecimal price = retailPrice.get().setScale(2, RoundingMode.HALF_UP);
        String barcode = emptyToNull(row.text(InventoryColumn.BARCODE));
        String unitOfMeasure = defaultIfEmpty(row.text(InventoryColumn.UNIT_OF_MEASURE), DEFAULT_UNIT_OF_MEASURE);
        String batchCode = defaultIfEmpty(row.text(InventoryColumn.BATCH_CODE), DEFAULT_BATCH_CODE);

        if (exceedsCatalogLimits(sku, barcode, name, category, unitOfMeasure, batchCode, cost, price)) {
            return new RowValidationResult.Rejected(IngestErrorCode.VALUE_TOO_LONG);
        }

        return new RowValidationResult.Valid(new SpreadsheetRowDto(
                row.rowNumber(),
                sku,
                barcode,
                name,
                category,
                unitOfMeasure,
                cost,
                price,
                batchCode,
                manufacturingDate,
                expirationDate.get(),
                quantity.get().intValueExact()));
    }

    private static boolean exceedsCatalogLimits(String sku, String barcode, String name, String category,
                                                String unitOfMeasure, String batchCode,
                                                BigDecimal cost, BigDecimal price) {
        return sku.length() > 50
                || (barcode != null && barcode.length() > 50)
                || name.length() > 200
                || category.length() > 80
                || unitOfMeasure.length() > 20
                || batchCode.length() > 80
                || cost.compareTo(MAX_MONEY) > 0
                || price.compareTo(MAX_MONEY) > 0;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String defaultIfEmpty(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }
}
