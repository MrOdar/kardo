package com.kardo.exception;

/**
 * Ya existe una empresa con ese RUC o una persona con ese correo.
 *
 * <p>Se distingue de un error de validacion a proposito: el dato esta bien
 * formado y el problema es que ya esta cogido, de modo que quien lo recibe sabe
 * que no tiene que corregirlo sino usar otro -o entrar con el que ya tiene-.
 */
public class DuplicateAccountException extends RuntimeException {

    public DuplicateAccountException(String message) {
        super(message);
    }
}
