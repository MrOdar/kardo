package com.kardo.exception;

import java.util.List;

/**
 * La carga ya no puede deshacerse. Mapea a HTTP 409.
 *
 * <p>Lleva el detalle de que lotes lo impiden: el gerente necesita saber que se
 * movio para decidir que ajuste hacer a mano, no solo que no se puede.
 */
public class UploadNotRevertibleException extends RuntimeException {

    private final transient List<String> blockers;

    public UploadNotRevertibleException(String message, List<String> blockers) {
        super(message);
        this.blockers = List.copyOf(blockers);
    }

    public List<String> blockers() {
        return blockers;
    }
}
