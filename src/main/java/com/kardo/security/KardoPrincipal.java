package com.kardo.security;

import java.util.List;

/**
 * Identidad autenticada.
 *
 * <p>Lleva la tienda consigo porque esa es la correccion de fondo: la tienda
 * sobre la que se escribe es un atributo del usuario, no un parametro que el
 * cliente pueda elegir. Todo endpoint que antes recibia {@code store_id} lo
 * toma ahora de aqui.
 *
 * @param email   identifica al operario en la auditoria
 * @param storeId unica tienda que este usuario puede leer y modificar
 * @param roles   autoridades ya prefijadas con {@code ROLE_}
 */
public record KardoPrincipal(String email, long storeId, List<String> roles) {

    public boolean isManager() {
        return roles.contains("ROLE_MANAGER");
    }
}
