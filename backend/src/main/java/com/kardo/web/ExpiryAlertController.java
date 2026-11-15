package com.kardo.web;

import com.kardo.dto.ExpiryAlertBoardDto;
import com.kardo.dto.MarkdownDecisionRequestDto;
import com.kardo.inventory.ExpiryAlertService;
import com.kardo.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
 * Panel de alertas de vencimiento.
 *
 * <p>Declarar una rebaja es trabajo de anaquel, no una decision contable: la
 * admite tambien el operario, a diferencia de resolver una merma. Lo que cierra
 * el circuito es que la decision queda firmada, de modo que luego puede
 * explicarse por que esa mercaderia salio a ese precio.
 */
@RestController
@RequestMapping("/api/v1/inventory/alerts")
public class ExpiryAlertController {

    private final ExpiryAlertService alertService;

    public ExpiryAlertController(ExpiryAlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<ExpiryAlertBoardDto> board() {
        return ResponseEntity.ok(alertService.board(CurrentUser.storeId()));
    }

    /** Devuelve el panel ya actualizado, para que la interfaz no tenga que recargarlo. */
    @PostMapping(value = "/{batchId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<ExpiryAlertBoardDto> decide(@PathVariable long batchId,
                                                      @Valid @RequestBody MarkdownDecisionRequestDto request) {
        var user = CurrentUser.require();
        return ResponseEntity.ok(
                alertService.decide(user.storeId(), batchId, request.decision(), user.email()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<Map<String, Object>>> history(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(alertService.history(CurrentUser.storeId(), Math.clamp(limit, 1, 200)));
    }
}
