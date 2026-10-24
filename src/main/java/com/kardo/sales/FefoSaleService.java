package com.kardo.sales;

import com.kardo.dto.SaleLineDto;
import com.kardo.dto.SaleResponseDto;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salida de mercaderia por el motor FEFO.
 *
 * <p>La logica vive en {@code sp_process_fefo_sale}: es un procedimiento con
 * candados ({@code FOR SHARE} sobre la tienda, {@code FOR UPDATE} sobre los
 * lotes) que debe ejecutarse entero o nada. Partirlo en consultas desde Java
 * abriria ventanas de carrera entre la lectura del stock y su descuento.
 *
 * <p>Lo que si hace Java es contar que lotes consumio la venta, para que la
 * pantalla muestre el FEFO en accion en lugar de un simple "listo".
 */
@Service
public class FefoSaleService {

    private static final Logger log = LoggerFactory.getLogger(FefoSaleService.class);

    /**
     * Las ventas recien creadas se identifican por ser posteriores al ultimo
     * {@code sale_id} previo a la llamada. Dentro de la misma transaccion nadie
     * mas puede haber insertado filas visibles, asi que el corte es exacto.
     */
    private static final String LAST_SALE_ID =
            "SELECT COALESCE(max(sale_id), 0) FROM daily_sales WHERE store_id = ?";

    private static final String CONSUMED_BATCHES = """
            SELECT s.batch_id,
                   b.batch_code,
                   b.expiration_date,
                   (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                   s.quantity,
                   s.unit_retail_price,
                   s.lot_unit_cogs,
                   p.name AS product_name
            FROM daily_sales s
            JOIN inventory_batches b ON b.batch_id = s.batch_id
            JOIN products p ON p.product_id = s.product_id
            WHERE s.store_id = ? AND s.sale_id > ?
            ORDER BY s.sale_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public FefoSaleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param storeId    tienda del operario, tomada de su token
     * @param operatorId queda registrado en {@code daily_sales} para la auditoria
     */
    @Transactional
    public SaleResponseDto sell(long storeId, String sku, int quantity, String operatorId) {
        Long lastSaleId = jdbcTemplate.queryForObject(LAST_SALE_ID, Long.class, storeId);

        try {
            // Los casts son necesarios: en Java la tienda es long y el driver la
            // envia como BIGINT, con lo que PostgreSQL no encuentra el
            // procedimiento, que declara sus parametros como INT.
            jdbcTemplate.update("CALL sp_process_fefo_sale(?::int, ?::varchar, ?::int, ?::varchar)",
                    storeId, sku, quantity, operatorId);
        } catch (DataAccessException ex) {
            throw FefoSaleErrorTranslator.translate(ex, sku, storeId);
        }

        List<SaleLineDto> consumed = jdbcTemplate.query(CONSUMED_BATCHES,
                (rs, rowNum) -> new SaleLineDto(
                        rs.getLong("batch_id"),
                        rs.getString("batch_code"),
                        rs.getObject("expiration_date", java.time.LocalDate.class),
                        rs.getInt("dias_restantes"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_retail_price"),
                        rs.getBigDecimal("lot_unit_cogs")),
                storeId, lastSaleId);

        String productName = jdbcTemplate.queryForObject("""
                SELECT p.name FROM products p
                JOIN stores s ON s.company_id = p.company_id
                WHERE s.store_id = ? AND p.sku = ?
                """, String.class, storeId, sku);

        BigDecimal revenue = consumed.stream()
                .map(line -> line.unitRetailPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cogs = consumed.stream()
                .map(line -> line.lotUnitCogs().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("Salida FEFO: {} x{} en la tienda {} por {} ({} lote(s) consumidos)",
                sku, quantity, storeId, operatorId, consumed.size());

        return new SaleResponseDto(sku, productName, quantity, revenue, cogs, revenue.subtract(cogs), consumed);
    }
}
