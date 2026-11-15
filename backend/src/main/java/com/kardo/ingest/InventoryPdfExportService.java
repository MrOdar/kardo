package com.kardo.ingest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * Informe valorizado imprimible.
 *
 * <p>No duplica el Excel, lo complementa: el {@code .xlsx} es para trabajar con
 * los datos y este es para <b>firmar, archivar o entregar</b> al contador. De
 * ahi que lleve cosas que una hoja de calculo no necesita -totales arriba, el
 * criterio de valorizacion al pie, quien lo genero y cuando- y que prescinda de
 * columnas que solo sirven para filtrar.
 *
 * <p><b>Este si se arma antes de enviarse</b>, al reves que el Excel. Un PDF
 * lleva al final la tabla de referencias cruzadas que apunta a cada objeto, de
 * modo que no se puede ir escribiendo sobre la respuesta. Para que eso no se
 * traduzca en memoria: los flujos de contenido van a un archivo temporal en
 * lugar de al heap, el resumen sale de una consulta agregada en vez de
 * recorrer las filas dos veces, y el listado tiene un tope. Un informe de
 * cuatrocientas paginas no lo lee nadie; para eso esta el Excel.
 */
@Service
public class InventoryPdfExportService {

    private static final Logger log = LoggerFactory.getLogger(InventoryPdfExportService.class);

    /** Tope de lotes listados; mas alla, el informe lo dice y remite al Excel. */
    private static final int MAX_ROWS = 1500;

    private static final int FETCH_SIZE = 500;

    /** Dias que definen el "valor en riesgo" del resumen, igual que el semaforo. */
    private static final int RISK_DAYS = 5;

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final String SUMMARY = """
            SELECT count(*) AS lotes,
                   COALESCE(sum(b.current_qty), 0) AS unidades,
                   COALESCE(sum(b.current_qty * b.unit_cost), 0) AS valor,
                   COALESCE(sum(b.current_qty * b.unit_cost)
                            FILTER (WHERE (b.expiration_date - CURRENT_DATE) <= ?), 0) AS en_riesgo,
                   count(*) FILTER (WHERE (b.expiration_date - CURRENT_DATE) < 0) AS vencidos,
                   COALESCE(sum(b.quarantined_qty), 0) AS cuarentena
            FROM inventory_batches b
            WHERE b.store_id = ?
              AND (b.current_qty > 0 OR b.quarantined_qty > 0)
            """;

    private static final String INVENTORY = """
            SELECT p.sku,
                   p.name,
                   b.batch_code,
                   b.expiration_date,
                   (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                   b.current_qty,
                   b.unit_cost,
                   (b.current_qty * b.unit_cost) AS valor
            FROM inventory_batches b
            JOIN products p USING (product_id)
            WHERE b.store_id = ?
              AND (b.current_qty > 0 OR b.quarantined_qty > 0)
            ORDER BY b.expiration_date ASC, p.name
            """;

    // Columnas: x de inicio y, para las numericas, x donde termina el texto.
    private static final float MARGIN = 36f;
    private static final float RIGHT_EDGE = PDRectangle.A4.getWidth() - MARGIN;
    // El lote manda sobre el nombre del producto en el reparto de ancho: es lo
    // que el auditor coteja contra la etiqueta del envase, y un lote recortado
    // no sirve para nada, mientras que un nombre recortado se sigue entendiendo.
    private static final float[] COLUMN_X = {36f, 88f, 122f, 244f, 332f, 426f, 458f, 500f};
    private static final String[] COLUMN_TITLE = {
            "Vence", "Dias", "Producto", "SKU", "Lote", "Cant.", "Costo", "Valor"};

    /** Ancho util de cada columna de texto, para recortar lo que no entre. */
    private static final float[] COLUMN_WIDTH = {50f, 0f, 118f, 84f, 92f, 0f, 0f, 0f};

    private static final float ROW_HEIGHT = 13f;
    private static final float BODY_SIZE = 8f;
    private static final float BOTTOM = MARGIN + 28f;

    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final JdbcTemplate jdbcTemplate;

