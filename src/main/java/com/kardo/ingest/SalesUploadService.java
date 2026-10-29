package com.kardo.ingest;

import com.kardo.dto.SalesIngestSummaryDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.exception.DuplicateUploadException;
import com.kardo.repository.InventoryUploadRepository;
import com.kardo.support.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga de ventas con rastro de auditoria e idempotencia por contenido.
 *
 * <p>Gemelo de {@link ExcelUploadService} y por el mismo motivo: la huella del
 * archivo es lo unico que impide que la misma hoja de ventas entre dos veces. Y
 * aqui importa mas que en inventario. Un inventario cargado dos veces se puede
 * revertir, porque un lote sin vender es un apunte que no ha pasado nada; una
 * venta duplicada no, porque deshacerla significa devolver unidades a un lote
 * que a lo mejor ya se agoto, o que entretanto se dio de baja por merma. Esta
 * guarda es la unica que hay, de modo que se pone por delante.
 */
@Service
public class SalesUploadService {

    private static final Logger log = LoggerFactory.getLogger(SalesUploadService.class);

    private final SalesExcelIngestService ingestService;
    private final InventoryUploadRepository uploadRepository;

    public SalesUploadService(SalesExcelIngestService ingestService,
                              InventoryUploadRepository uploadRepository) {
        this.ingestService = ingestService;
        this.uploadRepository = uploadRepository;
    }

    /**
     * Registra las ventas del archivo una sola vez por tienda.
     *
     * <p>El archivo se recorre dos veces -una para la huella, otra para las
     * ventas- porque Tomcat ya lo dejo en disco y rechazar una recarga en
     * milisegundos es mejor que descubrirlo tras descontar 200 lineas de stock.
     *
     * <p>La carga se marca como {@code SALES}, que es lo que permite subir la
     * MISMA plantilla primero para el inventario y despues para las ventas sin
     * que la segunda parezca un duplicado.
     *
     * @throws DuplicateUploadException si esa tienda ya registro estas ventas
     */
    @Transactional
    public SalesIngestSummaryDto upload(long storeId, Supplier<InputStream> streams,
                                        ExcelUploadService.UploadContext context) {
        String fileHash = hash(streams);
        var kind = InventoryUploadRepository.Kind.SALES;

        uploadRepository.findByHash(storeId, fileHash, kind).ifPresent(previous -> {
            log.info("Carga de ventas rechazada por duplicada en la tienda {}: {} ya procesado el {}",
                    storeId, context.fileName(), previous.createdAt());
            throw new DuplicateUploadException(previous);
        });

        // El asiento se abre antes de registrar nada: si dos peticiones
        // identicas entran a la vez, el indice unico rechaza a la segunda antes
        // de que ninguna de las dos toque el stock.
        long uploadId = uploadRepository.record(storeId, fileHash, context.fileName(), context.sizeBytes(),
                context.userEmail(), context.clientIp(), kind);

        SalesIngestSummaryDto summary;
        try (InputStream stream = streams.get()) {
            summary = ingestService.ingest(stream, storeId, context.userEmail());
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException("No se pudo leer el archivo: " + ex.getMessage(), 0);
        }

        uploadRepository.updateCounts(uploadId, summary.totalRowsProcessed(),
                summary.totalRegistered(), summary.totalQuarantined());

        log.info("Ventas {} registradas: {} por {} ({} filas, {} ventas, {} unidades, {} en cuarentena)",
                uploadId, context.fileName(), context.userEmail(), summary.totalRowsProcessed(),
                summary.totalRegistered(), summary.totalUnits(), summary.totalQuarantined());

        return new SalesIngestSummaryDto(
                summary.totalRowsProcessed(),
                summary.totalRegistered(),
                summary.totalQuarantined(),
                summary.totalUnits(),
                summary.revenue(),
                summary.quarantinedRows(),
                uploadId);
    }

    private static String hash(Supplier<InputStream> streams) {
        try (InputStream stream = streams.get()) {
            // Se le añade el proposito a la huella del contenido? No: el
            // proposito va en su propia columna y en el indice unico. Mezclarlo
            // en el hash haria que la misma huella significara cosas distintas
            // segun la columna, y dejaria de poder compararse entre cargas.
            return Sha256.of(stream);
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException("No se pudo leer el archivo: " + ex.getMessage(), 0);
        }
    }
}
