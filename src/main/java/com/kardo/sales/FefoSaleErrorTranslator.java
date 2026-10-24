package com.kardo.sales;

import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidSaleException;
import com.kardo.exception.ProductNotFoundException;
import com.kardo.exception.StoreNotFoundException;
import java.sql.SQLException;
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

    /** Se queda con el mensaje de PostgreSQL, que ya viene redactado para el operario. */
    static RuntimeException translate(DataAccessException exception, String sku, long storeId) {
        String sqlState = sqlStateOf(exception);
        String message = rootMessage(exception);

        return switch (sqlState == null ? "" : sqlState) {
            case "P0001" -> new ProductNotFoundException(sku);
            case "P0002" -> new InsufficientStockException(message);
            case "P0003" -> new StoreNotFoundException(storeId);
            case "P0004" -> new InvalidSaleException(message);
            default -> exception;
        };
    }

    private static String sqlStateOf(DataAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    /**
     * PostgreSQL antepone "ERROR: " y añade la posicion; el operario solo
     * necesita la frase.
     */
    private static String rootMessage(DataAccessException exception) {
        Throwable cause = exception;
        String message = exception.getMessage();
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                message = sqlException.getMessage();
                break;
            }
            cause = cause.getCause();
        }
        if (message == null) {
            return "No se pudo registrar la salida.";
        }
        String firstLine = message.lines().findFirst().orElse(message);
        return firstLine.replaceFirst("^ERROR:\\s*", "").trim();
    }
}
