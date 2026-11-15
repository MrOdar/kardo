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
import com.kardo.security.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    /**
     * Impide que el contenedor de servlets registre el filtro JWT por su cuenta.
     *
     * <p><b>El fallo que esto evita, y es silencioso.</b>
     * {@code JwtAuthenticationFilter} es un {@code @Component} que extiende
     * {@code OncePerRequestFilter}, de modo que Spring Boot lo da de alta
     * automaticamente como filtro del servlet, <em>ademas</em> de donde lo
     * ponemos nosotros con {@code addFilterBefore}. Queda registrado dos veces y
     * el orden lo arruina todo:
     *
     * <ol>
     *   <li>el registro automatico corre <b>antes</b> de la cadena de seguridad,
     *   lee el token y deja la autenticacion en el contexto;</li>
     *   <li>ya dentro de la cadena, {@code SecurityContextHolderFilter} carga el
     *   contexto del repositorio -vacio, porque somos sin estado- y
     *   <b>sustituye</b> el que acababa de poner el filtro;</li>
     *   <li>cuando le toca el turno al filtro en su sitio correcto,
     *   {@code OncePerRequestFilter} ve que esta peticion ya paso por el y
     *   <b>se salta</b>, asi que no vuelve a autenticar.</li>
     * </ol>
     *
     * <p>Resultado: un token perfectamente valido acaba en 401. No lo delata
     * ningun error en el log, solo el codigo de respuesta.
     */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterSoloEnLaCadena(
            JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        // Desde que la interfaz se despliega aparte, esta
                        // politica ya NO gobierna la pagina del operario: una
                        // CSP protege al documento que la trae, y el documento
                        // lo sirve ahora el host estatico. La de la pagina vive
                        // en un <meta> de frontend/app/index.html, con la
                        // perdida que eso implica -`frame-ancestors` no
                        // funciona en <meta>- apuntada alli.
                        //
                        // Se queda igualmente, y no por inercia: cubre lo que
                        // este servicio si devuelve como documento -las paginas
                        // de error de Tomcat, el visor de Swagger- y mantiene
                        // `frame-ancestors 'none'` sobre la propia API, que es
                        // lo que impide meterla en un iframe ajeno.
                        //
                        // `connect-src 'self'` aqui ya no dice gran cosa: en una
                        // respuesta JSON no hay nada que haga peticiones. Lo que
                        // de verdad acota a donde puede hablar la interfaz es
                        // ahora la lista de CORS de abajo.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                "default-src 'self'",
                                "script-src 'self'",
                                // Sin dominios de Google: las dos tipografias
                                // se sirven desde este jar, de modo que ya no
                                // queda ningun tercero del que dependa como se
                                // ve la pagina.
                                "style-src 'self' 'unsafe-inline'",
                                "font-src 'self'",
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
                        // Ya no hay estaticos que autorizar: la interfaz se
                        // despliega aparte y este servicio es solo la API. Lo
                        // que la deja entrar es CORS, no una lista de rutas.
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

