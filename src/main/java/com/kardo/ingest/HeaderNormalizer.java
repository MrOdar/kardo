package com.kardo.ingest;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normaliza encabezados antes de compararlos: minusculas, sin tildes, sin
 * puntuacion y con espacios colapsados.
 *
 * <p>Gracias a esto "F. Vencimiento", "FECHA DE VENCIMIENTO " y
 * "fecha_de_vencimiento" convergen a la misma clave.
 */
final class HeaderNormalizer {

    private HeaderNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return withoutAccents
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
