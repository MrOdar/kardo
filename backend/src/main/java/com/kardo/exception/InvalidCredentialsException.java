package com.kardo.exception;

/**
 * Credenciales invalidas. Mapea a HTTP 401.
 *
 * <p>El mensaje no distingue entre correo inexistente y contraseña incorrecta:
 * hacerlo permitiria enumerar los correos dados de alta.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Correo o contraseña incorrectos.");
    }
}
