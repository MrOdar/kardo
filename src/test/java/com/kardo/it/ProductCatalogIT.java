package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.dto.ProductCreateRequestDto;
import com.kardo.dto.ProductUpdateRequestDto;
import com.kardo.exception.DuplicateSkuException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.ProductNotFoundException;
import com.kardo.inventory.ProductCatalogService;
import com.kardo.sales.FefoSaleService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Alta manual de un producto del catalogo.
 *
 * <p>Hasta que existio esto, el catalogo solo crecia por carga de Excel:
 * sensato para 88 productos, absurdo para añadir una gaseosa de otra marca.
 *
 * <p><b>La diferencia que se fija aqui</b> es con la ingesta masiva. Aquella
 * inserta con {@code ON CONFLICT DO UPDATE} porque un archivo del proveedor
 * trae el catalogo entero y se espera que actualice precios. Un alta manual es
 * el gesto contrario, y pisar en silencio el precio de un SKU existente es un
 * error que nadie ve hasta que la caja cobra de menos.
 */
@DisplayName("Catalogo de productos: alta y correccion")
class ProductCatalogIT extends PostgresIntegrationTest {

    private static final String AUTOR = "operario@kardo.pe";

    @Autowired
    private ProductCatalogService service;

    @Autowired
    private FefoSaleService ventas;

