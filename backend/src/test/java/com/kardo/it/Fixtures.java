package com.kardo.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Datos de partida para las pruebas de integracion.
 *
 * <p>Cada prueba crea su propia compania con un RUC unico, de modo que todas
 * comparten contenedor sin pisarse. No se limpian tablas entre pruebas: es mas
 * barato y mas realista que truncar, y obliga a que las consultas esten bien
 * acotadas por tienda o compania, que es justo lo que se quiere verificar.
 */
final class Fixtures {

    private final JdbcTemplate jdbcTemplate;
    private long companyId;

    Fixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Fixtures company(String name, String taxId) {
        String base = taxId.length() >= 4 ? taxId.substring(0, 4) : "2099";
        String unique = base + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        companyId = jdbcTemplate.queryForObject("""
                INSERT INTO companies (legal_name, tax_id) VALUES (?, ?) RETURNING company_id
                """, Long.class, name, unique);
        return this;
    }

    long store(String code) {
        String uniqueCode = code + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return jdbcTemplate.queryForObject("""
                INSERT INTO stores (company_id, store_code, name)
                VALUES (?, ?, ?) RETURNING store_id
                """, Long.class, companyId, uniqueCode, code);
    }

    long product(long storeId, String sku, String name, String cost, String price) {
        return product(storeId, sku, name, "PRUEBAS", cost, price);
    }

    /** Con familia explicita, para lo que se agrupa por categoria. */
    long product(long storeId, String sku, String name, String category, String cost, String price) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO products (company_id, sku, name, category, unit_cost, retail_price)
                SELECT s.company_id, ?, ?, ?, ?, ?
                FROM stores s WHERE s.store_id = ?
                RETURNING product_id
                """, Long.class, sku, name, category,
                new BigDecimal(cost), new BigDecimal(price), storeId);
    }

    long batch(long storeId, long productId, String code, LocalDate expiration, int quantity, String cost) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO inventory_batches (store_id, product_id, batch_code, expiration_date,
                                               initial_qty, current_qty, unit_cost, batch_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                RETURNING batch_id
                """, Long.class, storeId, productId, code, expiration, quantity, quantity, new BigDecimal(cost));
    }

    /** Carga de auditoria a la que colgar lotes, para las pruebas de reversion. */
    long upload(long storeId, String fileHash, String fileName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO inventory_uploads (store_id, file_hash, file_name, file_size_bytes, uploaded_by)
                VALUES (?, ?, ?, 1024, 'operario@kardo.pe')
                RETURNING upload_id
                """, Long.class, storeId, fileHash, fileName);
    }

    void attachToUpload(long batchId, long uploadId) {
        jdbcTemplate.update("UPDATE inventory_batches SET upload_id = ? WHERE batch_id = ?", uploadId, batchId);
    }
}
