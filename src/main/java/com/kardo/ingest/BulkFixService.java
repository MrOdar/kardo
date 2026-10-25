package com.kardo.ingest;

import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.SpreadsheetRowDto;
import com.kardo.repository.InventoryUploadRepository;
import com.kardo.support.Sha256;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reenvio de filas corregidas, con el mismo rastro que una carga de archivo.
 *
 * <p>Las correcciones crean lotes igual que un Excel, asi que merecen el mismo
 * tratamiento: quedan en {@code inventory_uploads} con su autor y sus metricas,
 * los lotes nacen ligados a ese asiento —de modo que tambien pueden revertirse—
 * y un reenvio identico se rechaza en vez de duplicar mercaderia.
 *
 * <p>No hay archivo del que sacar la huella, asi que se calcula sobre el
 * contenido de las filas: dos envios con las mismas correcciones producen el
 * mismo hash, que es justo lo que se quiere detectar.
 */
@Service
public class BulkFixService {

    private static final Logger log = LoggerFactory.getLogger(BulkFixService.class);

    private final ExcelStreamingIngestService ingestService;
    private final InventoryUploadRepository uploadRepository;

    public BulkFixService(ExcelStreamingIngestService ingestService,
                          InventoryUploadRepository uploadRepository) {
        this.ingestService = ingestService;
        this.uploadRepository = uploadRepository;
    }

    @Transactional
    public IngestSummaryResponseDto apply(long storeId, List<SpreadsheetRowDto> rows,
                                          String userEmail, String clientIp) {
        String fingerprint = fingerprint(rows);

        var previous = uploadRepository.findByHash(storeId, fingerprint);
        if (previous.isPresent()) {
            throw new com.kardo.exception.DuplicateUploadException(previous.get());
        }

        long uploadId = uploadRepository.record(storeId, fingerprint,
                "correcciones-" + rows.size() + "-filas.json", payload(rows).length(),
                userEmail, clientIp);

        IngestSummaryResponseDto summary = ingestService.ingestFixedRows(storeId, rows, uploadId);

        uploadRepository.updateCounts(uploadId, summary.totalRowsProcessed(),
                summary.totalInserted(), summary.totalQuarantined());

        log.info("Correcciones {} registradas por {}: {} filas, {} lotes",
                uploadId, userEmail, summary.totalRowsProcessed(), summary.totalInserted());

        return new IngestSummaryResponseDto(
                summary.totalRowsProcessed(),
                summary.totalInserted(),
                summary.totalQuarantined(),
                summary.quarantinedRows(),
                uploadId);
    }

    /** Huella del contenido corregido, equivalente al SHA-256 de un archivo. */
    private static String fingerprint(List<SpreadsheetRowDto> rows) {
        try (var stream = new ByteArrayInputStream(payload(rows).getBytes(StandardCharsets.UTF_8))) {
            return Sha256.of(stream);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Representacion estable de las filas. El orden importa poco, pero debe ser
     * el mismo entre dos envios iguales, de ahi que se ordene.
     */
    private static String payload(List<SpreadsheetRowDto> rows) {
        return rows.stream()
                .map(row -> String.join("|",
                        row.sku(), row.name(), row.category(), row.unitOfMeasure(),
                        String.valueOf(row.unitCost()), String.valueOf(row.retailPrice()),
                        row.batchCode(), String.valueOf(row.manufacturingDate()),
                        String.valueOf(row.expirationDate()), String.valueOf(row.initialQty())))
                .sorted()
                .collect(Collectors.joining("\n"));
    }
}
