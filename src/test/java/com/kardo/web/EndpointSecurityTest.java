package com.kardo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kardo.conciliation.ReceiptConciliationService;
import com.kardo.diagnostics.DiagnosticsService;
import com.kardo.config.SecurityConfig;
import com.kardo.config.SecurityProperties;
import com.kardo.dto.BulkFixRequestDto;
import com.kardo.dto.DemoDashboardDto;
import com.kardo.dto.DiagnosticsReportDto;
import com.kardo.dto.ExpiryAlertBoardDto;
import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.MarkdownDecisionRequestDto;
import com.kardo.dto.NewStoreRequestDto;
import com.kardo.dto.NewUserRequestDto;
import com.kardo.dto.RegisterRequestDto;
import com.kardo.dto.ReturnRequestDto;
import com.kardo.dto.RollbackResponseDto;
import com.kardo.dto.SaleRequestDto;
import com.kardo.dto.SaleResponseDto;
import com.kardo.dto.SessionDto;
import com.kardo.dto.SpreadsheetRowDto;
import com.kardo.dto.SwitchStoreRequestDto;
import com.kardo.dto.VisionQuotaDto;
import com.kardo.dto.WasteReportRequestDto;
import com.kardo.ingest.BulkFixService;
import com.kardo.ingest.ExcelUploadService;
import com.kardo.ingest.InventoryExportService;
import com.kardo.ingest.UploadRollbackService;
import com.kardo.inventory.ExpiryAlertService;
import com.kardo.inventory.InventoryBatchService;
import com.kardo.repository.DemoRepository;
import com.kardo.repository.ProductCatalogRepository;
import com.kardo.repository.StoreRepository;
import com.kardo.sales.FefoSaleService;
import com.kardo.security.AuthenticationService;
import com.kardo.throttle.DemoResetThrottle;
import com.kardo.throttle.LoginThrottle;
import com.kardo.security.JwtAuthenticationFilter;
import com.kardo.security.JwtService;
import com.kardo.vision.VisionQuotaService;
import com.kardo.waste.WasteService;
import io.jsonwebtoken.Jwts;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lo que la cadena de filtros deja pasar y lo que no.
 *
 * <p>Esta clase existe porque hasta ahora nada en el build comprobaba la
 * seguridad: las pruebas de integracion ejercitan el SQL y las unitarias la
 * logica con dobles, de modo que borrar un {@code @PreAuthorize} dejaba la
 * suite verde. Aqui se levanta la cadena real, con el filtro real y tokens
 * firmados de verdad, y se comprueba lo unico que importa desde fuera: quien
 * entra, con que rol, y sobre que tienda acaba escribiendo.
 *
 * <p>Los servicios son dobles a proposito: la pregunta no es si el SQL funciona,
 * sino si la peticion llega hasta el servicio y con que argumentos. Que un
 * doble quede sin usar es, aqui, la afirmacion mas fuerte de todas.
 */
