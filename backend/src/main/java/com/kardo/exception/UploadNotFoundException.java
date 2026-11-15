package com.kardo.exception;

/** La carga no existe o es de otra tienda. Mapea a HTTP 404. */
public class UploadNotFoundException extends RuntimeException {

    public UploadNotFoundException(long uploadId) {
        super("La carga " + uploadId + " no existe en esta tienda.");
    }
}
