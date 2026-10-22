package com.kardo.repository;

import com.kardo.config.MatchingProperties;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entity matching de las lineas de la guia contra el catalogo.
 *
 * <p>Resuelve todas las lineas en un unico viaje de red. Dos detalles hacen que
 * la consulta use el indice GIN en lugar de un Seq Scan:
 *
 * <ul>
 *   <li>El operador va conmutado, con la columna indexada a la izquierda
 *       ({@code name %> item.ocr_text}). Al reves PostgreSQL descarta el indice.</li>
 *   <li>El {@code LEFT JOIN LATERAL} preserva las lineas sin match, que vuelven
 *       con {@code product_id = null}. Un {@code CROSS JOIN} las perderia en
 *       silencio y el operario nunca veria los productos nuevos.</li>
 * </ul>
 */
@Repository
public class SkuMatchingRepository {

    private static final String MATCH_LINES = """
            SELECT item.ocr_line_id,
                   p.product_id,
                   p.name,
                   word_similarity(item.ocr_text, p.name) AS score
            FROM UNNEST(?::int[], ?::text[]) AS item(ocr_line_id, ocr_text)
            LEFT JOIN LATERAL (
                SELECT product_id, name
                FROM products
                WHERE company_id = ?
                  AND name %> item.ocr_text
                ORDER BY word_similarity(item.ocr_text, name) DESC
                LIMIT 1
            ) p ON TRUE
            ORDER BY item.ocr_line_id
            """;

    /**
     * El operador {@code %>} no compara contra el umbral de la aplicacion sino
     * contra el GUC {@code pg_trgm.word_similarity_threshold}, que por defecto
     * vale 0.6. Con ese valor "CERV PILSEN 650" frente a "Cerveza Pilsen Callao
     * Botella 650ml" (similitud real 0.55) se descarta en el WHERE y la linea
     * llega como producto nuevo, aunque el catalogo si lo tiene.
     *
     * <p>Por eso se alinea el GUC con {@code kardo.matching.min-score} en la
     * misma transaccion ({@code is_local = true}, de modo que no contamina otras
     * sesiones del pool). El filtro definitivo sigue aplicandose en el servicio.
     */
    private static final String ALIGN_TRIGRAM_THRESHOLD =
            "SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)";

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties matchingProperties;

    public SkuMatchingRepository(JdbcTemplate jdbcTemplate, MatchingProperties matchingProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchingProperties = matchingProperties;
    }

    /**
     * Cruza todas las lineas de la guia contra el catalogo.
     *
     * <p>Transaccional de solo lectura porque el ajuste del umbral de trigramas
     * es local a la transaccion. La llamada a la IA queda deliberadamente fuera:
     * no tiene sentido retener una de las 5 conexiones del pool durante los 20 s
     * que puede tardar Gemini.
     *
     * @param companyId acota el catalogo a la compania dueña de la tienda
     * @param lineIds   identificadores de linea, en paralelo con los textos
     * @param ocrTexts  descripciones literales leidas del documento
     * @return un resultado por linea, con {@code productId} nulo cuando no hubo
     *         ningun candidato por encima del umbral
     */
    @Transactional(readOnly = true)
    public List<SkuMatch> matchLines(long companyId, List<Integer> lineIds, List<String> ocrTexts) {
        if (lineIds.isEmpty()) {
            return List.of();
        }
        if (lineIds.size() != ocrTexts.size()) {
            throw new IllegalArgumentException("Los arreglos paralelos deben tener el mismo tamaño.");
        }

        // Debe ejecutarse en la misma transaccion que la consulta para que el
        // ajuste local siga vigente; de ahi el @Transactional de este metodo.
        jdbcTemplate.queryForObject(ALIGN_TRIGRAM_THRESHOLD, String.class,
                String.valueOf(matchingProperties.minScore()));

        return jdbcTemplate.query(MATCH_LINES, ps -> {
            Array ids = ps.getConnection().createArrayOf("integer", lineIds.toArray(Integer[]::new));
            Array texts = ps.getConnection().createArrayOf("text", ocrTexts.toArray(String[]::new));
            ps.setArray(1, ids);
            ps.setArray(2, texts);
            ps.setLong(3, companyId);
        }, rs -> {
            List<SkuMatch> matches = new ArrayList<>();
            while (rs.next()) {
                int ocrLineId = rs.getInt("ocr_line_id");

                // wasNull() se refiere SIEMPRE a la ultima columna leida, asi que
                // cada valor nullable se consulta justo despues de leerlo.
                long productId = rs.getLong("product_id");
                Long matchedProductId = rs.wasNull() ? null : productId;

                String name = rs.getString("name");

                double score = rs.getDouble("score");
                Double matchedScore = rs.wasNull() ? null : score;

                matches.add(new SkuMatch(ocrLineId, matchedProductId, name, matchedScore));
            }
            return matches;
        });
    }

    /**
     * @param productId nulo cuando la linea no tiene candidato
     * @param score     {@code word_similarity} del candidato, entre 0 y 1
     */
    public record SkuMatch(int ocrLineId, Long productId, String productName, Double score) {
    }
}
