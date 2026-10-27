package com.kardo.onboarding;

import com.kardo.dto.StoreDto;
import com.kardo.exception.DuplicateAccountException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
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

    public long createUser(long companyId, long storeId, String email, String fullName,
                           String passwordHash, String role) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO users (company_id, store_id, email, full_name, password_hash, role)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING user_id
                    """, Long.class, companyId, storeId, email, fullName, passwordHash, role);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateAccountException(
                    "El correo " + email + " ya tiene cuenta. Entra con el o usa otro.");
        }
    }

    /** Tiendas de la empresa, con cuanta gente trabaja ya en cada una. */
    public List<StoreDto> storesOfCompany(long companyId) {
        return jdbcTemplate.query("""
                SELECT s.store_id, s.store_code, s.name, s.address,
                       count(u.user_id) FILTER (WHERE u.is_active) AS users
                  FROM stores s
                  LEFT JOIN users u ON u.store_id = s.store_id
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
