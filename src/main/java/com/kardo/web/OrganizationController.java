package com.kardo.web;

import com.kardo.dto.NewStoreRequestDto;
import com.kardo.dto.NewUserRequestDto;
import com.kardo.dto.StoreDto;
import com.kardo.onboarding.OnboardingService;
import com.kardo.repository.StoreRepository;
import com.kardo.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que una empresa hace con su propia estructura: abrir tiendas y dar de alta
 * a quien trabaja en ellas.
 *
 * <p><b>La empresa nunca llega del cliente.</b> Sale de la tienda del token, por
 * el mismo camino que el resto de la aplicacion resuelve el catalogo. De las dos
 * escrituras, solo el alta de usuario recibe una tienda en el cuerpo -una
 * empresa puede tener varias-, y el servicio comprueba que sea suya antes de
 * escribir nada.
 *
 * <p>Las dos escrituras son del gerente. Un operario ve la lista de tiendas,
 * porque es lo que necesita para entender donde esta, y no puede crear nada.
 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationController {

    private final OnboardingService onboardingService;
    private final StoreRepository storeRepository;

    public OrganizationController(OnboardingService onboardingService,
                                  StoreRepository storeRepository) {
        this.onboardingService = onboardingService;
        this.storeRepository = storeRepository;
    }

    @GetMapping("/stores")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<List<StoreDto>> stores() {
        return ResponseEntity.ok(onboardingService.stores(companyOfCaller()));
    }

    @PostMapping(value = "/stores", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> createStore(@Valid @RequestBody NewStoreRequestDto request) {
        long storeId = onboardingService.addStore(companyOfCaller(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("storeId", storeId));
    }

    /**
     * Da de alta a un companiero.
     *
     * <p>No devuelve la contraseña ni la repite: la eligio quien rellena el
     * formulario y tiene que comunicarsela a su companiero por donde sea. Es
     * deliberado que el sistema no la guarde en claro en ningun sitio, ni
     * siquiera en una respuesta.
     */
    @PostMapping(value = "/users", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> createUser(@Valid @RequestBody NewUserRequestDto request) {
        long userId = onboardingService.addUser(companyOfCaller(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", userId));
    }

    /** La empresa se deriva de la tienda del token; el cliente no la elige. */
    private long companyOfCaller() {
        return storeRepository.findCompanyIdByStore(CurrentUser.storeId());
    }
}
