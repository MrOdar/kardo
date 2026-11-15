package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.UploadNotFoundException;
import com.kardo.exception.UploadNotRevertibleException;
import com.kardo.ingest.UploadRollbackService;
import com.kardo.sales.FefoSaleService;
import com.kardo.waste.WasteService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reversion de una carga: deshacer solo lo que nadie ha usado todavia.
 *
 * <p>La regla de "todo o nada" es la que impide dejar ventas apuntando a un lote
 * que ya no existe, asi que merece prueba contra la base real y no solo sobre la
 * funcion pura que decide los bloqueantes.
 */
class UploadRollbackIT extends PostgresIntegrationTest {

    @Autowired
    private UploadRollbackService rollbackService;

    @Autowired
    private FefoSaleService fefoSaleService;

    @Autowired
    private WasteService wasteService;

    private long storeId;
    private long uploadId;
    private long primerLote;

    @BeforeEach
    void seed() {
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Rollback", "20700000007").store("TIENDA-R");
        long producto = fixtures.product(storeId, "SKU-ROLL", "Producto de carga", "2.00", "3.00");

        uploadId = fixtures.upload(storeId, "a".repeat(64), "inventario-octubre.xlsx");
        primerLote = fixtures.batch(storeId, producto, "LOTE-CARGA-1", LocalDate.now().plusDays(20), 24, "2.00");
        long segundo = fixtures.batch(storeId, producto, "LOTE-CARGA-2", LocalDate.now().plusDays(40), 36, "2.00");
        fixtures.attachToUpload(primerLote, uploadId);
        fixtures.attachToUpload(segundo, uploadId);
    }

    @Test
    @DisplayName("Una carga intacta se revierte entera y deja constancia de quien")
    void reversionDeCargaIntacta() {
        var resultado = rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe");

        assertThat(resultado.removedBatches()).isEqualTo(2);
        assertThat(lotesDeLaCarga()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolled_back_by FROM inventory_uploads WHERE upload_id = ?", String.class, uploadId))
                .isEqualTo("gerente@kardo.pe");
    }

    @Test
    @DisplayName("El catalogo sobrevive a la reversion")
    void elCatalogoNoSeToca() {
        rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM products p
                JOIN stores s ON s.company_id = p.company_id
                WHERE s.store_id = ? AND p.sku = 'SKU-ROLL'
                """, Integer.class, storeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Una venta sobre un lote de la carga bloquea la reversion entera")
    void ventaBloqueaLaReversion() {
        fefoSaleService.sell(storeId, "SKU-ROLL", 1, "operario@kardo.pe");

        assertThatThrownBy(() -> rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe"))
                .isInstanceOf(UploadNotRevertibleException.class)
                .satisfies(ex -> assertThat(((UploadNotRevertibleException) ex).blockers())
                        .anySatisfy(blocker -> assertThat(blocker).contains("LOTE-CARGA-1")));

        // Todo o nada: el segundo lote, intacto, tampoco desaparece
        assertThat(lotesDeLaCarga()).isEqualTo(2);
    }

    @Test
    @DisplayName("Una merma pendiente tambien bloquea")
    void mermaBloqueaLaReversion() {
        wasteService.report(storeId, primerLote, 4, "DAMAGE", "operario@kardo.pe");

        assertThatThrownBy(() -> rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe"))
                .isInstanceOf(UploadNotRevertibleException.class);

        assertThat(lotesDeLaCarga()).isEqualTo(2);
    }

    @Test
    @DisplayName("Revertir dos veces no vuelve a borrar nada")
    void reversionIdempotente() {
        rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe");

        assertThatThrownBy(() -> rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe"))
                .isInstanceOf(UploadNotRevertibleException.class)
                .hasMessageContaining("ya fue revertida");
    }

    @Test
    @DisplayName("Una carga de otra tienda no existe para esta")
    void cargaDeOtraTienda() {
        Fixtures otra = new Fixtures(jdbcTemplate);
        long otraTienda = otra.company("Ajena Rollback", "20800000008").store("TIENDA-Z");
        long ajena = otra.upload(otraTienda, "b".repeat(64), "ajeno.xlsx");

        assertThatThrownBy(() -> rollbackService.rollback(storeId, ajena, "gerente@kardo.pe"))
                .isInstanceOf(UploadNotFoundException.class);
    }

    @Test
    @DisplayName("Tras revertir, el mismo archivo puede volver a cargarse")
    void laHuellaDejaDeBloquear() {
        rollbackService.rollback(storeId, uploadId, "gerente@kardo.pe");

        // La unicidad es parcial: solo rige entre cargas vigentes
        Fixtures fixtures = new Fixtures(jdbcTemplate);
        long nueva = fixtures.upload(storeId, "a".repeat(64), "inventario-octubre.xlsx");

        assertThat(nueva).isNotEqualTo(uploadId);
    }

    private int lotesDeLaCarga() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_batches WHERE upload_id = ?", Integer.class, uploadId);
    }
}
