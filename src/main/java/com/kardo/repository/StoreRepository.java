package com.kardo.repository;

import com.kardo.exception.StoreNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consultas sobre tiendas.
 *
 * <p>La compania es el limite de los datos del cliente: el catalogo
 * ({@code products}) y los proveedores son por compania, mientras que el stock
 * es por tienda. Todo flujo que toque el catalogo necesita resolver primero esta
 * relacion, de ahi que viva en un solo sitio.
 */
@Repository
public class StoreRepository {

    private final JdbcTemplate jdbcTemplate;

    public StoreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long findCompanyIdByStore(long storeId) {
        try {
            Long companyId = jdbcTemplate.queryForObject(
                    "SELECT company_id FROM stores WHERE store_id = ?", Long.class, storeId);
            if (companyId == null) {
                throw new StoreNotFoundException(storeId);
            }
            return companyId;
        } catch (EmptyResultDataAccessException ex) {
            throw new StoreNotFoundException(storeId);
        }
    }
}
