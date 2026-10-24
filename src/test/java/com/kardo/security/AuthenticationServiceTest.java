package com.kardo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kardo.config.SecurityProperties;
import com.kardo.dto.SessionDto;
import com.kardo.exception.InvalidCredentialsException;
import com.kardo.repository.StoreRepository;
import com.kardo.repository.UserRepository;
import com.kardo.repository.UserRepository.UserAccount;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticationServiceTest {

    private static final String SECRET = "kardo-secreto-de-pruebas-con-mas-de-32-bytes";
    private static final String PASSWORD = "kardo-demo-2026";

    @Mock
    private UserRepository userRepository;
    @Mock
    private StoreRepository storeRepository;

    private AuthenticationService service;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties(
                new SecurityProperties.Jwt(SECRET, "kardo", Duration.ofHours(8)),
                new SecurityProperties.Cors(List.of("http://localhost:5173")));
        jwtService = new JwtService(properties);

        when(storeRepository.findNameById(anyLong())).thenReturn("Tienda Demo Miraflores");

        service = new AuthenticationService(
                userRepository, storeRepository, new BCryptPasswordEncoder(), jwtService, properties);
    }

    @Test
    @DisplayName("La sesion lleva la tienda del usuario dentro del token firmado")
    void loginCorrecto() {
        when(userRepository.findActiveByEmail("demo@kardo.pe")).thenReturn(Optional.of(account(999L, "MANAGER")));

        SessionDto session = service.login("demo@kardo.pe", PASSWORD);

        assertThat(session.email()).isEqualTo("demo@kardo.pe");
        assertThat(session.role()).isEqualTo("MANAGER");
        assertThat(session.storeId()).isEqualTo(999L);
        assertThat(session.storeName()).isEqualTo("Tienda Demo Miraflores");

        // La tienda no es solo un dato de pantalla: viaja firmada
        var claims = jwtService.parseToken(session.token());
        assertThat(claims.get(JwtService.CLAIM_STORE_ID, Number.class).longValue()).isEqualTo(999L);
        assertThat(claims.get(JwtService.CLAIM_ROLES, List.class)).containsExactly("ROLE_MANAGER");

        verify(userRepository).touchLastLogin(42L);
    }

    @Test
    @DisplayName("Contraseña incorrecta y correo inexistente dan el mismo error")
    void credencialesInvalidas() {
        when(userRepository.findActiveByEmail("demo@kardo.pe")).thenReturn(Optional.of(account(999L, "MANAGER")));
        when(userRepository.findActiveByEmail("nadie@kardo.pe")).thenReturn(Optional.empty());

        String conPasswordMala = catchMessage(() -> service.login("demo@kardo.pe", "otra-cosa"));
        String conCorreoInexistente = catchMessage(() -> service.login("nadie@kardo.pe", PASSWORD));

        // Mensajes identicos: distinguirlos permitiria enumerar correos dados de alta
        assertThat(conPasswordMala).isEqualTo(conCorreoInexistente);
        verify(userRepository, never()).touchLastLogin(anyLong());
    }

    @Test
    @DisplayName("La sesion demo sin contraseña solo sirve para la tienda de demostracion")
    void sesionDemoAcotada() {
        when(userRepository.findActiveByEmail("demo@kardo.pe")).thenReturn(Optional.of(account(999L, "MANAGER")));

        assertThat(service.openDemoSession("demo@kardo.pe", 999L).storeId()).isEqualTo(999L);

        // El mismo usuario, si perteneciera a una tienda real, no abriria sesion
        when(userRepository.findActiveByEmail("gerente@real.pe")).thenReturn(Optional.of(account(7L, "MANAGER")));
        assertThatThrownBy(() -> service.openDemoSession("gerente@real.pe", 999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pertenece a la tienda de demostracion");
    }

    @Test
    @DisplayName("Si falta el usuario demo, el error dice que script ejecutar")
    void usuarioDemoAusente() {
        when(userRepository.findActiveByEmail("demo@kardo.pe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openDemoSession("demo@kardo.pe", 999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sql/03_users_and_auth.sql");
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Se esperaba InvalidCredentialsException");
        } catch (InvalidCredentialsException ex) {
            return ex.getMessage();
        }
    }

    private static UserAccount account(long storeId, String role) {
        return new UserAccount(42L, 999L, storeId, "demo@kardo.pe", "Gerente Demo",
                new BCryptPasswordEncoder().encode(PASSWORD), role);
    }
}
