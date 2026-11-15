package com.kardo.web;

import com.kardo.diagnostics.DiagnosticsService;
import com.kardo.dto.DiagnosticsReportDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostico de rendimiento.
 *
 * <p>Restringido al gerente porque describe la infraestructura -version del
 * pool, latencias de los proveedores, memoria del contenedor- y eso no le sirve
 * de nada al operario, pero si a quien quiera sondear el despliegue.
 *
 * <p>Vive en la API y no solo en {@code /actuator/metrics} porque el actuator
 * entrega series en crudo para un recolector que aqui no existe: esta respuesta
 * esta pensada para que la lea una persona.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final DiagnosticsService diagnosticsService;

    public OpsController(DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping("/diagnostics")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<DiagnosticsReportDto> diagnostics() {
        return ResponseEntity.ok(diagnosticsService.report());
    }
}
