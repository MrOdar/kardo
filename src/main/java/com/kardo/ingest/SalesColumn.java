package com.kardo.ingest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Columnas de la hoja de ventas y sus alias reales.
 *
 * <p>Son tres, y las tres que faltan son la parte interesante: <b>no hay lote y
 * no hay precio</b>. El lote lo elige el motor FEFO -del mas proximo a vencer
 * al mas lejano- y el precio sale de ese lote, ya con su rebaja si la tiene.
 * Pedirselos a quien llena el Excel deshace las dos cosas que hace el producto:
 * una tienda que teclea el lote a mano acabara vendiendo del equivocado, y una
 * que teclea el precio registrara un margen que no es el que cobro la caja.
 *
 * <p>La fecha es opcional a proposito. Una celda vacia significa "ahora", que es
 * el caso de quien registra la venta al momento; con fecha se puede subir al
 * cerrar el dia, o recuperar una semana atrasada, y es lo que necesita la
 * ventana de 30 dias con la que se calcula la rotacion.
 */
public enum SalesColumn {

    SKU(true, "sku", "codigo", "cod", "codigo producto", "codigo interno", "item", "id producto",
            "producto", "codigo de barras"),

    QUANTITY(true, "cantidad", "unidades", "qty", "cant", "vendidas", "cantidad vendida",
            "unidades vendidas", "salida", "salidas", "cantidad de venta"),

    SALE_DATE(false, "fecha", "fecha venta", "fecha de venta", "f venta", "dia", "fecha salida",
            "fecha de salida");

    private final boolean required;
    private final Set<String> aliases;

    SalesColumn(boolean required, String... aliases) {
        this.required = required;
        this.aliases = List.of(aliases).stream()
                .map(HeaderNormalizer::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean required() {
        return required;
    }

    public Set<String> aliases() {
        return aliases;
    }
}
