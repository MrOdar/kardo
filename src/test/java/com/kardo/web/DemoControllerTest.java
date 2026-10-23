package com.kardo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kardo.config.SecurityProperties;
import com.kardo.dto.DemoDashboardDto;
import com.kardo.repository.DemoRepository;
import com.kardo.security.JwtService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoControllerTest {

    private static final String SECRET = "kardo-secreto-de-pruebas-con-mas-de-32-bytes";

    @Mock
    private DemoRepository demoRepository;

    private MockMvc mockMvc;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties(
                new SecurityProperties.Jwt(SECRET, "kardo", Duration.ofHours(8)),
                new SecurityProperties.Cors(List.of("http://localhost:5173")));
        jwtService = new JwtService(properties);

        when(demoRepository.loadDashboard()).thenReturn(dashboard());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DemoController(demoRepository, jwtService, properties))
                .build();
    }

    @Test
    @DisplayName("El reset ejecuta el procedimiento y devuelve un token usable de la tienda demo")
    void resetSiembraYEntregaToken() throws Exception {
        String token = mockMvc.perform(post("/api/v1/demo/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(999))
                .andExpect(jsonPath("$.message").value("Tienda demo restaurada correctamente."))
                .andExpect(jsonPath("$.expiresInSeconds").value(28800))
                .andExpect(jsonPath("$.dashboard.redAlertBatches").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        verify(demoRepository).resetDemoEnvironment();

        // El token debe servir de verdad para las demas llamadas de la SPA
        var claims = jwtService.parseToken(token);
        org.assertj.core.api.Assertions.assertThat(claims.getSubject()).isEqualTo("demo@kardo.pe");
        org.assertj.core.api.Assertions.assertThat(
                claims.get(JwtService.CLAIM_STORE_ID, Number.class).longValue()).isEqualTo(999L);
        org.assertj.core.api.Assertions.assertThat(
                claims.get(JwtService.CLAIM_ROLES, List.class)).contains("ROLE_OPERATOR");
    }

    @Test
    @DisplayName("El tablero expone las metricas del semaforo FEFO")
    void dashboardDevuelveMetricas() throws Exception {
        mockMvc.perform(get("/api/v1/demo/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(999))
                .andExpect(jsonPath("$.activeBatches").value(7))
                .andExpect(jsonPath("$.redAlertBatches").value(2))
                .andExpect(jsonPath("$.yellowAlertBatches").value(1))
                .andExpect(jsonPath("$.expiredBatches").value(0))
                .andExpect(jsonPath("$.valueAtRisk").value(184.60))
                .andExpect(jsonPath("$.inventoryValue").value(912.40))
                .andExpect(jsonPath("$.linesWithDiscrepancy").value(1));
    }

    @Test
    @DisplayName("La cola FEFO se acota para no devolver el inventario entero")
    void colaFefoSeAcota() throws Exception {
        when(demoRepository.loadFefoQueue(anyInt())).thenReturn(List.of(
                Map.of("sku", "SKU-LECHE-UHT-1L", "batch_code", "LOT-LCH-EXP-48H", "dias_restantes", 2)));

        mockMvc.perform(get("/api/v1/demo/fefo-queue").param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batch_code").value("LOT-LCH-EXP-48H"));

        // 500 se recorta a 50: el tablero muestra una cola, no un inventario completo
        verify(demoRepository).loadFefoQueue(50);
    }

    private static DemoDashboardDto dashboard() {
        return new DemoDashboardDto(999L, 7, 2, 1, 0,
                new BigDecimal("184.60"), new BigDecimal("912.40"), 1);
    }
}
