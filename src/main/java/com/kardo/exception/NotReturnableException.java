package com.kardo.exception;

/**
 * Ese lote no se puede canjear. Mapea a HTTP 422.
 *
 * <p>El caso real: entro por una carga de Excel y no por una guia de remision,
 * de modo que nadie sabe quien lo trajo. No es un error del operario ni un dato
 * mal formado -el lote existe y la cantidad es valida-, es que la operacion no
 * tiene sentido para ese lote, y por eso no es ni un 400 ni un 404.
 */
public class NotReturnableException extends RuntimeException {

    public NotReturnableException(String message) {
        super(message);
    }
}
