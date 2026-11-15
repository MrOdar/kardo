package com.kardo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kardo.config.ClockConfig;
import com.kardo.config.SecurityConfig;
import com.kardo.config.SecurityProperties;
import com.kardo.config.ThrottleProperties;
import com.kardo.dto.DemoDashboardDto;
import com.kardo.dto.SessionDto;
import com.kardo.exception.InvalidCredentialsException;
import com.kardo.repository.DemoRepository;
import com.kardo.security.AuthenticationService;
import com.kardo.security.JwtAuthenticationFilter;
import com.kardo.security.JwtService;
import com.kardo.throttle.DemoResetThrottle;
import com.kardo.throttle.LoginThrottle;
import com.kardo.throttle.RegistrationThrottle;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Los frenos vistos desde fuera, a traves de la cadena real.
 *
 * <p>Que el freno funcione aislado no sirve de nada si nadie lo llama, y ya ha
 * pasado en este proyecto: la primera version de la instrumentacion de SQL
 * estaba bien escrita y no se ejecutaba. Esto comprueba lo unico que importa,
 * que es que una peticion de mas reciba un 429.
 *
 * <p>Los limites se bajan a tres para no tener que escribir veinte peticiones
 * por prueba.
 */
@WebMvcTest(controllers = {AuthController.class, DemoController.class})
@Import({SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class,
        LoginThrottle.class, DemoResetThrottle.class, RegistrationThrottle.class, ClockConfig.class})
@EnableConfigurationProperties({SecurityProperties.class, ThrottleProperties.class})
@TestPropertySource(properties = {
        "kardo.security.jwt.secret=clave-de-pruebas-kardo-con-mas-de-32-bytes",
        "kardo.security.jwt.issuer=kardo-test",
        "kardo.security.jwt.expiration=PT1H",
        "kardo.security.cors.allowed-origins=http://localhost:5173",
        "kardo.throttle.login.max-per-account=3",
        "kardo.throttle.login.max-per-ip=1000",
        "kardo.throttle.login.window=PT15M",
        "kardo.throttle.demo.min-interval=PT30S",
        "kardo.throttle.demo.max-per-ip=100",
        "kardo.throttle.demo.window=PT10M",
        "kardo.throttle.registration.max-per-ip=2",
        "kardo.throttle.registration.window=PT1H"
})
class PublicEndpointThrottleTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private DemoRepository demoRepository;

    @MockitoBean
    private com.kardo.onboarding.OnboardingService onboardingService;

    /**
     * Cada prueba usa su propio correo: el contador vive en el contexto, que es
     * uno solo para toda la clase.
     */
    @Test
    @DisplayName("Al cuarto intento fallido, el login deja de atender")
    void elLoginSeFrena() throws Exception {
        when(authenticationService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException());

        for (int i = 0; i < 3; i++) {
            intentar("frenado@kardo.pe", "mala").andExpect(status().isUnauthorized());
        }

        intentar("frenado@kardo.pe", "mala")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Demasiados intentos")));
    }

    /**
     * El BCrypt del login cuesta cien milisegundos a proposito. Si cada intento
     * frenado siguiera gastandolos, el propio freno seria la forma mas comoda de
     * tumbar el servicio.
     */
    @Test
    @DisplayName("Una peticion frenada no llega a comprobar la contraseña")
    void elFrenoNoGastaBcrypt() throws Exception {
        when(authenticationService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException());

        for (int i = 0; i < 3; i++) {
            intentar("sinbcrypt@kardo.pe", "mala");
        }
        org.mockito.Mockito.clearInvocations(authenticationService);

        intentar("sinbcrypt@kardo.pe", "mala").andExpect(status().isTooManyRequests());

        verify(authenticationService, never()).login(anyString(), anyString());
    }

    @Test
    @DisplayName("Acertar borra los fallos: el usuario torpe no queda castigado")
    void elAciertoLimpia() throws Exception {
        when(authenticationService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException())
                .thenThrow(new InvalidCredentialsException())
                .thenReturn(session())
                .thenThrow(new InvalidCredentialsException())
                .thenThrow(new InvalidCredentialsException());

        intentar("torpe@kardo.pe", "mala").andExpect(status().isUnauthorized());
        intentar("torpe@kardo.pe", "mala").andExpect(status().isUnauthorized());
        intentar("torpe@kardo.pe", "buena").andExpect(status().isOk());

        // Dos fallos mas: sin el borrado, estos dos sumarian cuatro y frenarian
        intentar("torpe@kardo.pe", "mala").andExpect(status().isUnauthorized());
        intentar("torpe@kardo.pe", "mala").andExpect(status().isUnauthorized());
    }

    /**
     * El tercer endpoint publico que escribe, y el unico que deja filas
     * permanentes: una empresa, una tienda y un usuario que nadie borra.
     */
    @Test
    @DisplayName("Al tercer alta seguida, el registro deja de atender")
    void elRegistroSeFrena() throws Exception {
        when(onboardingService.register(any())).thenReturn(session());

        registrar("una@empresa.pe", "203.0.113.10").andExpect(status().isCreated());
        registrar("otra@empresa.pe", "203.0.113.10").andExpect(status().isCreated());

        registrar("tercera@empresa.pe", "203.0.113.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        // Otra IP no arrastra el castigo: el freno es por origen, no global.
        registrar("vecina@empresa.pe", "203.0.113.99").andExpect(status().isCreated());
    }

    /**
     * Equivocarse al teclear el RUC no puede gastar cupo: quien lo corrige y
     * reintenta se encontraria bloqueado sin haber creado nada.
     */
    @Test
    @DisplayName("Un alta rechazada no gasta cupo")
    void elAltaInvalidaNoGastaCupo() throws Exception {
        when(onboardingService.register(any())).thenReturn(session());

        // RUC de tres digitos: lo corta la validacion antes del servicio.
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(desde("203.0.113.20"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Mal S.A.C.","taxId":"123","storeName":"T",
                                 "fullName":"Ana","email":"ana@mal.pe","password":"clave-larga-2026"}
                                """))
                .andExpect(status().isBadRequest());

        // El cupo es de dos: si el fallido contara, el segundo alta buena de
        // esta misma IP ya vendria frenada.
        registrar("buena@empresa.pe", "203.0.113.20").andExpect(status().isCreated());
        registrar("tambien@empresa.pe", "203.0.113.20").andExpect(status().isCreated());
    }

    /** Cada prueba usa su IP: el contador del registro vive en el contexto. */
    private static RequestPostProcessor desde(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions registrar(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                .with(desde(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"companyName":"Empresa S.A.C.","taxId":"20123456789","storeName":"Tienda",
                         "fullName":"Ana Quispe","email":"%s","password":"clave-larga-2026"}
                        """.formatted(email)));
    }

    @Test
    @DisplayName("El segundo reinicio seguido de la demo no se atiende")
    void elResetSeFrena() throws Exception {
        when(demoRepository.loadDashboard(anyLong())).thenReturn(dashboard());
        when(authenticationService.openDemoSession(anyString(), anyLong())).thenReturn(session());

        mockMvc.perform(post("/api/v1/demo/reset")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/demo/reset"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        // Y lo caro -borrar y resembrar la tienda- ocurrio una sola vez
        verify(demoRepository).resetDemoEnvironment();
    }

    private org.springframework.test.web.servlet.ResultActions intentar(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
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
