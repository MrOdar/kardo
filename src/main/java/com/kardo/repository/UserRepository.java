package com.kardo.repository;

import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Usuarios que pueden operar una tienda. */
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
     * @param passwordHash hash BCrypt; nunca sale de esta capa hacia la respuesta
     * @param role         OPERATOR o MANAGER, segun el CHECK del DDL
     */
    public record UserAccount(
            long userId,
            long companyId,
            long storeId,
            String email,
            String fullName,
            String passwordHash,
            String role) {

        /** Autoridad de Spring Security equivalente al rol almacenado. */
        public String authority() {
            return "ROLE_" + role;
        }
    }
}
