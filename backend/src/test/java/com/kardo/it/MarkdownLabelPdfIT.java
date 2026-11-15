package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.ingest.MarkdownLabelPdfService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * La etiqueta se comprueba leyendola, igual que el informe.
 *
 * <p>Lo que importa de esta hoja no es que exista: es que el precio impreso sea
 * el mismo que cobrara la caja y que el lote venga identificado. Un cartel con
 * el precio de otro lote es peor que no tener cartel.
 */
class MarkdownLabelPdfIT extends PostgresIntegrationTest {

    @Autowired
    private MarkdownLabelPdfService labelService;

    private Fixtures fixtures;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Etiquetas", "21700000040").store("TIENDA-ETQ");
        productId = fixtures.product(storeId, "SKU-ETQ-1", "Jugo de naranja 1L", "3.00", "7.00");
    }

    @Test
    @DisplayName("La etiqueta lleva el precio rebajado, el de lista y el lote")
    void laEtiquetaDiceLosTresDatos() {
        rebajar(fixtures.batch(storeId, productId, "LOT-ETQ-A", LocalDate.now().plusDays(3), 12, "3.00"),
                "4.20");

        String texto = render();

        assertThat(texto)
                .contains("OFERTA POR VENCIMIENTO")
                .contains("Jugo de naranja")
                .contains("S/ 4.20")     // lo que cobrara la caja
                .contains("S/ 7.00")     // el precio tachado
                .contains("LOT-ETQ-A")
                .contains("12 unidades");
    }

    @Test
    @DisplayName("Un lote sin rebaja no genera etiqueta")
    void soloSalenLosQueEstanEnOferta() {
        fixtures.batch(storeId, productId, "LOT-ETQ-B", LocalDate.now().plusDays(3), 5, "3.00");

        assertThat(render()).contains("No hay ningun lote con precio rebajado vigente");
    }

    @Test
    @DisplayName("Un lote agotado deja de imprimirse aunque conserve el precio")
    void elStockMandaSobreLaOferta() {
        long batchId = fixtures.batch(storeId, productId, "LOT-ETQ-C", LocalDate.now().plusDays(4), 6, "3.00");
        rebajar(batchId, "4.50");
        jdbcTemplate.update("UPDATE inventory_batches SET current_qty = 0 WHERE batch_id = ?", batchId);

        assertThat(render()).contains("No hay ningun lote con precio rebajado vigente");
    }

    /** Once etiquetas no caben en una hoja de diez: tiene que abrir la segunda. */
    @Test
    @DisplayName("Mas de diez etiquetas reparten en varias hojas")
    void laRejillaPasaDePagina() {
        for (int i = 0; i < 11; i++) {
            rebajar(fixtures.batch(storeId, productId, "LOT-ETQ-P" + i,
                    LocalDate.now().plusDays(3), 4, "3.00"), "4.00");
        }

        assertThat(paginas()).isEqualTo(2);
    }

    private void rebajar(long batchId, String price) {
        jdbcTemplate.update(
                "UPDATE inventory_batches SET markdown_price = ?::numeric WHERE batch_id = ?",
                price, batchId);
    }

    private String render() {
        var salida = new ByteArrayOutputStream();
        labelService.writeTo(storeId, "Minimarket Etiquetas", salida);
        try (PDDocument document = Loader.loadPDF(salida.toByteArray())) {
            return new PDFTextStripper().getText(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private int paginas() {
        var salida = new ByteArrayOutputStream();
        labelService.writeTo(storeId, "Minimarket Etiquetas", salida);
        try (PDDocument document = Loader.loadPDF(salida.toByteArray())) {
            return document.getNumberOfPages();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
