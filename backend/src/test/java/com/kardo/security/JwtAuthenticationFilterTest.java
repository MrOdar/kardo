package com.kardo.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.config.SecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "kardo-secreto-de-pruebas-con-mas-de-32-bytes";

    private final SecurityProperties properties = new SecurityProperties(
            new SecurityProperties.Jwt(SECRET, "kardo", Duration.ofHours(8)),
            new SecurityProperties.Cors(List.of("http://localhost:5173")));

    private final JwtService jwtService = new JwtService(properties);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Un token valido deja en el contexto la tienda del usuario")
    void tokenValido() throws Exception {
        String token = jwtService.issueToken("operario@kardo.pe", 999L, List.of("ROLE_OPERATOR"));

        doFilter(token);

        KardoPrincipal principal = CurrentUser.require();
        assertThat(principal.email()).isEqualTo("operario@kardo.pe");
        assertThat(principal.storeId()).isEqualTo(999L);
        assertThat(principal.isManager()).isFalse();
    }

    @Test
    @DisplayName("Un token sin tienda no autentica: nadie opera sin tienda asignada")
    void tokenSinTienda() throws Exception {
        // Token bien firmado pero sin el claim storeId
        String sinTienda = Jwts.builder()
                .issuer("kardo")
                .subject("intruso@kardo.pe")
                .claim(JwtService.CLAIM_ROLES, List.of("ROLE_MANAGER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        doFilter(sinTienda);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Un token firmado con otra clave no autentica")
    void firmaAjena() throws Exception {
        String ajeno = Jwts.builder()
                .issuer("kardo")
                .subject("intruso@kardo.pe")
                .claim(JwtService.CLAIM_STORE_ID, 1234L)
                .claim(JwtService.CLAIM_ROLES, List.of("ROLE_MANAGER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("otro-secreto-igualmente-largo-de-32-bytes"
                        .getBytes(StandardCharsets.UTF_8)))
                .compact();

        doFilter(ajeno);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Sin cabecera Authorization la peticion sigue sin autenticar")
    void sinCabecera() throws Exception {
        doFilter(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void doFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inventory/ingest/excel");
        if (token != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }
}
