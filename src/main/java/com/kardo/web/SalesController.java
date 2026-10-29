package com.kardo.web;

import com.kardo.dto.SaleRequestDto;
import com.kardo.dto.SalesLedgerDto;
import com.kardo.dto.SaleResponseDto;
import com.kardo.sales.FefoSaleService;
import com.kardo.sales.SalesLedgerService;
import com.kardo.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final SalesLedgerService ledgerService;

    public SalesController(FefoSaleService fefoSaleService, SalesLedgerService ledgerService) {
        this.fefoSaleService = fefoSaleService;
        this.ledgerService = ledgerService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<SaleResponseDto> sell(@Valid @RequestBody SaleRequestDto request) {
        var user = CurrentUser.require();
        return ResponseEntity.ok(fefoSaleService.sell(
                user.storeId(), request.sku().trim(), request.quantity(), user.email()));
    }
    /**
     * Las ventas registradas de la tienda, las mas recientes primero.
     *
     * <p>Existe porque {@code daily_sales} no se podia mirar: se escribia desde
     * la caja y desde la carga por Excel, y solo se leia como insumo del ritmo
     * de salida. Despues de registrar 72 ventas no habia donde verlas.
     *
     * @param days  ventana hacia atras, acotada a un ano
     * @param limit tope de filas, acotado para no volcar un mes entero al SPA
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<SalesLedgerDto> recent(
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(ledgerService.recent(
                CurrentUser.storeId(), Math.clamp(days, 1, 365), Math.clamp(limit, 1, 500)));
    }

}
