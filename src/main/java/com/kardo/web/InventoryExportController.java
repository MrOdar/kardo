package com.kardo.web;

import com.kardo.ingest.InventoryExportService;
import com.kardo.ingest.InventoryPdfExportService;
import com.kardo.ingest.MarkdownLabelPdfService;
import com.kardo.repository.StoreRepository;
import com.kardo.security.CurrentUser;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Exportacion del inventario valorizado para contabilidad o auditoria. */
@RestController
@RequestMapping("/api/v1/inventory/export")
public class InventoryExportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final InventoryExportService exportService;
    private final InventoryPdfExportService pdfExportService;
    private final MarkdownLabelPdfService labelPdfService;
    private final StoreRepository storeRepository;

    public InventoryExportController(InventoryExportService exportService,
                                     InventoryPdfExportService pdfExportService,
                                     MarkdownLabelPdfService labelPdfService,
                                     StoreRepository storeRepository) {
        this.exportService = exportService;
        this.pdfExportService = pdfExportService;
        this.labelPdfService = labelPdfService;
        this.storeRepository = storeRepository;
    }

    /**
     * Devuelve un .xlsx con el stock actual de la tienda del usuario.
     *
     * <p>{@link StreamingResponseBody} escribe sobre la respuesta sin construir
     * el archivo en memoria: el heap se mantiene plano aunque el inventario
     * crezca, igual que en la ingesta.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<StreamingResponseBody> export() {
        long storeId = CurrentUser.storeId();
        String storeName = storeRepository.findNameById(storeId);
        String fileName = "inventario-" + storeId + "-" + LocalDate.now() + ".xlsx";

        StreamingResponseBody body = output -> exportService.writeTo(storeId, storeName, output);

        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(body);
    }

    /**
     * Informe valorizado imprimible.
     *
     * <p>No sustituye al Excel: ese es para trabajar con los datos y este para
     * firmar, archivar o entregar. Por eso lleva quien lo genero -sale del
     * token, no del cliente- y el criterio de valorizacion al pie.
     */
    @GetMapping("/pdf")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<StreamingResponseBody> exportPdf() {
        var user = CurrentUser.require();
        String storeName = storeRepository.findNameById(user.storeId());
        String fileName = "inventario-" + user.storeId() + "-" + LocalDate.now() + ".pdf";

        StreamingResponseBody body = output ->
                pdfExportService.writeTo(user.storeId(), storeName, user.email(), output);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(body);
    }

    /**
     * Etiquetas de los lotes que estan hoy en oferta.
     *
     * <p>Cierra el ultimo tramo que quedaba fuera del sistema: entre decidir la
     * rebaja y que el cliente la vea hay un cartel que hasta ahora se escribia
     * a mano. Sale de la misma columna que cobra la venta FEFO, asi que el
     * cartel y la caja no pueden decir precios distintos.
     */
    @GetMapping("/markdown-labels")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<StreamingResponseBody> markdownLabels() {
        long storeId = CurrentUser.storeId();
        String storeName = storeRepository.findNameById(storeId);
        String fileName = "etiquetas-rebaja-" + storeId + "-" + LocalDate.now() + ".pdf";

        StreamingResponseBody body = output ->
                labelPdfService.writeTo(storeId, storeName, output);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(body);
    }
}