@WebMvcTest
@Import({SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(SecurityProperties.class)
@TestPropertySource(properties = {
        "kardo.security.jwt.secret=" + EndpointSecurityTest.SECRET,
        "kardo.security.jwt.issuer=" + EndpointSecurityTest.ISSUER,
        "kardo.security.jwt.expiration=PT1H",
        "kardo.security.cors.allowed-origins=http://localhost:5173"
})
class EndpointSecurityTest {

    static final String SECRET = "clave-de-pruebas-kardo-con-mas-de-32-bytes";
    static final String ISSUER = "kardo-test";

    /** Tienda del portador del token en todas las pruebas. */
    private static final long TOKEN_STORE = 999L;

    /** Otra tienda cualquiera: la que un cliente malicioso intentaria tocar. */
    private static final long OTHER_STORE = 777L;

    private static final String OPERATOR = "operario@kardo.pe";
    private static final String MANAGER = "gerente@kardo.pe";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private DemoRepository demoRepository;

    @MockBean
    private InventoryBatchService batchService;

    @MockBean
    private InventoryExportService exportService;

    @MockBean
    private com.kardo.ingest.InventoryPdfExportService pdfExportService;

    @MockBean
    private com.kardo.ingest.MarkdownLabelPdfService labelPdfService;

    @MockBean
    private com.kardo.inventory.InventoryInsightsService insightsService;

    @MockBean
    private com.kardo.inventory.RiskHistoryService riskHistoryService;

    @MockBean
    private com.kardo.onboarding.OnboardingService onboardingService;

    @MockBean
    private com.kardo.throttle.RegistrationThrottle registrationThrottle;

    @MockBean
    private StoreRepository storeRepository;

    @MockBean
    private BulkFixService bulkFixService;

    @MockBean
    private ExcelUploadService uploadService;

    @MockBean
    private UploadRollbackService rollbackService;

    @MockBean
    private com.kardo.ingest.SalesUploadService salesUploadService;

    @MockBean
    private com.kardo.ingest.SalesExcelIngestService salesIngestService;

    @MockBean
    private ProductCatalogRepository productCatalogRepository;

    @MockBean
    private ReceiptConciliationService conciliationService;

    @MockBean
    private FefoSaleService fefoSaleService;

    @MockBean
    private WasteService wasteService;

    @MockBean
    private com.kardo.returns.SupplierReturnService returnService;

    @MockBean
    private ExpiryAlertService alertService;

    @MockBean
    private VisionQuotaService visionQuotaService;

    @MockBean
    private DiagnosticsService diagnosticsService;

    // Los frenos de los endpoints publicos tienen sus propias pruebas en
    // PublicEndpointThrottleTest; aqui estorbarian, porque varias pruebas
    // repiten el mismo login a proposito.
    @MockBean
    private LoginThrottle loginThrottle;

    @MockBean
    private DemoResetThrottle demoResetThrottle;

    private String operatorToken;
    private String managerToken;

    @BeforeEach
    void issueTokens() {
        operatorToken = jwtService.issueToken(OPERATOR, TOKEN_STORE, List.of("ROLE_OPERATOR"));
        managerToken = jwtService.issueToken(MANAGER, TOKEN_STORE, List.of("ROLE_MANAGER"));
    }

    // ---------------------------------------------------------------------
    // Sin credenciales
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Sin token")
    class SinToken {

        /**
         * El 401 (y no el 403 que Spring da por defecto) es contractual: la SPA
         * distingue "renueva tu sesion" de "no tienes permiso" por el codigo.
         */
        @ParameterizedTest(name = "{0} {1} responde 401")
        @CsvSource({
                "GET,  /api/v1/auth/me",
                "GET,  /api/v1/products",
                "GET,  /api/v1/inventory/batches",
                "GET,  /api/v1/inventory/alerts",
                "GET,  /api/v1/inventory/alerts/history",
                "POST, /api/v1/inventory/alerts/7/decision",
                "GET,  /api/v1/inventory/export",
                "GET,  /api/v1/inventory/export/pdf",
                "GET,  /api/v1/inventory/export/markdown-labels",
                "GET,  /api/v1/inventory/insights",
                "GET,  /api/v1/inventory/risk-history",
                "GET,  /api/v1/stores",
                "POST, /api/v1/stores",
                "POST, /api/v1/users",
                "GET,  /api/v1/auth/stores",
                "POST, /api/v1/auth/store",
                "GET,  /api/v1/inventory/ingest/history",
                "POST, /api/v1/inventory/ingest/excel",
                "POST, /api/v1/inventory/ingest/bulk-fix",
                "POST, /api/v1/inventory/ingest/7/rollback",
                "POST, /api/v1/receipts/scan",
                "GET,  /api/v1/receipts/quota",
                "POST, /api/v1/receipts/confirm",
                "POST, /api/v1/inventory/sales",
                "GET,  /api/v1/inventory/returns",
                "POST, /api/v1/inventory/returns",
                "POST, /api/v1/inventory/returns/9/credit",
                "POST, /api/v1/inventory/returns/9/refuse",
                "GET,  /api/v1/inventory/waste",
                "POST, /api/v1/inventory/waste",
                "POST, /api/v1/inventory/waste/9/approve",
                "POST, /api/v1/inventory/waste/9/reject",
                "GET,  /api/v1/ops/diagnostics",
                "GET,  /api/v1/demo/dashboard",
                "GET,  /api/v1/demo/fefo-queue"
        })
        void endpointProtegidoExigeToken(String method, String path) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(method.trim()), URI.create(path.trim())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Una peticion rechazada no llega a ningun servicio ni a la base")
        void elRechazoNoConsultaNada() throws Exception {
            mockMvc.perform(get("/api/v1/inventory/batches")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/inventory/waste/9/approve")).andExpect(status().isUnauthorized());

            verifyNoInteractions(batchService, wasteService, bulkFixService, uploadService,
                    rollbackService, fefoSaleService, conciliationService, productCatalogRepository,
                    exportService, storeRepository);
        }
    }

    // ---------------------------------------------------------------------
    // Lo que debe seguir siendo publico
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Endpoints publicos")
    class Publicos {

        /** Si el keep-alive dejara de ser publico, Render apagaria el servicio. */
        @Test
        @DisplayName("El ping responde sin token, por GET y por HEAD")
        void pingEsPublico() throws Exception {
            mockMvc.perform(get("/api/v1/ping"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("pong"));

            mockMvc.perform(head("/api/v1/ping")).andExpect(status().isOk());
        }

        /** Puerta de entrada del evaluador: es la llamada que le da el token. */
        @Test
        @DisplayName("El reset de la demo responde sin token")
        void resetEsPublico() throws Exception {
            when(demoRepository.loadDashboard(anyLong())).thenReturn(dashboard());
            when(authenticationService.openDemoSession(anyString(), anyLong())).thenReturn(session());

            mockMvc.perform(post("/api/v1/demo/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(999));
        }

        /**
         * Quien da de alta su empresa no tiene token todavia, y esta llamada es
         * la que se lo da. Crea lo suyo y nada mas: no recibe ningun
         * identificador de empresa ni de tienda que pudiera apuntar a otra.
         */
        @Test
        @DisplayName("El alta de empresa responde sin token y devuelve 201")
        void registroEsPublico() throws Exception {
            when(onboardingService.register(any())).thenReturn(session());

            mockMvc.perform(json(post("/api/v1/auth/register"), new RegisterRequestDto(
                            "Minimarket El Sol S.A.C.", "20123456789", "Tienda Centro", "Av. Siempre Viva 123",
                            "Ana Quispe", "ana@elsol.pe", "clave-larga-2026")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.token").isNotEmpty());

            verify(registrationThrottle).checkAllowed(anyString());
            verify(registrationThrottle).recordSuccess(anyString());
        }

        /** Un alta mal formada no debe gastar el cupo de quien se equivoca. */
        @Test
        @DisplayName("Un RUC invalido se rechaza sin tocar el servicio ni el cupo")
        void registroInvalidoNoGastaCupo() throws Exception {
            mockMvc.perform(json(post("/api/v1/auth/register"), new RegisterRequestDto(
                            "Minimarket El Sol S.A.C.", "123", "Tienda Centro", null,
                            "Ana Quispe", "ana@elsol.pe", "clave-larga-2026")))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(onboardingService);
            verify(registrationThrottle, org.mockito.Mockito.never()).recordSuccess(anyString());
        }

        /**
         * El contrato de la API se lee sin token: describe que endpoints hay,
         * no devuelve datos de ninguna tienda.
         *
         * <p>Lo que se comprueba es que la cadena de filtros <b>no responde
         * 401</b>. Aqui no hay springdoc -este es un corte de web, no la
         * aplicacion entera-, asi que la ruta no esta mapeada y el 404 es la
         * prueba de que la peticion paso la seguridad y murio por falta de
         * controlador, no por falta de credenciales.
         */
        @Test
        @DisplayName("El contrato de la API no exige token")
        void elContratoDeLaApiEsPublico() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("El login es publico, pero sigue siendo el servicio quien decide")
        void loginEsPublico() throws Exception {
            when(authenticationService.login(anyString(), anyString())).thenReturn(session());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"demo@kardo.pe\",\"password\":\"kardo-demo-2026\"}"))
                    .andExpect(status().isOk());

            verify(authenticationService).login("demo@kardo.pe", "kardo-demo-2026");
        }
    }

    // ---------------------------------------------------------------------
    // Tokens que no valen
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Tokens invalidos")
    class TokensInvalidos {

        private static final SecretKey OTRA_CLAVE =
                Keys.hmacShaKeyFor("una-clave-distinta-igual-de-larga-que-la-real".getBytes(StandardCharsets.UTF_8));

        private static final SecretKey CLAVE_REAL =
                Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        @Test
        @DisplayName("Un token firmado con otra clave no entra")
        void firmaAjena() throws Exception {
            String forjado = Jwts.builder()
                    .issuer(ISSUER)
                    .subject(MANAGER)
                    .claim(JwtService.CLAIM_ROLES, List.of("ROLE_MANAGER"))
                    .claim(JwtService.CLAIM_STORE_ID, TOKEN_STORE)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(OTRA_CLAVE)
                    .compact();

            expectUnauthorized(forjado);
        }

        @Test
        @DisplayName("Un token expirado no entra, aunque la firma sea buena")
        void expirado() throws Exception {
            Instant ayer = Instant.now().minusSeconds(86_400);
            String caducado = Jwts.builder()
                    .issuer(ISSUER)
                    .subject(MANAGER)
                    .claim(JwtService.CLAIM_ROLES, List.of("ROLE_MANAGER"))
                    .claim(JwtService.CLAIM_STORE_ID, TOKEN_STORE)
                    .issuedAt(Date.from(ayer))
                    .expiration(Date.from(ayer.plusSeconds(3600)))
                    .signWith(CLAVE_REAL)
                    .compact();

            expectUnauthorized(caducado);
        }

        /**
         * Un token sin tienda no dice sobre que puede operar su portador. Se
         * rechaza en el filtro en lugar de dejar que un endpoint elija por su
         * cuenta, que es como se cuelan las escrituras en la tienda equivocada.
         */
        @Test
        @DisplayName("Un token sin la tienda no entra")
        void sinTienda() throws Exception {
            String sinStore = Jwts.builder()
                    .issuer(ISSUER)
                    .subject(MANAGER)
                    .claim(JwtService.CLAIM_ROLES, List.of("ROLE_MANAGER"))
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(CLAVE_REAL)
                    .compact();

            expectUnauthorized(sinStore);
        }

        @Test
        @DisplayName("Un token de otro emisor no entra")
        void otroEmisor() throws Exception {
            String ajeno = Jwts.builder()
                    .issuer("otro-sistema")
                    .subject(MANAGER)
                    .claim(JwtService.CLAIM_ROLES, List.of("ROLE_MANAGER"))
                    .claim(JwtService.CLAIM_STORE_ID, TOKEN_STORE)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(CLAVE_REAL)
                    .compact();

            expectUnauthorized(ajeno);
        }

        @Test
        @DisplayName("Una cabecera con basura no entra")
        void basura() throws Exception {
            expectUnauthorized("no-es-un-jwt");
        }

        /**
         * La unica credencial aceptada es el token. Spring Boot siembra un
         * usuario 'user' en memoria con contraseña aleatoria cuando no encuentra
         * un UserDetailsService, de modo que esto fija que ninguna credencial
         * basica -ni esa ni otra- sea una via de entrada.
         */
        @Test
        @DisplayName("Una credencial basica no es una via de entrada")
        void autenticacionBasica() throws Exception {
            String basic = java.util.Base64.getEncoder()
                    .encodeToString("user:cualquiera".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(get("/api/v1/inventory/batches")
                            .header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(batchService);
        }

        private void expectUnauthorized(String token) throws Exception {
            mockMvc.perform(get("/api/v1/inventory/batches").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(batchService);
        }
    }

    // ---------------------------------------------------------------------
    // Roles
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Separacion de roles")
    class Roles {

        /**
         * Resolver una merma consolida una perdida contable y revertir una carga
         * borra mercaderia: ambas son del gerente. Lo que se prueba aqui es que
         * el operario no solo recibe un 403, sino que el servicio no se toca.
         */
        @ParameterizedTest(name = "el operario no puede POST {0}")
        @CsvSource({
                "/api/v1/inventory/waste/9/approve",
                "/api/v1/inventory/waste/9/reject",
                "/api/v1/inventory/ingest/7/rollback",
                // Confirmar que el proveedor abono es anotar dinero recuperado;
                // lo firma quien responde de el.
                "/api/v1/inventory/returns/9/credit",
                "/api/v1/inventory/returns/9/refuse"
        })
        void operarioNoResuelve(String path) throws Exception {
            mockMvc.perform(post(path.trim()).header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(wasteService, rollbackService, returnService);
        }

        /**
         * Declararlo si, en cambio: el operario ve la fecha de cerca y sabe lo
         * que le cabe al camion de mañana, igual que declara una merma.
         */
        @Test
        @DisplayName("El operario si puede declarar un canje, con su correo y su tienda")
        void elOperarioDeclaraCanjes() throws Exception {
            mockMvc.perform(json(post("/api/v1/inventory/returns"), new ReturnRequestDto(7L, 5))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isCreated());

            verify(returnService).declare(TOKEN_STORE, 7L, 5, OPERATOR);
        }

        /**
         * Abrir tiendas y dar de alta a gente es decidir quien entra al sistema:
         * si lo pudiera hacer un operario, el circuito de mermas se podria
         * burlar creandose un segundo usuario para aprobarse a uno mismo.
         */
        @Test
        @DisplayName("Abrir tienda y dar de alta usuarios es solo del gerente")
        void estructuraSoloGerente() throws Exception {
            mockMvc.perform(json(post("/api/v1/stores"), new NewStoreRequestDto("Sucursal Norte", null))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isForbidden());

            mockMvc.perform(json(post("/api/v1/users"), new NewUserRequestDto(
                            "Nuevo Operario", "nuevo@tienda.pe", "clave-larga-2026", "OPERATOR", 5L))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(onboardingService);
        }

        /** Ver donde trabaja uno no es un privilegio: la lista la ven los dos. */
        @Test
        @DisplayName("La lista de tiendas la ve tambien el operario")
        void listaDeTiendasParaTodos() throws Exception {
            when(storeRepository.findCompanyIdByStore(TOKEN_STORE)).thenReturn(42L);
            when(onboardingService.stores(42L)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/stores")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(onboardingService).stores(42L);
        }

        /**
         * El diagnostico describe la infraestructura -pool, latencias de los
         * proveedores, memoria del contenedor-, que no le sirve al operario pero
         * si a quien quiera sondear el despliegue.
         */
        @Test
        @DisplayName("El diagnostico de rendimiento es solo del gerente")
        void diagnosticoSoloGerente() throws Exception {
            when(diagnosticsService.report()).thenReturn(new DiagnosticsReportDto(
                    "Nada destaca", List.of(), new DiagnosticsReportDto.PoolDto(1, 4, 5, 0, 2, 3), 120, 512, 0));

            mockMvc.perform(get("/api/v1/ops/diagnostics")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(diagnosticsService);

            mockMvc.perform(get("/api/v1/ops/diagnostics")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verdict").value("Nada destaca"));
        }

        @Test
        @DisplayName("El gerente si resuelve mermas")
        void gerenteResuelve() throws Exception {
            when(wasteService.list(anyLong(), anyString(), anyInt())).thenReturn(List.of());

            mockMvc.perform(post("/api/v1/inventory/waste/9/approve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isOk());

            // El autor de la decision sale del token, no del cuerpo de la peticion
            verify(wasteService).approve(TOKEN_STORE, 9L, MANAGER);
        }

        @Test
        @DisplayName("El gerente si revierte cargas")
        void gerenteRevierte() throws Exception {
            when(rollbackService.rollback(anyLong(), anyLong(), anyString()))
                    .thenReturn(new RollbackResponseDto(7L, "inventario.xlsx", 3, "Carga revertida."));

            mockMvc.perform(post("/api/v1/inventory/ingest/7/rollback")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isOk());

            verify(rollbackService).rollback(TOKEN_STORE, 7L, MANAGER);
        }

        @Test
        @DisplayName("El operario si consulta y declara, que es su trabajo")
        void operarioTrabaja() throws Exception {
            when(batchService.search(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
            when(wasteService.list(anyLong(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/inventory/batches")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            mockMvc.perform(json(post("/api/v1/inventory/waste"),
                            new WasteReportRequestDto(42L, 5, "EXPIRATION"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(wasteService).report(TOKEN_STORE, 42L, 5, "EXPIRATION", OPERATOR);
        }

        @Test
        @DisplayName("Un token sin roles no pasa de la puerta")
        void tokenSinRoles() throws Exception {
            String pelado = jwtService.issueToken("intruso@kardo.pe", TOKEN_STORE, List.of());

            mockMvc.perform(get("/api/v1/inventory/batches").header(HttpHeaders.AUTHORIZATION, "Bearer " + pelado))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(batchService);
        }
    }

    // ---------------------------------------------------------------------
    // Aislamiento entre tiendas
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Aislamiento por tienda")
    class Aislamiento {

        /**
         * Es el ataque que importa en un SaaS multi-inquilino: token legitimo de
         * una tienda, cuerpo apuntando a otra. Debe cortarse antes de escribir.
         */
        @Test
        @DisplayName("Pedir otra tienda en el cuerpo se rechaza sin escribir nada")
        void cuerpoConOtraTienda() throws Exception {
            mockMvc.perform(json(post("/api/v1/inventory/ingest/bulk-fix"),
                            new BulkFixRequestDto(OTHER_STORE, List.of(fila())))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(bulkFixService);
        }

        @Test
        @DisplayName("Coincidiendo con la del token, la correccion se aplica a la del token")
        void cuerpoCoincidente() throws Exception {
            when(bulkFixService.apply(anyLong(), any(), anyString(), any()))
                    .thenReturn(new IngestSummaryResponseDto(1, 1, 0, List.of(), 55L));

            mockMvc.perform(json(post("/api/v1/inventory/ingest/bulk-fix"),
                            new BulkFixRequestDto(TOKEN_STORE, List.of(fila())))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(bulkFixService).apply(eq(TOKEN_STORE), any(), eq(OPERATOR), any());
        }

        /**
         * Los endpoints que no reciben tienda tampoco deben poder influirse por
         * un parametro colado en la URL.
         */
        @Test
        @DisplayName("Un storeId en la URL no cambia la tienda consultada")
        void parametroEnLaUrlSeIgnora() throws Exception {
            when(batchService.search(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/inventory/batches")
                            .param("storeId", String.valueOf(OTHER_STORE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(batchService).search(eq(TOKEN_STORE), any(), any(), anyInt());
        }

        /**
         * Cambiar de tienda es la unica llamada que acepta un identificador de
         * tienda del cliente, asi que es la que hay que vigilar: lo que pide no
         * es escribir en esa tienda, sino un token para ella. El servicio
         * decide, y lo unico que el controlador puede hacer mal es pasarle el
         * correo de otro.
         */
        @Test
        @DisplayName("El cambio de tienda se pide siempre con el correo del token")
        void elCambioDeTiendaUsaElCorreoDelToken() throws Exception {
            when(authenticationService.switchStore(anyString(), anyLong())).thenReturn(session());

            mockMvc.perform(json(post("/api/v1/auth/store"), new SwitchStoreRequestDto(OTHER_STORE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            // El correo sale del token; el cuerpo solo dice a que tienda ir, y
            // quien comprueba que sea suya es el servicio.
            verify(authenticationService).switchStore(OPERATOR, OTHER_STORE);
        }

        /**
         * El tablero vive bajo {@code /demo} por historia, y durante mucho
         * tiempo consulto la tienda 999 a secas: mientras los unicos usuarios
         * eran los de la demo, nadie lo noto. Desde que una empresa puede darse
         * de alta por su cuenta, eso le enseñaria el inventario de la demo como
         * si fuera el suyo. Esta prueba es la que no deja que vuelva.
         */
        @Test
        @DisplayName("El tablero y la cola FEFO son de la tienda del token, no de la demo")
        void elTableroNoSeQuedaEnLaTiendaDemo() throws Exception {
            when(demoRepository.loadDashboard(anyLong())).thenReturn(dashboard());
            when(demoRepository.loadFefoQueue(anyLong(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/demo/dashboard")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/demo/fefo-queue")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(demoRepository).loadDashboard(TOKEN_STORE);
            verify(demoRepository).loadFefoQueue(eq(TOKEN_STORE), anyInt());
        }

        /**
         * El analisis agrega dinero de toda la tienda, de modo que una fuga aqui
         * seria peor que en una consulta de detalle: una sola cifra ya delata
         * cuanto factura el vecino.
         */
        @Test
        @DisplayName("El analisis solo agrega la tienda del token")
        void analisisSeCineALaTiendaDelToken() throws Exception {
            when(insightsService.coverage(anyLong())).thenReturn(List.of());
            when(insightsService.bySupplier(anyLong())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/inventory/insights")
                            .param("storeId", String.valueOf(OTHER_STORE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(insightsService).coverage(TOKEN_STORE);
            verify(insightsService).bySupplier(TOKEN_STORE);
        }

        @Test
        @DisplayName("La venta se descuenta de la tienda del token y la firma el portador")
        void laVentaUsaElToken() throws Exception {
            when(fefoSaleService.sell(anyLong(), anyString(), anyInt(), anyString()))
                    .thenReturn(new SaleResponseDto("SKU-1", "Leche", 2,
                            new BigDecimal("10.00"), new BigDecimal("6.00"), new BigDecimal("4.00"), List.of()));

            mockMvc.perform(json(post("/api/v1/inventory/sales"), new SaleRequestDto("SKU-1", 2))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(fefoSaleService).sell(TOKEN_STORE, "SKU-1", 2, OPERATOR);
        }

        /** El cupo es por tienda: pedir el de otra seria ver cuanto consume. */
        @Test
        @DisplayName("El cupo de vision que se consulta es el de la tienda del token")
        void cupoAcotado() throws Exception {
            when(visionQuotaService.status(anyLong()))
                    .thenReturn(new VisionQuotaDto(LocalDate.now(), 3, 40, 37, 3, 200));

            mockMvc.perform(get("/api/v1/receipts/quota")
                            .param("storeId", String.valueOf(OTHER_STORE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingToday").value(37));

            verify(visionQuotaService).status(TOKEN_STORE);
        }

        @Test
        @DisplayName("El catalogo solo muestra la tienda del token")
        void catalogoAcotado() throws Exception {
            when(productCatalogRepository.listByStore(anyLong())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(productCatalogRepository).listByStore(TOKEN_STORE);
        }

        /**
         * El peldaño de la escalera lo calcula el servidor: si el cliente
         * pudiera declararlo, bastaria un 100% para silenciar un lote hasta que
         * venza. Aqui se fija que el endpoint solo acepta la decision.
         */
        @Test
        @DisplayName("La decision de rebaja se firma con la tienda y el correo del token")
        void laRebajaUsaElToken() throws Exception {
            when(alertService.decide(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(new ExpiryAlertBoardDto(BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of()));

            mockMvc.perform(json(post("/api/v1/inventory/alerts/31/decision"),
                            new MarkdownDecisionRequestDto("APPLIED"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(alertService).decide(TOKEN_STORE, 31L, "APPLIED", OPERATOR);
        }

        @Test
        @DisplayName("Una decision que no existe se rechaza antes de llegar al servicio")
        void decisionInvalida() throws Exception {
            mockMvc.perform(json(post("/api/v1/inventory/alerts/31/decision"),
                            new MarkdownDecisionRequestDto("REGALAR"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(alertService);
        }

        @Test
        @DisplayName("El panel de alertas solo mira la tienda del token")
        void panelAcotado() throws Exception {
            when(alertService.board(anyLong()))
                    .thenReturn(new ExpiryAlertBoardDto(BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of()));

            mockMvc.perform(get("/api/v1/inventory/alerts")
                            .param("storeId", String.valueOf(OTHER_STORE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                    .andExpect(status().isOk());

            verify(alertService).board(TOKEN_STORE);
        }

        @Test
        @DisplayName("La sesion que devuelve /me es la del token, sin tocar la base")
        void meSaleDelToken() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(MANAGER))
                    .andExpect(jsonPath("$.storeId").value(TOKEN_STORE));

            verifyNoInteractions(authenticationService);
        }

        /**
         * El agujero que cerro {@code sql/15}.
         *
         * <p>Resolver una merma o un canje se hacia por su clave primaria, que
         * es un SERIAL global: la tienda del token no entraba en la sentencia.
         * Un gerente de la empresa B podia aprobar la merma 41 de la empresa A
         * -o dar por recuperado el costo de su canje- con solo cambiar el numero
         * de la URL, porque la autorizacion por rol pasaba y la de tienda no
         * existia. Estas cuatro comprobaciones son las que impiden que vuelva:
         * si alguien quita el {@code storeId} de la llamada, fallan.
         */
        @Test
        @DisplayName("Resolver una merma o un canje lleva siempre la tienda del token")
        void laResolucionLlevaLaTiendaDelToken() throws Exception {
            when(wasteService.list(anyLong(), anyString(), anyInt())).thenReturn(List.of());

            mockMvc.perform(post("/api/v1/inventory/waste/41/approve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/inventory/waste/41/reject")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/inventory/returns/41/credit")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/v1/inventory/returns/41/refuse")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                    .andExpect(status().isNoContent());

            verify(wasteService).approve(TOKEN_STORE, 41L, MANAGER);
            verify(wasteService).reject(TOKEN_STORE, 41L, MANAGER);
            verify(returnService).credit(TOKEN_STORE, 41L, MANAGER);
            verify(returnService).refuse(TOKEN_STORE, 41L, MANAGER);
        }
    }

    // ---------------------------------------------------------------------
    // Cabeceras de respuesta
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Cabeceras de seguridad")
    class Cabeceras {

        /**
         * La primera defensa del XSS sigue siendo que el SPA pinte con
         * textContent. Esto es lo que queda si alguna vez se cuela una, y la
         * parte que de verdad muerde es {@code connect-src 'self'}: el script
         * inyectado podria ejecutarse, pero no tiene a donde mandar el
         * inventario ni el token.
         */
        @Test
        @DisplayName("La respuesta lleva CSP, y connect-src no deja salir datos")
        void politicaDeContenido() throws Exception {
            mockMvc.perform(get("/api/v1/ping"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString("connect-src 'self'")))
                    // Un 'unsafe-inline' en script-src anula la politica entera:
                    // deja correr cualquier script inyectado. El bloque legitimo
                    // del HTML va autorizado por su hash, no por una barra libre.
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString(
                                    "script-src 'self' '" + com.kardo.config.SecurityConfig
                                            .TAILWIND_CONFIG_HASH + "'")))
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-inline'"))))
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString("object-src 'none'")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
        }

        /**
         * Kardo no emite cookies: el token va en {@code Authorization} y lo
         * adjunta el cliente. {@code allowCredentials} no habilitaba nada que
         * el SPA necesite y dejaba armado el peor error de CORS que existe: el
         * dia que alguien ponga un origen de mas en la lista, cualquier pagina
         * podria pedirle al navegador peticiones autenticadas y leer la
         * respuesta.
         */
        @Test
        @DisplayName("El preflight no autoriza credenciales")
        void preflightSinCredenciales() throws Exception {
            mockMvc.perform(options("/api/v1/inventory/batches")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                            "http://localhost:5173"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        /** Un origen que no esta en la lista no recibe la cabecera que lo autoriza. */
        @Test
        @DisplayName("Un origen ajeno no obtiene permiso")
        void origenAjeno() throws Exception {
            mockMvc.perform(options("/api/v1/inventory/batches")
                            .header(HttpHeaders.ORIGIN, "https://sitio-del-atacante.com")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    // ---------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private static SpreadsheetRowDto fila() {
        return new SpreadsheetRowDto(1, "SKU-SEG-1", null, "Producto corregido", "VARIOS", "UNIDAD",
                new BigDecimal("1.50"), new BigDecimal("2.50"), "L-SEG",
                null, LocalDate.now().plusMonths(6), 10);
    }

    private static DemoDashboardDto dashboard() {
        return new DemoDashboardDto(999L, 3, 1, 1, 0,
                new BigDecimal("143.50"), new BigDecimal("980.00"), 0);
    }

    private static SessionDto session() {
        return new SessionDto("token-de-prueba", 3600L, "demo@kardo.pe", "Gerente Demo",
                "MANAGER", 999L, "Tienda Demo Miraflores");
    }
}
