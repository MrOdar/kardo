package com.kardo.dto;

import java.time.Instant;

/**
 * Una carga de inventario registrada.
 *
 * @param fileHash SHA-256 del contenido; es lo que identifica una recarga aunque
 *                 el archivo llegue con otro nombre
 * @param clientIp dato personal: definir retencion antes de explotarlo
 */
public record UploadAuditDto(
        long uploadId,
        String fileName,
        String fileHash,
        long fileSizeBytes,
        int totalRows,
        int insertedRows,
        int quarantinedRows,
        String uploadedBy,
        String clientIp,
        Instant createdAt,

        /** Momento en que se deshizo la carga; nulo si sigue vigente. */
        Instant rolledBackAt,

        String rolledBackBy,

        /**
         * Para que sirvio la carga: {@code INVENTORY} o {@code SALES}.
         *
         * <p>El historial mezcla las dos, porque la plantilla trae las dos hojas
         * en el mismo archivo y el operario sube el mismo documento dos veces.
         * Sin esto, dos filas identicas en pantalla -mismo nombre, misma huella,
         * misma hora- serian indistinguibles, y "14 filas" significaria lotes en
         * una y ventas en la otra.
         */
        String kind) {

    public boolean isSales() {
        return "SALES".equals(kind);
    }
}
