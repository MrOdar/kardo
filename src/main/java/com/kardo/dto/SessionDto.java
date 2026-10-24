package com.kardo.dto;

/**
 * Sesion abierta.
 *
 * <p>Incluye la tienda para que la interfaz pueda mostrarla, pero el backend no
 * confia en lo que el cliente haga con este dato: la tienda autoritativa viaja
 * firmada dentro del token.
 *
 * @param role OPERATOR o MANAGER
 */
public record SessionDto(
        String token,
        long expiresInSeconds,
        String email,
        String fullName,
        String role,
        long storeId,
        String storeName) {
}