    public InventoryPdfExportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Escribe el informe en el flujo recibido.
     *
     * <p>Transaccional de solo lectura, igual que el Excel: el resumen de
     * arriba y el listado de abajo tienen que salir de la misma foto, o el
     * informe no cuadraria consigo mismo.
     */
    @Transactional(readOnly = true)
    public void writeTo(long storeId, String storeName, String generatedBy, OutputStream output) {
        Summary summary = loadSummary(storeId);

        try (PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            Layout layout = new Layout(document);
            layout.newPage();
            layout.header(storeName, generatedBy, summary);
            layout.tableHeader();

            Totals totals = new Totals();

            jdbcTemplate.query(connection -> {
                var statement = connection.prepareStatement(INVENTORY);
                statement.setFetchSize(FETCH_SIZE);
                statement.setLong(1, storeId);
                return statement;
            }, rs -> {
                if (totals.rows >= MAX_ROWS) {
                    return;
                }
                int quantity = rs.getInt("current_qty");
                BigDecimal value = rs.getBigDecimal("valor");
                int days = rs.getInt("dias_restantes");

                // El contrato del callback solo admite SQLException; un fallo al
                // dibujar sale como UncheckedIOException y lo traduce el manejador.
                try {
                    layout.row(
                        text(rs.getObject("expiration_date", LocalDate.class)),
                        String.valueOf(days),
                        rs.getString("name"),
                        rs.getString("sku"),
                        rs.getString("batch_code"),
                        String.valueOf(quantity),
                        money(rs.getBigDecimal("unit_cost")),
                        money(value),
                        days);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }

                totals.rows++;
                totals.units += quantity;
                totals.value = totals.value.add(value);
            });

            layout.totals(totals);
            if (summary.batches() > MAX_ROWS) {
                layout.note("Se listan los primeros " + MAX_ROWS + " lotes de " + summary.batches()
                        + ". El inventario completo esta en la exportacion a Excel.");
            }
            layout.finish(storeName);

            document.save(output);

            log.info("Informe PDF generado: tienda {}, {} lotes listados, valor {}",
                    storeId, totals.rows, totals.value);

        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Summary loadSummary(long storeId) {
        return jdbcTemplate.queryForObject(SUMMARY, (rs, rowNum) -> new Summary(
                rs.getInt("lotes"),
                rs.getInt("unidades"),
                rs.getBigDecimal("valor"),
                rs.getBigDecimal("en_riesgo"),
                rs.getInt("vencidos"),
                rs.getInt("cuarentena")), RISK_DAYS, storeId);
    }

    /** Composicion de la pagina: todo el dibujo vive aqui. */
    private final class Layout {

        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream content;
        private float y;

        private Layout(PDDocument document) {
            this.document = document;
        }

        private void newPage() throws IOException {
            close();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        private void header(String storeName, String generatedBy, Summary summary) throws IOException {
            write(BOLD, 15f, MARGIN, y, "Inventario valorizado");
            y -= 17f;
            write(REGULAR, 10f, MARGIN, y, storeName);
            y -= 12f;
            write(REGULAR, 8f, MARGIN, y,
                    "Generado el " + STAMP.format(LocalDateTime.now(LIMA)) + " por " + generatedBy);
            y -= 20f;

            // El resumen va arriba porque es lo unico que mira quien firma;
            // el detalle esta debajo para quien necesite comprobarlo.
            box(summary);
            y -= 16f;
        }

        private void box(Summary summary) throws IOException {
            float top = y;
            content.addRect(MARGIN, top - 54f, RIGHT_EDGE - MARGIN, 54f);
            content.setLineWidth(0.5f);
            content.stroke();

            float left = MARGIN + 8f;
            float line = top - 14f;
            cell(left, line, "Valor del inventario", money(summary.value()));
            cell(left + 170f, line, "Lotes con existencias", String.valueOf(summary.batches()));
            cell(left + 330f, line, "Unidades", String.valueOf(summary.units()));

            line -= 26f;
            cell(left, line, "Valor en riesgo (" + RISK_DAYS + " dias)", money(summary.atRisk()));
            cell(left + 170f, line, "Lotes vencidos", String.valueOf(summary.expired()));
            cell(left + 330f, line, "Unidades en cuarentena", String.valueOf(summary.quarantined()));

            y = top - 54f;
        }

        private void cell(float x, float line, String label, String value) throws IOException {
            write(REGULAR, 7f, x, line, label);
            write(BOLD, 11f, x, line - 12f, value);
        }

        private void tableHeader() throws IOException {
            for (int i = 0; i < COLUMN_TITLE.length; i++) {
                if (isNumeric(i)) {
                    writeRight(BOLD, 7.5f, columnRight(i), y, COLUMN_TITLE[i]);
                } else {
                    write(BOLD, 7.5f, COLUMN_X[i], y, COLUMN_TITLE[i]);
                }
            }
            y -= 4f;
            content.moveTo(MARGIN, y);
            content.lineTo(RIGHT_EDGE, y);
            content.setLineWidth(0.5f);
            content.stroke();
            y -= ROW_HEIGHT;
        }

        private void row(String vence, String dias, String producto, String sku,
                         String lote, String cantidad, String costo, String valor,
                         int daysToExpiry) throws IOException {

            if (y < BOTTOM) {
                newPage();
                tableHeader();
            }

            // Lo vencido y lo que vence en 48 h se marcan con una banda, que es
            // lo que se ve en un papel sin colores de fondo ni iconos.
            if (daysToExpiry <= 2) {
                content.addRect(MARGIN - 4f, y - 3f, 2f, 10f);
                content.fill();
            }

            String[] values = {vence, dias, producto, sku, lote, cantidad, costo, valor};
            for (int i = 0; i < values.length; i++) {
                if (isNumeric(i)) {
                    writeRight(REGULAR, BODY_SIZE, columnRight(i), y, values[i]);
                } else {
                    write(REGULAR, BODY_SIZE, COLUMN_X[i], y, fit(values[i], COLUMN_WIDTH[i]));
                }
            }
            y -= ROW_HEIGHT;
        }

        private void totals(Totals totals) throws IOException {
            if (y < BOTTOM + 20f) {
                newPage();
            }
            y -= 4f;
            content.moveTo(MARGIN, y);
            content.lineTo(RIGHT_EDGE, y);
            content.setLineWidth(0.5f);
            content.stroke();
            y -= 12f;

            write(BOLD, 8f, COLUMN_X[2], y, "Totales (" + totals.rows + " lotes)");
            writeRight(BOLD, 8f, columnRight(5), y, String.valueOf(totals.units));
            writeRight(BOLD, 8f, columnRight(7), y, money(totals.value));
            y -= ROW_HEIGHT;
        }

        private void note(String message) throws IOException {
            if (y < BOTTOM) {
                newPage();
            }
            y -= 6f;
            write(REGULAR, 7.5f, MARGIN, y, message);
            y -= ROW_HEIGHT;
        }

        /**
         * Cierra y escribe los pies.
         *
         * <p>El pie se pone al final porque hasta aqui no se sabe cuantas
         * paginas hay, y un informe que dice "pagina 3" sin decir de cuantas no
         * sirve para comprobar que esta completo, que es justo lo que mira
         * quien recibe un documento impreso.
         */
        private void finish(String storeName) throws IOException {
            close();

            int total = document.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage each = document.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(
                        document, each, PDPageContentStream.AppendMode.APPEND, true, true)) {

                    float line = MARGIN + 16f;
                    footer.moveTo(MARGIN, line + 8f);
                    footer.lineTo(RIGHT_EDGE, line + 8f);
                    footer.setLineWidth(0.5f);
                    footer.stroke();

                    writeOn(footer, REGULAR, 7f, MARGIN, line,
                            "Kardo - " + sanitize(storeName)
                                    + " - Valorizado al costo de cada lote, no al precio de venta.");
                    writeRightOn(footer, REGULAR, 7f, RIGHT_EDGE, line,
                            "Pagina " + (i + 1) + " de " + total);
                }
            }
        }

        private void close() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }

        private void write(PDFont font, float size, float x, float line, String value) throws IOException {
            writeOn(content, font, size, x, line, value);
        }

        private void writeRight(PDFont font, float size, float right, float line, String value)
                throws IOException {
            writeRightOn(content, font, size, right, line, value);
        }
    }

