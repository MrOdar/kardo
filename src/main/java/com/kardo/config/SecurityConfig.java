package com.kardo.config;

import com.kardo.security.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cadena de filtros stateless de Spring Security 6.
 *
 * <p>CSRF queda deshabilitado porque no se emiten cookies de sesion: la
 * credencial viaja en la cabecera {@code Authorization}, inmune a CSRF.
 */
@Configuration
@EnableWebSecurity
// Habilita @PreAuthorize: los roles del token dejan de ser decorativos.
@EnableMethodSecurity
public class SecurityConfig {

    private final SecurityProperties securityProperties;

    public SecurityConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        // Segunda linea de defensa del XSS. La primera sigue
                        // siendo que el SPA pinta con textContent y no con
                        // innerHTML; esto es lo que queda si alguna vez se
                        // cuela una. `connect-src 'self'` es la parte que de
                        // verdad muerde: un script inyectado podria ejecutarse,
                        // pero no tiene a donde mandar el inventario ni el
                        // token. `frame-ancestors 'none'` impide el
                        // clickjacking sobre los botones que aprueban mermas.
                        //
                        // 'unsafe-inline' esta ahi a disgusto y por una razon
                        // concreta: Tailwind por CDN se configura con un
                        // <script> en linea e inyecta <style> en tiempo de
                        // ejecucion. Quitarlo exige compilar el CSS, que es
                        // justo el paso de build que este proyecto no tiene.
                        // Queda anotado como deuda, no como decision.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                "default-src 'self'",
                                "script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com",
                                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
                                "font-src 'self' https://fonts.gstatic.com",
                                "img-src 'self' data:",
                                "connect-src 'self'",
                                // 'self' y no 'none': los formularios del SPA no
                                // declaran action y el JS los intercepta, pero si
                                // alguna vez uno se envia de verdad, el destino
                                // legitimo es este mismo origen. Lo que se corta es
                                // el formulario inyectado que postea credenciales a
                                // otro dominio.
                                "form-action 'self'",
                                "base-uri 'self'",
                                "object-src 'none'",
                                "frame-ancestors 'none'")))
                        // El navegador no debe adivinar el tipo: una guia
                        // subida como .jpg que en realidad es HTML no puede
                        // acabar ejecutandose al servirla de vuelta.
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        // Sin esto, la URL completa de Kardo viaja como
                        // Referer a fonts.googleapis.com y al CDN.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(permissions -> permissions.policy(
                                "camera=(self), microphone=(), geolocation=(), payment=()")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // El reenvio interno a /error vuelve a pasar por la cadena.
                        // Sin esto, un fallo dentro de un endpoint protegido (falta
                        // el archivo, el multipart excede el limite) se responde como
                        // 401 y oculta el error real al operario.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        // Keep-Alive y sondas: publicos y sin acceso a BD (GET y HEAD para UptimeRobot)
                        .requestMatchers(HttpMethod.GET, "/api/v1/ping").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/ping").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Emision de tokens
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Alta de una empresa nueva: quien se registra no tiene
                        // token todavia, y esta llamada es la que se lo da. Crea
                        // su propia empresa y su propia tienda, nunca toca una
                        // existente, y lleva freno por IP.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        // Contrato de la API y su visor. Publicos a proposito:
                        // describen que endpoints existen y que cuerpo esperan,
                        // que es exactamente lo que ya se lee en el repositorio.
                        // No devuelven ni un dato de ninguna tienda, y lo que
                        // protege las rutas que describen son las reglas de
                        // abajo, no el desconocimiento de que existen. Se apagan
                        // con springdoc.api-docs.enabled=false.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // La SPA se sirve desde el mismo contenedor y debe cargar
                        // antes de que exista ningun token.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/favicon.ico",
                                "/assets/**", "/css/**", "/js/**").permitAll()
                        // Puerta de entrada del evaluador: no tiene credenciales
                        // todavia, y esta llamada es justamente la que se las da.
                        // Solo puede tocar la tienda demo (999), nunca datos reales.
                        .requestMatchers(HttpMethod.POST, "/api/v1/demo/reset").permitAll()
                        // El preflight de CORS nunca lleva cabecera Authorization
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Sin esto una peticion sin token recibiria 403; el SPA necesita 401
                // para distinguir "renueva tu sesion" de "no tienes permiso".
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }

    /**
     * Origen del SPA desplegado en Vercel, configurable por entorno.
     * Se exponen {@code Authorization} y {@code Content-Type} para que el
     * cliente pueda leer el token renovado y el tipo de respuesta.
     *
     * <p><b>Sin credenciales, a proposito.</b> Kardo no emite cookies: el token
     * viaja en {@code Authorization}, que el cliente adjunta a mano. Pedir
     * {@code allowCredentials(true)} no habilitaba nada que el SPA necesite y
     * dejaba armado el peor error de CORS que existe: el dia que alguien
     * ponga {@code *} en {@code KARDO_CORS_ORIGINS} -o un
     * {@code https://kardo.pe.sitio-del-atacante.com} en la lista-, cualquier
     * pagina podria pedirle al navegador peticiones autenticadas contra esta
     * API y leer la respuesta. En {@code false}, el navegador no manda nada que
     * identifique al usuario aunque el origen este mal.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(securityProperties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        configuration.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

