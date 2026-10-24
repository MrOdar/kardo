package com.kardo.web;

import com.kardo.dto.WasteEntryDto;
import com.kardo.dto.WasteReportRequestDto;
import com.kardo.security.CurrentUser;
import com.kardo.waste.WasteService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Mermas: declaracion por el operario y resolucion por el gerente.
 *
 * <p>La separacion de roles es el control antifraude: quien declara la baja no
 * puede confirmarla. Por eso declarar admite ambos roles y resolver exige
 * MANAGER.
 */
@RestController
@RequestMapping("/api/v1/inventory/waste")
public class WasteController {

    private final WasteService wasteService;

    public WasteController(WasteService wasteService) {
        this.wasteService = wasteService;
    }

    /** Declara una merma: deja las unidades en cuarentena, pendientes de aprobacion. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<WasteEntryDto>> report(@Valid @RequestBody WasteReportRequestDto request) {
        var user = CurrentUser.require();
        wasteService.report(user.storeId(), request.batchId(), request.quantity(),
                request.reason(), user.email());
        return ResponseEntity.ok(wasteService.list(user.storeId(), "PENDING_APPROVAL", 50));
    }

    /**
     * @param status PENDING_APPROVAL, APPROVED o REJECTED; sin el, todo el historial
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<WasteEntryDto>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(
                wasteService.list(CurrentUser.storeId(), status, Math.clamp(limit, 1, 200)));
    }

    /** Consolida la perdida. Solo el gerente. */
    @PostMapping("/{wasteId}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<List<WasteEntryDto>> approve(@PathVariable long wasteId) {
        var user = CurrentUser.require();
        wasteService.approve(wasteId, user.email());
        return ResponseEntity.ok(wasteService.list(user.storeId(), "PENDING_APPROVAL", 50));
    }

    /** Devuelve la mercaderia al stock vendible. Solo el gerente. */
    @PostMapping("/{wasteId}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<List<WasteEntryDto>> reject(@PathVariable long wasteId) {
        var user = CurrentUser.require();
        wasteService.reject(wasteId, user.email());
        return ResponseEntity.ok(wasteService.list(user.storeId(), "PENDING_APPROVAL", 50));
    }
}
