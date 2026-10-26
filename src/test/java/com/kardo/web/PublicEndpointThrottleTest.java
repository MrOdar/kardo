package com.kardo.web;

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
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
        LoginThrottle.class, DemoResetThrottle.class, ClockConfig.class})
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
        "kardo.throttle.demo.window=PT10M"
})
class PublicEndpointThrottleTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private DemoRepository demoRepository;

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

    @Test
    @DisplayName("El segundo reinicio seguido de la demo no se atiende")
    void elResetSeFrena() throws Exception {
        when(demoRepository.loadDashboard()).thenReturn(dashboard());
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