    private static void writeOn(PDPageContentStream stream, PDFont font, float size,
                                float x, float line, String value) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, line);
        stream.showText(sanitize(value));
        stream.endText();
    }

    private static void writeRightOn(PDPageContentStream stream, PDFont font, float size,
                                     float right, float line, String value) throws IOException {
        String safe = sanitize(value);
        writeOn(stream, font, size, right - width(font, size, safe), line, safe);
    }

    private static float width(PDFont font, float size, String value) {
        try {
            return font.getStringWidth(value) / 1000f * size;
        } catch (IOException ex) {
            return value.length() * size * 0.5f;
        }
    }

    private static boolean isNumeric(int column) {
        return column == 1 || column >= 5;
    }

    private static float columnRight(int column) {
        return column == 7 ? RIGHT_EDGE : COLUMN_X[column + 1] - 6f;
    }

    /** Recorta lo que no entra en la columna; mejor truncado que superpuesto. */
    private static String fit(String value, float available) {
        String safe = sanitize(value);
        if (width(REGULAR, BODY_SIZE, safe) <= available) {
            return safe;
        }
        StringBuilder cut = new StringBuilder(safe);
        while (cut.length() > 1 && width(REGULAR, BODY_SIZE, cut + "...") > available) {
            cut.deleteCharAt(cut.length() - 1);
        }
        return cut + "...";
    }


    /**
     * Deja el texto en algo que las fuentes estandar pueden escribir.
     *
     * <p>Helvetica usa WinAnsi, que cubre el español pero no un guion largo ni
     * unas comillas tipograficas. Sin esto, un producto llamado <em>Leche "La
     * Vaquita"</em> con comillas curvas no recortaria el informe: lo haria
     * fallar entero con una excepcion a mitad del dibujo.
     */
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
                case ' ' -> safe.append(' ');
                default -> safe.append(c < 32 ? ' ' : (c <= 255 ? c : '?'));
            }
        }
        return safe.toString();
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return "S/ " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String text(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /** Cifras de cabecera, calculadas en PostgreSQL en una sola consulta. */
    private record Summary(int batches, int units, BigDecimal value,
                           BigDecimal atRisk, int expired, int quarantined) {
    }

    /** Acumuladores del pie de tabla. */
    private static final class Totals {
        private int rows;
        private int units;
        private BigDecimal value = BigDecimal.ZERO;
    }
}
