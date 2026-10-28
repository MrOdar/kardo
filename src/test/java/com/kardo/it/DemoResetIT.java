package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.repository.DemoRepository;
import com.kardo.returns.SupplierReturnService;
import com.kardo.sales.FefoSaleService;
import com.kardo.waste.WasteService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * La puerta de entrada del evaluador, ejercitada de verdad.
 *
 * <p><b>Por que existe este archivo.</b> {@code sp_reset_demo_environment()}
 * son mil lineas de PL/pgSQL que siembran la tienda 999, y hasta ahora lo unico
 * que las tocaba eran pruebas con el repositorio simulado: comprobaban que el
 * controlador llama al metodo, no que el procedimiento funcione. Un fallo ahi
 * no rompe una pantalla secundaria, rompe lo primero que ve quien evalua el
 * proyecto.
 *
 * <p><b>El fallo concreto que vigila.</b> {@code daily_sales.batch_id},
 * {@code waste_log.batch_id} y {@code supplier_returns.batch_id} son
 * {@code ON DELETE RESTRICT}. Mientras la demo esta recien sembrada no hay
 * ninguna de esas filas y el reset pasa; en cuanto alguien la <em>usa</em> -que
 * es para lo que esta- el {@code DELETE FROM inventory_batches} del
 * procedimiento choca con la clave foranea y la demo queda inservible hasta que
 * alguien entre a la base a mano. Ya paso una vez con los canjes. Por eso esta
 * prueba no comprueba el reset en frio: primero gasta la demo y luego la
 * restaura.
 */
class DemoResetIT extends PostgresIntegrationTest {

    private static final String OPERARIO = "operario@kardo.pe";
    private static final String GERENTE = "demo@kardo.pe";

    /** Lo que sql/16 siembra. Si cambia el catalogo, esta cifra cambia con el. */
    private static final int LOTES_SEMBRADOS = 88;

    @Autowired
    private DemoRepository demoRepository;
    @Autowired
    private FefoSaleService saleService;
    @Autowired
    private WasteService wasteService;
    @Autowired
    private SupplierReturnService returnService;

    @Test
    @DisplayName("Siembra el escenario completo con alertas FEFO que de verdad vencen pronto")
    void siembraElEscenario() {
        demoRepository.resetDemoEnvironment();

        assertThat(lotes()).isEqualTo(LOTES_SEMBRADOS);

        // Una demo sin nada venciendo no enseña nada: el semaforo saldria en
        // verde y el evaluador no tendria que decidir nada.
        assertThat(cuenta("""
                SELECT count(*) FROM inventory_batches
                 WHERE store_id = ? AND batch_status = 'ACTIVE'
                   AND expiration_date - CURRENT_DATE BETWEEN 0 AND 2
                """)).isPositive();

        // Y al menos un lote tiene que haber entrado por guia, porque el canje
        // solo se ofrece sobre esos: sin ninguno, ese circuito no se puede
        // demostrar aunque el codigo funcione.
        assertThat(cuenta("""
                SELECT count(*) FROM inventory_batches b
                 WHERE b.store_id = ? AND b.receipt_item_id IS NOT NULL
                """)).isPositive();
    }

    @Test
    @DisplayName("Se puede restaurar despues de usarla, que es cuando antes se atrancaba")
    void sobreviveAlUso() {
        demoRepository.resetDemoEnvironment();

        // Una venta -> daily_sales (RESTRICT)
        saleService.sell(DemoRepository.DEMO_STORE_ID, skuConStock(), 2, OPERARIO);

        // Una merma aprobada -> waste_log (RESTRICT)
        long loteMerma = loteConStock();
        wasteService.report(DemoRepository.DEMO_STORE_ID, loteMerma, 2, "DAMAGE", OPERARIO);
        wasteService.approve(DemoRepository.DEMO_STORE_ID, ultimaMerma(), GERENTE);

        // Un canje -> supplier_returns (RESTRICT)
        returnService.declare(DemoRepository.DEMO_STORE_ID, loteConGuia(), 2, OPERARIO);

        // Aqui es donde reventaba.
        demoRepository.resetDemoEnvironment();

        assertThat(lotes()).isEqualTo(LOTES_SEMBRADOS);
        assertThat(cuenta("SELECT count(*) FROM daily_sales WHERE store_id = ?")).isZero();
        assertThat(cuenta("SELECT count(*) FROM waste_log WHERE store_id = ?")).isZero();
        assertThat(cuenta("SELECT count(*) FROM supplier_returns WHERE store_id = ?")).isZero();
    }

