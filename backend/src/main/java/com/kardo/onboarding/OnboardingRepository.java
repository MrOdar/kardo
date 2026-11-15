package com.kardo.onboarding;

import com.kardo.dto.StoreDto;
import com.kardo.exception.DuplicateAccountException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Altas de empresa, tienda y usuario.
 *
 * <p>Vive aparte de {@link com.kardo.repository.UserRepository} porque esa
 * responde a la pregunta del login -quien es este correo- y esta escribe las
 * tres tablas que sostienen a todas las demas. Mezclarlas pondria la unica
 * escritura de identidad del sistema dentro de la clase que se consulta en cada
 * peticion.
 *
 * <p><b>Las violaciones de unicidad se traducen aqui.</b> Un RUC repetido o un
 * correo cogido no son un fallo del servidor: son la respuesta a lo que el
 * cliente pregunto, y tienen que llegarle dichas con palabras.
 */
@Repository
public class OnboardingRepository {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SUFFIX_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbcTemplate;

    public OnboardingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long createCompany(String legalName, String taxId) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO companies (legal_name, tax_id) VALUES (?, ?) RETURNING company_id
                    """, Long.class, legalName, taxId);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateAccountException(
                    "Ya hay una empresa registrada con el RUC " + taxId + ".");
        }
    }

    /**
     * Crea la tienda y le inventa un codigo.
     *
     * <p>{@code store_code} es unico en toda la base, no por empresa, asi que no
     * puede salir solo del nombre: dos "Tienda Centro" de empresas distintas
     * chocarian. Se compone del nombre normalizado mas un sufijo aleatorio sin
     * caracteres ambiguos, y si aun asi coincidiera, se reintenta.
     */
    public long createStore(long companyId, String name, String address) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return jdbcTemplate.queryForObject("""
                        INSERT INTO stores (company_id, store_code, name, address)
                        VALUES (?, ?, ?, ?) RETURNING store_id
                        """, Long.class, companyId, storeCode(name), name, address);
            } catch (DuplicateKeyException retry) {
                // Codigo repetido: se vuelve a sortear el sufijo.
            }
        }
        throw new IllegalStateException("No se pudo asignar un codigo de tienda libre.");
    }

    /**
     * Crea la persona y su primera membresia.
     *
     * <p>Las dos cosas o ninguna: un usuario sin membresia no podria abrir
     * sesion -no hay tienda con la que entrar- y seria una cuenta fantasma que
     * ademas ocuparia su correo para siempre.
     */
    public long createUser(long companyId, long storeId, String email, String fullName,
                           String passwordHash, String role) {
        long userId;
        try {
            userId = jdbcTemplate.queryForObject("""
                    INSERT INTO users (company_id, store_id, email, full_name, password_hash, role)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING user_id
                    """, Long.class, companyId, storeId, email, fullName, passwordHash, role);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateAccountException(
                    "El correo " + email + " ya tiene cuenta. Entra con el o usa otro.");
        }

        addMembership(userId, storeId, companyId, role);
        return userId;
    }

    /**
     * Pone a alguien a trabajar en una tienda.
     *
     * <p>No comprueba que la tienda sea de su empresa porque no puede fallar:
     * las dos claves foraneas de {@code user_stores} comparten la columna
     * {@code company_id}, asi que una membresia que cruce empresas no se puede
     * insertar. Lo que llega aqui como {@code DataIntegrityViolationException}
     * es exactamente ese intento.
     */
    public void addMembership(long userId, long storeId, long companyId, String role) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO user_stores (user_id, store_id, company_id, role)
                    VALUES (?, ?, ?, ?)
                    """, userId, storeId, companyId, role);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateAccountException("Esa persona ya trabaja en esa tienda.");
        }
    }

    /** La persona de esta empresa que tiene ese correo, si existe. */
    public Optional<Long> findUserInCompany(String email, long companyId) {
        return jdbcTemplate.query("""
                SELECT user_id FROM users WHERE email = ? AND company_id = ? AND is_active
                """, (rs, rowNum) -> rs.getLong("user_id"), email, companyId)
                .stream().findFirst();
    }

    /**
     * Tiendas de la empresa, con cuanta gente trabaja ya en cada una.
     *
     * <p>Se cuenta por membresias y no por {@code users.store_id}: desde sql/13
     * una persona puede estar en varias, y contar por la tienda de origen
     * dejaria a la mitad del equipo sin aparecer en ninguna parte.
     */
    public List<StoreDto> storesOfCompany(long companyId) {
        return jdbcTemplate.query("""
                SELECT s.store_id, s.store_code, s.name, s.address,
                       -- Se cuenta la columna de `users`: una membresia de
                       -- alguien dado de baja deja NULL y no suma.
                       count(u.user_id) AS users
                  FROM stores s
                  LEFT JOIN user_stores us ON us.store_id = s.store_id
                  LEFT JOIN users u ON u.user_id = us.user_id AND u.is_active
                 WHERE s.company_id = ?
                 GROUP BY s.store_id
                 ORDER BY s.created_at
                """, (rs, rowNum) -> new StoreDto(
                        rs.getLong("store_id"),
                        rs.getString("store_code"),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getInt("users")), companyId);
    }

    /**
     * Si esa tienda es de esa empresa.
     *
     * <p>Es la comprobacion que impide dar de alta a alguien en la tienda del
     * vecino conociendo su numero.
     */
    public boolean storeBelongsToCompany(long storeId, long companyId) {
        Integer found = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stores WHERE store_id = ? AND company_id = ?",
                Integer.class, storeId, companyId);
        return found != null && found > 0;
    }

    private static String storeCode(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        if (base.length() > 16) {
            base = base.substring(0, 16).replaceAll("-$", "");
        }
        if (base.isBlank()) {
            base = "TIENDA";
        }

        StringBuilder suffix = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return base + "-" + suffix;
    }
}
