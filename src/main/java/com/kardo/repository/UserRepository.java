package com.kardo.repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Usuarios y las tiendas en las que trabajan.
 *
 * <p>Desde sql/13 una persona puede estar en varias, con un rol distinto en
 * cada una, asi que la identidad se lee en dos pasos: la cuenta -quien es y su
 * contraseña- y las membresias -donde trabaja y como-.
 */
@Repository
public class UserRepository {

    private static final String FIND_BY_EMAIL = """
            SELECT user_id, company_id, store_id, email, full_name, password_hash, role
            FROM users
            WHERE email = ? AND is_active
            """;

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @param email se normaliza a minusculas: el correo no distingue mayusculas */
    public Optional<UserAccount> findActiveByEmail(String email) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_EMAIL,
                    (rs, rowNum) -> new UserAccount(
                            rs.getLong("user_id"),
                            rs.getLong("company_id"),
                            rs.getLong("store_id"),
                            rs.getString("email"),
                            rs.getString("full_name"),
                            rs.getString("password_hash"),
                            rs.getString("role")),
                    email.trim().toLowerCase(Locale.ROOT)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Deja constancia del ultimo acceso; es la base del rastro de auditoria. */
    public void touchLastLogin(long userId) {
        jdbcTemplate.update("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE user_id = ?", userId);
    }

    /**
     * Donde trabaja esta persona y con que rol en cada sitio.
     *
     * <p>Sale de {@code user_stores}, que es quien manda desde sql/13;
     * {@code users.store_id} se conserva solo como tienda de origen, es decir,
     * con cual se abre sesion por defecto.
     *
     * <p>Orden: la tienda de origen primero y el resto por antiguedad, para que
     * el selector de la interfaz no baile entre dos peticiones.
     */
    public List<StoreMembership> findMemberships(long userId) {
        try {
            return membershipsOf(userId);
        } catch (BadSqlGrammarException ex) {
            // Sin la tabla no hay login, y un 500 a secas no dice por que. Esto
            // convierte un despliegue hecho en el orden equivocado en una
            // instruccion, igual que el mensaje del usuario demo ausente.
            throw new IllegalStateException(
                    "Falta la tabla user_stores: ejecuta sql/13_user_stores.sql en la base.", ex);
        }
    }

    private List<StoreMembership> membershipsOf(long userId) {
        return jdbcTemplate.query("""
                SELECT us.store_id, s.name, us.role,
                       (us.store_id = u.store_id) AS es_origen
                  FROM user_stores us
                  JOIN stores s USING (store_id)
                  JOIN users u ON u.user_id = us.user_id
                 WHERE us.user_id = ?
                 ORDER BY es_origen DESC, us.created_at, us.store_id
                """, (rs, rowNum) -> new StoreMembership(
                        rs.getLong("store_id"),
                        rs.getString("name"),
                        rs.getString("role")), userId);
    }

    /**
     * El rol de esta persona en esa tienda, si trabaja en ella.
     *
     * <p>Es la comprobacion del cambio de tienda: devolver vacio significa «ahi
     * no trabajas», y es lo unico que impide que alguien se emita un token de
     * una tienda que no es suya.
     */
    public Optional<StoreMembership> findMembership(long userId, long storeId) {
        return findMemberships(userId).stream()
                .filter(membership -> membership.storeId() == storeId)
                .findFirst();
    }

    /**
     * @param passwordHash hash BCrypt; nunca sale de esta capa hacia la respuesta
     * @param storeId      tienda de origen: con cual se abre sesion por defecto
     * @param role         rol en la tienda de origen; el que manda en cada
     *                     tienda vive en {@link StoreMembership}
     */
    public record UserAccount(
            long userId,
            long companyId,
            long storeId,
            String email,
            String fullName,
            String passwordHash,
            String role) {
    }

    /** Una tienda en la que alguien trabaja, con el rol que tiene ahi. */
    public record StoreMembership(long storeId, String storeName, String role) {

        /** Autoridad de Spring Security equivalente al rol en esta tienda. */
        public String authority() {
            return "ROLE_" + role;
        }
    }
}
