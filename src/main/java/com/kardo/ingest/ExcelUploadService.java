package com.kardo.ingest;

import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.UploadAuditDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.exception.DuplicateUploadException;
import com.kardo.repository.InventoryUploadRepository;
import com.kardo.support.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga de inventario con rastro de auditoria e idempotencia por contenido.
 *
 * <p>Envuelve a {@link ExcelStreamingIngestService} con las dos preguntas que no
 * son de parseo sino de operacion: quien subio esto, y si ya lo habiamos
 * procesado antes.
 */
@Service
public class ExcelUploadService {

    private static final Logger log = LoggerFactory.getLogger(ExcelUploadService.class);

    private final ExcelStreamingIngestService ingestService;
    private final InventoryUploadRepository uploadRepository;

    public ExcelUploadService(ExcelStreamingIngestService ingestService,
                              InventoryUploadRepository uploadRepository) {
        this.ingestService = ingestService;
        this.uploadRepository = uploadRepository;
    }

    /**
     * Procesa el archivo una sola vez por tienda.
     *
     * <p>Se recorre el archivo dos veces: la primera para la huella, la segunda
     * para la ingesta. Es barato porque Tomcat ya lo dejo en disco, y permite
     * rechazar una recarga en milisegundos en lugar de tras procesar 10 MB.
     *
     * <p>Todo ocurre en una transaccion, incluido el asiento de auditoria. Si dos
     * peticiones identicas entran a la vez, la restriccion
     * {@code uq_upload_hash_store} hace fallar a la segunda y sus lotes
     * desaparecen con el rollback.
     *
     * @param streams  abre un flujo nuevo del archivo cada vez que se le pide
     * @param context  quien carga y desde donde
     * @throws DuplicateUploadException si esa tienda ya proceso el mismo contenido
     */
    @Transactional
    public IngestSummaryResponseDto upload(long storeId, Supplier<InputStream> streams, UploadContext context) {
        String fileHash = hash(streams);

        uploadRepository.findByHash(storeId, fileHash).ifPresent(previous -> {
            log.info("Carga rechazada por duplicada en la tienda {}: {} ya procesado el {}",
                    storeId, context.fileName(), previous.createdAt());
            throw new DuplicateUploadException(previous);
        });

        // El asiento se abre ANTES de ingerir por dos razones: los lotes nacen ya
        // ligados a su carga (lo que permite revertirla sin copiar el inventario)
        // y la restriccion de unicidad rechaza a la segunda de dos peticiones
        // simultaneas antes de que ninguna de las dos haga trabajo.
        long uploadId = uploadRepository.record(storeId, fileHash, context.fileName(), context.sizeBytes(),
                context.userEmail(), context.clientIp());

        IngestSummaryResponseDto summary;
        try (InputStream stream = streams.get()) {
            summary = ingestService.ingest(stream, storeId, uploadId);
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException("No se pudo leer el archivo: " + ex.getMessage(), 0);
        }

        uploadRepository.updateCounts(uploadId, summary.totalRowsProcessed(),
                summary.totalInserted(), summary.totalQuarantined());

        log.info("Carga {} registrada: {} por {} ({} filas, {} lotes, {} en cuarentena)",
                uploadId, context.fileName(), context.userEmail(),
                summary.totalRowsProcessed(), summary.totalInserted(), summary.totalQuarantined());

        return new IngestSummaryResponseDto(
                summary.totalRowsProcessed(),
                summary.totalInserted(),
                summary.totalQuarantined(),
                summary.quarantinedRows(),
                uploadId);
    }

    public List<UploadAuditDto> history(long storeId, int limit) {
        return uploadRepository.listRecent(storeId, limit);
    }

    private static String hash(Supplier<InputStream> streams) {
        try (InputStream stream = streams.get()) {
            return Sha256.of(stream);
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException("No se pudo leer el archivo: " + ex.getMessage(), 0);
        }
    }

    /**
     * Quien carga y desde donde.
     *
     * @param clientIp dato personal; se guarda para poder rastrear un acceso
     *                 indebido, no para perfilar al operario
     */
    public record UploadContext(String fileName, long sizeBytes, String userEmail, String clientIp) {
    }
}
