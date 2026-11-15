package com.kardo.ingest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Etiquetas de rebaja para el anaquel.
 *
 * <p><b>Que hueco cierra.</b> Entre decidir la rebaja y que el cliente la vea
 * hay un paso que hasta ahora quedaba fuera del sistema: escribir el precio
 * nuevo en un cartel. Hacerlo a mano es donde se pierde la trazabilidad -el
 * precio del cartel deja de ser el precio del lote- y donde se cuela el error
 * que luego nadie sabe explicar. Esta hoja sale de la misma columna
 * {@code inventory_batches.markdown_price} que cobra la venta FEFO, de modo que
 * el cartel y la caja no pueden discrepar.
 *
 * <p>Lleva el codigo de lote impreso a proposito: el precio rebajado es del
 * lote, no del producto, y sin el codigo nadie puede comprobar a cual de los
 * dos paquetes iguales del anaquel corresponde la oferta.
 */
@Service
public class MarkdownLabelPdfService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownLabelPdfService.class);

    private static final String LABELS = """
            SELECT p.name,
                   p.sku,
                   p.retail_price,
                   b.batch_code,
                   b.expiration_date,
                   b.markdown_price,
                   b.current_qty
              FROM inventory_batches b
              JOIN products p USING (product_id)
             WHERE b.store_id = ?
               AND b.markdown_price IS NOT NULL
               AND b.current_qty > 0
             ORDER BY b.expiration_date ASC, p.name
            """;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    // Rejilla de 2 x 5 en A4: etiquetas grandes, que es lo que se lee desde el
    // pasillo. Caben diez por hoja y se cortan por las lineas de guia.
    private static final int COLUMNS = 2;
    private static final int ROWS = 5;
    private static final float MARGIN = 28f;
    private static final float LABEL_WIDTH = (PDRectangle.A4.getWidth() - 2 * MARGIN) / COLUMNS;
    private static final float LABEL_HEIGHT = (PDRectangle.A4.getHeight() - 2 * MARGIN) / ROWS;
    private static final float PAD = 14f;

    private final JdbcTemplate jdbcTemplate;

    public MarkdownLabelPdfService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public void writeTo(long storeId, String storeName, OutputStream output) {
        List<Label> labels = jdbcTemplate.query(LABELS, (rs, rowNum) -> new Label(
                rs.getString("name"),
                rs.getString("sku"),
                rs.getString("batch_code"),
                rs.getObject("expiration_date", LocalDate.class),
                rs.getBigDecimal("retail_price"),
                rs.getBigDecimal("markdown_price"),
                rs.getInt("current_qty")), storeId);

        try (PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            if (labels.isEmpty()) {
                empty(document, storeName);
            } else {
                draw(document, labels);
            }
            document.save(output);
            log.info("Etiquetas de rebaja generadas: tienda {}, {} etiquetas", storeId, labels.size());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void draw(PDDocument document, List<Label> labels) throws IOException {
        List<List<Label>> pages = new ArrayList<>();
        for (int i = 0; i < labels.size(); i += COLUMNS * ROWS) {
            pages.add(labels.subList(i, Math.min(i + COLUMNS * ROWS, labels.size())));
        }

        for (List<Label> page : pages) {
            PDPage sheet = new PDPage(PDRectangle.A4);
            document.addPage(sheet);
            try (PDPageContentStream content = new PDPageContentStream(document, sheet)) {
                for (int i = 0; i < page.size(); i++) {
                    float x = MARGIN + (i % COLUMNS) * LABEL_WIDTH;
                    float top = PDRectangle.A4.getHeight() - MARGIN - (i / COLUMNS) * LABEL_HEIGHT;
                    label(content, page.get(i), x, top);
                }
            }
        }
    }

    private void label(PDPageContentStream content, Label label, float x, float top) throws IOException {
        // Marco de corte fino, no decorativo: es por donde se recorta.
        content.setLineWidth(0.4f);
        content.addRect(x + 4f, top - LABEL_HEIGHT + 4f, LABEL_WIDTH - 8f, LABEL_HEIGHT - 8f);
        content.stroke();

        float left = x + PAD;
        float line = top - PAD - 12f;
        float available = LABEL_WIDTH - 2 * PAD;

        write(content, BOLD, 9f, left, line, "OFERTA POR VENCIMIENTO");
        line -= 20f;

        write(content, BOLD, 13f, left, line, fit(label.name(), available, BOLD, 13f));
        line -= 30f;

        write(content, BOLD, 30f, left, line, money(label.markdownPrice()));

        // El precio anterior va al lado y tachado: sin el, una rebaja no se lee
        // como rebaja sino como el precio de siempre.
        float priceWidth = width(BOLD, 30f, money(label.markdownPrice()));
        float beforeX = left + priceWidth + 12f;
        String before = money(label.retailPrice());
        write(content, REGULAR, 12f, beforeX, line + 6f, before);
        content.moveTo(beforeX, line + 10f);
        content.lineTo(beforeX + width(REGULAR, 12f, before), line + 10f);
        content.setLineWidth(0.8f);
        content.stroke();

        line -= 22f;
        write(content, REGULAR, 8f, left, line,
                "Lote " + label.batchCode() + "  -  " + label.sku());
        line -= 11f;
        write(content, REGULAR, 8f, left, line,
                "Vence el " + DAY.format(label.expirationDate())
                        + "  -  " + label.currentQty() + " unidades");
    }

    /** Una hoja que explica por que esta vacia vale mas que un PDF de cero paginas. */
    private void empty(PDDocument document, String storeName) throws IOException {
        PDPage sheet = new PDPage(PDRectangle.A4);
        document.addPage(sheet);
        try (PDPageContentStream content = new PDPageContentStream(document, sheet)) {
            float line = PDRectangle.A4.getHeight() - 80f;
            write(content, BOLD, 15f, MARGIN, line, "Etiquetas de rebaja");
            write(content, REGULAR, 10f, MARGIN, line - 18f, storeName);
            write(content, REGULAR, 10f, MARGIN, line - 44f,
                    "No hay ningun lote con precio rebajado vigente.");
            write(content, REGULAR, 9f, MARGIN, line - 60f,
                    "Aplica una rebaja en \"Que rebajar hoy\" y vuelve a generar la hoja.");
        }
    }

    private static void write(PDPageContentStream content, PDFont font, float size,
                              float x, float line, String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, line);
        content.showText(sanitize(value));
        content.endText();
    }

    private static float width(PDFont font, float size, String value) {
        try {
            return font.getStringWidth(sanitize(value)) / 1000f * size;
        } catch (IOException ex) {
            return value.length() * size * 0.5f;
        }
    }

    private static String fit(String value, float available, PDFont font, float size) {
        String safe = sanitize(value);
        if (width(font, size, safe) <= available) {
            return safe;
        }
        StringBuilder cut = new StringBuilder(safe);
        while (cut.length() > 1 && width(font, size, cut + "...") > available) {
            cut.deleteCharAt(cut.length() - 1);
        }
        return cut + "...";
    }

    /** Helvetica escribe WinAnsi: lo que no entra ahi se traduce, no revienta. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '–', '—', '−' -> safe.append('-');
                case '‘', '’', '′' -> safe.append('\'');
                case '“', '”' -> safe.append('"');
                case '…' -> safe.append("...");
                case ' ' -> safe.append(' ');
                default -> safe.append(c < 32 ? ' ' : (c <= 255 ? c : '?'));
            }
        }
        return safe.toString();
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return "S/ " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record Label(String name, String sku, String batchCode, LocalDate expirationDate,
                         BigDecimal retailPrice, BigDecimal markdownPrice, int currentQty) {
    }
}
