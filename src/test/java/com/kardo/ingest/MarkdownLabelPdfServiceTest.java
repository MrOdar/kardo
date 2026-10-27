package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * El dibujo de la etiqueta, sin base de datos.
 *
 * <p>Lo que aqui se comprueba no es el SQL -de eso se ocupa
 * {@code MarkdownLabelPdfIT}- sino lo unico que un cambio de maquetacion puede
 * romper en silencio: que los tres datos que hacen util un cartel sigan dentro
 * del papel. Como el servicio solo necesita un {@code JdbcTemplate} para leer
 * las filas, un doble basta, y la prueba corre en cualquier maquina aunque no
 * haya Docker.
 */
class MarkdownLabelPdfServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MarkdownLabelPdfService service = new MarkdownLabelPdfService(jdbcTemplate);

    @Test
    @DisplayName("La etiqueta lleva el precio nuevo, el viejo y el lote")
    void losTresDatosDelCartel() {
        devuelve(fila("Yogurt griego natural 500g", "SKU-YOG-GR", "LOT-YG-77",
                LocalDate.of(2026, 11, 2), "9.90", "6.90", 14));

        String texto = render();

        assertThat(texto)
                .contains("OFERTA POR VENCIMIENTO")
                .contains("Yogurt griego natural")
                .contains("S/ 6.90")
                .contains("S/ 9.90")
                .contains("LOT-YG-77")
                .contains("SKU-YOG-GR")
                .contains("02/11/2026")
                .contains("14 unidades");
    }

    /**
     * Un nombre con comillas tipograficas tumbaria el documento entero: las
     * fuentes estandar usan WinAnsi y no saben escribirlas.
     */
    @Test
    @DisplayName("Un nombre con caracteres que WinAnsi no conoce no rompe la hoja")
    void caracteresRaros() {
        devuelve(fila("Leche “La Vaquita” — 1º", "SKU-RARO", "LOT-RARO",
                LocalDate.of(2026, 12, 1), "7.00", "5.00", 3));

        assertThat(render()).contains("Leche \"La Vaquita\"");
    }

    @Test
    @DisplayName("Un nombre larguisimo se recorta en vez de salirse de la etiqueta")
    void nombreQueNoCabe() {
        devuelve(fila("Pack familiar de galletas rellenas de chocolate con leche edicion especial",
                "SKU-LARGO", "LOT-LARGO", LocalDate.of(2026, 12, 1), "12.00", "9.00", 2));

        assertThat(render()).contains("...").contains("LOT-LARGO");
    }

    @Test
    @DisplayName("Sin lotes en oferta, la hoja explica por que esta vacia")
    void hojaVacia() {
        devuelve();

        assertThat(render())
                .contains("No hay ningun lote con precio rebajado vigente")
                .contains("Que rebajar hoy");
    }

    @Test
    @DisplayName("Once etiquetas no caben en una hoja de diez")
    void laRejillaPasaDePagina() {
        Object[][] filas = new Object[11][];
        for (int i = 0; i < filas.length; i++) {
            filas[i] = fila("Producto " + i, "SKU-" + i, "LOT-" + i,
                    LocalDate.of(2026, 12, 1), "5.00", "3.50", 4);
        }
        devuelve(filas);

        assertThat(paginas()).isEqualTo(2);
    }

    // ------------------------------------------------------------------

    private Object[] fila(String name, String sku, String batchCode, LocalDate expiration,
                          String retail, String markdown, int qty) {
        return new Object[] {name, sku, batchCode, expiration,
                new BigDecimal(retail), new BigDecimal(markdown), qty};
    }

    /**
     * Hace que el doble aplique el {@code RowMapper} real sobre filas
     * inventadas: asi la prueba cubre tambien el mapeo, que es donde se cuela
     * un nombre de columna mal escrito.
     */
    @SuppressWarnings("unchecked")
    private void devuelve(Object[]... filas) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    List<Object> mapeadas = new ArrayList<>();
                    for (Object[] fila : filas) {
                        mapeadas.add(mapper.mapRow(resultSet(fila), 0));
                    }
                    return mapeadas;
                });
    }

    private ResultSet resultSet(Object[] fila) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("name")).thenReturn((String) fila[0]);
        when(rs.getString("sku")).thenReturn((String) fila[1]);
        when(rs.getString("batch_code")).thenReturn((String) fila[2]);
        when(rs.getObject("expiration_date", LocalDate.class)).thenReturn((LocalDate) fila[3]);
        when(rs.getBigDecimal("retail_price")).thenReturn((BigDecimal) fila[4]);
        when(rs.getBigDecimal("markdown_price")).thenReturn((BigDecimal) fila[5]);
        when(rs.getInt("current_qty")).thenReturn((Integer) fila[6]);
        return rs;
    }

    private String render() {
        try (PDDocument document = Loader.loadPDF(bytes())) {
            return new PDFTextStripper().getText(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private int paginas() {
        try (PDDocument document = Loader.loadPDF(bytes())) {
            return document.getNumberOfPages();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private byte[] bytes() {
        var salida = new ByteArrayOutputStream();
        service.writeTo(999L, "Tienda de pruebas", salida);
        return salida.toByteArray();
    }
}
