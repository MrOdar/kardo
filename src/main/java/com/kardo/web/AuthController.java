package com.kardo.web;

import com.kardo.dto.LoginRequestDto;
import com.kardo.dto.SessionDto;
import com.kardo.exception.InvalidCredentialsException;
import com.kardo.security.AuthenticationService;
import com.kardo.throttle.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import com.kardo.security.CurrentUser;
import com.kardo.security.KardoPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inicio de sesion y consulta de la sesion vigente. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final LoginThrottle loginThrottle;

    public AuthController(AuthenticationService authenticationService, LoginThrottle loginThrottle) {
        this.authenticationService = authenticationService;
        this.loginThrottle = loginThrottle;
    }

    /**
     * El freno envuelve al servicio en lugar de vivir dentro de el.
     *
     * <p>Asi {@code AuthenticationService} sigue contestando a una sola
     * pregunta -si estas credenciales valen- sin saber nada de IP ni de
     * intentos, y el freno queda donde se ve de un vistazo que esta puesto.
     */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionDto> login(@Valid @RequestBody LoginRequestDto request,
                                            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        loginThrottle.checkAllowed(request.email(), clientIp);

        try {
            SessionDto session = authenticationService.login(request.email(), request.password());
            loginThrottle.recordSuccess(request.email(), clientIp);
            return ResponseEntity.ok(session);
        } catch (InvalidCredentialsException fallo) {
            loginThrottle.recordFailure(request.email(), clientIp);
            throw fallo;
        }
    }

    /** Permite a la interfaz saber quien es el portador del token que ya tiene. */
    @GetMapping("/me")
    public ResponseEntity<KardoPrincipal> me() {
        return ResponseEntity.ok(CurrentUser.require());
    }
}
