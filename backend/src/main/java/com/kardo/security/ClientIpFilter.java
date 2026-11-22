package com.kardo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fija la IP del cliente a partir de la cabecera que escribe el borde de confianza.
 *
 * <p><b>El agujero que tapa.</b> Con {@code forward-headers-strategy=framework},
 * {@code getRemoteAddr()} devuelve la <em>primera</em> entrada de
 * {@code X-Forwarded-For}, y esa la escribe el cliente. Bastaba con mandar una
 * IP inventada en cada peticion para estrenar cupo en los frenos por IP (login,
 * alta de empresas, reset de la demo) y para firmar la auditoria con una IP falsa.
 *
 * <p>Render publica el servicio detras de Cloudflare, que pone la IP real en
 * {@code CF-Connecting-IP} y pisa cualquier valor que traiga el cliente. Si la
 * cabecera falta (desarrollo local, tests), no se toca nada.
 *
 * <p>Corre justo despues de {@code ForwardedHeaderFilter}, para que su valor sea
 * el ultimo en envolver la peticion, y antes de la cadena de seguridad, de los
 * frenos y de {@code DiagnosticsFilter}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ClientIpFilter extends OncePerRequestFilter {

    /** Solo caracteres de IPv4/IPv6: lo que no lo parezca no llega a la auditoria. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final String header;

    public ClientIpFilter(@Value("${kardo.security.client-ip-header:CF-Connecting-IP}") String header) {
        this.header = header;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String trusted = StringUtils.hasText(header) ? request.getHeader(header) : null;
        if (trusted != null && IP_LITERAL.matcher(trusted.trim()).matches()) {
            String ip = trusted.trim();
            filterChain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public String getRemoteAddr() {
                    return ip;
                }

                @Override
                public String getRemoteHost() {
                    return ip;
                }
            }, response);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
