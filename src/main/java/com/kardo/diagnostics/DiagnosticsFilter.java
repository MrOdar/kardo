package com.kardo.diagnostics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Traza de cada peticion: un identificador y el reparto de su tiempo.
 *
 * <p>Es la version de "trazas" que tiene sentido en este despliegue. Un sistema
 * de trazado distribuido necesita un colector al que enviarlas, y aqui no hay
 * ninguno ni presupuesto para levantarlo; pero el problema real -un operario
 * dice que la recepcion va lenta y nadie sabe si fue Gemini, Supabase o el pool-
 * se resuelve con una linea por peticion en el log de Render.
 *
 * <p>El identificador vuelve en {@code X-Request-Id}: cuando alguien reporta un
 * fallo, ese codigo lleva directo a su linea en el log en vez de obligar a
 * buscar por hora aproximada.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DiagnosticsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    /**
     * Por debajo de este umbral la linea se escribe en DEBUG.
     *
     * <p>Una linea por peticion en INFO llenaria el log del tier gratuito de
     * ruido en el que no se ve lo que importa; lo que hay que poder leer de un
     * vistazo es lo que fue lento o fallo.
     */
    private final long slowRequestMillis;

    public DiagnosticsFilter(@Value("${kardo.diagnostics.slow-request-ms:1500}") long slowRequestMillis) {
        this.slowRequestMillis = slowRequestMillis;
    }

    /** El keep-alive y los estaticos no aportan nada al diagnostico. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/v1/ping");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL);
        RequestDiagnostics diagnostics = RequestDiagnostics.begin(requestId);

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            report(request, response, diagnostics);
            MDC.remove(MDC_KEY);
            RequestDiagnostics.end();
        }
    }

    private void report(HttpServletRequest request, HttpServletResponse response,
                        RequestDiagnostics diagnostics) {

        String linea = "{} {} -> {} · {}";
        Object[] datos = {request.getMethod(), request.getRequestURI(),
                response.getStatus(), diagnostics.breakdown()};

        // Lo lento y lo que fallo se ven siempre; el resto solo si alguien va a
        // mirarlo a proposito.
        if (diagnostics.totalMillis() >= slowRequestMillis || response.getStatus() >= 500) {
            log.warn(linea, datos);
        } else if (response.getStatus() >= 400) {
            log.info(linea, datos);
        } else {
            log.debug(linea, datos);
        }
    }
}
