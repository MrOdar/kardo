package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.dto.SalesFixRowDto;
import com.kardo.exception.CorruptSpreadsheetException;
import com.kardo.exception.DuplicateUploadException;
import com.kardo.exception.UploadNotRevertibleException;
import com.kardo.ingest.ExcelUploadService;
import com.kardo.ingest.SalesExcelIngestService;
import com.kardo.ingest.SalesUploadService;
import com.kardo.ingest.UploadRollbackService;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Registro de ventas desde la hoja "Ventas" del Excel.
 *
 * <p><b>Lo que de verdad se vigila aqui</b> no es que el archivo se lea -eso lo
 * hace fastexcel- sino las tres cosas que pueden dejar datos mal sin que nada
 * se rompa:
 *
 * <ol>
 *   <li><b>Que una fila mala no se lleve las buenas por delante.</b> En
 *   PostgreSQL un error aborta la transaccion entera, asi que sin un savepoint
 *   por fila la primera linea sin stock haria fallar todas las de detras con
 *   "current transaction is aborted". La cuarentena se convertiria en una lista
 *   de victimas en vez de un diagnostico, y el operario corregiria filas que
 *   nunca estuvieron mal.</li>
 *
 *   <li><b>Que el mismo archivo no entre dos veces.</b> Una venta duplicada no
 *   se puede revertir: devolver unidades a un lote que ya se agoto, o que
 *   entretanto se dio de baja, es reconstruir un estado que la base no guarda.
 *   La huella es la unica guarda que hay.</li>
 *
 *   <li><b>Que la fecha de la hoja llegue a `daily_sales`.</b> La ventana de 30
 *   dias con la que se calcula la rotacion se mide sobre `sale_timestamp`: si
 *   las ventas de una semana se apilan en el dia de la carga, el ritmo de salida
 *   de todo el catalogo sale mal y con el la cobertura que decide las compras.</li>
 * </ol>
 */
@DisplayName("Ingesta de ventas por Excel")
class SalesIngestIT extends PostgresIntegrationTest {

    private static final String OPERARIO = "operario@kardo.pe";

    @Autowired
    private SalesExcelIngestService ingestService;

    @Autowired
    private SalesUploadService uploadService;

    @Autowired
    private UploadRollbackService rollbackService;