    private Fixtures fixtures;
    private long storeId;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbcTemplate).company("Alta SAC", "2099");
        storeId = fixtures.store("ALTA");
    }

    private ProductCreateRequestDto gaseosa(Integer cantidad, LocalDate vence) {
        return new ProductCreateRequestDto(
                "BEB-PEP-020", "7750100000011", "Gaseosa Pepsi Botella 500ml", "bebidas",
                "BOTELLA", new BigDecimal("2.10"), new BigDecimal("3.20"),
                null, cantidad, vence);
    }

    @Test
    @DisplayName("Con unidades, el producto nace vendible")
    void conUnidadesSePuedeVender() {
        var creado = service.create(storeId, gaseosa(24, LocalDate.now().plusDays(120)), AUTOR);

        assertThat(creado).containsEntry("sku", "BEB-PEP-020");
        assertThat(creado.get("batchId")).isNotEqualTo("");

        // La prueba de que de verdad nacio vendible: se vende.
        var venta = ventas.sell(storeId, "BEB-PEP-020", 3, AUTOR);
        assertThat(venta.quantity()).isEqualTo(3);
        assertThat(venta.revenue()).isEqualByComparingTo("9.60");
    }

    /**
     * Un producto sin lote no tiene existencias: no sale en el semaforo, no se
     * puede vender y no aparece en el analisis. Es legitimo darlo de alta asi
     * -el catalogo primero, la mercaderia despues- pero tiene que quedar claro
     * que no hay nada que vender.
     */
    @Test
    @DisplayName("Sin unidades entra al catalogo, pero no hay nada que vender")
    void sinUnidadesNoHayLote() {
        var creado = service.create(storeId, gaseosa(0, null), AUTOR);

        assertThat(creado).containsEntry("initialQty", 0);
        assertThat(creado.get("batchId")).isEqualTo("");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_batches WHERE store_id = ?", Integer.class, storeId))
                .isZero();
    }

    /**
     * La diferencia con la ingesta masiva, y la razon de que esta clase exista.
     */
    @Test
    @DisplayName("Un SKU que ya existe se rechaza, no se actualiza en silencio")
    void skuDuplicado() {
        service.create(storeId, gaseosa(10, LocalDate.now().plusDays(90)), AUTOR);

        assertThatThrownBy(() -> service.create(storeId, gaseosa(5, LocalDate.now().plusDays(90)), AUTOR))
                .isInstanceOf(DuplicateSkuException.class)
                // Nombra al que ya ocupa el SKU: sin eso el choque es un misterio.
                .hasMessageContaining("Gaseosa Pepsi");

        // Y lo que importa: no se duplico ni se toco nada.
        assertThat(cuantosConEseSku(storeId)).isEqualTo(1);
    }

    /**
     * El FEFO ordena por vencimiento: un lote sin fecha seria invisible al motor
     * y nunca saldria a la venta. Es peor que no crearlo, porque el operario
     * creeria tener existencias.
     */
    @Test
    @DisplayName("Unidades sin fecha de vencimiento no se aceptan")
    void unidadesSinVencimiento() {
        assertThatThrownBy(() -> service.create(storeId, gaseosa(10, null), AUTOR))
                .isInstanceOf(InvalidQuantityException.class)
                .hasMessageContaining("FEFO");

        assertThat(cuantosConEseSku(storeId)).isZero();
    }

    @Test
    @DisplayName("Un lote que naceria vencido se rechaza")
    void vencimientoEnElPasado() {
        assertThatThrownBy(() ->
                service.create(storeId, gaseosa(10, LocalDate.now().minusDays(1)), AUTOR))
                .isInstanceOf(InvalidQuantityException.class)
                .hasMessageContaining("no se podría vender");
    }

    /**
     * El grafico por familias agrupa por este campo tal cual, de modo que
     * "bebidas" y "BEBIDAS" serian dos barras distintas para lo mismo.
     */
    @Test
    @DisplayName("La familia se guarda en mayusculas, venga como venga")
    void familiaNormalizada() {
        service.create(storeId, gaseosa(0, null), AUTOR);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT p.category FROM products p
                  JOIN stores s ON s.company_id = p.company_id
                 WHERE s.store_id = ? AND p.sku = 'BEB-PEP-020'
                """, String.class, storeId))
                .isEqualTo("BEBIDAS");
    }

    @Test
    @DisplayName("Sin codigo de lote se genera uno, en vez de dejarlo vacio")
    void loteGenerado() {
        service.create(storeId, gaseosa(5, LocalDate.now().plusDays(30)), AUTOR);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT batch_code FROM inventory_batches WHERE store_id = ?", String.class, storeId))
                .contains("BEB-PEP-020");
    }

    /**
     * El SKU es unico POR COMPANIA, no globalmente: dos clientes distintos
     * pueden usar el mismo codigo para productos distintos y ninguno estorba al
     * otro. Es el mismo invariante que ya protege la venta.
     */
    @Test
    @DisplayName("Otra compania puede usar el mismo SKU sin chocar")
    void mismoSkuEnOtraCompania() {
        service.create(storeId, gaseosa(5, LocalDate.now().plusDays(30)), AUTOR);

        long otraTienda = new Fixtures(jdbcTemplate).company("Otra SAC", "2100").store("OTRA");
        var enLaOtra = service.create(otraTienda, gaseosa(5, LocalDate.now().plusDays(30)), AUTOR);

        assertThat(enLaOtra).containsEntry("sku", "BEB-PEP-020");
        // Uno en cada compania, y cada cual solo ve el suyo.
        assertThat(cuantosConEseSku(storeId)).isEqualTo(1);
        assertThat(cuantosConEseSku(otraTienda)).isEqualTo(1);
    }

    @Test
    @DisplayName("El lote nuevo cuelga de la tienda, no de la compania entera")
    void elLoteEsDeLaTienda() {
        long otraTienda = fixtures.store("HERMANA");

        service.create(storeId, gaseosa(7, LocalDate.now().plusDays(60)), AUTOR);

        // Misma compania, otra tienda: ve el producto en su catalogo pero no
        // tiene sus existencias.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_batches WHERE store_id = ?", Integer.class, otraTienda))
                .isZero();
        assertThatThrownBy(() -> ventas.sell(otraTienda, "BEB-PEP-020", 1, AUTOR))
                .hasMessageContaining("Stock insuficiente");
    }
    // -----------------------------------------------------------------------
    // Correccion de la ficha
    // -----------------------------------------------------------------------

    /**
     * La parte que hay que entender de corregir un precio: rige desde la
     * proxima venta, tambien para los lotes que ya estan en el anaquel, porque
     * el procedimiento lee el precio del catalogo salvo que el lote tenga
     * rebaja propia.
     */
    @Test
    @DisplayName("El precio nuevo rige desde la proxima venta, tambien en lotes ya recibidos")
    void elPrecioNuevoRigeDeInmediato() {
        service.create(storeId, gaseosa(20, LocalDate.now().plusDays(90)), AUTOR);
        var antes = ventas.sell(storeId, "BEB-PEP-020", 2, AUTOR);
        assertThat(antes.revenue()).isEqualByComparingTo("6.40");   // 2 x 3.20

        service.update(storeId, "BEB-PEP-020", new ProductUpdateRequestDto(
                "Gaseosa Pepsi Botella 500ml", "BEBIDAS", null, "BOTELLA",
                new BigDecimal("2.10"), new BigDecimal("4.00")), AUTOR);

        // Mismo lote, precio nuevo.
        var despues = ventas.sell(storeId, "BEB-PEP-020", 2, AUTOR);
        assertThat(despues.revenue()).isEqualByComparingTo("8.00");
    }

    /**
     * Y la que sorprende: el costo NO se propaga. Cada lote guarda el costo al
     * que entro SU mercaderia, y eso es lo que ancla el margen de lo ya
     * recibido. Si corregir la ficha reescribiera el costo de lo comprado hace
     * tres meses, el margen historico cambiaria retroactivamente y el analisis
     * dejaria de poder compararse consigo mismo.
     */
    @Test
    @DisplayName("El costo nuevo no toca los lotes que ya entraron")
    void elCostoNoSePropagaALosLotes() {
        service.create(storeId, gaseosa(20, LocalDate.now().plusDays(90)), AUTOR);

        var corregido = service.update(storeId, "BEB-PEP-020", new ProductUpdateRequestDto(
                "Gaseosa Pepsi Botella 500ml", "BEBIDAS", null, "BOTELLA",
                new BigDecimal("9.99"), new BigDecimal("3.20")), AUTOR);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_cost FROM inventory_batches WHERE store_id = ?", BigDecimal.class, storeId))
                .isEqualByComparingTo("2.10");
        // Y se dice cuantos lotes conservan el suyo, para poder avisar.
        assertThat(corregido).containsEntry("batchesKeepingTheirCost", 1);

        // El margen de la venta sale del costo del LOTE, no del de la ficha.
        var venta = ventas.sell(storeId, "BEB-PEP-020", 1, AUTOR);
        assertThat(venta.cogs()).isEqualByComparingTo("2.10");
    }

    @Test
    @DisplayName("Las ventas ya registradas no se mueven")
    void lasVentasPasadasQuedanCongeladas() {
        service.create(storeId, gaseosa(20, LocalDate.now().plusDays(90)), AUTOR);
        ventas.sell(storeId, "BEB-PEP-020", 3, AUTOR);

        service.update(storeId, "BEB-PEP-020", new ProductUpdateRequestDto(
                "Gaseosa Pepsi Botella 500ml", "BEBIDAS", null, "BOTELLA",
                new BigDecimal("9.99"), new BigDecimal("9.99")), AUTOR);

        // daily_sales congela precio y costo en cada fila: el historico es
        // historico, no una vista del catalogo de hoy.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_retail_price FROM daily_sales WHERE store_id = ?",
                BigDecimal.class, storeId))
                .isEqualByComparingTo("3.20");
    }

    @Test
    @DisplayName("Corregir un SKU que no existe es 404, no un alta silenciosa")
    void corregirLoQueNoExiste() {
        assertThatThrownBy(() -> service.update(storeId, "NO-EXISTE", new ProductUpdateRequestDto(
                "Fantasma", "VARIOS", null, "UNIDAD",
                new BigDecimal("1.00"), new BigDecimal("2.00")), AUTOR))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("No se puede corregir el producto de otra compania")
    void noSeCorrigeElDeOtraCompania() {
        service.create(storeId, gaseosa(5, LocalDate.now().plusDays(30)), AUTOR);
        long ajena = new Fixtures(jdbcTemplate).company("Ajena SAC", "2111").store("AJENA");

        // Mismo SKU, otra compania: para ella ese producto no existe.
        assertThatThrownBy(() -> service.update(ajena, "BEB-PEP-020", new ProductUpdateRequestDto(
                "Secuestrado", "VARIOS", null, "UNIDAD",
                new BigDecimal("1.00"), new BigDecimal("1.00")), AUTOR))
                .isInstanceOf(ProductNotFoundException.class);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT p.name FROM products p JOIN stores s ON s.company_id = p.company_id
                 WHERE s.store_id = ? AND p.sku = 'BEB-PEP-020'
                """, String.class, storeId))
                .isEqualTo("Gaseosa Pepsi Botella 500ml");
    }

    @Test
    @DisplayName("La familia tambien se normaliza al corregir")
    void familiaNormalizadaAlCorregir() {
        service.create(storeId, gaseosa(0, null), AUTOR);

        service.update(storeId, "BEB-PEP-020", new ProductUpdateRequestDto(
                "Gaseosa Pepsi Botella 500ml", "  bebidas  ", null, "BOTELLA",
                new BigDecimal("2.10"), new BigDecimal("3.20")), AUTOR);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT p.category FROM products p JOIN stores s ON s.company_id = p.company_id
                 WHERE s.store_id = ? AND p.sku = 'BEB-PEP-020'
                """, String.class, storeId))
                .isEqualTo("BEBIDAS");
    }

    /**
     * Acotado a la compania de la tienda, no global: Fixtures no limpia tablas
     * entre pruebas -es mas barato y obliga a que las consultas esten bien
     * acotadas- asi que el mismo SKU existe en varias companias a la vez.
     */
    private int cuantosConEseSku(long store) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM products p
                  JOIN stores s ON s.company_id = p.company_id
                 WHERE s.store_id = ? AND p.sku = 'BEB-PEP-020'
                """, Integer.class, store);
    }
}
