package com.kardo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "kardo-secreto-de-pruebas-con-mas-de-32-bytes";

    @Test
    @DisplayName("Emite un token legible con roles y tienda")
    void emiteYVerificaToken() {
        JwtService service = serviceWith(SECRET);

        String token = service.issueToken("operador@kardo.pe", 999L, List.of("ROLE_OPERATOR"));
        Claims claims = service.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo("operador@kardo.pe");
        assertThat(claims.getIssuer()).isEqualTo("kardo");
        assertThat(claims.get(JwtService.CLAIM_STORE_ID, Number.class).longValue()).isEqualTo(999L);
        assertThat(claims.get(JwtService.CLAIM_ROLES, List.class)).containsExactly("ROLE_OPERATOR");
    }

    @Test
    @DisplayName("Rechaza un token firmado con otra clave")
    void rechazaFirmaAjena() {
        String tokenAjeno = serviceWith("otro-secreto-igualmente-largo-de-32-bytes")
                .issueToken("intruso", 1L, List.of("ROLE_ADMIN"));

        assertThatThrownBy(() -> serviceWith(SECRET).parseToken(tokenAjeno))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Un JWT_SECRET sin resolver falla nombrando la variable que falta")
    void secretoSinResolver() {
        // Spring deja el literal cuando la variable de entorno no existe; sin esta
        // guarda el arranque muere con un error de bits que no dice que configurar.
        assertThatThrownBy(() -> serviceWith("${JWT_SECRET}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("Un secreto corto se rechaza antes de arrancar")
    void secretoDemasiadoCorto() {
        assertThatThrownBy(() -> serviceWith("corto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static JwtService serviceWith(String secret) {
        return new JwtService(new SecurityProperties(
                new SecurityProperties.Jwt(secret, "kardo", Duration.ofHours(8)),
                new SecurityProperties.Cors(List.of("http://localhost:5173"))));
    }
}
