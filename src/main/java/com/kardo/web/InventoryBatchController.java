package com.kardo.web;

import com.kardo.dto.BatchRowDto;
import com.kardo.inventory.BatchFilter;
import com.kardo.inventory.InventoryBatchService;
import com.kardo.security.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inventario fisico de la tienda, con busqueda y filtro por estado. */
@RestController
@RequestMapping("/api/v1/inventory/batches")
public class InventoryBatchController {

    private final InventoryBatchService batchService;

    public InventoryBatchController(InventoryBatchService batchService) {
        this.batchService = batchService;
    }

    /**
     * @param q      texto libre sobre SKU, nombre o lote
     * @param status TODOS, ACTIVOS, POR_VENCER_48H, POR_VENCER_5D, VENCIDOS o EN_CUARENTENA
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<BatchRowDto>> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {

        return ResponseEntity.ok(batchService.search(
                CurrentUser.storeId(), q, BatchFilter.from(status), Math.clamp(limit, 1, 500)));
    }
}
