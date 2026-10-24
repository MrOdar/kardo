package com.kardo.web;

import com.kardo.dto.SaleRequestDto;
import com.kardo.dto.SaleResponseDto;
import com.kardo.sales.FefoSaleService;
import com.kardo.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salidas de inventario.
 *
 * <p>Registra ventas reales, no simulaciones: descuenta stock y deja asiento en
 * {@code daily_sales}. La respuesta detalla que lotes se consumieron para que
 * la rotacion FEFO sea visible en pantalla.
 */
@RestController
@RequestMapping("/api/v1/inventory/sales")
public class SalesController {

    private final FefoSaleService fefoSaleService;

    public SalesController(FefoSaleService fefoSaleService) {
        this.fefoSaleService = fefoSaleService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<SaleResponseDto> sell(@Valid @RequestBody SaleRequestDto request) {
        var user = CurrentUser.require();
        return ResponseEntity.ok(fefoSaleService.sell(
                user.storeId(), request.sku().trim(), request.quantity(), user.email()));
    }
}
