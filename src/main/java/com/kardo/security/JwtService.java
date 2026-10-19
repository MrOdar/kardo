package com.kardo.security;

import com.kardo.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import io.jsonwebtoken.security.Keys;

/**
 * Emision y verificacion de access tokens HS256.
 *
 * <p>El token es la unica fuente de identidad: no hay sesion en servidor, de modo
 * que el contenedor de Render no retiene estado entre peticiones.
 */
@Service
public class JwtService {

    /** Claim con los roles del usuario, p. ej. {@code ["ROLE_OPERATOR"]}. */
    public static final String CLAIM_ROLES = "roles";

    /** Claim con la tienda activa; acota las consultas al {@code store_id} del operario. */
    public static final String CLAIM_STORE_ID = "storeId";

    private final SecretKey signingKey;
    private final SecurityProperties.Jwt properties;

    /** HS256 exige una clave de 256 bits (RFC 7518, seccion 3.2). */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtService(SecurityProperties securityProperties) {
        this.properties = securityProperties.jwt();
        byte[] secret = resolveSecret(this.properties.secret()).getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    /**
     * Valida el secreto antes de derivar la clave.
     *
     * <p>Un {@code JWT_SECRET} ausente llega aqui como el literal
     * {@code "${JWT_SECRET}"}, y jjwt lo rechaza con un error sobre bits que no
     * dice nada del despliegue. Este mensaje nombra la variable que falta.
     */
    private static String resolveSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalStateException(
                    "Falta la variable de entorno JWT_SECRET (o kardo.security.jwt.secret).");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET debe tener al menos " + MIN_SECRET_BYTES + " bytes para firmar con HS256.");
        }
        return secret;
    }

    public String issueToken(String subject, Long storeId, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_STORE_ID, storeId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.expiration())))
                .signWith(signingKey)
                .compact();
    }

    /**
     * @return los claims del token si la firma, el emisor y la expiracion son validos
     * @throws JwtException si el token es invalido o ha expirado
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
