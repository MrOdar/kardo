package com.kardo.web;

import com.kardo.dto.LoginRequestDto;
import com.kardo.dto.SessionDto;
import com.kardo.security.AuthenticationService;
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

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionDto> login(@Valid @RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(authenticationService.login(request.email(), request.password()));
    }

    /** Permite a la interfaz saber quien es el portador del token que ya tiene. */
    @GetMapping("/me")
    public ResponseEntity<KardoPrincipal> me() {
        return ResponseEntity.ok(CurrentUser.require());
    }
}
