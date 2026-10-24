package com.kardo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resuelve la identidad desde la cabecera {@code Authorization: Bearer <token>}.
 *
 * <p>Un token ausente o invalido deja el contexto vacio: la decision de rechazar
 * con 401 queda en manos de la cadena de autorizacion, lo que mantiene publicos
 * los endpoints de keep-alive sin ramificaciones aqui.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parseToken(token);
                var authentication = new UsernamePasswordAuthenticationToken(
                        toPrincipal(claims), null, extractAuthorities(claims));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                // Token manipulado o expirado: se continua sin autenticar.
                log.debug("Token JWT rechazado: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    /**
     * Construye la identidad a partir del token firmado.
     *
     * <p>Un token sin {@code storeId} no identifica sobre que tienda puede
     * operar su portador, asi que se rechaza aqui en lugar de dejar que un
     * endpoint decida por su cuenta.
     */
    private static KardoPrincipal toPrincipal(Claims claims) {
        Object storeId = claims.get(JwtService.CLAIM_STORE_ID);
        if (!(storeId instanceof Number store)) {
            throw new IllegalArgumentException("El token no declara la tienda del usuario.");
        }
        return new KardoPrincipal(claims.getSubject(), store.longValue(), extractRoles(claims));
    }

    private static List<String> extractRoles(Claims claims) {
        Object rawRoles = claims.get(JwtService.CLAIM_ROLES);
        if (!(rawRoles instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private static Collection<GrantedAuthority> extractAuthorities(Claims claims) {
        return extractRoles(claims).stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }
}
