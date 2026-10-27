package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.dto.NewStoreRequestDto;
import com.kardo.dto.NewUserRequestDto;
import com.kardo.dto.RegisterRequestDto;
import com.kardo.dto.SessionDto;
import com.kardo.exception.DuplicateAccountException;
import com.kardo.exception.ForbiddenStoreException;
import com.kardo.onboarding.OnboardingService;
import com.kardo.security.AuthenticationService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Que una empresa pueda nacer fuera de la demo.
 *
 * <p>Hasta que existio esto, una empresa, una tienda y un usuario solo salian
 * de {@code sp_reset_demo_environment}: el producto funcionaba entero dentro de
 * la demo y no habia forma de salir de ella. Lo que se fija aqui es la puerta
 * de entrada y, sobre todo, que esa puerta no sirva para colarse en la casa de
 * otro.
 */
class OnboardingIT extends PostgresIntegrationTest {

    @Autowired
    private OnboardingService onboarding;

    @Autowired
    private AuthenticationService authentication;

    // ------------------------------------------------------------------
    // Alta de empresa
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un alta crea empresa, tienda y gerente, y deja la sesion abierta")
    void elAltaDejaTodoListoParaTrabajar() {
        SessionDto session = onboarding.register(alta("Minimarket El Sol S.A.C.", "Tienda Centro"));

        assertThat(session.token()).isNotBlank();
        assertThat(session.role()).isEqualTo("MANAGER");
        assertThat(session.storeName()).isEqualTo("Tienda Centro");

        // La tienda nace vacia: es lo correcto, y es lo que la interfaz tiene
        // que saber contar sin parecer rota.
        Integer lotes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_batches WHERE store_id = ?",
                Integer.class, session.storeId());
        assertThat(lotes).isZero();
    }

    /**
     * El primero no puede ser operario: no habria nadie que pudiera darle el rol
     * despues, ni aprobar la primera merma que el mismo declarase.
     */
    @Test
    @DisplayName("El primer usuario de una empresa es gerente")
    void elPrimeroEsGerente() {
        SessionDto session = onboarding.register(alta("Bodega La Esquina E.I.R.L.", "Local 1"));

        String role = jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE email = ?", String.class, session.email());
        assertThat(role).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("La contraseña se guarda cifrada y sirve para entrar")
    void laContraseniaSeGuardaCifrada() {
        RegisterRequestDto request = alta("Market Lima S.A.C.", "Tienda Lima");
        onboarding.register(request);

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, request.email());
        assertThat(hash).startsWith("$2").doesNotContain(request.password());

        // Y el camino normal funciona: el alta no crea una credencial distinta.
        assertThat(authentication.login(request.email(), request.password()).role()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("El correo se guarda en minusculas, como lo busca el login")
    void elCorreoSeNormaliza() {
        RegisterRequestDto request = new RegisterRequestDto(
                "Mayusculas S.A.C.", ruc(), "Tienda", null, "Ana Quispe",
                "ANA." + UUID.randomUUID().toString().substring(0, 6) + "@MAYUSCULAS.PE",
                "clave-larga-2026");

        SessionDto session = onboarding.register(request);

        assertThat(session.email()).isEqualTo(request.email().toLowerCase());
        assertThat(authentication.login(request.email(), request.password()).email())
                .isEqualTo(request.email().toLowerCase());
    }

    // ------------------------------------------------------------------
    // Lo que no se puede repetir
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Dos empresas no pueden compartir RUC")
    void elRucEsUnico() {
        String ruc = ruc();
        onboarding.register(new RegisterRequestDto(
                "Primera S.A.C.", ruc, "Tienda", null, "Ana", correo(), "clave-larga-2026"));

        assertThatThrownBy(() -> onboarding.register(new RegisterRequestDto(
                "Segunda S.A.C.", ruc, "Tienda", null, "Beto", correo(), "clave-larga-2026")))
                .isInstanceOf(DuplicateAccountException.class)
                .hasMessageContaining(ruc);
    }

    /**
     * Y lo que importa de verdad: el RUC ocupado a medias no puede bloquear el
     * alta legitima de esa empresa. Si la transaccion no fuera una, el primer
     * intento fallido dejaria la empresa creada y sin gerente, y nadie podria
     * volver a registrarla nunca.
     */
    @Test
    @DisplayName("Un alta a medias no deja la empresa creada")
    void unAltaFallidaNoDejaRastro() {
        String correoOcupado = correo();
        onboarding.register(new RegisterRequestDto(
                "Ocupante S.A.C.", ruc(), "Tienda", null, "Ana", correoOcupado, "clave-larga-2026"));

        String rucLibre = ruc();
        assertThatThrownBy(() -> onboarding.register(new RegisterRequestDto(
                "Aspirante S.A.C.", rucLibre, "Tienda", null, "Beto", correoOcupado, "clave-larga-2026")))
                .isInstanceOf(DuplicateAccountException.class);

        Integer empresas = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM companies WHERE tax_id = ?", Integer.class, rucLibre);
        assertThat(empresas).describedAs("empresa a medias tras el fallo").isZero();

        // Y la prueba de que de verdad se puede reintentar con otro correo.
        assertThat(onboarding.register(new RegisterRequestDto(
                "Aspirante S.A.C.", rucLibre, "Tienda", null, "Beto", correo(), "clave-larga-2026"))
                .role()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("Dos tiendas con el mismo nombre conviven con codigos distintos")
    void losCodigosDeTiendaNoChocan() {
        SessionDto primera = onboarding.register(alta("Cadena A S.A.C.", "Tienda Centro"));
        SessionDto segunda = onboarding.register(alta("Cadena B S.A.C.", "Tienda Centro"));

        String codigoA = codigoDe(primera.storeId());
        String codigoB = codigoDe(segunda.storeId());

        assertThat(codigoA).isNotEqualTo(codigoB).startsWith("TIENDA-CENTRO-");
        assertThat(codigoB).startsWith("TIENDA-CENTRO-");
    }

    @Test
    @DisplayName("Un nombre con tildes y simbolos produce un codigo legible")
    void elCodigoSeNormaliza() {
        SessionDto session = onboarding.register(alta("Acentos S.A.C.", "Bodega Niños Héroes #2"));

        assertThat(codigoDe(session.storeId())).startsWith("BODEGA-NINOS-HER");
    }

    // ------------------------------------------------------------------
    // Crecer: mas tiendas, mas gente
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El gerente abre otra tienda y aparece en su lista")
    void unaEmpresaPuedeTenerVariasTiendas() {
        SessionDto session = onboarding.register(alta("Cadena Norte S.A.C.", "Sucursal Uno"));
        long companyId = companyOf(session.storeId());

        onboarding.addStore(companyId, new NewStoreRequestDto("Sucursal Dos", "Av. Dos 456"));

        assertThat(onboarding.stores(companyId))
                .hasSize(2)
                .extracting("name")
                .containsExactly("Sucursal Uno", "Sucursal Dos");
    }

    @Test
    @DisplayName("El alta de un companiero le deja entrar con su rol")
    void elGerenteDaDeAltaAUnOperario() {
        SessionDto session = onboarding.register(alta("Con Equipo S.A.C.", "Tienda"));
        long companyId = companyOf(session.storeId());
        String correoOperario = correo();

        onboarding.addUser(companyId, new NewUserRequestDto(
                "Operario Nuevo", correoOperario, "clave-larga-2026", "OPERATOR", session.storeId()));

        SessionDto delOperario = authentication.login(correoOperario, "clave-larga-2026");
        assertThat(delOperario.role()).isEqualTo("OPERATOR");
        assertThat(delOperario.storeId()).isEqualTo(session.storeId());
    }

    /**
     * El salto entre inquilinos, por la unica puerta por la que seria posible:
     * un gerente legitimo colocando un usuario suyo dentro de la tienda de otra
     * empresa, que a partir de ahi leeria su inventario desde dentro.
     */
    @Test
    @DisplayName("Nadie da de alta a nadie en la tienda de otra empresa")
    void noSePuedeColarUnUsuarioEnOtraEmpresa() {
        SessionDto propia = onboarding.register(alta("Mia S.A.C.", "Mi tienda"));
        SessionDto ajena = onboarding.register(alta("Ajena S.A.C.", "Su tienda"));

        long miEmpresa = companyOf(propia.storeId());

        assertThatThrownBy(() -> onboarding.addUser(miEmpresa, new NewUserRequestDto(
                "Infiltrado", correo(), "clave-larga-2026", "OPERATOR", ajena.storeId())))
                .isInstanceOf(ForbiddenStoreException.class);

        Integer intrusos = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE store_id = ? AND company_id = ?",
                Integer.class, ajena.storeId(), miEmpresa);
        assertThat(intrusos).isZero();
    }

    @Test
    @DisplayName("La lista de tiendas solo trae las de su empresa")
    void laListaNoSeAsomaAOtrasEmpresas() {
        SessionDto propia = onboarding.register(alta("Solo Mia S.A.C.", "Unica"));
        onboarding.register(alta("Otra Distinta S.A.C.", "Tambien unica"));

        assertThat(onboarding.stores(companyOf(propia.storeId())))
                .singleElement()
                .extracting("name")
                .isEqualTo("Unica");
    }

    @Test
    @DisplayName("La lista cuenta cuanta gente trabaja en cada tienda")
    void laListaCuentaLaGente() {
        SessionDto session = onboarding.register(alta("Contada S.A.C.", "Tienda"));
        long companyId = companyOf(session.storeId());

        onboarding.addUser(companyId, new NewUserRequestDto(
                "Segundo", correo(), "clave-larga-2026", "OPERATOR", session.storeId()));

        assertThat(onboarding.stores(companyId))
                .singleElement()
                .extracting("users")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------

    private RegisterRequestDto alta(String empresa, String tienda) {
        return new RegisterRequestDto(empresa, ruc(), tienda, "Av. Siempre Viva 123",
                "Ana Quispe", correo(), "clave-larga-2026");
    }

    /** RUC de once digitos distinto en cada llamada: la unicidad es real. */
    private static String ruc() {
        return "20" + String.format("%09d", Math.abs(UUID.randomUUID().hashCode()) % 1_000_000_000);
    }

    private static String correo() {
        return "persona-" + UUID.randomUUID().toString().substring(0, 8) + "@kardo-test.pe";
    }

    private String codigoDe(long storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT store_code FROM stores WHERE store_id = ?", String.class, storeId);
    }

    private long companyOf(long storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT company_id FROM stores WHERE store_id = ?", Long.class, storeId);
    }
}
