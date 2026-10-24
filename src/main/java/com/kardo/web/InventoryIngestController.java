package com.kardo.web;

import com.kardo.dto.BulkFixRequestDto;
import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.ingest.ExcelStreamingIngestService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ingesta masiva de inventario: carga inicial desde Excel y reenvio de correcciones. */
@RestController
@RequestMapping("/api/v1/inventory/ingest")
public class InventoryIngestController {

    private final ExcelStreamingIngestService ingestService;

    public InventoryIngestController(ExcelStreamingIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Carga un Excel de inventario en streaming.
     *
     * <p>Se consume {@code file.getInputStream()} directamente: el archivo ya
     * esta en disco por el spooling de Tomcat y nunca se copia a un arreglo de
     * bytes en el heap.
     */
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<IngestSummaryResponseDto> ingestExcel(@RequestPart("file") MultipartFile file) {

        if (file.isEmpty()) {
            throw new CorruptSpreadsheetException("El archivo esta vacio.", 0);
        }

        // La tienda sale del token, no de la peticion: nadie carga inventario
        // en una tienda que no es la suya.
        try (InputStream inputStream = file.getInputStream()) {
            return ResponseEntity.ok(ingestService.ingest(inputStream, StoreScope.current()));
        } catch (IOException ex) {
            throw new CorruptSpreadsheetException("No se pudo leer el archivo: " + ex.getMessage(), 0);
        }
    }

    /**
     * Reinserta unicamente las filas que el operario corrigio en su navegador.
     *
     * <p>No hay estado de la carga previa en el servidor, de modo que esta
     * peticion es autosuficiente y puede llegar incluso minutos despues.
     */
    @PostMapping(value = "/bulk-fix", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<IngestSummaryResponseDto> ingestFixedRows(@Valid @RequestBody BulkFixRequestDto request) {
        long storeId = StoreScope.requireMatching(request.storeId());
        return ResponseEntity.ok(ingestService.ingestFixedRows(storeId, request.rows()));
    }
}
