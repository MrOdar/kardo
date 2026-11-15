package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.dto.SpreadsheetRowDto;
import com.kardo.exception.DuplicateUploadException;
import com.kardo.ingest.BulkFixService;
import com.kardo.scheduler.ClientIpRetentionScheduler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rastro de las correcciones manuales y retencion de la IP.
 *
 * <p>Las correcciones crean lotes igual que un archivo, asi que deben dejar el
 * mismo asiento; y la IP, que es dato personal, no puede quedarse para siempre.
 */
class AuditRetentionIT extends PostgresIntegrationTest {

    @Autowired
    private BulkFixService bulkFixService;

    @Autowired
    private ClientIpRetentionScheduler retentionScheduler;

    private long storeId;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Auditoria", "21200000012").store("TIENDA-AU");
    }

    @Test
    @DisplayName("Una correccion deja asiento con su autor y sus lotes ligados")
    void correccionAuditada() {
        var summary = bulkFixService.apply(storeId, List.of(fila("SKU-FIX-1")), "operario@kardo.pe", "190.12.0.1");

        assertThat(summary.totalInserted()).isEqualTo(1);
        assertThat(summary.uploadId()).isNotNull();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT uploaded_by || '|' || file_name || '|' || client_ip
                FROM inventory_uploads WHERE upload_id = ?
                """, String.class, summary.uploadId()))
                .isEqualTo("operario@kardo.pe|correcciones-1-filas.json|190.12.0.1");

        // El lote queda ligado al asiento, de modo que tambien puede revertirse
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_batches WHERE upload_id = ?", Integer.class, summary.uploadId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Reenviar las mismas correcciones no duplica la mercaderia")
    void correccionIdempotente() {
        var filas = List.of(fila("SKU-FIX-2"));
        bulkFixService.apply(storeId, filas, "operario@kardo.pe", "190.12.0.1");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> bulkFixService.apply(storeId, filas, "operario@kardo.pe", "190.12.0.1"))
                .isInstanceOf(DuplicateUploadException.class);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM inventory_batches b
                JOIN products p USING (product_id)
                WHERE b.store_id = ? AND p.sku = 'SKU-FIX-2'
                """, Integer.class, storeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("La IP se borra pasada la ventana de retencion; el resto del asiento queda")
    void retencionDeIp() {
        var reciente = bulkFixService.apply(storeId, List.of(fila("SKU-FIX-3")), "operario@kardo.pe", "190.12.0.3");
        var antigua = bulkFixService.apply(storeId, List.of(fila("SKU-FIX-4")), "operario@kardo.pe", "190.12.0.4");

        // Se envejece artificialmente una de las dos cargas
        jdbcTemplate.update("""
                UPDATE inventory_uploads SET created_at = CURRENT_TIMESTAMP - INTERVAL '200 days'
                WHERE upload_id = ?
                """, antigua.uploadId());

        retentionScheduler.purgeOldClientIps();

        assertThat(ip(antigua.uploadId())).isNull();
        assertThat(ip(reciente.uploadId())).isEqualTo("190.12.0.3");

        // Lo que importa para auditar sigue ahi
        assertThat(jdbcTemplate.queryForObject(
                "SELECT uploaded_by FROM inventory_uploads WHERE upload_id = ?", String.class, antigua.uploadId()))
                .isEqualTo("operario@kardo.pe");
    }

    private String ip(long uploadId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_ip FROM inventory_uploads WHERE upload_id = ?", String.class, uploadId);
    }

    private static SpreadsheetRowDto fila(String sku) {
        return new SpreadsheetRowDto(1, sku, null, "Producto corregido", "VARIOS", "UNIDAD",
                new BigDecimal("1.50"), new BigDecimal("2.50"), "L-FIX",
                null, LocalDate.now().plusMonths(6), 10);
    }
}
