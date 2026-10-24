package com.kardo.exception;

import com.kardo.dto.UploadAuditDto;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * El mismo archivo ya se cargo en esta tienda. Mapea a HTTP 409.
 *
 * <p>El mensaje nombra quien lo subio y cuando, porque el operario que reintenta
 * una carga tras un corte de red necesita saber si la primera llego, no solo que
 * esta fue rechazada.
 */
public class DuplicateUploadException extends RuntimeException {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm").withZone(ZoneId.of("America/Lima"));

    private final transient UploadAuditDto previous;

    public DuplicateUploadException(UploadAuditDto previous) {
        super("Este archivo ya se cargo el " + WHEN.format(previous.createdAt())
                + " por " + previous.uploadedBy()
                + " (" + previous.insertedRows() + " lotes creados). No se volvio a procesar.");
        this.previous = previous;
    }

    public UploadAuditDto previous() {
        return previous;
    }
}
