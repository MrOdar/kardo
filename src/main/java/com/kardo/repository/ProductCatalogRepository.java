package com.kardo.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lectura del catalogo de productos de una compania, a traves de su tienda. */
@Repository
public class ProductCatalogRepository {

    private static final String LIST_BY_STORE = """
            SELECT p.product_id, p.sku, p.name, p.category, p.unit_of_measure, p.unit_cost, p.retail_price
            FROM products p
            JOIN stores s ON s.company_id = p.company_id
            WHERE s.store_id = ?
            ORDER BY p.name
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StoreRepository storeRepository;

    public ProductCatalogRepository(JdbcTemplate jdbcTemplate, StoreRepository storeRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.storeRepository = storeRepository;
    }

    public List<Map<String, Object>> listByStore(long storeId) {
        // Valida la tienda primero para devolver 404 en vez de una lista vacia
        // que el operario leeria como "catalogo sin productos".
        storeRepository.findCompanyIdByStore(storeId);
        return jdbcTemplate.queryForList(LIST_BY_STORE, storeId);
    }
}
