package com.kardo.repository;

import com.kardo.dto.SpreadsheetRowDto;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia de la ingesta con {@code JdbcTemplate.batchUpdate}.
 *
 * <p>Deliberadamente sin JPA: el Persistence Context de Hibernate retendria
 * cada entidad del archivo en su First-Level Cache y agotaria los 512 MB del
 * contenedor. Aqui cada micro-lote se envia y se olvida.
 */
@Repository
public class InventoryIngestRepository {

    private static final String UPSERT_PRODUCT = """
            INSERT INTO products (company_id, sku, barcode, name, category,
                                  unit_of_measure, unit_cost, retail_price)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (company_id, sku) DO UPDATE SET
                barcode         = COALESCE(EXCLUDED.barcode, products.barcode),
                name            = EXCLUDED.name,
                category        = EXCLUDED.category,
                unit_of_measure = EXCLUDED.unit_of_measure,
                unit_cost       = EXCLUDED.unit_cost,
                retail_price    = EXCLUDED.retail_price
            """;

    private static final String SELECT_PRODUCT_IDS = """
            SELECT sku, product_id
            FROM products
            WHERE company_id = ? AND sku = ANY (?)
            """;

    /**
     * El lote nace con {@code current_qty = initial_qty} (balance de masa intacto)
     * y marcado como EXPIRED si la fecha ya paso, para que el indice parcial de
     * FEFO no lo considere vendible.
     */
    private static final String INSERT_BATCH = """
            INSERT INTO inventory_batches (store_id, product_id, batch_code, manufacturing_date,
                                           expiration_date, initial_qty, current_qty, unit_cost,
                                           batch_status, upload_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? < CURRENT_DATE THEN 'EXPIRED' ELSE 'ACTIVE' END, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StoreRepository storeRepository;

    public InventoryIngestRepository(JdbcTemplate jdbcTemplate, StoreRepository storeRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.storeRepository = storeRepository;
    }

    /** La tienda determina la compania duena del catalogo (products es 3NF por company_id). */
    public long findCompanyIdByStore(long storeId) {
        return storeRepository.findCompanyIdByStore(storeId);
    }

    /**
     * Persiste un micro-lote completo: upsert del catalogo, resolucion de ids en
     * un unico viaje de red y alta de los lotes fisicos.
     *
     * @return numero de lotes insertados
     */
    public int persistMicroBatch(long storeId, long companyId, List<SpreadsheetRowDto> rows, Long uploadId) {
        if (rows.isEmpty()) {
            return 0;
        }
        upsertProducts(companyId, rows);
        Map<String, Long> productIdsBySku = resolveProductIds(companyId, rows);
        return insertBatches(storeId, rows, productIdsBySku, uploadId);
    }

    private void upsertProducts(long companyId, List<SpreadsheetRowDto> rows) {
        jdbcTemplate.batchUpdate(UPSERT_PRODUCT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SpreadsheetRowDto row = rows.get(i);
                ps.setLong(1, companyId);
                ps.setString(2, row.sku());
                if (row.barcode() == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, row.barcode());
                }
                ps.setString(4, row.name());
                ps.setString(5, row.category());
                ps.setString(6, row.unitOfMeasure());
                ps.setBigDecimal(7, row.unitCost());
                ps.setBigDecimal(8, row.retailPrice());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /**
     * Un solo SELECT con {@code = ANY(array)} en lugar de una consulta por SKU:
     * evita el patron N+1 contra la capa gratuita de Supabase, donde el pool
     * esta limitado a 5 conexiones.
     */
    private Map<String, Long> resolveProductIds(long companyId, List<SpreadsheetRowDto> rows) {
        String[] skus = rows.stream().map(SpreadsheetRowDto::sku).distinct().toArray(String[]::new);

        return jdbcTemplate.query(SELECT_PRODUCT_IDS, ps -> {
            ps.setLong(1, companyId);
            Array skuArray = ps.getConnection().createArrayOf("varchar", skus);
            ps.setArray(2, skuArray);
        }, rs -> {
            Map<String, Long> ids = new HashMap<>();
            while (rs.next()) {
                ids.put(rs.getString("sku"), rs.getLong("product_id"));
            }
            return ids;
        });
    }

    private int insertBatches(long storeId, List<SpreadsheetRowDto> rows,
                              Map<String, Long> productIdsBySku, Long uploadId) {
        int[] affected = jdbcTemplate.batchUpdate(INSERT_BATCH, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SpreadsheetRowDto row = rows.get(i);
                Long productId = productIdsBySku.get(row.sku());
                if (productId == null) {
                    // El upsert previo garantiza la fila; si falta, la transaccion
                    // debe caer en vez de insertar un lote huerfano.
                    throw new IllegalStateException("No se resolvio el product_id del SKU " + row.sku());
                }
                ps.setLong(1, storeId);
                ps.setLong(2, productId);
                ps.setString(3, row.batchCode());
                if (row.manufacturingDate() == null) {
                    ps.setNull(4, Types.DATE);
                } else {
                    ps.setObject(4, row.manufacturingDate());
                }
                ps.setObject(5, row.expirationDate());
                ps.setInt(6, row.initialQty());
                ps.setInt(7, row.initialQty());
                ps.setBigDecimal(8, row.unitCost());
                ps.setObject(9, row.expirationDate());
                // Ata el lote a la carga que lo creo: es lo que permite revertirla
                // sin guardar una copia del inventario entero.
                if (uploadId == null) {
                    ps.setNull(10, Types.INTEGER);
                } else {
                    ps.setLong(10, uploadId);
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });

        int inserted = 0;
        for (int count : affected) {
            // PostgreSQL devuelve 1 por fila insertada; SUCCESS_NO_INFO (-2) se
            // cuenta como exito porque el driver no reporta el detalle.
            inserted += count >= 0 ? count : 1;
        }
        return inserted;
    }
}
