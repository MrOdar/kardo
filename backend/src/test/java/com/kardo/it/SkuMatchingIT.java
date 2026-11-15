package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.repository.SkuMatchingRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Entity matching de SKUs con el indice GIN y el umbral de trigramas.
 *
 * <p>Protege las dos decisiones del Reto 4 que son faciles de romper sin notarlo:
 * el orden de los operandos (`name %> texto`) y la alineacion del GUC
 * `pg_trgm.word_similarity_threshold` con el umbral de la aplicacion. Con el
 * valor de fabrica (0.6) el caso insignia del documento se descartaba.
 */
class SkuMatchingIT extends PostgresIntegrationTest {

    @Autowired
    private SkuMatchingRepository matchingRepository;

    private long companyId;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        long storeId = fixtures.company("Minimarket Matching", "20500000005").store("TIENDA-X");
        fixtures.product(storeId, "SKU-CERV", "Cerveza Pilsen Callao Botella 650ml", "4.50", "8.10");
        fixtures.product(storeId, "SKU-LECHE", "Leche Gloria Evaporada 400g", "3.20", "4.80");
        fixtures.product(storeId, "SKU-GALLETA", "Galleta Rellena Chocolate Paquete x6", "2.10", "3.50");

        companyId = jdbcTemplate.queryForObject(
                "SELECT company_id FROM stores WHERE store_id = ?", Long.class, storeId);
    }

    @Test
    @DisplayName("El caso insignia del spec: 'CERV PILSEN 650' encuentra su producto")
    void casoInsignia() {
        var matches = matchingRepository.matchLines(companyId, List.of(1), List.of("CERV PILSEN 650"));

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.productName()).isEqualTo("Cerveza Pilsen Callao Botella 650ml");
            // 0.55: por encima del umbral de la aplicacion (0.35) y por debajo
            // del valor de fabrica de pg_trgm (0.6), que es lo que lo descartaba
            assertThat(match.score()).isBetween(0.5, 0.6);
        });
    }

    @Test
    @DisplayName("Resuelve todas las lineas de la guia en un solo viaje, en orden")
    void variasLineasDeUnaVez() {
        var matches = matchingRepository.matchLines(
                companyId,
                List.of(1, 2, 3),
                List.of("LECHE GLORIA 400", "GALLETA RELL CHOC X6", "CERV PILSEN 650"));

        assertThat(matches).extracting(SkuMatchingRepository.SkuMatch::ocrLineId)
                .containsExactly(1, 2, 3);
        assertThat(matches).extracting(SkuMatchingRepository.SkuMatch::productName)
                .containsExactly(
                        "Leche Gloria Evaporada 400g",
                        "Galleta Rellena Chocolate Paquete x6",
                        "Cerveza Pilsen Callao Botella 650ml");
    }

    @Test
    @DisplayName("Un producto nuevo vuelve sin candidato, no con el mas parecido")
    void productoNuevoNoSeFuerza() {
        var matches = matchingRepository.matchLines(
                companyId, List.of(1, 2), List.of("CERV PILSEN 650", "TORNILLO INOXIDABLE M8"));

        assertThat(matches.get(0).productId()).isNotNull();

        // El LEFT JOIN LATERAL conserva la linea sin match en lugar de perderla
        assertThat(matches.get(1).productId()).isNull();
        assertThat(matches.get(1).score()).isNull();
    }

    @Test
    @DisplayName("El catalogo de otra compania no contamina el matching")
    void aislamientoPorCompania() {
        Fixtures otra = new Fixtures(jdbcTemplate);
        long otraTienda = otra.company("Competencia Matching", "20600000006").store("TIENDA-Y");
        otra.product(otraTienda, "SKU-CERV", "Cerveza Pilsen Callao Botella 650ml", "1.00", "2.00");

        var matches = matchingRepository.matchLines(companyId, List.of(1), List.of("CERV PILSEN 650"));

        Long encontrado = matches.get(0).productId();
        Long deLaOtra = jdbcTemplate.queryForObject("""
                SELECT p.product_id FROM products p
                JOIN stores s ON s.company_id = p.company_id
                WHERE s.store_id = ? AND p.sku = 'SKU-CERV'
                """, Long.class, otraTienda);

        assertThat(encontrado).isNotEqualTo(deLaOtra);
    }

    @Test
    @DisplayName("Una guia sin lineas no consulta nada")
    void guiaVacia() {
        assertThat(matchingRepository.matchLines(companyId, List.of(), List.of())).isEmpty();
    }
}
