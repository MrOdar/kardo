package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.ingest.InventoryPdfExportService;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El informe se comprueba <b>releyendolo</b>.
 *
 * <p>Afirmar que el PDF "se genero sin excepciones" no dice nada: un documento
 * valido y vacio tambien cumple eso. Aqui se vuelve a abrir con PDFBox y se
 * busca dentro lo que el contador va a buscar con el dedo —los totales, el
 * criterio de valorizacion, la numeracion de paginas—, que es lo unico que
 * distingue un informe util de un archivo que se abre.
 */
class InventoryPdfExportIT extends PostgresIntegrationTest {

    @Autowired
    private InventoryPdfExportService pdfService;

    private Fixtures fixtures;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Informe", "21500000020").store("TIENDA-PDF");
        productId = fixtures.product(storeId, "SKU-PDF-1", "Queso fresco 500g", "4.00", "10.00");
    }

    @Test
    @DisplayName("El informe lleva el resumen, el lote y los totales")
    void elInformeDiceLoQueTieneQueDecir() {
        fixtures.batch(storeId, productId, "LOT-PDF-A", LocalDate.now().plusDays(30), 10, "4.00");

        String texto = render();

        assertThat(texto)
                .contains("Inventario valorizado")
                .contains("Minimarket Informe")
                .contains("gerente@kardo.pe")
                .contains("SKU-PDF-1")
                .contains("LOT-PDF-A")
                .contains("Queso fresco")
                .contains("S/ 40.00")
                .contains("Totales (1 lotes)");
    }

    /**
     * Es lo que convierte el PDF en un documento y no en una captura: sin el
     * criterio, dos personas pueden leer el mismo numero de forma distinta.
     */
    @Test
    @DisplayName("El pie dice al coste de que se valoriza y numera las paginas")
    void elPieSostieneElDocumento() {
        fixtures.batch(storeId, productId, "LOT-PDF-B", LocalDate.now().plusDays(10), 5, "4.00");

        assertThat(render())
                .contains("Valorizado al costo de cada lote, no al precio de venta")
                .contains("Pagina 1 de 1");
    }

    @Test
    @DisplayName("El valor en riesgo separa lo que vence pronto de lo que no")
    void elResumenSeparaElRiesgo() {
        fixtures.batch(storeId, productId, "LOT-RIESGO", LocalDate.now().plusDays(2), 10, "4.00");
        fixtures.batch(storeId, productId, "LOT-TRANQUILO", LocalDate.now().plusDays(90), 10, "4.00");

        String texto = render();

        // 80.00 de inventario, de los cuales 40.00 vencen dentro de cinco dias
        assertThat(texto).contains("S/ 80.00").contains("S/ 40.00");
        assertThat(texto).contains("Valor en riesgo");
    }

    @Test
    @DisplayName("Un inventario vacio produce un informe valido, no un archivo roto")
    void inventarioVacio() {
        long vacia = fixtures.company("Minimarket Vacio", "21500000021").store("TIENDA-VAC");

        var salida = new ByteArrayOutputStream();
        pdfService.writeTo(vacia, "Minimarket Vacio", "gerente@kardo.pe", salida);

        assertThat(salida.size()).isPositive();
        assertThat(leer(salida)).contains("Totales (0 lotes)").contains("S/ 0.00");
    }

    /**
     * Un nombre con comillas tipograficas no puede tumbar el informe: las
     * fuentes estandar usan WinAnsi y no saben escribirlas.
     */
    @Test
    @DisplayName("Un producto con caracteres raros no rompe el informe")
    void caracteresQueWinAnsiNoConoce() {
        long raro = fixtures.product(storeId, "SKU-RARO",
                "Leche “La Vaquita” — edicion 1º", "4.00", "10.00");
        fixtures.batch(storeId, raro, "LOT-RARO", LocalDate.now().plusDays(20), 3, "4.00");

        String texto = render();

        assertThat(texto).contains("SKU-RARO");
        assertThat(texto).contains("Leche \"La Vaquita\"");
    }

    @Test
    @DisplayName("El informe de una tienda no lista lotes de otra")
    void aislamientoPorTienda() {
        long otra = fixtures.company("Minimarket Vecino PDF", "21500000022").store("TIENDA-VPD");
        long ajeno = fixtures.product(otra, "SKU-AJENO-PDF", "Producto del vecino", "4.00", "10.00");
        fixtures.batch(otra, ajeno, "LOT-AJENO-PDF", LocalDate.now().plusDays(5), 10, "4.00");

        fixtures.batch(storeId, productId, "LOT-PROPIO-PDF", LocalDate.now().plusDays(5), 10, "4.00");

        assertThat(render())
                .contains("LOT-PROPIO-PDF")
                .doesNotContain("LOT-AJENO-PDF");
    }

    /** Muchas filas deben repartirse en paginas, con cabecera en cada una. */
    @Test
    @DisplayName("Un inventario largo se pagina y repite la cabecera")
    void sePagina() {
        for (int i = 0; i < 80; i++) {
            fixtures.batch(storeId, productId, "LOT-P-" + i, LocalDate.now().plusDays(30 + i), 2, "4.00");
        }

        String texto = render();

        // No se fija el numero exacto de paginas: depende del alto de fila, y
        // atarlo aqui convertiria un ajuste tipografico en una prueba rota.
        var paginas = java.util.regex.Pattern.compile("Pagina (\\d+) de (\\d+)").matcher(texto);
        assertThat(paginas.find()).isTrue();
        assertThat(Integer.parseInt(paginas.group(2))).isGreaterThan(1);

        // La cabecera de la tabla se repite en cada pagina
        assertThat(texto.split("Producto", -1).length - 1).isGreaterThanOrEqualTo(2);
    }

    private String render() {
        var salida = new ByteArrayOutputStream();
        pdfService.writeTo(storeId, "Minimarket Informe", "gerente@kardo.pe", salida);
        return leer(salida);
    }

    private static String leer(ByteArrayOutputStream salida) {
        try (PDDocument documento = Loader.loadPDF(salida.toByteArray())) {
            return new PDFTextStripper().getText(documento);
        } catch (Exception ex) {
            throw new IllegalStateException("El PDF generado no se puede volver a abrir", ex);
        }
    }
}
