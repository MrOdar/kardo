package com.kardo.sales;

import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.ProductNotFoundException;
import com.kardo.exception.StoreNotFoundException;
import com.kardo.support.SqlErrors;
import org.springframework.dao.DataAccessException;

/**
 * Traduce los errores que levanta {@code sp_process_fefo_sale} a excepciones del
 * dominio.
 *
 * <p>El procedimiento es quien conoce las reglas de la salida FEFO, asi que es
 * tambien quien decide que esta mal. Aqui solo se traduce su ERRCODE para que el
 * manejador de excepciones devuelva el HTTP correcto, en vez de un 500 opaco
 * sobre una violacion de PL/pgSQL.
 */
final class FefoSaleErrorTranslator {

    private FefoSaleErrorTranslator() {
    }

    static RuntimeException translate(DataAccessException exception, String sku, long storeId) {
        String message = SqlErrors.message(exception);

        return switch (SqlErrors.sqlState(exception)) {
            case "P0001" -> new ProductNotFoundException(sku);
            case "P0002" -> new InsufficientStockException(message);
            case "P0003" -> new StoreNotFoundException(storeId);
            case "P0004" -> new InvalidQuantityException(message);
            default -> exception;
        };
    }
}