    @Test
    @DisplayName("Restaurar dos veces seguidas deja el mismo escenario, no el doble")
    void esIdempotente() {
        demoRepository.resetDemoEnvironment();
        demoRepository.resetDemoEnvironment();
        demoRepository.resetDemoEnvironment();

        assertThat(lotes()).isEqualTo(LOTES_SEMBRADOS);
        // El historico de carga tambien: si se acumulara, la pestaña de Excel
        // mostraria una carga nueva por cada clic en "Restaurar tienda demo".
        assertThat(cuenta("SELECT count(*) FROM inventory_uploads WHERE store_id = ?")).isEqualTo(1);
    }

    /**
     * La huella sembrada no puede ser la del Excel que se reparte en
     * {@code static/assets}.
     *
     * <p>La ingesta rechaza por duplicado comparando {@code (store_id, file_hash)}.
     * Si coincidieran, el evaluador que descargue ese archivo y lo suba -la
     * demostracion de la ingesta en streaming- recibiria un 409 en lugar de ver
     * nada funcionar. Son dos cargas distintas a proposito y esto lo fija.
     */
    @Test
    @DisplayName("La carga sembrada no colisiona con el Excel de ejemplo ni es un relleno")
    void laHuellaSembradaNoEstorba() throws Exception {
        demoRepository.resetDemoEnvironment();

        String huella = jdbcTemplate.queryForObject(
                "SELECT file_hash FROM inventory_uploads WHERE store_id = ?",
                String.class, DemoRepository.DEMO_STORE_ID);

        byte[] ejemplo = getClass().getResourceAsStream(
                "/static/assets/inventario_ejemplo_kardo.xlsx").readAllBytes();
        String huellaDelEjemplo = com.kardo.support.Sha256.of(
                new java.io.ByteArrayInputStream(ejemplo));

        assertThat(huella)
                .describedAs("si coincide, subir el Excel de ejemplo da 409 y la demo de ingesta muere")
                .isNotEqualTo(huellaDelEjemplo);

        // El SHA-256 de la cadena vacia. Estuvo sembrado aqui, y la interfaz lo
        // enseña en la columna HUELLA: una constante que cualquiera reconoce,
        // exhibida como prueba de trazabilidad.
        assertThat(huella)
                .describedAs("huella de relleno a la vista del evaluador")
                .isNotEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    // ---------------------------------------------------------------------

    private long lotes() {
        return cuenta("SELECT count(*) FROM inventory_batches WHERE store_id = ?");
    }

    private long cuenta(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class, DemoRepository.DEMO_STORE_ID);
    }

    private String skuConStock() {
        return jdbcTemplate.queryForObject("""
                SELECT p.sku FROM inventory_batches b JOIN products p USING (product_id)
                 WHERE b.store_id = ? AND b.batch_status = 'ACTIVE' AND b.current_qty > 5
                 ORDER BY b.expiration_date LIMIT 1
                """, String.class, DemoRepository.DEMO_STORE_ID);
    }

    private long loteConStock() {
        return jdbcTemplate.queryForObject("""
                SELECT batch_id FROM inventory_batches
                 WHERE store_id = ? AND batch_status = 'ACTIVE' AND current_qty > 5
                 ORDER BY batch_id LIMIT 1
                """, Long.class, DemoRepository.DEMO_STORE_ID);
    }

    private long loteConGuia() {
        List<Long> candidatos = jdbcTemplate.queryForList("""
                SELECT b.batch_id FROM inventory_batches b
                 WHERE b.store_id = ? AND b.batch_status = 'ACTIVE'
                   AND b.current_qty > 5 AND b.receipt_item_id IS NOT NULL
                 ORDER BY b.batch_id LIMIT 1
                """, Long.class, DemoRepository.DEMO_STORE_ID);

        assertThat(candidatos)
                .describedAs("ningun lote de la demo llego por guia: el canje no se puede demostrar")
                .isNotEmpty();
        return candidatos.get(0);
    }

    private long ultimaMerma() {
        return jdbcTemplate.queryForObject(
                "SELECT max(waste_id) FROM waste_log WHERE store_id = ?",
                Long.class, DemoRepository.DEMO_STORE_ID);
    }
}
