package com.kardo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.ForbiddenStoreException;
import com.kardo.security.KardoPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * La regla que impide el salto entre tiendas: la peticion no elige sobre que
 * tienda escribe, lo decide el token.
 */
class StoreScopeTest {

    @BeforeEach
    void authenticateAsStore999() {
        var principal = new KardoPrincipal("operario@kardo.pe", 999L, List.of("ROLE_OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("La tienda siempre sale del token")
    void tiendaDelToken() {
        assertThat(StoreScope.current()).isEqualTo(999L);
    }

    @Test
    @DisplayName("Un cuerpo con la tienda propia se acepta")
    void mismaTienda() {
        assertThat(StoreScope.requireMatching(999L)).isEqualTo(999L);
    }

    @Test
    @DisplayName("Un cuerpo sin tienda usa la del token")
    void sinTiendaEnElCuerpo() {
        assertThat(StoreScope.requireMatching(null)).isEqualTo(999L);
    }

    @Test
    @DisplayName("Apuntar a otra tienda se rechaza en vez de escribir en la propia")
    void tiendaAjena() {
        assertThatThrownBy(() -> StoreScope.requireMatching(1234L))
                .isInstanceOf(ForbiddenStoreException.class)
                .hasMessageContaining("1234")
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("Sin usuario autenticado no hay tienda que resolver")
    void sinUsuario() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(StoreScope::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay un usuario autenticado");
    }
}
