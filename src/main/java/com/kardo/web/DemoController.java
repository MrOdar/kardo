package com.kardo.web;

import com.kardo.dto.DemoDashboardDto;
import com.kardo.dto.DemoResetResponseDto;
import com.kardo.repository.DemoRepository;
import com.kardo.security.AuthenticationService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Escenario de demostracion de un clic.
 *
 * <p>{@code /reset} es el unico endpoint publico que escribe: es la puerta de
 * entrada del evaluador, que no tiene credenciales. Por eso devuelve ya el token
 * con el que la SPA hace todo lo demas, y actua siempre sobre la tienda 999.
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Gerente sembrado por {@code sql/03_users_and_auth.sql} en la tienda demo. */
    private static final String DEMO_USER_EMAIL = "demo@kardo.pe";

    private final DemoRepository demoRepository;
    private final AuthenticationService authenticationService;

    public DemoController(DemoRepository demoRepository, AuthenticationService authenticationService) {
        this.demoRepository = demoRepository;
        this.authenticationService = authenticationService;
    }

    /**
     * Reinicia la tienda demo y entrega un token listo para usar.
     *
     * <p>Es idempotente: el procedimiento borra y vuelve a sembrar, de modo que
     * pulsar el boton dos veces deja el mismo escenario determinista.
     */
    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<DemoResetResponseDto> reset() {
        demoRepository.resetDemoEnvironment();

        // La sesion sale del usuario demo real de la tabla users, no de una
        // identidad inventada: lo que la auditoria registre sera una persona
        // que existe en la base.
        var session = authenticationService.openDemoSession(DEMO_USER_EMAIL, DemoRepository.DEMO_STORE_ID);
        DemoDashboardDto dashboard = demoRepository.loadDashboard();

        log.info("Escenario demo reiniciado: {} lotes activos, {} en alerta roja",
                dashboard.activeBatches(), dashboard.redAlertBatches());

        return ResponseEntity.ok(new DemoResetResponseDto(
                "Tienda demo restaurada correctamente.",
                DemoRepository.DEMO_STORE_ID,
                session,
                dashboard));
    }

    /** Metricas del semaforo FEFO para el tablero de la tienda demo. */
    @GetMapping("/dashboard")
    public ResponseEntity<DemoDashboardDto> dashboard() {
        return ResponseEntity.ok(demoRepository.loadDashboard());
    }

    /** Cola FEFO: el orden exacto en que se vendera la mercaderia. */
    @GetMapping("/fefo-queue")
    public ResponseEntity<List<Map<String, Object>>> fefoQueue(
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(demoRepository.loadFefoQueue(Math.clamp(limit, 1, 50)));
    }
}
