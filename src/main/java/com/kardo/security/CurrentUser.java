package com.kardo.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acceso al usuario de la peticion en curso.
 *
 * <p>Un unico punto de lectura del {@code SecurityContext}: si manana cambia la
 * forma de autenticar, los controladores no se enteran.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @return el usuario autenticado
     * @throws IllegalStateException si no hay ninguno, lo que significa que la
     *                               cadena de filtros dejo pasar una peticion que
     *                               no debia llegar al controlador
     */
    public static KardoPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof KardoPrincipal principal)) {
            throw new IllegalStateException("No hay un usuario autenticado en el contexto de seguridad.");
        }
        return principal;
    }

    /** Tienda sobre la que este usuario puede operar. */
    public static long storeId() {
        return require().storeId();
    }
}
