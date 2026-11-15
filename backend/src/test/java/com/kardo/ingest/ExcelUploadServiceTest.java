package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.UploadAuditDto;
import com.kardo.exception.DuplicateUploadException;
import com.kardo.repository.InventoryUploadRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
class ExcelUploadServiceTest {

    private static final long STORE_ID = 999L;

    /** SHA-256 conocido de "contenido-del-excel". */
    private static final byte[] CONTENIDO = "contenido-del-excel".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ExcelStreamingIngestService ingestService;
    @Mock
    private InventoryUploadRepository uploadRepository;

    private ExcelUploadService service;

    private final ExcelUploadService.UploadContext context = new ExcelUploadService.UploadContext(
            "inventario-octubre.xlsx", CONTENIDO.length, "operario@kardo.pe", "190.12.33.4");

    @BeforeEach
    void setUp() {
        when(ingestService.ingest(any(), anyLong(), any()))
                .thenReturn(new IngestSummaryResponseDto(120, 115, 5, List.of()));
        when(uploadRepository.findByHash(anyLong(), anyString(), any())).thenReturn(Optional.empty());
        when(uploadRepository.record(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), any())).thenReturn(77L);

        service = new ExcelUploadService(ingestService, uploadRepository);
    }

    @Test
    @DisplayName("Una carga nueva se procesa y deja asiento de auditoria con su autor")
    void cargaNueva() {
        IngestSummaryResponseDto summary = service.upload(STORE_ID, streams(), context);

        assertThat(summary.totalInserted()).isEqualTo(115);
        assertThat(summary.uploadId()).isEqualTo(77L);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(uploadRepository).record(eq(STORE_ID), hash.capture(), eq("inventario-octubre.xlsx"),
                eq((long) CONTENIDO.length), eq("operario@kardo.pe"), eq("190.12.33.4"),
                eq(InventoryUploadRepository.Kind.INVENTORY));
        // Las metricas se escriben al terminar de recorrer el archivo
        verify(uploadRepository).updateCounts(77L, 120, 115, 5);

        // El hash identifica el contenido, no el nombre: 64 hex de SHA-256
        assertThat(hash.getValue()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("Reenviar el mismo archivo no vuelve a procesarlo")
    void recargaDuplicada() {
        when(uploadRepository.findByHash(anyLong(), anyString(), any()))
                .thenReturn(Optional.of(previo()));

        assertThatThrownBy(() -> service.upload(STORE_ID, streams(), context))
                .isInstanceOf(DuplicateUploadException.class)
                .hasMessageContaining("ya se cargo")
                .hasMessageContaining("operario@kardo.pe")
                .hasMessageContaining("115 lotes creados");

        // Lo importante: no se duplican los lotes
        verify(ingestService, never()).ingest(any(), anyLong(), any());
        verify(uploadRepository, never()).record(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Un archivo con el mismo contenido y otro nombre tambien se reconoce")
    void mismoContenidoOtroNombre() {
        String hashOriginal = capturarHash(context);
        String hashRenombrado = capturarHash(new ExcelUploadService.UploadContext(
                "copia-del-inventario.xlsx", CONTENIDO.length, "otro@kardo.pe", "10.0.0.1"));

        assertThat(hashRenombrado).isEqualTo(hashOriginal);
    }

    @Test
    @DisplayName("Un contenido distinto produce otra huella")
    void contenidoDistinto() {
        String primero = capturarHash(context);

        byte[] otro = "contenido-diferente".getBytes(StandardCharsets.UTF_8);
        service.upload(STORE_ID, () -> new ByteArrayInputStream(otro), context);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(uploadRepository, org.mockito.Mockito.atLeastOnce())
                .record(anyLong(), hash.capture(), anyString(), anyLong(), anyString(), anyString(), any());

        assertThat(hash.getAllValues()).last().isNotEqualTo(primero);
    }

    private String capturarHash(ExcelUploadService.UploadContext ctx) {
        service.upload(STORE_ID, streams(), ctx);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(uploadRepository, org.mockito.Mockito.atLeastOnce())
                .record(anyLong(), hash.capture(), anyString(), anyLong(), anyString(), anyString(), any());
        return hash.getValue();
    }

    private static Supplier<InputStream> streams() {
        return () -> new ByteArrayInputStream(CONTENIDO);
    }

    private static UploadAuditDto previo() {
        return new UploadAuditDto(12L, "inventario-octubre.xlsx", "a".repeat(64), CONTENIDO.length,
                120, 115, 5, "operario@kardo.pe", "190.12.33.4",
                Instant.parse("2026-10-18T19:32:00Z"), null, null, "INVENTORY");
    }
}
