package com.kardo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kardo.config.SecurityProperties;
import com.kardo.dto.SessionDto;
import com.kardo.exception.ForbiddenStoreException;
import com.kardo.exception.InvalidCredentialsException;
import com.kardo.repository.StoreRepository;
import com.kardo.repository.UserRepository;
import com.kardo.repository.UserRepository.StoreMembership;
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
        trabajaEn("demo@kardo.pe", 999L, "MANAGER");

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
        trabajaEn("demo@kardo.pe", 999L, "MANAGER");
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
        trabajaEn("demo@kardo.pe", 999L, "MANAGER");

        assertThat(service.openDemoSession("demo@kardo.pe", 999L).storeId()).isEqualTo(999L);

        // El mismo usuario, si perteneciera a una tienda real, no abriria sesion
        trabajaEn("gerente@real.pe", 7L, "MANAGER");
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

    /**
     * El cambio de tienda emite un token nuevo con el rol de <b>esa</b> tienda.
     * Es la propiedad que paga el modelo de sql/13: la misma persona manda en
     * una tienda y no en la otra, y el token lo dice.
     */
    @Test
    @DisplayName("Cambiar de tienda trae el rol que se tiene en la tienda nueva")
    void cambioDeTiendaTraeElRolDeAlli() {
        trabajaEnVarias();

        SessionDto enLaSegunda = service.switchStore("demo@kardo.pe", 7L);

        assertThat(enLaSegunda.storeId()).isEqualTo(7L);
        assertThat(enLaSegunda.role()).isEqualTo("OPERATOR");
        assertThat(enLaSegunda.storeName()).isEqualTo("Sucursal Norte");

        var claims = jwtService.parseToken(enLaSegunda.token());
        assertThat(claims.get(JwtService.CLAIM_STORE_ID, Number.class).longValue()).isEqualTo(7L);
        assertThat(claims.get(JwtService.CLAIM_ROLES, List.class)).containsExactly("ROLE_OPERATOR");
    }

    /**
     * Sin esto, el cambio de tienda seria una via para emitirse un token de
     * cualquier tienda del sistema conociendo su numero.
     */
    @Test
    @DisplayName("No se puede cambiar a una tienda en la que no se trabaja")
    void noSeCambiaAUnaTiendaAjena() {
        trabajaEnVarias();

        assertThatThrownBy(() -> service.switchStore("demo@kardo.pe", 1234L))
                .isInstanceOf(ForbiddenStoreException.class)
                .hasMessageContaining("sin trabajar en ella");
    }

    @Test
    @DisplayName("Se abre sesion en la tienda de origen cuando se trabaja en varias")
    void elLoginEntraPorLaTiendaDeOrigen() {
        trabajaEnVarias();

        SessionDto session = service.login("demo@kardo.pe", PASSWORD);

        assertThat(session.storeId()).isEqualTo(999L);
        assertThat(session.role()).isEqualTo("MANAGER");
    }

    /**
     * Puede pasar: a alguien se le retira de la ultima tienda y su cuenta sigue
     * activa. Un token sin tienda no podria pedir nada, asi que es mejor no
     * emitirlo que explicar despues por que todo responde 403.
     */
    @Test
    @DisplayName("Quien no trabaja en ninguna tienda no abre sesion")
    void sinTiendasNoHaySesion() {
        when(userRepository.findActiveByEmail("exoperario@kardo.pe")).thenReturn(Optional.of(
                new UserAccount(77L, 999L, 999L, "exoperario@kardo.pe", "Ex Operario",
                        new BCryptPasswordEncoder().encode(PASSWORD), "OPERATOR")));
        when(userRepository.findMemberships(77L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.login("exoperario@kardo.pe", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /** Gerente en su tienda de origen y operario en la segunda. */
    private void trabajaEnVarias() {
        when(userRepository.findActiveByEmail("demo@kardo.pe")).thenReturn(Optional.of(
                new UserAccount(42L, 999L, 999L, "demo@kardo.pe", "Gerente Demo",
                        new BCryptPasswordEncoder().encode(PASSWORD), "MANAGER")));
        when(userRepository.findMemberships(42L)).thenReturn(List.of(
                new StoreMembership(999L, "Tienda Demo Miraflores", "MANAGER"),
                new StoreMembership(7L, "Sucursal Norte", "OPERATOR")));
        when(userRepository.findMembership(42L, 7L)).thenReturn(
                Optional.of(new StoreMembership(7L, "Sucursal Norte", "OPERATOR")));
        when(userRepository.findMembership(42L, 1234L)).thenReturn(Optional.empty());
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Se esperaba InvalidCredentialsException");
        } catch (InvalidCredentialsException ex) {
            return ex.getMessage();
        }
    }

    /**
     * Deja preparado un usuario que existe y trabaja en una tienda.
     *
     * <p>Desde sql/13 son dos lecturas: la cuenta dice quien es y la membresia
     * con que tienda y rol entra. Se preparan juntas y en su propia linea, no
     * dentro de otro {@code when(...)}: Mockito no admite anidar stubs.
     */
    private void trabajaEn(String email, long storeId, String role) {
        when(userRepository.findActiveByEmail(email)).thenReturn(Optional.of(
                new UserAccount(42L, 999L, storeId, email, "Gerente Demo",
                        new BCryptPasswordEncoder().encode(PASSWORD), role)));
        when(userRepository.findMemberships(42L)).thenReturn(
                List.of(new StoreMembership(storeId, "Tienda Demo Miraflores", role)));
    }
}
