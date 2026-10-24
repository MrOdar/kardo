package com.kardo.security;

import com.kardo.config.SecurityProperties;
import com.kardo.dto.SessionDto;
import com.kardo.exception.InvalidCredentialsException;
import com.kardo.repository.StoreRepository;
import com.kardo.repository.UserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verificacion de credenciales y emision de la sesion. */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;

    public AuthenticationService(UserRepository userRepository,
                                 StoreRepository storeRepository,
                                 PasswordEncoder passwordEncoder,
                                 JwtService jwtService,
                                 SecurityProperties securityProperties) {
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
    }

    /**
     * Abre sesion sin contraseña para el usuario de la tienda de demostracion.
     *
     * <p>Es la unica puerta sin credenciales del sistema y existe porque el
     * evaluador llega sin ninguna. Para que no pueda convertirse en una via de
     * acceso general, rechaza cualquier usuario que no pertenezca a la tienda
     * demo: aunque alguien lograra invocarla con otro correo, no obtendria un
     * token de una tienda real.
     *
     * @param demoStoreId tienda de demostracion admitida
     */
    @Transactional
    public SessionDto openDemoSession(String email, long demoStoreId) {
        var account = userRepository.findActiveByEmail(email)
                .orElseThrow(() -> new IllegalStateException(
                        "Falta el usuario " + email + ": ejecuta sql/03_users_and_auth.sql en la base."));

        if (account.storeId() != demoStoreId) {
            throw new IllegalStateException(
                    "El usuario " + email + " no pertenece a la tienda de demostracion.");
        }

        userRepository.touchLastLogin(account.userId());
        return toSession(account);
    }

    /**
     * @throws InvalidCredentialsException si el correo no existe, el usuario esta
     *                                     inactivo o la contraseña no coincide.
     *                                     El mensaje es el mismo en los tres casos:
     *                                     distinguirlos revelaria que correos estan
     *                                     dados de alta.
     */
    @Transactional
    public SessionDto login(String email, String rawPassword) {
        var account = userRepository.findActiveByEmail(email)
                .filter(user -> passwordEncoder.matches(rawPassword, user.passwordHash()))
                .orElseThrow(() -> {
                    log.warn("Intento de acceso fallido para {}", email);
                    return new InvalidCredentialsException();
                });

        userRepository.touchLastLogin(account.userId());
        return toSession(account);
    }

    private SessionDto toSession(UserRepository.UserAccount account) {
        // La tienda del usuario entra en el token; a partir de aqui es la unica
        // que el backend aceptara para ese portador.
        String token = jwtService.issueToken(account.email(), account.storeId(), List.of(account.authority()));

        log.info("Sesion iniciada: {} ({}) en la tienda {}", account.email(), account.role(), account.storeId());

        return new SessionDto(
                token,
                securityProperties.jwt().expiration().toSeconds(),
                account.email(),
                account.fullName(),
                account.role(),
                account.storeId(),
                storeRepository.findNameById(account.storeId()));
    }
}
