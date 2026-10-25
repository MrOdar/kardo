package com.kardo.web;

import com.kardo.dto.BulkFixRequestDto;
import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.RollbackResponseDto;
import com.kardo.dto.UploadAuditDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.ingest.BulkFixService;
import com.kardo.ingest.ExcelUploadService;
import com.kardo.ingest.UploadRollbackService;
import com.kardo.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ingesta masiva de inventario: carga inicial desde Excel y reenvio de correcciones. */
@RestController
@RequestMapping("/api/v1/inventory/ingest")
public class InventoryIngestController {

    private final BulkFixService bulkFixService;
    private final ExcelUploadService uploadService;
    private final UploadRollbackService rollbackService;

    public InventoryIngestController(BulkFixService bulkFixService,
                                     ExcelUploadService uploadService,
                                     UploadRollbackService rollbackService) {
        this.bulkFixService = bulkFixService;
        this.uploadService = uploadService;
        this.rollbackService = rollbackService;
    }

    /**
     * Carga un Excel de inventario en streaming.
     *
     * <p>Se consume {@code file.getInputStream()} directamente: el archivo ya
     * esta en disco por el spooling de Tomcat y nunca se copia a un arreglo de
     * bytes en el heap. Un archivo ya procesado por esta tienda se rechaza con
     * HTTP 409 en lugar de duplicar sus lotes.
     */
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<IngestSummaryResponseDto> ingestExcel(@RequestPart("file") MultipartFile file,
                                                                HttpServletRequest request) {
        if (file.isEmpty()) {
            throw new CorruptSpreadsheetException("El archivo esta vacio.", 0);
        }

        var user = CurrentUser.require();
        var context = new ExcelUploadService.UploadContext(
                sanitizedName(file),
                file.getSize(),
                user.email(),
                request.getRemoteAddr());

        // La tienda sale del token, no de la peticion: nadie carga inventario
        // en una tienda que no es la suya.
        return ResponseEntity.ok(uploadService.upload(user.storeId(), () -> openStream(file), context));
    }

    /**
     * Reinserta unicamente las filas que el operario corrigio en su navegador.
     *
     * <p>No hay estado de la carga previa en el servidor, de modo que esta
     * peticion es autosuficiente y puede llegar incluso minutos despues.
     */
    @PostMapping(value = "/bulk-fix", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<IngestSummaryResponseDto> ingestFixedRows(@Valid @RequestBody BulkFixRequestDto request,
                                                                    HttpServletRequest httpRequest) {
        long storeId = StoreScope.requireMatching(request.storeId());
        // Las correcciones crean lotes igual que un archivo: dejan el mismo rastro.
        return ResponseEntity.ok(bulkFixService.apply(
                storeId, request.rows(), CurrentUser.require().email(), httpRequest.getRemoteAddr()));
    }

    /** Historial de cargas de la tienda: quien subio que archivo y cuando. */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<UploadAuditDto>> history(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(uploadService.history(CurrentUser.storeId(), Math.clamp(limit, 1, 100)));
    }

    /**
     * Revierte una carga, si su mercaderia sigue intacta.
     *
     * <p>Solo el gerente: deshacer un movimiento de inventario no es una
     * correccion de datos, es una decision sobre el stock del local.
     */
    @PostMapping("/{uploadId}/rollback")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<RollbackResponseDto> rollback(@PathVariable long uploadId) {
        var user = CurrentUser.require();
        return ResponseEntity.ok(rollbackService.rollback(user.storeId(), uploadId, user.email()));
    }

    /** El nombre lo elige el cliente: se acota antes de guardarlo en la auditoria. */
    private static String sanitizedName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "sin-nombre.xlsx";
        }
        String bare = name.replace('\\', '/');
        bare = bare.substring(bare.lastIndexOf('/') + 1);
        return bare.length() > 255 ? bare.substring(0, 255) : bare;
    }

    private static InputStream openStream(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