    private Fixtures fixtures;
    private long storeId;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbcTemplate).company("Ventas SAC", "2077");
        storeId = fixtures.store("VENTAS");
    }

    // -----------------------------------------------------------------------
    // El camino normal
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Registra cada linea descontando del lote que vence antes")
    void registraPorFefo() throws IOException {
        long producto = fixtures.product(storeId, "LECHE-1", "Leche entera", "LACTEOS", "3.00", "5.00");
        long pronto = fixtures.batch(storeId, producto, "L-PRONTO",
                LocalDate.now().plusDays(3), 10, "3.00");
        long tarde = fixtures.batch(storeId, producto, "L-TARDE",
                LocalDate.now().plusDays(60), 10, "3.20");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("LECHE-1", 4)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isEqualTo(1);
        assertThat(resumen.totalUnits()).isEqualTo(4);
        assertThat(resumen.totalQuarantined()).isZero();
        // 4 x 5.00: el precio sale del catalogo, no de la hoja.
        assertThat(resumen.revenue()).isEqualByComparingTo("20.00");

        assertThat(stock(pronto)).isEqualTo(6);
        assertThat(stock(tarde)).isEqualTo(10);
    }

    @Test
    @DisplayName("Una venta que no cabe en un lote sigue en el siguiente")
    void parteEntreVariosLotes() throws IOException {
        long producto = fixtures.product(storeId, "PAN-1", "Pan frances", "PANADERIA", "0.20", "0.50");
        long pronto = fixtures.batch(storeId, producto, "L-HOY", LocalDate.now().plusDays(1), 5, "0.20");
        long tarde = fixtures.batch(storeId, producto, "L-LUEGO", LocalDate.now().plusDays(20), 20, "0.25");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("PAN-1", 12)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isEqualTo(1);
        assertThat(stock(pronto)).isZero();
        assertThat(stock(tarde)).isEqualTo(13);

        // Dos asientos, uno por lote, con el costo de cada uno.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE store_id = ?", Integer.class, storeId))
                .isEqualTo(2);
    }

    /**
     * El precio rebajado del lote manda sobre el de lista. Es el mismo invariante
     * que `sql/11` puso en la venta por caja, comprobado por la via nueva: una
     * carga de ventas que cobrara el precio de lista inflaria el margen justo en
     * los lotes que el sistema mando rebajar.
     */
    @Test
    @DisplayName("Cobra el precio rebajado del lote, no el de lista")
    void respetaLaRebaja() throws IOException {
        long producto = fixtures.product(storeId, "YOG-1", "Yogurt", "LACTEOS", "4.00", "7.00");
        long lote = fixtures.batch(storeId, producto, "L-REBAJA", LocalDate.now().plusDays(2), 10, "4.00");
        jdbcTemplate.update("UPDATE inventory_batches SET markdown_price = 4.90 WHERE batch_id = ?", lote);

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("YOG-1", 3)))),
                storeId, OPERARIO);

        assertThat(resumen.revenue()).isEqualByComparingTo("14.70");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_retail_price FROM daily_sales WHERE store_id = ?", BigDecimal.class, storeId))
                .isEqualByComparingTo("4.90");
    }

    // -----------------------------------------------------------------------
    // La fecha, que es lo que sql/17 vino a permitir
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("La fecha de la hoja es la que queda en el asiento")
    void respetaLaFechaDeLaHoja() throws IOException {
        long producto = fixtures.product(storeId, "GAS-1", "Gaseosa", "BEBIDAS", "2.00", "3.50");
        fixtures.batch(storeId, producto, "L-GAS", LocalDate.now().plusDays(90), 50, "2.00");

        LocalDate anteayer = LocalDate.now().minusDays(2);
        ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("GAS-1", 5, anteayer.toString())))),
                storeId, OPERARIO);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sale_timestamp::date FROM daily_sales WHERE store_id = ?",
                LocalDate.class, storeId))
                .isEqualTo(anteayer);
    }

    @Test
    @DisplayName("Sin fecha, la venta es de hoy")
    void sinFechaEsHoy() throws IOException {
        long producto = fixtures.product(storeId, "GAS-2", "Gaseosa 1L", "BEBIDAS", "3.00", "4.50");
        fixtures.batch(storeId, producto, "L-GAS2", LocalDate.now().plusDays(90), 50, "3.00");

        ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        // Tercera celda ausente: la columna es opcional.
                        List.of("GAS-2", 2)))),
                storeId, OPERARIO);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sale_timestamp::date FROM daily_sales WHERE store_id = ?",
                LocalDate.class, storeId))
                .isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("Una fecha futura no entra: falsearia la rotacion hacia adelante")
    void rechazaFechaFutura() throws IOException {
        long producto = fixtures.product(storeId, "GAS-3", "Gaseosa 3L", "BEBIDAS", "5.00", "8.00");
        fixtures.batch(storeId, producto, "L-GAS3", LocalDate.now().plusDays(90), 50, "5.00");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("GAS-3", 2, LocalDate.now().plusDays(1).toString())))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isZero();
        assertThat(resumen.quarantinedRows()).singleElement()
                .satisfies(fila -> assertThat(fila.errorCode()).isEqualTo("FECHA_FUTURA"));
        assertThat(ventasRegistradas()).isZero();
    }

    // -----------------------------------------------------------------------
    // La cuarentena, y el savepoint que la hace posible
    // -----------------------------------------------------------------------

    /**
     * La prueba insignia de este archivo. Sin un savepoint por fila, la linea 2
     * abortaria la transaccion y las lineas 3 y 4 fallarian con "current
     * transaction is aborted" aunque sean perfectas: el operario veria tres
     * filas en cuarentena y solo una estaria mal.
     */
    @Test
    @DisplayName("Una linea sin stock no se lleva por delante a las que vienen detras")
    void unaFilaMalaNoTumbaElArchivo() throws IOException {
        long leche = fixtures.product(storeId, "L-1", "Leche", "LACTEOS", "3.00", "5.00");
        long pan = fixtures.product(storeId, "P-1", "Pan", "PANADERIA", "0.20", "0.50");
        long queso = fixtures.product(storeId, "Q-1", "Queso", "LACTEOS", "8.00", "12.00");
        fixtures.batch(storeId, leche, "L-LECHE", LocalDate.now().plusDays(10), 20, "3.00");
        fixtures.batch(storeId, pan, "L-PAN", LocalDate.now().plusDays(2), 3, "0.20");
        fixtures.batch(storeId, queso, "L-QUESO", LocalDate.now().plusDays(15), 10, "8.00");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("L-1", 5),      // bien
                        List.of("P-1", 99),     // solo hay 3: esta es la mala
                        List.of("Q-1", 2),      // bien, y va DESPUES de la mala
                        List.of("L-1", 1)))),   // bien
                storeId, OPERARIO);

        assertThat(resumen.totalRowsProcessed()).isEqualTo(4);
        assertThat(resumen.totalRegistered()).isEqualTo(3);
        assertThat(resumen.totalUnits()).isEqualTo(8);
        assertThat(resumen.quarantinedRows()).singleElement()
                .satisfies(fila -> {
                    assertThat(fila.errorCode()).isEqualTo("SIN_STOCK");
                    // El mensaje del procedimiento dice cuantas faltaron, que es
                    // lo que el operario necesita para corregir la cantidad.
                    assertThat(fila.message()).contains("96");
                    assertThat(fila.originalValues()).containsEntry("quantity", "99");
                });

        // Y lo que importa de verdad: las buenas estan en la base.
        assertThat(ventasRegistradas()).isEqualTo(3);
    }

    @Test
    @DisplayName("Un SKU que no existe se dice sin nombrar la tabla")
    void skuDesconocido() throws IOException {
        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("NO-EXISTE", 1)))),
                storeId, OPERARIO);

        assertThat(resumen.quarantinedRows()).singleElement()
                .satisfies(fila -> {
                    assertThat(fila.errorCode()).isEqualTo("SKU_DESCONOCIDO");
                    assertThat(fila.message()).doesNotContain("products");
                });
    }

    @Test
    @DisplayName("Una cantidad que no es un numero entero se rechaza sin truncar")
    void cantidadNoEntera() throws IOException {
        long producto = fixtures.product(storeId, "A-1", "Arroz", "ABARROTES", "3.00", "4.50");
        fixtures.batch(storeId, producto, "L-ARROZ", LocalDate.now().plusDays(200), 50, "3.00");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("A-1", 2.5),
                        List.of("A-1", "tres")))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isZero();
        assertThat(resumen.quarantinedRows()).hasSize(2)
                .allSatisfy(fila -> assertThat(fila.errorCode()).isEqualTo("CANTIDAD_INVALIDA"));
        // Lo que no se hace: truncar 2.5 a 2 y descontar stock en silencio.
        assertThat(ventasRegistradas()).isZero();
    }

    @Test
    @DisplayName("Un lote ya vencido no se vende, aunque haya existencias")
    void loteVencidoNoSeVende() throws IOException {
        long producto = fixtures.product(storeId, "V-1", "Vencido", "LACTEOS", "3.00", "5.00");
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (store_id, product_id, batch_code, expiration_date,
                                               initial_qty, current_qty, unit_cost, batch_status)
                VALUES (?, ?, 'L-VENCIDO', CURRENT_DATE - 1, 10, 10, 3.00, 'ACTIVE')
                """, storeId, producto);

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("V-1", 1)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isZero();
        assertThat(resumen.quarantinedRows()).singleElement()
                .satisfies(fila -> assertThat(fila.errorCode()).isEqualTo("SIN_STOCK"));
    }

    // -----------------------------------------------------------------------
    // Las correcciones
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Las filas corregidas entran, y las que siguen mal vuelven a salir")
    void reenvioDeCorrecciones() throws IOException {
        long pan = fixtures.product(storeId, "P-2", "Pan", "PANADERIA", "0.20", "0.50");
        fixtures.batch(storeId, pan, "L-PAN2", LocalDate.now().plusDays(2), 5, "0.20");

        var resumen = ingestService.registerFixedRows(storeId, List.of(
                new SalesFixRowDto(2, "P-2", 3, null),            // ahora si cabe
                new SalesFixRowDto(3, "P-2", 50, null),           // sigue sin caber
                new SalesFixRowDto(4, "NO-EXISTE", 1, null)),     // sigue sin existir
                OPERARIO);

        assertThat(resumen.totalRegistered()).isEqualTo(1);
        assertThat(resumen.totalUnits()).isEqualTo(3);
        assertThat(resumen.quarantinedRows()).hasSize(2);
        // El numero de fila del Excel original se conserva: es como el operario
        // la localiza en su archivo.
        assertThat(resumen.quarantinedRows()).extracting("rowNumber").containsExactly(3, 4);
    }

    @Test
    @DisplayName("Una correccion se revalida en servidor, no se cree al cliente")
    void lasCorreccionesSeRevalidan() {
        long producto = fixtures.product(storeId, "R-1", "Revalidar", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-REV", LocalDate.now().plusDays(30), 10, "1.00");

        var resumen = ingestService.registerFixedRows(storeId, List.of(
                // Un cliente manipulado podria mandar una fecha futura: el
                // servidor no la acepta por venir del navegador.
                new SalesFixRowDto(2, "R-1", 1, LocalDate.now().plusDays(5).toString())),
                OPERARIO);

        assertThat(resumen.totalRegistered()).isZero();
        assertThat(resumen.quarantinedRows()).singleElement()
                .satisfies(fila -> assertThat(fila.errorCode()).isEqualTo("FECHA_FUTURA"));
    }

    // -----------------------------------------------------------------------
    // La hoja, y la huella
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("La hoja de ventas se encuentra aunque no sea la primera del libro")
    void encuentraLaHojaAunqueNoSeaLaPrimera() throws IOException {
        long producto = fixtures.product(storeId, "H-1", "Hoja", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-HOJA", LocalDate.now().plusDays(30), 10, "1.00");

        // Las dos hojas, como la plantilla de verdad: Inventario primero.
        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.conAmbasHojas(List.of(
                        List.of("H-1", 2)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un libro sin hoja de ventas lo dice, y manda a la plantilla")
    void sinHojaDeVentas() throws IOException {
        assertThatThrownBy(() -> ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.conHoja("Otra cosa", List.of(
                        List.of("H-1", 2)))),
                storeId, OPERARIO))
                .isInstanceOf(CorruptSpreadsheetException.class)
                .hasMessageContaining("Ventas")
                .hasMessageContaining("plantilla");
    }

    @Test
    @DisplayName("Los encabezados se reconocen por alias, no por posicion")
    void encabezadosPorAlias() throws IOException {
        long producto = fixtures.product(storeId, "AL-1", "Alias", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-ALIAS", LocalDate.now().plusDays(30), 10, "1.00");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.conCabecera(
                        List.of("Código", "Unidades vendidas", "Día"),
                        List.of(List.of("AL-1", 3)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isEqualTo(1);
        assertThat(resumen.totalUnits()).isEqualTo(3);
    }

    @Test
    @DisplayName("Sin columna de cantidad se corta de inmediato, no fila por fila")
    void faltaUnaColumnaRequerida() throws IOException {
        assertThatThrownBy(() -> ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.conCabecera(
                        List.of("SKU", "Observaciones"),
                        List.of(List.of("AL-1", "lo que sea")))),
                storeId, OPERARIO))
                .isInstanceOf(CorruptSpreadsheetException.class)
                .hasMessageContaining("QUANTITY");
    }

    /**
     * La guarda que sostiene todo lo demas: una venta registrada dos veces no se
     * puede deshacer, de modo que la unica defensa es no dejarla entrar.
     */
    @Test
    @DisplayName("El mismo archivo de ventas no se registra dos veces")
    void mismoArchivoDosVeces() throws IOException {
        long producto = fixtures.product(storeId, "D-1", "Duplicado", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-DUP", LocalDate.now().plusDays(30), 20, "1.00");

        byte[] archivo = SalesWorkbooks.soloVentas(List.of(List.of("D-1", 4)));
        var contexto = new ExcelUploadService.UploadContext(
                "ventas-lunes.xlsx", archivo.length, OPERARIO, "190.12.33.4");

        var primera = uploadService.upload(storeId, () -> SalesWorkbooks.stream(archivo), contexto);
        assertThat(primera.totalRegistered()).isEqualTo(1);
        assertThat(primera.uploadId()).isNotNull();

        assertThatThrownBy(() ->
                uploadService.upload(storeId, () -> SalesWorkbooks.stream(archivo), contexto))
                .isInstanceOf(DuplicateUploadException.class);

        // Lo que importa: el stock se descontó una vez, no dos.
        assertThat(ventasRegistradas()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(current_qty) FROM inventory_batches WHERE store_id = ?", Integer.class, storeId))
                .isEqualTo(16);
    }

    /**
     * El fallo que `sql/18` vino a arreglar. La plantilla trae las dos hojas en
     * el MISMO archivo, y el flujo que se le pide al usuario es subirla una vez
     * para el inventario y otra para las ventas. Con la huella sin `kind`, la
     * segunda subida se rechazaba por duplicada siendo otra operacion.
     */
    @Test
    @DisplayName("El mismo archivo sirve para inventario y para ventas: son dos cargas")
    void elMismoArchivoParaLasDosCosas() throws IOException {
        long producto = fixtures.product(storeId, "K-1", "Kind", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-KIND", LocalDate.now().plusDays(30), 20, "1.00");

        byte[] archivo = SalesWorkbooks.soloVentas(List.of(List.of("K-1", 2)));
        var contexto = new ExcelUploadService.UploadContext(
                "plantilla.xlsx", archivo.length, OPERARIO, "190.12.33.4");

        // Se simula la carga de inventario dejando su asiento con la misma
        // huella, que es lo que haria ExcelUploadService con este archivo.
        String huella = com.kardo.support.Sha256.of(SalesWorkbooks.stream(archivo));
        jdbcTemplate.update("""
                INSERT INTO inventory_uploads (store_id, file_hash, file_name, file_size_bytes,
                                               uploaded_by, kind)
                VALUES (?, ?, 'plantilla.xlsx', ?, ?, 'INVENTORY')
                """, storeId, huella, archivo.length, OPERARIO);

        // Y aun asi la carga de ventas pasa, porque es otra cosa.
        var resumen = uploadService.upload(storeId, () -> SalesWorkbooks.stream(archivo), contexto);
        assertThat(resumen.totalRegistered()).isEqualTo(1);
    }

    /**
     * Sin la guarda, la reversion "funcionaria": no encontraria lotes que
     * borrar, marcaria la carga como revertida y devolveria un exito. El gerente
     * creeria haber deshecho unas ventas que siguen descontadas, y la huella
     * dejaria de bloquear el archivo, de modo que volver a subirlo las
     * registraria por segunda vez.
     */
    @Test
    @DisplayName("Una carga de ventas no se puede revertir, y se dice por que")
    void lasVentasNoSeRevierten() throws IOException {
        long producto = fixtures.product(storeId, "RV-1", "Reversion", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-REV2", LocalDate.now().plusDays(30), 20, "1.00");

        byte[] archivo = SalesWorkbooks.soloVentas(List.of(List.of("RV-1", 5)));
        var resumen = uploadService.upload(storeId, () -> SalesWorkbooks.stream(archivo),
                new ExcelUploadService.UploadContext("ventas.xlsx", archivo.length, OPERARIO, "190.12.33.4"));

        assertThatThrownBy(() -> rollbackService.rollback(storeId, resumen.uploadId(), "demo@kardo.pe"))
                .isInstanceOf(UploadNotRevertibleException.class)
                .hasMessageContaining("es de ventas")
                .hasMessageContaining("mermas");

        // Y lo que importa: ni el stock volvio ni la carga quedo marcada, de
        // modo que la huella sigue impidiendo subir el archivo otra vez.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(current_qty) FROM inventory_batches WHERE store_id = ?", Integer.class, storeId))
                .isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolled_back_at FROM inventory_uploads WHERE upload_id = ?",
                java.sql.Timestamp.class, resumen.uploadId()))
                .isNull();
    }

    @Test
    @DisplayName("Las ventas de una tienda no tocan el stock de otra")
    void aislamientoPorTienda() throws IOException {
        long otraTienda = fixtures.store("OTRA");
        long producto = fixtures.product(storeId, "T-1", "Tienda", "ABARROTES", "1.00", "2.00");
        long loteAjeno = fixtures.batch(otraTienda, producto, "L-AJENO",
                LocalDate.now().plusDays(30), 20, "1.00");

        // El producto es de la compania, pero el lote esta en la otra tienda.
        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(List.of(
                        List.of("T-1", 1)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRegistered()).isZero();
        assertThat(resumen.quarantinedRows()).singleElement()
                .satisfies(fila -> assertThat(fila.errorCode()).isEqualTo("SIN_STOCK"));
        assertThat(stock(loteAjeno)).isEqualTo(20);
    }

    @Test
    @DisplayName("Las filas vacias del final no cuentan como lineas")
    void filasVacias() throws IOException {
        long producto = fixtures.product(storeId, "E-1", "Vacias", "ABARROTES", "1.00", "2.00");
        fixtures.batch(storeId, producto, "L-VACIAS", LocalDate.now().plusDays(30), 10, "1.00");

        var resumen = ingestService.ingest(
                SalesWorkbooks.stream(SalesWorkbooks.soloVentas(java.util.Arrays.asList(
                        List.of("E-1", 2),
                        java.util.Arrays.asList(null, null, null),
                        List.of("E-1", 1)))),
                storeId, OPERARIO);

        assertThat(resumen.totalRowsProcessed()).isEqualTo(2);
        assertThat(resumen.totalRegistered()).isEqualTo(2);
    }

    private int stock(long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_qty FROM inventory_batches WHERE batch_id = ?", Integer.class, batchId);
    }

    private int ventasRegistradas() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE store_id = ?", Integer.class, storeId);
    }
}
