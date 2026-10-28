package com.kardo.web;

import com.kardo.dto.ReturnRequestDto;
import com.kardo.dto.SupplierReturnDto;
import com.kardo.returns.SupplierReturnService;
import com.kardo.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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
 * Canje al proveedor.
 *
 * <p>Declararlo es trabajo de anaquel y lo hace tambien el operario, igual que
 * declarar una merma: ve la fecha de cerca y sabe que le cabe en el camion de
 * mañana. Resolverlo es anotar lo que hizo el proveedor -abono o no quiso- y lo
 * firma el gerente, que es quien responde del dinero.
 */
@RestController
@RequestMapping("/api/v1/inventory/returns")
public class SupplierReturnController {

    private final SupplierReturnService returnService;

    public SupplierReturnController(SupplierReturnService returnService) {
        this.returnService = returnService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<Void> declare(@Valid @RequestBody ReturnRequestDto request) {
        var user = CurrentUser.require();
        returnService.declare(user.storeId(), request.batchId(), request.quantity(), user.email());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** @param status filtro opcional: PENDING, CREDITED o REFUSED */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<SupplierReturnDto>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(
                returnService.list(CurrentUser.storeId(), status, Math.clamp(limit, 1, 200)));
    }

    /** El proveedor se la llevo y abono el costo. */
    @PostMapping("/{returnId}/credit")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Void> credit(@PathVariable long returnId) {
        returnService.credit(returnId, CurrentUser.require().email());
        return ResponseEntity.noContent().build();
    }

    /** El proveedor no lo acepto: la mercaderia vuelve al stock. */
    @PostMapping("/{returnId}/refuse")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Void> refuse(@PathVariable long returnId) {
        returnService.refuse(returnId, CurrentUser.require().email());
        return ResponseEntity.noContent().build();
    }
}
