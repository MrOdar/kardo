package com.kardo.web;

import com.kardo.inventory.InventoryInsightsService;
import com.kardo.inventory.RiskHistoryService;
import com.kardo.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que explica las alertas en vez de limitarse a contarlas.
 *
 * <p>El resto de la aplicacion responde "que pasa hoy". Esto responde las dos
 * preguntas de alrededor: si va a mejor o a peor (la serie del valor en
 * riesgo), por que se compro de mas (los dias de cobertura) y de quien viene la
 * mercaderia que acaba en riesgo (el reparto por proveedor).
 *
 * <p>Lo ven los dos roles: el operario tambien decide que pedir, y esconderselo
 * no lo hace mejor comprador.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryInsightsController {

    private final InventoryInsightsService insightsService;
    private final RiskHistoryService riskHistoryService;

    public InventoryInsightsController(InventoryInsightsService insightsService,
                                       RiskHistoryService riskHistoryService) {
        this.insightsService = insightsService;
        this.riskHistoryService = riskHistoryService;
    }

    @GetMapping("/insights")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<Map<String, Object>> insights() {
        long storeId = CurrentUser.storeId();
        return ResponseEntity.ok(Map.of(
                "salesWindowDays", insightsService.salesWindowDays(),
                "coverage", insightsService.coverage(storeId),
                "categories", insightsService.byCategory(storeId),
                "suppliers", insightsService.bySupplier(storeId)));
    }

    /**
     * La serie diaria del valor en riesgo.
     *
     * <p>Puede venir vacia o con un solo punto, y eso no es un fallo: la tabla
     * empieza a llenarse el dia que se despliega. La interfaz lo dice asi en
     * vez de dibujar una linea plana que parezca un dato.
     */
    @GetMapping("/risk-history")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<Map<String, Object>>> riskHistory(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(
                riskHistoryService.history(CurrentUser.storeId(), Math.clamp(days, 2, 180)));
    }
}
