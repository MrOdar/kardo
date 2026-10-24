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
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // El reenvio interno a /error vuelve a pasar por la cadena.
                        // Sin esto, un fallo dentro de un endpoint protegido (falta
                        // el archivo, el multipart excede el limite) se responde como
                        // 401 y oculta el error real al operario.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        // Keep-Alive y sondas: publicos y sin acceso a BD
                        .requestMatchers(HttpMethod.GET, "/api/v1/ping").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Emision de tokens
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
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
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(securityProperties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        configuration.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        configuration.setAllowCredentials(true);
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

