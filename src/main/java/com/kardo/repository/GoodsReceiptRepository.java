package com.kardo.repository;

import com.kardo.dto.ConfirmedLineDto;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia de una recepcion de mercaderia (GRN).
 *
 * <p>Las tres tablas se escriben dentro de la transaccion del servicio: la
 * cabecera, sus lineas y los lotes fisicos entran o no entran en bloque. Una
 * recepcion a medias dejaria stock sin documento o documento sin stock.
 */
@Repository
public class GoodsReceiptRepository {

    private static final String UPSERT_SUPPLIER = """
            INSERT INTO suppliers (company_id, name, tax_id)
            VALUES (?, ?, ?)
            ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
            RETURNING supplier_id
            """;

    private static final String INSERT_HEADER = """
            INSERT INTO goods_receipt_headers (store_id, supplier_id, grn_number, issue_date, ocr_raw_json)
            VALUES (?, ?, ?, ?, ?::jsonb)
            RETURNING receipt_id
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO goods_receipt_items (receipt_id, product_id, invoiced_qty, received_qty, unit_cost)
            VALUES (?, ?, ?, ?, ?)
            RETURNING item_id
            """;

    /** El lote nace completo: {@code current_qty = initial_qty} y sin cuarentena. */
    private static final String INSERT_BATCH = """
            INSERT INTO inventory_batches (store_id, product_id, receipt_item_id, batch_code,
                                           manufacturing_date, expiration_date,
                                           initial_qty, current_qty, unit_cost, batch_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? < CURRENT_DATE THEN 'EXPIRED' ELSE 'ACTIVE' END)
            """;

    private final JdbcTemplate jdbcTemplate;

    public GoodsReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Alta o actualizacion del proveedor por RUC dentro de la compania. */
    public long upsertSupplier(long companyId, String name, String taxId) {
        Long supplierId = jdbcTemplate.queryForObject(UPSERT_SUPPLIER, Long.class, companyId, name, taxId);
        if (supplierId == null) {
            throw new IllegalStateException("No se pudo registrar el proveedor con RUC " + taxId);
        }
        return supplierId;
    }

    /**
     * @param ocrRawJson respuesta cruda de la IA; se archiva en {@code jsonb}
     *                   para poder auditar despues que leyo el modelo
     */
    public long insertHeader(long storeId, long supplierId, String grnNumber,
                             java.time.LocalDate issueDate, String ocrRawJson) {
        Long receiptId = jdbcTemplate.queryForObject(INSERT_HEADER, Long.class,
                storeId, supplierId, grnNumber, issueDate, ocrRawJson);
        if (receiptId == null) {
            throw new IllegalStateException("No se pudo crear la cabecera de la guia " + grnNumber);
        }
        return receiptId;
    }

    /**
     * Inserta la linea y devuelve su id, necesario para enlazar el lote.
     *
     * <p>Se usa {@code RETURNING} por linea en lugar de un {@code batchUpdate}
     * porque el lote necesita el {@code item_id} recien generado. Una guia tiene
     * decenas de lineas, no miles: el coste es acotado por documento.
     */
    public long insertItem(long receiptId, ConfirmedLineDto line) {
        Long itemId = jdbcTemplate.queryForObject(INSERT_ITEM, Long.class,
                receiptId, line.productId(), line.invoicedQty(), line.receivedQty(), line.unitCost());
        if (itemId == null) {
            throw new IllegalStateException("No se pudo registrar la linea " + line.ocrLineId());
        }
        return itemId;
    }

    public void insertBatch(long storeId, long receiptItemId, ConfirmedLineDto line, String batchCode) {
        int quantity = line.receivedQty().intValueExact();
        jdbcTemplate.update(INSERT_BATCH, ps -> {
            ps.setLong(1, storeId);
            ps.setLong(2, line.productId());
            ps.setLong(3, receiptItemId);
            ps.setString(4, batchCode);
            if (line.manufacturingDate() == null) {
                ps.setNull(5, Types.DATE);
            } else {
                ps.setObject(5, line.manufacturingDate());
            }
            ps.setObject(6, line.expirationDate());
            ps.setInt(7, quantity);
            ps.setInt(8, quantity);
            ps.setBigDecimal(9, line.unitCost());
            ps.setObject(10, line.expirationDate());
        });
    }
}
