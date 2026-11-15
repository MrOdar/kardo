package com.kardo.inventory;

import com.kardo.dto.BatchRowDto;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Consulta del inventario fisico de una tienda.
 *
 * <p>Ordena siempre por vencimiento: la pantalla de inventario muestra lo mismo
 * que vera el motor FEFO cuando venda, de modo que el operario no tiene que
 * traducir entre dos ordenaciones distintas.
 */
@Service
public class InventoryBatchService {

    /**
     * La busqueda cubre los tres identificadores que el operario tiene a mano:
     * el codigo interno, el nombre del producto y el lote impreso en el envase.
     * Va por {@code ILIKE} y no por trigramas porque aqui se busca lo que el
     * usuario esta escribiendo, no se adivina lo que quiso decir.
     */
    private static final String SEARCH = """
            SELECT b.batch_id,
                   p.sku,
                   p.name AS product_name,
                   p.category,
                   b.batch_code,
                   b.expiration_date,
                   (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                   b.current_qty,
                   b.quarantined_qty,
                   b.unit_cost,
                   (b.current_qty * b.unit_cost) AS valor,
                   b.batch_status
            FROM inventory_batches b
            JOIN products p USING (product_id)
            WHERE b.store_id = ?
              AND (%s)
              AND (?::text IS NULL
                   OR p.sku ILIKE ?
                   OR p.name ILIKE ?
                   OR b.batch_code ILIKE ?)
            ORDER BY b.expiration_date ASC, p.name
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public InventoryBatchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param query texto libre; nulo o en blanco devuelve todo el tramo del filtro
     * @param limit tope de filas, para que una tienda grande no vuelque su
     *              inventario entero en una pantalla
     */
    public List<BatchRowDto> search(long storeId, String query, BatchFilter filter, int limit) {
        String sql = SEARCH.formatted(filter.predicate());
        String pattern = (query == null || query.isBlank()) ? null : "%" + query.trim() + "%";

        return jdbcTemplate.query(sql, (rs, rowNum) -> new BatchRowDto(
                        rs.getLong("batch_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getString("category"),
                        rs.getString("batch_code"),
                        rs.getObject("expiration_date", java.time.LocalDate.class),
                        rs.getInt("dias_restantes"),
                        rs.getInt("current_qty"),
                        rs.getInt("quarantined_qty"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("valor"),
                        rs.getString("batch_status")),
                storeId, pattern, pattern, pattern, pattern, limit);
    }
}
