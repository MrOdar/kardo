package com.kardo.web;

import com.kardo.conciliation.ReceiptConciliationService;
import com.kardo.vision.VisionQuotaService;
import com.kardo.dto.ReceiptConfirmationRequestDto;
import com.kardo.dto.ReceiptConfirmationResponseDto;
import com.kardo.dto.ReceiptScanResponseDto;
import com.kardo.dto.VisionQuotaDto;
import com.kardo.exception.UnreadableImageException;
import com.kardo.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Recepcion de mercaderia a partir de la foto de una guia de remision. */
@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptScanController {

    private final ReceiptConciliationService conciliationService;
    private final VisionQuotaService visionQuotaService;

    public ReceiptScanController(ReceiptConciliationService conciliationService,
                                 VisionQuotaService visionQuotaService) {
        this.conciliationService = conciliationService;
        this.visionQuotaService = visionQuotaService;
    }

    /**
     * Lee la guia con vision multimodal y propone un SKU por linea.
     *
     * <p>No escribe nada: devuelve la propuesta con su nivel de confianza para
     * que el operario la corrija antes de confirmar.
     */
    @PostMapping(value = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<ReceiptScanResponseDto> scan(@RequestPart("file") MultipartFile file) {

        if (file.isEmpty()) {
            throw new UnreadableImageException("La imagen esta vacia.");
        }

        try (InputStream imageStream = file.getInputStream()) {
            return ResponseEntity.ok(conciliationService.scan(
                    imageStream, StoreScope.current(), CurrentUser.require().email()));
        } catch (IOException ex) {
            throw new UnreadableImageException("No se pudo leer la imagen: " + ex.getMessage(), ex);
        }
    }

    /**
     * Cupo de escaneos que le queda hoy a la tienda.
     *
     * <p>La interfaz lo pide al abrir el panel para decirlo <em>antes</em> de
     * que el operario saque la foto: enterarse del tope al pulsar el boton, con
     * la guia ya en la mano, es la peor forma de descubrirlo.
     */
    @GetMapping("/quota")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<VisionQuotaDto> quota() {
        return ResponseEntity.ok(visionQuotaService.status(StoreScope.current()));
    }

    /** Persiste la conciliacion confirmada por el operario de forma atomica. */
    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<ReceiptConfirmationResponseDto> confirm(
            @Valid @RequestBody ReceiptConfirmationRequestDto request,
            HttpServletRequest httpRequest) {
        StoreScope.requireMatching(request.storeId());
        return ResponseEntity.ok(conciliationService.confirm(
                request, CurrentUser.require().email(), httpRequest.getRemoteAddr()));
    }
}
