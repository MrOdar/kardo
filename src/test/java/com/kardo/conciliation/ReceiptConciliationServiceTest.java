package com.kardo.conciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kardo.config.MatchingProperties;
import com.kardo.dto.ConciliationLineDto;
import com.kardo.dto.ConfirmedLineDto;
import com.kardo.dto.ExtractedItemDto;
import com.kardo.dto.ExtractedReceiptDto;
import com.kardo.dto.ReceiptConfirmationRequestDto;
import com.kardo.dto.ReceiptConfirmationResponseDto;
import com.kardo.dto.ReceiptScanResponseDto;
import com.kardo.exception.InvalidReceiptLineException;
import com.kardo.repository.GoodsReceiptRepository;
import com.kardo.repository.SkuMatchingRepository;
import com.kardo.repository.SkuMatchingRepository.SkuMatch;
import com.kardo.repository.StoreRepository;
import com.kardo.vision.GeminiVisionClient;
import com.kardo.vision.GeminiVisionClient.GeminiExtractionResult;
import com.kardo.vision.ImageProcessingService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class ReceiptConciliationServiceTest {

    private static final long STORE_ID = 999L;
    private static final long COMPANY_ID = 7L;

    @Mock
    private ImageProcessingService imageProcessingService;
    @Mock
    private GeminiVisionClient geminiVisionClient;
    @Mock
    private SkuMatchingRepository skuMatchingRepository;
    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;
    @Mock
    private StoreRepository storeRepository;

    private ReceiptConciliationService service;

    @BeforeEach
    void setUp() {
        when(storeRepository.findCompanyIdByStore(STORE_ID)).thenReturn(COMPANY_ID);
        when(imageProcessingService.toBase64Jpeg(any())).thenReturn("BASE64");
        service = new ReceiptConciliationService(
                imageProcessingService, geminiVisionClient, skuMatchingRepository,
                goodsReceiptRepository, storeRepository,
                new MatchingProperties(0.35), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("Extrae la guia y cruza cada linea contra el catalogo en una sola consulta")
    void extraeYCruzaContraCatalogo() {
        when(geminiVisionClient.extract("BASE64")).thenReturn(new GeminiExtractionResult(
                new ExtractedReceiptDto("20123456789", "Distribuidora Lima S.A.C.", "T001-4567",
                        LocalDate.of(2026, 10, 15), List.of(
                        item("CERV PILSEN 650", "24", "4.50", "L-A", LocalDate.of(2027, 5, 1)),
                        item("LECHE GLORIA 400", "48", "3.20", null, null),
                        item("PRODUCTO NUEVO XYZ", "10", "9.90", null, null))),
                "gemini-2.0-flash"));

        when(skuMatchingRepository.matchLines(eq(COMPANY_ID), any(), any())).thenReturn(List.of(
                new SkuMatch(1, 101L, "Cerveza Pilsen Callao Botella 650ml", 0.82),
                // Score por debajo del umbral: no se acepta el candidato
                new SkuMatch(2, 202L, "Leche Gloria Evaporada 400g", 0.21),
                // Sin candidato en el catalogo
                new SkuMatch(3, null, null, null)));

        ReceiptScanResponseDto response = service.scan(image(), STORE_ID);

        assertThat(response.supplierTaxId()).isEqualTo("20123456789");
        assertThat(response.documentNumber()).isEqualTo("T001-4567");
        assertThat(response.issueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(response.modelUsed()).isEqualTo("gemini-2.0-flash");
        assertThat(response.unmatchedLines()).isEqualTo(2);

        assertThat(response.lines()).hasSize(3);

        ConciliationLineDto matched = response.lines().get(0);
        assertThat(matched.productId()).isEqualTo(101L);
        assertThat(matched.matchedName()).isEqualTo("Cerveza Pilsen Callao Botella 650ml");
        assertThat(matched.matchScore()).isEqualTo(0.82);
        assertThat(matched.batchCode()).isEqualTo("L-A");
        assertThat(matched.expirationDate()).isEqualTo(LocalDate.of(2027, 5, 1));

        ConciliationLineDto weak = response.lines().get(1);
        assertThat(weak.productId()).isNull();
        assertThat(weak.matchedName()).isNull();
        // El score se conserva para que la interfaz pueda explicar el descarte
        assertThat(weak.matchScore()).isEqualTo(0.21);

        ConciliationLineDto nuevo = response.lines().get(2);
        assertThat(nuevo.productId()).isNull();
        assertThat(nuevo.matchScore()).isNull();

        // Un solo viaje de red con arreglos paralelos: sin N+1
        ArgumentCaptor<List<Integer>> ids = captorOfList();
        ArgumentCaptor<List<String>> texts = captorOfList();
        verify(skuMatchingRepository).matchLines(eq(COMPANY_ID), ids.capture(), texts.capture());
        assertThat(ids.getValue()).containsExactly(1, 2, 3);
        assertThat(texts.getValue()).containsExactly("CERV PILSEN 650", "LECHE GLORIA 400", "PRODUCTO NUEVO XYZ");
    }

    @Test
    @DisplayName("Una guia sin lineas no consulta el catalogo")
    void guiaVacia() {
        when(geminiVisionClient.extract("BASE64")).thenReturn(new GeminiExtractionResult(
                new ExtractedReceiptDto("20123456789", "Proveedor", "T001-1", LocalDate.now(), null),
                "gemini-2.0-flash"));

        ReceiptScanResponseDto response = service.scan(image(), STORE_ID);

        assertThat(response.lines()).isEmpty();
        assertThat(response.unmatchedLines()).isZero();
        verify(skuMatchingRepository, never()).matchLines(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Confirmar persiste cabecera, lineas y lotes, y marca las discrepancias")
    void confirmacionPersisteTodo() {
        when(goodsReceiptRepository.upsertSupplier(eq(COMPANY_ID), any(), any())).thenReturn(55L);
        when(goodsReceiptRepository.insertHeader(anyLong(), anyLong(), any(), any(), any(), any(), any())).thenReturn(900L);
        when(goodsReceiptRepository.insertItem(anyLong(), any())).thenReturn(1L, 2L, 3L);

        ReceiptConfirmationRequestDto request = new ReceiptConfirmationRequestDto(
                STORE_ID, "20123456789", "Distribuidora Lima S.A.C.", "T001-4567",
                LocalDate.of(2026, 10, 15),
                List.of(
                        // Recibido igual a facturado
                        line(1, 101L, "24", "24", "L-A", LocalDate.of(2027, 5, 1)),
                        // Faltante: genera discrepancia pero tambien lote por lo recibido
                        line(2, 202L, "48", "40", null, LocalDate.of(2027, 1, 20)),
                        // Nada llego: se registra la linea para el reclamo, sin lote
                        line(3, 303L, "10", "0", null, null)));

        ReceiptConfirmationResponseDto response = service.confirm(request, "operario@kardo.pe", "10.0.0.1");

        assertThat(response.receiptId()).isEqualTo(900L);
        assertThat(response.itemsPersisted()).isEqualTo(3);
        assertThat(response.batchesCreated()).isEqualTo(2);
        assertThat(response.discrepancyLineIds()).containsExactly(2, 3);

        verify(goodsReceiptRepository).upsertSupplier(COMPANY_ID, "Distribuidora Lima S.A.C.", "20123456789");
        verify(goodsReceiptRepository).insertHeader(eq(STORE_ID), eq(55L), eq("T001-4567"),
                eq(LocalDate.of(2026, 10, 15)), any(), eq("operario@kardo.pe"), eq("10.0.0.1"));

        // La linea sin lote impreso recibe el marcador, porque batch_code es NOT NULL
        ArgumentCaptor<String> batchCodes = ArgumentCaptor.forClass(String.class);
        verify(goodsReceiptRepository, org.mockito.Mockito.times(2))
                .insertBatch(eq(STORE_ID), anyLong(), any(), batchCodes.capture());
        assertThat(batchCodes.getAllValues()).containsExactly("L-A", "SIN-LOTE");
    }

    @Test
    @DisplayName("Rechaza recibir mercaderia sin fecha de vencimiento")
    void exigeVencimientoAlRecibir() {
        when(goodsReceiptRepository.upsertSupplier(anyLong(), any(), any())).thenReturn(55L);
        when(goodsReceiptRepository.insertHeader(anyLong(), anyLong(), any(), any(), any(), any(), any())).thenReturn(900L);

        ReceiptConfirmationRequestDto request = new ReceiptConfirmationRequestDto(
                STORE_ID, "20123456789", "Proveedor", "T001-9", LocalDate.now(),
                List.of(line(1, 101L, "5", "5", "L-A", null)));

        assertThatThrownBy(() -> service.confirm(request, "operario@kardo.pe", "10.0.0.1"))
                .isInstanceOf(InvalidReceiptLineException.class)
                .hasMessageContaining("Linea 1")
                .hasMessageContaining("vencimiento");

        verify(goodsReceiptRepository, never()).insertBatch(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Rechaza cantidades fraccionarias porque el lote se mide en unidades enteras")
    void rechazaFraccionesDeUnidad() {
        when(goodsReceiptRepository.upsertSupplier(anyLong(), any(), any())).thenReturn(55L);
        when(goodsReceiptRepository.insertHeader(anyLong(), anyLong(), any(), any(), any(), any(), any())).thenReturn(900L);

        ReceiptConfirmationRequestDto request = new ReceiptConfirmationRequestDto(
                STORE_ID, "20123456789", "Proveedor", "T001-9", LocalDate.now(),
                List.of(line(1, 101L, "5", "2.5", "L-A", LocalDate.now().plusMonths(3))));

        assertThatThrownBy(() -> service.confirm(request, "operario@kardo.pe", "10.0.0.1"))
                .isInstanceOf(InvalidReceiptLineException.class)
                .hasMessageContaining("entero");
    }

    private static InputStream image() {
        return new ByteArrayInputStream(new byte[]{1, 2, 3});
    }

    private static ExtractedItemDto item(String description, String qty, String cost,
                                         String batch, LocalDate expires) {
        return new ExtractedItemDto(description, new BigDecimal(qty), new BigDecimal(cost), batch, expires);
    }

    private static ConfirmedLineDto line(int ocrLineId, long productId, String invoiced, String received,
                                         String batch, LocalDate expires) {
        return new ConfirmedLineDto(ocrLineId, productId, new BigDecimal(invoiced), new BigDecimal(received),
                new BigDecimal("4.50"), batch, null, expires);
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<List<T>> captorOfList() {
        return ArgumentCaptor.forClass(List.class);
    }
}
