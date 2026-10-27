package com.kardo.web;

import com.kardo.dto.LoginRequestDto;
import com.kardo.dto.RegisterRequestDto;
import com.kardo.dto.SessionDto;
import com.kardo.dto.SwitchStoreRequestDto;
import com.kardo.repository.UserRepository;
import java.util.List;
import com.kardo.exception.InvalidCredentialsException;
import com.kardo.security.AuthenticationService;
import com.kardo.onboarding.OnboardingService;
import com.kardo.throttle.LoginThrottle;
import com.kardo.throttle.RegistrationThrottle;
import jakarta.servlet.http.HttpServletRequest;
import com.kardo.security.CurrentUser;
import com.kardo.security.KardoPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Alta de una empresa, inicio de sesion y consulta de la sesion vigente. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final OnboardingService onboardingService;
    private final LoginThrottle loginThrottle;
    private final RegistrationThrottle registrationThrottle;

    public AuthController(AuthenticationService authenticationService,
                          OnboardingService onboardingService,
                          LoginThrottle loginThrottle,
                          RegistrationThrottle registrationThrottle) {
        this.authenticationService = authenticationService;
        this.onboardingService = onboardingService;
        this.loginThrottle = loginThrottle;
        this.registrationThrottle = registrationThrottle;
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

    /**
     * Alta de una empresa nueva, con su primera tienda y su primer gerente.
     *
     * <p>Es publico por necesidad -quien se da de alta no tiene token todavia- y
     * por eso lleva freno, igual que el login. El freno solo cuenta las altas
     * que ocurrieron: equivocarse al teclear el RUC no gasta cupo.
     *
     * <p>Devuelve 201 y la sesion ya abierta: acaba de elegir la contraseña en
     * esta misma peticion, pedirle que inicie sesion seria pedirle que repita
     * algo que ya ha demostrado.
     */
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionDto> register(@Valid @RequestBody RegisterRequestDto request,
                                               HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        registrationThrottle.checkAllowed(clientIp);

        SessionDto session = onboardingService.register(request);
        registrationThrottle.recordSuccess(clientIp);

        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * Cambia la tienda sobre la que opera quien ya tiene sesion.
     *
     * <p>Devuelve una sesion nueva porque eso es literalmente lo que pasa: otro
     * token, con otra tienda y con el rol que esa persona tiene alli. Se puede
     * ser gerente en una tienda y operario en otra, y el token lo refleja.
     *
     * <p>No lleva {@code @PreAuthorize} de rol a proposito: cambiar de tienda
     * no es un privilegio de gerente, es moverse a donde uno trabaja. Lo que
     * autoriza es la membresia, y eso lo comprueba el servicio.
     */
    @PostMapping(value = "/store", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionDto> switchStore(@Valid @RequestBody SwitchStoreRequestDto request) {
        return ResponseEntity.ok(
                authenticationService.switchStore(CurrentUser.require().email(), request.storeId()));
    }

    /** Las tiendas en las que trabaja, para poder elegir entre ellas. */
    @GetMapping("/stores")
    public ResponseEntity<List<UserRepository.StoreMembership>> myStores() {
        return ResponseEntity.ok(authenticationService.memberships(CurrentUser.require().email()));
    }

    /** Permite a la interfaz saber quien es el portador del token que ya tiene. */
    @GetMapping("/me")
    public ResponseEntity<KardoPrincipal> me() {
        return ResponseEntity.ok(CurrentUser.require());
    }
}
