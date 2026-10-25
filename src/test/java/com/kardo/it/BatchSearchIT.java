package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.dto.BatchRowDto;
import com.kardo.inventory.BatchFilter;
import com.kardo.inventory.InventoryBatchService;
import com.kardo.waste.WasteService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Buscador y filtros de la pantalla de inventario.
 *
 * <p>Los tramos del filtro son los mismos que pinta el semaforo FEFO, asi que
 * una discrepancia entre ambos confundiria al operario: estas pruebas fijan esa
 * correspondencia.
 */
class BatchSearchIT extends PostgresIntegrationTest {

    @Autowired
    private InventoryBatchService batchService;

    @Autowired
    private WasteService wasteService;

    private long storeId;
    private long loteYogurt;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Buscador", "20900000009").store("TIENDA-B");

        long leche = fixtures.product(storeId, "SKU-LECHE-B", "Leche Entera UHT 1L", "3.80", "5.20");
        long yogurt = fixtures.product(storeId, "SKU-YOG-B", "Yogurt Fresa Botella 1L", "4.50", "6.80");

        fixtures.batch(storeId, leche, "LOTE-MANANA", LocalDate.now().plusDays(1), 10, "3.80");
        loteYogurt = fixtures.batch(storeId, yogurt, "LOTE-SEMANA", LocalDate.now().plusDays(4), 20, "4.50");
        fixtures.batch(storeId, leche, "LOTE-LEJANO", LocalDate.now().plusDays(60), 30, "3.80");
        fixtures.batch(storeId, yogurt, "LOTE-PASADO", LocalDate.now().minusDays(3), 5, "4.50");
    }

    @Test
    @DisplayName("Sin filtro devuelve todo lo que tiene existencias, ordenado por vencimiento")
    void ordenPorVencimiento() {
        var lotes = batchService.search(storeId, null, BatchFilter.TODOS, 100);

        assertThat(lotes).extracting(BatchRowDto::batchCode)
                .containsExactly("LOTE-PASADO", "LOTE-MANANA", "LOTE-SEMANA", "LOTE-LEJANO");
    }

    @Test
    @DisplayName("El tramo de 48 horas es el mismo que la alerta roja del semaforo")
    void alertaRoja() {
        var lotes = batchService.search(storeId, null, BatchFilter.POR_VENCER_48H, 100);

        assertThat(lotes).extracting(BatchRowDto::batchCode).containsExactly("LOTE-MANANA");
        assertThat(lotes.get(0).daysToExpiry()).isEqualTo(1);
    }

    @Test
    @DisplayName("El tramo de 5 dias incluye al de 48 horas")
    void alertaAmarillaIncluyeLaRoja() {
        var lotes = batchService.search(storeId, null, BatchFilter.POR_VENCER_5D, 100);

        assertThat(lotes).extracting(BatchRowDto::batchCode)
                .containsExactly("LOTE-MANANA", "LOTE-SEMANA");
    }

    @Test
    @DisplayName("Los vencidos se listan aparte, no se esconden")
    void vencidos() {
        var lotes = batchService.search(storeId, null, BatchFilter.VENCIDOS, 100);

        assertThat(lotes).extracting(BatchRowDto::batchCode).containsExactly("LOTE-PASADO");
        assertThat(lotes.get(0).daysToExpiry()).isNegative();
    }

    @Test
    @DisplayName("Busca por nombre, por SKU y por lote impreso")
    void buscaPorLosTresIdentificadores() {
        assertThat(batchService.search(storeId, "yogurt", BatchFilter.TODOS, 100))
                .extracting(BatchRowDto::batchCode)
                .containsExactly("LOTE-PASADO", "LOTE-SEMANA");

        assertThat(batchService.search(storeId, "SKU-LECHE-B", BatchFilter.TODOS, 100))
                .extracting(BatchRowDto::batchCode)
                .containsExactly("LOTE-MANANA", "LOTE-LEJANO");

        assertThat(batchService.search(storeId, "LOTE-LEJANO", BatchFilter.TODOS, 100))
                .singleElement()
                .extracting(BatchRowDto::productName)
                .isEqualTo("Leche Entera UHT 1L");
    }

    @Test
    @DisplayName("La busqueda no distingue mayusculas ni exige la palabra completa")
    void busquedaTolerante() {
        assertThat(batchService.search(storeId, "LECHE ENTERA", BatchFilter.TODOS, 100)).hasSize(2);
        assertThat(batchService.search(storeId, "fresa", BatchFilter.TODOS, 100)).hasSize(2);
        assertThat(batchService.search(storeId, "inexistente", BatchFilter.TODOS, 100)).isEmpty();
    }

    @Test
    @DisplayName("El filtro de cuarentena muestra lo que espera al gerente")
    void enCuarentena() {
        wasteService.report(storeId, loteYogurt, 6, "DAMAGE", "operario@kardo.pe");

        var lotes = batchService.search(storeId, null, BatchFilter.EN_CUARENTENA, 100);

        assertThat(lotes).singleElement().satisfies(lote -> {
            assertThat(lote.batchCode()).isEqualTo("LOTE-SEMANA");
            assertThat(lote.quarantinedQty()).isEqualTo(6);
            assertThat(lote.currentQty()).isEqualTo(14);
        });
    }

    @Test
    @DisplayName("Un filtro desconocido no rompe la pantalla: muestra todo")
    void filtroDesconocido() {
        assertThat(BatchFilter.from("loquesea")).isEqualTo(BatchFilter.TODOS);
        assertThat(BatchFilter.from(null)).isEqualTo(BatchFilter.TODOS);
        assertThat(BatchFilter.from("por_vencer_48h")).isEqualTo(BatchFilter.POR_VENCER_48H);
    }

    @Test
    @DisplayName("Solo lista lotes de la tienda del usuario")
    void aislamientoPorTienda() {
        Fixtures otra = new Fixtures(jdbcTemplate);
        long otraTienda = otra.company("Ajena Buscador", "21000000010").store("TIENDA-C");
        long producto = otra.product(otraTienda, "SKU-AJENO-B", "Leche Entera UHT 1L", "1.00", "2.00");
        otra.batch(otraTienda, producto, "LOTE-AJENO", LocalDate.now().plusDays(2), 10, "1.00");

        assertThat(batchService.search(storeId, "Leche", BatchFilter.TODOS, 100))
                .extracting(BatchRowDto::batchCode)
                .doesNotContain("LOTE-AJENO");
    }
}
