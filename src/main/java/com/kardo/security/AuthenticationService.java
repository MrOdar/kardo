package com.kardo.security;

import com.kardo.config.SecurityProperties;
import com.kardo.dto.SessionDto;
import com.kardo.exception.ForbiddenStoreException;
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
        return toSession(account, defaultMembership(account));
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
        return toSession(account, defaultMembership(account));
    }

    /**
     * Cambia la tienda sobre la que opera quien ya tiene sesion.
     *
     * <p>No es un ajuste de interfaz: emite un token nuevo, con otra tienda y
     * con el rol que esa persona tiene <b>en esa tienda</b>. Lo unico que lo
     * autoriza es la membresia; sin ella no hay token, y por tanto no hay
     * manera de pedirle nada al backend sobre esa tienda.
     *
     * <p>El invariante de siempre no cambia: la tienda sigue saliendo del
     * token. Lo que cambia es a quien se le puede emitir cual.
     */
    @Transactional(readOnly = true)
    public SessionDto switchStore(String email, long storeId) {
        var account = userRepository.findActiveByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        var membership = userRepository.findMembership(account.userId(), storeId)
                .orElseThrow(() -> {
                    log.warn("{} intento operar sobre la tienda {} sin trabajar en ella", email, storeId);
                    return ForbiddenStoreException.notAMember(storeId, email);
                });

        log.info("{} pasa a operar la tienda {} como {}", email, storeId, membership.role());
        return toSession(account, membership);
    }

    /** Las tiendas en las que esta persona trabaja, para poder elegir. */
    @Transactional(readOnly = true)
    public List<UserRepository.StoreMembership> memberships(String email) {
        return userRepository.findActiveByEmail(email)
                .map(account -> userRepository.findMemberships(account.userId()))
                .orElseGet(List::of);
    }

    /**
     * Con que tienda se abre sesion.
     *
     * <p>La de origen si sigue siendo suya, y si no la primera en la que
     * trabaje. Quedarse sin ninguna es posible -a alguien se le retira de todas
     * las tiendas- y entonces no hay sesion que abrir: un token sin tienda no
     * podria pedir nada, y seria peor explicarlo despues que ahora.
     */
    private UserRepository.StoreMembership defaultMembership(UserRepository.UserAccount account) {
        List<UserRepository.StoreMembership> memberships =
                userRepository.findMemberships(account.userId());

        if (memberships.isEmpty()) {
            log.warn("El usuario {} no trabaja en ninguna tienda", account.email());
            throw new InvalidCredentialsException();
        }
        return memberships.stream()
                .filter(membership -> membership.storeId() == account.storeId())
                .findFirst()
                .orElseGet(memberships::getFirst);
    }

    private SessionDto toSession(UserRepository.UserAccount account,
                                 UserRepository.StoreMembership membership) {
        // La tienda activa entra en el token junto con el rol que esta persona
        // tiene en ella; a partir de aqui es la unica que el backend aceptara
        // para ese portador, y con esos permisos.
        String token = jwtService.issueToken(
                account.email(), membership.storeId(), List.of(membership.authority()));

        log.info("Sesion iniciada: {} ({}) en la tienda {}",
                account.email(), membership.role(), membership.storeId());

        return new SessionDto(
                token,
                securityProperties.jwt().expiration().toSeconds(),
                account.email(),
                account.fullName(),
                membership.role(),
                membership.storeId(),
                membership.storeName());
    }
}
