package com.kardo.ingest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Columnas canonicas de la plantilla de inventario y sus alias reales.
 *
 * <p>Cada minimarket exporta su Excel con encabezados distintos ("Vence",
 * "F. Vencimiento", "FECHA DE VENCIMIENTO"), asi que el mapeo se hace por
 * alias normalizados en vez de por posicion fija: una columna movida o
 * renombrada no rompe la ingesta.
 */
public enum InventoryColumn {

    SKU(true, "sku", "codigo", "cod", "codigo producto", "codigo interno", "item", "id producto"),

    BARCODE(false, "codigo de barras", "codigo barras", "barras", "ean", "ean13", "barcode", "upc"),

    NAME(true, "nombre", "producto", "descripcion", "detalle", "articulo", "nombre producto"),

    CATEGORY(true, "categoria", "familia", "linea", "rubro", "grupo", "tipo"),

    UNIT_OF_MEASURE(false, "unidad", "um", "unidad de medida", "medida", "presentacion", "uom"),

    UNIT_COST(true, "costo", "costo unitario", "precio compra", "precio de compra", "costo compra", "p compra"),

    RETAIL_PRICE(true, "precio", "precio venta", "precio de venta", "pvp", "precio publico", "p venta"),

    BATCH_CODE(false, "lote", "codigo de lote", "codigo lote", "nro lote", "numero de lote", "batch"),

    MANUFACTURING_DATE(false, "fecha fabricacion", "fecha de fabricacion", "f fabricacion", "fabricacion",
            "produccion", "fecha produccion", "elaboracion", "fecha elaboracion"),

    EXPIRATION_DATE(true, "fecha vencimiento", "fecha de vencimiento", "f vencimiento", "vencimiento", "vence",
            "caducidad", "fecha caducidad", "expira", "fecha expiracion"),

    INITIAL_QTY(true, "cantidad", "cantidad inicial", "stock", "stock inicial", "existencias", "saldo", "qty",
            "unidades");

    private final boolean required;
    private final Set<String> aliases;

    InventoryColumn(boolean required, String... aliases) {
        this.required = required;
        this.aliases = List.of(aliases).stream()
                .map(HeaderNormalizer::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Una columna requerida ausente invalida la plantilla completa (HTTP 422). */
    public boolean isRequired() {
        return required;
    }

    public Set<String> aliases() {
        return aliases;
    }

    /** Nombre canonico usado como clave en {@code QuarantineRowDto.originalValues}. */
    public String canonicalName() {
        return name().toLowerCase();
    }
}
