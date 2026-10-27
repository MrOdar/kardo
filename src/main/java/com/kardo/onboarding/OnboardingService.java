package com.kardo.onboarding;

import com.kardo.dto.NewStoreRequestDto;
import com.kardo.dto.NewUserRequestDto;
import com.kardo.dto.RegisterRequestDto;
import com.kardo.dto.SessionDto;
import com.kardo.dto.StoreDto;
import com.kardo.exception.ForbiddenStoreException;
import com.kardo.security.AuthenticationService;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Como nace una empresa en Kardo.
 *
 * <p><b>Que agujero cierra.</b> Hasta aqui, una empresa, una tienda y un
 * usuario solo existian porque los sembraba {@code sp_reset_demo_environment}:
 * el producto funcionaba perfectamente dentro de la demo y no habia manera de
 * salir de ella. Esto es la puerta de entrada real.
 *
 * <p><b>Tres escrituras, una transaccion.</b> Una empresa sin tienda no sirve
 * de nada y una tienda sin gerente no se puede ni mirar, porque todo endpoint
 * exige un token y el token sale de un usuario. Si falla cualquiera de las
 * tres, no debe quedar ninguna: un RUC ocupado a medias bloquearia el alta
 * legitima de esa misma empresa para siempre.
 *
 * <p><b>El primero es gerente, sin eleccion.</b> No hay nadie que pueda darle
 * el rol, y una empresa cuyo unico usuario fuese operario no podria aprobar su
 * primera merma ni dar de alta a quien lo hiciera.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final OnboardingRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationService authenticationService;

    public OnboardingService(OnboardingRepository repository,
                             PasswordEncoder passwordEncoder,
                             AuthenticationService authenticationService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationService = authenticationService;
    }

    /**
     * Da de alta empresa, primera tienda y primer gerente, y abre sesion.
     *
     * <p>Devuelve la sesion ya iniciada en vez de pedir que inicie sesion a
     * continuacion: acaba de demostrar que sabe la contraseña, porque la ha
     * elegido en esta misma peticion.
     */
    @Transactional
    public SessionDto register(RegisterRequestDto request) {
        String email = normalize(request.email());

        long companyId = repository.createCompany(request.companyName().trim(), request.taxId());
        long storeId = repository.createStore(
                companyId, request.storeName().trim(), blankToNull(request.storeAddress()));

        repository.createUser(companyId, storeId, email, request.fullName().trim(),
                passwordEncoder.encode(request.password()), "MANAGER");

        log.info("Empresa dada de alta: {} (RUC {}), tienda {}, gerente {}",
                request.companyName(), request.taxId(), storeId, email);

        // La sesion se arma por el mismo camino que un login normal, de modo que
        // no hay una segunda forma de emitir un token que pueda divergir.
        return authenticationService.login(email, request.password());
    }

    /** Tienda nueva en la empresa de quien firma, que sale del token. */
    @Transactional
    public long addStore(long companyId, NewStoreRequestDto request) {
        long storeId = repository.createStore(
                companyId, request.name().trim(), blankToNull(request.address()));
        log.info("Tienda {} dada de alta en la empresa {}", storeId, companyId);
        return storeId;
    }

    /**
     * Alta de un companiero.
     *
     * <p>La tienda llega en el cuerpo porque una empresa puede tener varias,
     * pero se comprueba contra la empresa del token antes de escribir nada: sin
     * esta linea, cualquier gerente podria colocar un usuario suyo dentro de la
     * tienda de otra empresa y leer su inventario desde dentro.
     */
    @Transactional
    public long addUser(long companyId, NewUserRequestDto request) {
        if (!repository.storeBelongsToCompany(request.storeId(), companyId)) {
            throw ForbiddenStoreException.outsideCompany(request.storeId(), companyId);
        }
        String email = normalize(request.email());

        // Quien ya trabaja en la empresa no se duplica: se le añade la tienda
        // nueva con el rol que tenga ahi. Crear una segunda cuenta era lo que
        // habia que hacer antes de sql/13, y es justo lo que se quiere evitar.
        var existente = repository.findUserInCompany(email, companyId);
        if (existente.isPresent()) {
            repository.addMembership(existente.get(), request.storeId(), companyId, request.role());
            log.info("{} pasa a trabajar tambien en la tienda {} como {}",
                    email, request.storeId(), request.role());
            return existente.get();
        }

        long userId = repository.createUser(
                companyId,
                request.storeId(),
                email,
                request.fullName().trim(),
                passwordEncoder.encode(request.password()),
                request.role());

        log.info("Usuario {} dado de alta como {} en la tienda {}",
                userId, request.role(), request.storeId());
        return userId;
    }

    @Transactional(readOnly = true)
    public List<StoreDto> stores(long companyId) {
        return repository.storesOfCompany(companyId);
    }

    /** El correo se guarda como se busca en el login: en minusculas y sin espacios. */
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
