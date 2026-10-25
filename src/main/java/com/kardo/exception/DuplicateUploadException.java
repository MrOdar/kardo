package com.kardo.exception;

import com.kardo.dto.UploadAuditDto;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * El mismo contenido ya se proceso en esta tienda. Mapea a HTTP 409.
 *
 * <p>El mensaje nombra quien lo subio y cuando, porque el operario que reintenta
 * tras un corte de red necesita saber si la primera vez llego, no solo que esta
 * fue rechazada.
 */
public class DuplicateUploadException extends RuntimeException {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm").withZone(ZoneId.of("America/Lima"));

    private final transient UploadAuditDto previous;

    public DuplicateUploadException(UploadAuditDto previous) {
        super(describe(previous));
        this.previous = previous;
    }

    public UploadAuditDto previous() {
        return previous;
    }

    /**
     * Un archivo y un reenvio de correcciones llegan por caminos distintos y el
     * operario los vive como cosas distintas; el mensaje habla de lo que el hizo.
     */
    private static String describe(UploadAuditDto previous) {
        boolean corrections = previous.fileName() != null && previous.fileName().endsWith(".json");
        String subject = corrections ? "Estas correcciones ya se enviaron" : "Este archivo ya se cargo";
        String created = previous.insertedRows() == 1
                ? "1 lote creado"
                : previous.insertedRows() + " lotes creados";

        return subject + " el " + WHEN.format(previous.createdAt())
                + " por " + previous.uploadedBy() + " (" + created + "). No se volvio a procesar.";
    }
}
