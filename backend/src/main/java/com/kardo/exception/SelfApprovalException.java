package com.kardo.exception;

/**
 * Quien declaro la merma intenta resolverla. Mapea a HTTP 409.
 *
 * <p>Es el control antifraude del Reto 1 llevado hasta el final: separar quien
 * declara la perdida de quien la autoriza. Sin esto, el registro de mermas
 * documenta el descarte pero no lo controla.
 */
public class SelfApprovalException extends RuntimeException {

    public SelfApprovalException(String message) {
        super(message);
    }
}
