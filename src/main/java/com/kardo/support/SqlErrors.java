package com.kardo.support;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

/**
 * Lectura del ERRCODE y del mensaje que levanta un procedimiento de PostgreSQL.
 *
 * <p>Spring envuelve el {@link SQLException} en varias capas segun el tipo de
 * fallo, asi que hay que recorrer la cadena de causas para llegar al dato util.
 * Lo comparten el motor de ventas y el circuito de mermas, que delegan sus
 * reglas en la base de datos.
 */
public final class SqlErrors {

    private SqlErrors() {
    }

    /** @return el SQLSTATE (p. ej. {@code P0002}) o cadena vacia si no lo hay */
    public static String sqlState(DataAccessException exception) {
        SQLException sqlException = find(exception);
        String state = sqlException == null ? null : sqlException.getSQLState();
        return state == null ? "" : state;
    }

    /**
     * Mensaje redactado en el procedimiento, sin el prefijo {@code ERROR:} ni la
     * posicion que añade PostgreSQL: lo que el procedimiento escribio es lo que
     * el operario necesita leer.
     */
    public static String message(DataAccessException exception) {
        SQLException sqlException = find(exception);
        String message = sqlException == null ? exception.getMessage() : sqlException.getMessage();
        if (message == null) {
            return "La operacion no se pudo completar.";
        }
        return message.lines().findFirst().orElse(message)
                .replaceFirst("^ERROR:\\s*", "")
                .trim();
    }

    private static SQLException find(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
            cause = cause.getCause();
        }
        return null;
    }
}
