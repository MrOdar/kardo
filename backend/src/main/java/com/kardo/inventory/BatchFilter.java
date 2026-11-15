package com.kardo.inventory;

import java.util.Locale;

/**
 * Filtros del listado de lotes, en los mismos tramos que el semaforo FEFO.
 *
 * <p>Cada valor lleva su propio predicado SQL en lugar de componer cadenas en el
 * repositorio: el listado acepta texto del operario, y mantener el SQL fuera de
 * su alcance es lo que impide que una busqueda se convierta en una inyeccion.
 */
public enum BatchFilter {

    /** Todo lo que tenga existencias, vendible o no. */
    TODOS("b.current_qty > 0 OR b.quarantined_qty > 0"),

    ACTIVOS("b.batch_status = 'ACTIVE' AND b.current_qty > 0 AND b.expiration_date >= CURRENT_DATE"),

    /** Alerta roja del semaforo. */
    POR_VENCER_48H("b.current_qty > 0 AND b.expiration_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 2"),

    /** Alerta amarilla. */
    POR_VENCER_5D("b.current_qty > 0 AND b.expiration_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 5"),

    VENCIDOS("b.expiration_date < CURRENT_DATE AND (b.current_qty > 0 OR b.quarantined_qty > 0)"),

    /** Lotes con unidades inmovilizadas esperando al gerente. */
    EN_CUARENTENA("b.quarantined_qty > 0");

    private final String predicate;

    BatchFilter(String predicate) {
        this.predicate = predicate;
    }

    String predicate() {
        return predicate;
    }

    /** Un filtro desconocido no es un error del operario: se le muestra todo. */
    public static BatchFilter from(String raw) {
        if (raw == null || raw.isBlank()) {
            return TODOS;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TODOS;
        }
    }
}
