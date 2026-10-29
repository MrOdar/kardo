package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.dto.SaleRecordDto;
import com.kardo.sales.FefoSaleService;
import com.kardo.sales.SalesLedgerService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Las ventas, miradas despues de registrarlas.
 *
 * <p><b>Por que existe esta pantalla y por tanto estas pruebas.</b>
 * {@code daily_sales} se escribia desde la caja y desde la carga por Excel, y
 * se leia desde un solo sitio -{@code InventoryInsightsService}, y ahi como
 * insumo del ritmo de salida-. Se podian registrar 72 ventas y luego no
 * encontrarlas en ninguna parte.
 *
 * <p>Lo que se fija aqui es lo que esta vista afirma y ninguna otra puede: que
 * cada fila traiga <b>el lote que atendio la venta</b>, que el precio sea el de
 * ese lote y no el del catalogo, y que los totales sean de la ventana entera y
 * no de las filas que caben en pantalla.
 */
@DisplayName("Ventas registradas")
class SalesLedgerIT extends PostgresIntegrationTest {

    private static final String OPERARIO = "operario@kardo.pe";

    @Autowired
    private SalesLedgerService ledger;

    @Autowired
    private FefoSaleService ventas;

    private Fixtures fixtures;
    private long storeId;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbcTemplate).company("Libro SAC", "2088");
        storeId = fixtures.store("LIBRO");
    }

    /**
     * La afirmacion central de la pantalla: se ve de que lote salio cada venta.
     * Sin esa columna, una venta es "salieron 4 unidades" y no hay forma de
     * comprobar que el FEFO hizo su trabajo.
     */
    @Test
    @DisplayName("Cada venta dice de que lote salio, y es el que vence antes")
    void muestraElLoteQueAtendioLaVenta() {
        long producto = fixtures.product(storeId, "LECHE-1", "Leche entera", "LACTEOS", "3.00", "5.00");
        fixtures.batch(storeId, producto, "L-PRONTO", LocalDate.now().plusDays(3), 10, "3.00");
        fixtures.batch(storeId, producto, "L-TARDE", LocalDate.now().plusDays(60), 10, "3.20");

        ventas.sell(storeId, "LECHE-1", 4, OPERARIO);

        var libro = ledger.recent(storeId, 30, 100, 0);

        assertThat(libro.sales()).singleElement().satisfies(v -> {
            assertThat(v.batchCode()).isEqualTo("L-PRONTO");
            assertThat(v.expiresOn()).isEqualTo(LocalDate.now().plusDays(3));
            assertThat(v.sku()).isEqualTo("LECHE-1");
            assertThat(v.productName()).isEqualTo("Leche entera");
            assertThat(v.quantity()).isEqualTo(4);
            assertThat(v.amount()).isEqualByComparingTo("20.00");
            // 4 x (5.00 - 3.00)
            assertThat(v.margin()).isEqualByComparingTo("8.00");
            assertThat(v.operator()).isEqualTo(OPERARIO);
        });
    }

    @Test
    @DisplayName("Una venta repartida entre dos lotes sale como dos lineas")
    void unaVentaEnDosLotesSonDosLineas() {
        long producto = fixtures.product(storeId, "PAN-1", "Pan frances", "PANADERIA", "0.20", "0.50");
        fixtures.batch(storeId, producto, "L-HOY", LocalDate.now().plusDays(1), 5, "0.20");
        fixtures.batch(storeId, producto, "L-LUEGO", LocalDate.now().plusDays(20), 20, "0.25");

        ventas.sell(storeId, "PAN-1", 12, OPERARIO);

        var libro = ledger.recent(storeId, 30, 100, 0);

        // Dos asientos con el costo de SU lote: por eso el margen de cada linea
        // es distinto aunque el precio cobrado sea el mismo.
        assertThat(libro.sales()).hasSize(2)
                .extracting("batchCode", "quantity")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("L-HOY", 5),
                        org.assertj.core.groups.Tuple.tuple("L-LUEGO", 7));
        assertThat(libro.totalUnits()).isEqualTo(12);
    }

    /**
     * El precio que se ensena es el que cobro la caja. Si la pantalla mostrara
     * el del catalogo, el margen de un lote rebajado saldria inflado justo en
     * los lotes que el propio sistema mando rebajar.
     */
    @Test
    @DisplayName("Ensena el precio rebajado del lote, y lo marca como tal")
    void marcaLoRebajado() {
        long producto = fixtures.product(storeId, "YOG-1", "Yogurt", "LACTEOS", "4.00", "7.00");
        long lote = fixtures.batch(storeId, producto, "L-REBAJA", LocalDate.now().plusDays(2), 10, "4.00");
        jdbcTemplate.update("UPDATE inventory_batches SET markdown_price = 4.90 WHERE batch_id = ?", lote);

        ventas.sell(storeId, "YOG-1", 3, OPERARIO);

        assertThat(ledger.recent(storeId, 30, 100, 0).sales()).singleElement().satisfies(v -> {
            assertThat(v.price()).isEqualByComparingTo("4.90");
            assertThat(v.discounted()).isTrue();
            // 3 x (4.90 - 4.00): el margen que queda tras ceder la rebaja
            assertThat(v.margin()).isEqualByComparingTo("2.70");
        });
    }

    @Test
    @DisplayName("Una venta a precio de catalogo no se marca como rebajada")
    void noMarcaLoQueNoEstaRebajado() {
        long producto = fixtures.product(storeId, "GAS-1", "Gaseosa", "BEBIDAS", "2.00", "3.50");
        fixtures.batch(storeId, producto, "L-GAS", LocalDate.now().plusDays(90), 50, "2.00");

        ventas.sell(storeId, "GAS-1", 2, OPERARIO);

        assertThat(ledger.recent(storeId, 30, 100, 0).sales())
                .singleElement()
                .satisfies(v -> assertThat(v.discounted()).isFalse());
    }

    /**
     * El fallo que esto evita es de los que no se ven: si los totales se
     * dedujeran de las filas devueltas -acotadas por LIMIT- la pantalla
     * mostraria dos cifras que no cuadran y nadie sabria cual mirar.
     */
    @Test
    @DisplayName("Los totales son de la ventana entera, no de las filas que caben")
    void losTotalesNoDependenDelLimite() {
        long producto = fixtures.product(storeId, "ABA-1", "Arroz", "ABARROTES", "3.00", "4.50");
        fixtures.batch(storeId, producto, "L-ARROZ", LocalDate.now().plusDays(200), 100, "3.00");

        for (int i = 0; i < 6; i++) {
            ventas.sell(storeId, "ABA-1", 2, OPERARIO);
        }

        var acotado = ledger.recent(storeId, 30, 2, 0);

        assertThat(acotado.sales()).hasSize(2);
        assertThat(acotado.totalSales()).isEqualTo(6);
        assertThat(acotado.totalUnits()).isEqualTo(12);
        assertThat(acotado.totalAmount()).isEqualByComparingTo("54.00");
        assertThat(acotado.totalMargin()).isEqualByComparingTo("18.00");
    }

    @Test
    @DisplayName("Una venta fuera de la ventana no cuenta ni aparece")
    void fueraDeLaVentanaNoCuenta() {
        long producto = fixtures.product(storeId, "VIE-1", "Viejo", "ABARROTES", "1.00", "2.00");
        long lote = fixtures.batch(storeId, producto, "L-VIEJO", LocalDate.now().plusDays(200), 50, "1.00");

        ventas.sell(storeId, "VIE-1", 3, OPERARIO);
        // Se envejece el asiento mas alla de la ventana de 7 dias.
        jdbcTemplate.update("""
                UPDATE daily_sales SET sale_timestamp = CURRENT_TIMESTAMP - INTERVAL '10 days'
                 WHERE store_id = ? AND batch_id = ?
                """, storeId, lote);

        assertThat(ledger.recent(storeId, 7, 100, 0).sales()).isEmpty();
        assertThat(ledger.recent(storeId, 7, 100, 0).totalSales()).isZero();
        // Pero en una ventana que la abarque, sigue estando.
        assertThat(ledger.recent(storeId, 30, 100, 0).totalSales()).isEqualTo(1);
    }

    @Test
    @DisplayName("Las mas recientes primero")
    void ordenadasDeLaMasRecienteALaMasVieja() {
        long producto = fixtures.product(storeId, "ORD-1", "Orden", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-ORD", LocalDate.now().plusDays(90), 50, "1.00");

        ventas.sell(storeId, "ORD-1", 1, OPERARIO);
        ventas.sell(storeId, "ORD-1", 2, OPERARIO);
        ventas.sell(storeId, "ORD-1", 3, OPERARIO);

        assertThat(ledger.recent(storeId, 30, 100, 0).sales())
                .extracting("quantity")
                .containsExactly(3, 2, 1);
    }

    @Test
    @DisplayName("Solo las ventas de la tienda del usuario")
    void aislamientoPorTienda() {
        long otra = fixtures.store("OTRA");
        long producto = fixtures.product(storeId, "AIS-1", "Aislado", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-MIO", LocalDate.now().plusDays(90), 50, "1.00");
        fixtures.batch(otra, producto, "L-AJENO", LocalDate.now().plusDays(90), 50, "1.00");

        ventas.sell(storeId, "AIS-1", 2, OPERARIO);
        ventas.sell(otra, "AIS-1", 7, "otro@kardo.pe");

        var mio = ledger.recent(storeId, 30, 100, 0);
        assertThat(mio.totalSales()).isEqualTo(1);
        assertThat(mio.sales()).singleElement()
                .satisfies(v -> assertThat(v.batchCode()).isEqualTo("L-MIO"));
    }

    @Test
    @DisplayName("Una tienda sin ventas devuelve una lista vacia y ceros, no nulos")
    void sinVentas() {
        var vacio = ledger.recent(storeId, 30, 100, 0);

        assertThat(vacio.sales()).isEmpty();
        assertThat(vacio.totalSales()).isZero();
        assertThat(vacio.totalUnits()).isZero();
        // Cero y no null: la pantalla los pinta con money() sin comprobar nada.
        assertThat(vacio.totalAmount()).isEqualByComparingTo("0");
        assertThat(vacio.totalMargin()).isEqualByComparingTo("0");
        assertThat(vacio.from()).isNull();
    }

    @Test
    @DisplayName("El ticket medio sale de los totales, no de las filas mostradas")
    void ticketMedio() {
        long producto = fixtures.product(storeId, "TKT-1", "Ticket", "ABARROTES", "1.00", "3.00");
        fixtures.batch(storeId, producto, "L-TKT", LocalDate.now().plusDays(90), 100, "1.00");

        ventas.sell(storeId, "TKT-1", 2, OPERARIO);   // 6.00
        ventas.sell(storeId, "TKT-1", 4, OPERARIO);   // 12.00

        var libro = ledger.recent(storeId, 30, 1, 0);

        assertThat(libro.sales()).hasSize(1);
        // 18.00 / 2 ventas, aunque solo se muestre una.
        assertThat(SalesLedgerService.averageTicket(libro)).isEqualByComparingTo("9.00");
    }

    // -----------------------------------------------------------------------
    // Paginacion
    // -----------------------------------------------------------------------

    /**
     * El hueco que la paginacion vino a cerrar: con 2.000 ventas en treinta
     * dias, el tope dejaba las mas viejas fuera de alcance, y ensanchar la
     * ventana tampoco servia porque el tope seguia cortando por arriba.
     */
    @Test
    @DisplayName("Con offset se sigue leyendo hacia atras, sin repetir ni saltarse ninguna")
    void paginaHaciaAtras() {
        long producto = fixtures.product(storeId, "PAG-1", "Paginado", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-PAG", LocalDate.now().plusDays(90), 100, "1.00");

        for (int i = 0; i < 7; i++) {
            ventas.sell(storeId, "PAG-1", 1, OPERARIO);
        }

        var primera = ledger.recent(storeId, 30, 3, 0);
        var segunda = ledger.recent(storeId, 30, 3, 3);
        var tercera = ledger.recent(storeId, 30, 3, 6);

        assertThat(primera.sales()).hasSize(3);
        assertThat(segunda.sales()).hasSize(3);
        assertThat(tercera.sales()).hasSize(1);

        // Ni una repetida ni una perdida entre paginas: siete ids distintos.
        var ids = java.util.stream.Stream.of(primera, segunda, tercera)
                .flatMap(p -> p.sales().stream())
                .map(SaleRecordDto::saleId)
                .toList();
        assertThat(ids).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("hasMore dice si queda algo detras, y deja de decirlo al final")
    void sabeCuandoSeAcabo() {
        long producto = fixtures.product(storeId, "FIN-1", "Final", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-FIN", LocalDate.now().plusDays(90), 50, "1.00");

        for (int i = 0; i < 5; i++) {
            ventas.sell(storeId, "FIN-1", 1, OPERARIO);
        }

        assertThat(ledger.recent(storeId, 30, 2, 0).hasMore()).isTrue();
        assertThat(ledger.recent(storeId, 30, 2, 2).hasMore()).isTrue();
        // La ultima pagina trae una sola y ya no queda nada detras.
        assertThat(ledger.recent(storeId, 30, 2, 4).hasMore()).isFalse();
    }

    /**
     * Las ventas de una carga de Excel comparten la hora del archivo, asi que
     * la paginacion tiene que seguir siendo correcta cuando el criterio de
     * orden empata en todas las filas.
     *
     * <p><b>Limite de esta prueba, dicho para que nadie se confie.</b> La
     * consulta desempata por {@code sale_id} precisamente para esto, pero
     * quitar ese desempate <em>no</em> la hace fallar: con seis filas
     * PostgreSQL elige el mismo plan y devuelve un orden estable de todos
     * modos. Hace falta mas volumen, o un plan distinto, para que el orden
     * arbitrario se manifieste. Queda como prueba de la propiedad que
     * importa -no repetir ni perder filas entre paginas- y no como guardia del
     * {@code ORDER BY}, que se sostiene por lectura del SQL.
     */
    @Test
    @DisplayName("Ventas del mismo instante no se repiten ni se pierden entre paginas")
    void empateDeFechaNoRompeLaPaginacion() {
        long producto = fixtures.product(storeId, "EMP-1", "Empate", "ABARROTES", "1.00", "2.00");
        long lote = fixtures.batch(storeId, producto, "L-EMP", LocalDate.now().plusDays(90), 50, "1.00");

        for (int i = 0; i < 6; i++) {
            ventas.sell(storeId, "EMP-1", 1, OPERARIO);
        }
        // Todas al mismo instante, como las de un archivo.
        jdbcTemplate.update("""
                UPDATE daily_sales SET sale_timestamp = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE store_id = ? AND batch_id = ?
                """, storeId, lote);

        var ids = java.util.stream.Stream.of(
                        ledger.recent(storeId, 30, 2, 0),
                        ledger.recent(storeId, 30, 2, 2),
                        ledger.recent(storeId, 30, 2, 4))
                .flatMap(p -> p.sales().stream())
                .map(SaleRecordDto::saleId)
                .toList();

        assertThat(ids).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Un offset mas alla del final devuelve vacio, no un error")
    void offsetDemasiadoGrande() {
        var vacio = ledger.recent(storeId, 30, 10, 500);

        assertThat(vacio.sales()).isEmpty();
        assertThat(vacio.hasMore()).isFalse();
    }
}
