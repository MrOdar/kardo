package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kardo.config.IngestProperties;
import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.QuarantineRowDto;
import com.kardo.dto.SpreadsheetRowDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.exception.SpreadsheetTooLargeException;
import com.kardo.repository.InventoryIngestRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExcelStreamingIngestServiceTest {

    private static final long STORE_ID = 7L;
    private static final long COMPANY_ID = 1L;

    private static final List<String> HEADERS_CON_ALIAS = List.of(
            "Codigo", "EAN", "Descripcion", "Familia", "UM",
            "Precio Compra", "Precio", "Nro Lote", "F. Fabricacion", "Vence", "Stock");

    @Mock
    private InventoryIngestRepository repository;

    private ExcelStreamingIngestService service;

    @BeforeEach
    void setUp() {
        when(repository.findCompanyIdByStore(anyLong())).thenReturn(COMPANY_ID);
        // El repositorio simulado confirma tantas inserciones como filas recibe.
        when(repository.persistMicroBatch(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyList(), any()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(2)).size());
        service = new ExcelStreamingIngestService(repository, new IngestProperties(500, 100, 200_000));
    }

    @Test
    @DisplayName("Mapea encabezados por alias y persiste las filas validas")
    void ingestaFilasValidasConEncabezadosAlternativos() throws IOException {
        InputStream excel = TestWorkbooks.xlsx(HEADERS_CON_ALIAS, List.of(
                fila("SKU-001", "7751234567890", "Cerveza Pilsen 650ml", "BEBIDAS", "BOTELLA",
                        "4.50", "7.90", "L-2026-A", "2026-01-10", "2027-01-10", 24),
                fila("SKU-002", null, "Leche Gloria 400g", "LACTEOS", "LATA",
                        "3.20", "4.80", "L-2026-B", null, "31/12/2026", 48)));

        IngestSummaryResponseDto summary = service.ingest(excel, STORE_ID, 7L);

        assertThat(summary.totalRowsProcessed()).isEqualTo(2);
        assertThat(summary.totalInserted()).isEqualTo(2);
        assertThat(summary.totalQuarantined()).isZero();
        assertThat(summary.quarantinedRows()).isEmpty();

        List<SpreadsheetRowDto> persisted = capturarFilasPersistidas();
        assertThat(persisted).hasSize(2);

        SpreadsheetRowDto primera = persisted.get(0);
        assertThat(primera.rowNumber()).isEqualTo(2);
        assertThat(primera.sku()).isEqualTo("SKU-001");
        assertThat(primera.barcode()).isEqualTo("7751234567890");
        assertThat(primera.name()).isEqualTo("Cerveza Pilsen 650ml");
        assertThat(primera.unitCost()).isEqualByComparingTo("4.50");
        assertThat(primera.retailPrice()).isEqualByComparingTo("7.90");
        assertThat(primera.manufacturingDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(primera.expirationDate()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(primera.initialQty()).isEqualTo(24);

        SpreadsheetRowDto segunda = persisted.get(1);
        assertThat(segunda.barcode()).isNull();
        assertThat(segunda.manufacturingDate()).isNull();
        // "31/12/2026" en formato peruano se interpreta como dia/mes/anio
        assertThat(segunda.expirationDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("Envia las filas en micro-lotes del tamanio configurado")
    void troceaEnMicroLotes() throws IOException {
        service = new ExcelStreamingIngestService(repository, new IngestProperties(2, 100, 200_000));

        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            filas.add(fila("SKU-" + i, null, "Producto " + i, "VARIOS", "UNIDAD",
                    "1.00", "2.00", "L-1", null, "2027-05-05", 10));
        }

        IngestSummaryResponseDto summary = service.ingest(TestWorkbooks.xlsx(HEADERS_CON_ALIAS, filas), STORE_ID, 7L);

        assertThat(summary.totalInserted()).isEqualTo(5);
        // 2 + 2 + 1: el ultimo micro-lote se descarga al cerrar el flujo
        ArgumentCaptor<List<SpreadsheetRowDto>> captor = captor();
        verify(repository, org.mockito.Mockito.times(3))
                .persistMicroBatch(eq(STORE_ID), eq(COMPANY_ID), captor.capture(), any());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
    }

    @Test
    @DisplayName("Deja en cuarentena las filas que violan las reglas de calidad")
    void cuarentenaFilasInvalidas() throws IOException {
        InputStream excel = TestWorkbooks.xlsx(HEADERS_CON_ALIAS, List.of(
                fila("SKU-OK", null, "Valido", "VARIOS", "UNIDAD", "1.00", "2.00", "L-1", null, "2027-01-01", 5),
                fila("", null, "Sin SKU", "VARIOS", "UNIDAD", "1.00", "2.00", "L-1", null, "2027-01-01", 5),
                fila("SKU-NEG", null, "Costo negativo", "VARIOS", "UNIDAD", "-1.00", "2.00", "L-1", null,
                        "2027-01-01", 5),
                fila("SKU-QTY", null, "Cantidad cero", "VARIOS", "UNIDAD", "1.00", "2.00", "L-1", null,
                        "2027-01-01", 0),
                fila("SKU-FEC", null, "Fecha ilegible", "VARIOS", "UNIDAD", "1.00", "2.00", "L-1", null,
                        "manana", 5),
                fila("SKU-ORD", null, "Vence antes de fabricarse", "VARIOS", "UNIDAD", "1.00", "2.00", "L-1",
                        "2026-06-01", "2026-01-01", 5)));

        IngestSummaryResponseDto summary = service.ingest(excel, STORE_ID, 7L);

        assertThat(summary.totalRowsProcessed()).isEqualTo(6);
        assertThat(summary.totalInserted()).isEqualTo(1);
        assertThat(summary.totalQuarantined()).isEqualTo(5);
        assertThat(summary.quarantinedRows())
                .extracting(QuarantineRowDto::errorCode)
                .containsExactly(
                        IngestErrorCode.MISSING_SKU.name(),
                        IngestErrorCode.INVALID_COST.name(),
                        IngestErrorCode.INVALID_QUANTITY.name(),
                        IngestErrorCode.INVALID_DATE_FORMAT.name(),
                        IngestErrorCode.EXPIRATION_BEFORE_MANUFACTURING.name());

        QuarantineRowDto sinSku = summary.quarantinedRows().get(0);
        assertThat(sinSku.rowNumber()).isEqualTo(3);
        assertThat(sinSku.originalValues()).containsEntry("name", "Sin SKU");
        assertThat(sinSku.message()).isNotBlank();
    }

    @Test
    @DisplayName("El Circuit Breaker aborta al superar el umbral de filas corruptas")
    void circuitBreakerCorta() throws IOException {
        service = new ExcelStreamingIngestService(repository, new IngestProperties(500, 3, 200_000));

        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            // Todas sin SKU: filas corruptas desde la primera
            filas.add(fila("", null, "Basura " + i, "VARIOS", "UNIDAD", "1.00", "2.00", "L-1", null,
                    "2027-01-01", 5));
        }

        InputStream excel = TestWorkbooks.xlsx(HEADERS_CON_ALIAS, filas);

        assertThatThrownBy(() -> service.ingest(excel, STORE_ID, 7L))
                .isInstanceOf(CorruptSpreadsheetException.class)
                .hasMessageContaining("plantilla no compatible")
                .extracting(ex -> ((CorruptSpreadsheetException) ex).getCorruptRows())
                .isEqualTo(4);

        // Corta sin persistir nada: no se escriben lotes de un archivo invalido
        verify(repository, never()).persistMicroBatch(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyList(), any());
    }

    @Test
    @DisplayName("Corta el archivo que pasa del tope de filas, sin escribir nada")
    void topeDeFilas() throws IOException {
        // Tope de 3 para no tener que generar 200.000 filas en una prueba: lo
        // que se verifica es que el corte exista y llegue antes de la escritura.
        service = new ExcelStreamingIngestService(repository, new IngestProperties(500, 100, 3));

        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            filas.add(fila("SKU-" + i, null, "Producto " + i, "VARIOS", "UNIDAD",
                    "1.00", "2.00", "L-1", null, "2027-05-05", 10));
        }

        InputStream excel = TestWorkbooks.xlsx(HEADERS_CON_ALIAS, filas);

        assertThatThrownBy(() -> service.ingest(excel, STORE_ID, 7L))
                .isInstanceOf(SpreadsheetTooLargeException.class)
                .hasMessageContaining("3 filas");

        // No es un 422 de archivo corrupto: el archivo esta bien, lo que no
        // cabe es el trabajo. La distincion le dice al cliente que la salida es
        // dividirlo, no corregirlo.
        assertThatThrownBy(() -> service.ingest(TestWorkbooks.xlsx(HEADERS_CON_ALIAS, filas), STORE_ID, 7L))
                .isNotInstanceOf(CorruptSpreadsheetException.class);

        verify(repository, never())
                .persistMicroBatch(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyList(), any());
    }

    @Test
    @DisplayName("Rechaza la plantilla si falta una columna requerida")
    void plantillaSinColumnaRequerida() throws IOException {
        InputStream excel = TestWorkbooks.xlsx(
                List.of("Codigo", "Descripcion", "Familia", "Precio Compra", "Precio", "Stock"),
                List.of(List.of("SKU-001", "Producto", "VARIOS", "1.00", "2.00", 5)));

        assertThatThrownBy(() -> service.ingest(excel, STORE_ID, 7L))
                .isInstanceOf(CorruptSpreadsheetException.class)
                .hasMessageContaining("expiration_date");
    }

    @Test
    @DisplayName("Revalida en servidor las filas corregidas en el navegador")
    void bulkFixRevalidaEnServidor() {
        SpreadsheetRowDto valida = new SpreadsheetRowDto(3, "SKU-001", null, "Producto", "VARIOS", "UNIDAD",
                new BigDecimal("1.00"), new BigDecimal("2.00"), "L-1", null, LocalDate.of(2027, 1, 1), 5);
        SpreadsheetRowDto incoherente = new SpreadsheetRowDto(9, "SKU-002", null, "Producto", "VARIOS", "UNIDAD",
                new BigDecimal("1.00"), new BigDecimal("2.00"), "L-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), 5);

        IngestSummaryResponseDto summary = service.ingestFixedRows(STORE_ID, List.of(valida, incoherente), 7L);

        assertThat(summary.totalRowsProcessed()).isEqualTo(2);
        assertThat(summary.totalInserted()).isEqualTo(1);
        assertThat(summary.quarantinedRows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.rowNumber()).isEqualTo(9);
                    assertThat(row.errorCode()).isEqualTo(IngestErrorCode.EXPIRATION_BEFORE_MANUFACTURING.name());
                });
    }

    private List<SpreadsheetRowDto> capturarFilasPersistidas() {
        ArgumentCaptor<List<SpreadsheetRowDto>> captor = captor();
        verify(repository).persistMicroBatch(eq(STORE_ID), eq(COMPANY_ID), captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<SpreadsheetRowDto>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static List<Object> fila(String sku, String barcode, String name, String category, String unit,
                                     String cost, String price, String batch, String manufactured,
                                     String expires, int qty) {
        return java.util.Arrays.asList(sku, barcode, name, category, unit, cost, price, batch,
                manufactured, expires, qty);
    }
}
