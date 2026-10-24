package com.kardo.web;

import com.kardo.ingest.InventoryExportService;
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
    private final StoreRepository storeRepository;

    public InventoryExportController(InventoryExportService exportService, StoreRepository storeRepository) {
        this.exportService = exportService;
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
}
