package com.kardo.dto;

/**
 * Resultado de revertir una carga.
 *
 * @param removedBatches lotes eliminados; el catalogo de productos no se toca
 */
public record RollbackResponseDto(
        long uploadId,
        String fileName,
        int removedBatches,
        String message) {
}
