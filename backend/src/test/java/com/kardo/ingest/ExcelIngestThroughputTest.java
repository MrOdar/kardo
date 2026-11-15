package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.config.IngestProperties;
import com.kardo.dto.IngestSummaryResponseDto;
import com.kardo.dto.SpreadsheetRowDto;
import com.kardo.repository.InventoryIngestRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cuanto aguanta la ingesta y con cuanta memoria.
 *
 * <p><b>Por que no corre con el resto.</b> Tarda y mide un entorno, no una
 * regla de negocio: en una maquina cargada el numero cambia sin que el codigo
 * haya cambiado. Va etiquetado {@code bench} y queda fuera de {@code mvn test};
 * se pide a proposito:
 *
 * <pre>mvn test -Pbench [-Dbench.rows=50000] [-Dbench.heap=64]</pre>
 *
 * <p><b>Cuidado con el generador.</b> El archivo se fabrica en esta misma JVM,
 * y escribir un XLSX cuesta memoria transitoria aunque se vaya vaciando la hoja.
 * Con el heap en 64 MB caben de sobra 50.000 filas; para 200.000 hay que subirlo
 * a 128 MB, y lo que no entra es la <em>generacion</em>, no la lectura: el
 * «vivo al terminar» se queda en los mismos 11 MB en los dos casos.
 *
 * <p><b>Que afirma y que no.</b> Mide el camino de lectura y validacion con un
 * repositorio de mentira: no hay base de datos detras, asi que el numero es el
 * techo del parser, no el de una carga real contra Supabase. Eso es justo lo
 * que interesa comprobar, porque la afirmacion que sostiene el diseño es que
 * <em>el archivo no se materializa en el heap</em>: el consumo tiene que ser
 * plano aunque el archivo crezca, y eso lo decide fastexcel, no la red.
 *
 * <p>El doble del repositorio esta escrito a mano y no con Mockito a proposito:
 * un mock guarda cada invocacion con sus argumentos, de modo que retendria las
 * cien mil filas que se le pasan y la medicion de memoria mediria el mock.
 */
class ExcelIngestThroughputTest {

    private static final long STORE_ID = 7L;
    private static final long COMPANY_ID = 1L;

    /**
     * Cuanta memoria puede quedar <b>viva</b> al terminar.
     *
     * <p>No se mide el pico de heap usado, aunque sea el numero que apetece
     * enseñar: {@code totalMemory - freeMemory} cuenta tambien la basura que el
     * recolector todavia no ha barrido, de modo que sube con el ritmo de
     * asignacion aunque no se retenga ni un objeto. La prueba de que el archivo
     * no se materializa es otra, y es doble: que esto corra con el heap de la
     * JVM limitado a 64 MB (lo fija el perfil {@code bench}), y que al acabar no
     * quede retenido mas que el resumen.
     */
    private static final long MAX_RETAINED_MB = 24;

    private static final List<String> HEADERS = List.of(
            "SKU", "Codigo de barras", "Producto", "Categoria", "Unidad",
            "Costo", "Precio", "Lote", "Fabricacion", "Vencimiento", "Cantidad");

    @Test
    @Tag("bench")
    @DisplayName("Lee un Excel grande con el heap plano")
    void ingestaDeArchivoGrande() throws Exception {
        int rows = Integer.getInteger("bench.rows", 50_000);

        Path file = Files.createTempFile("kardo-bench-", ".xlsx");
        try {
            long bytes = generar(file, rows);

            long afterWrite = liveHeapMb();

            long t0 = System.nanoTime();
            IngestSummaryResponseDto summary;
            try (InputStream input = Files.newInputStream(file)) {
                summary = servicio().ingest(input, STORE_ID, null);
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);

            long retained = liveHeapMb();

            assertThat(summary.totalRowsProcessed()).isEqualTo(rows);
            assertThat(summary.totalInserted()).isEqualTo(rows);
            assertThat(summary.totalQuarantined()).isZero();

            System.out.printf("""

                    ── Ingesta de Excel en streaming ──────────────────────────
                      Filas              %,d
                      Archivo            %.1f MB
                      Tiempo             %.2f s
                      Rendimiento        %,d filas/s
                      Heap maximo JVM    %d MB
                      Vivo tras generar  %d MB
                      Vivo al terminar   %d MB
                    ───────────────────────────────────────────────────────────
                    %n""",
                    rows,
                    bytes / 1024d / 1024d,
                    elapsed.toMillis() / 1000d,
                    Math.round(rows / (elapsed.toMillis() / 1000d)),
                    Runtime.getRuntime().maxMemory() / 1024 / 1024,
                    afterWrite,
                    retained);

            // Si el archivo se materializara, esto crecería con el numero de
            // filas. No crece: lo unico que sobrevive a la ingesta es el resumen.
            assertThat(retained)
                    .describedAs("heap vivo tras ingerir %,d filas", rows)
                    .isLessThan(MAX_RETAINED_MB);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Escribe el archivo en disco, como llega en produccion desde el multipart. */
    private long generar(Path file, int rows) throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) {
            Workbook workbook = new Workbook(output, "kardo-bench", "1.0");
            Worksheet sheet = workbook.newWorksheet("Inventario");

            for (int column = 0; column < HEADERS.size(); column++) {
                sheet.value(0, column, HEADERS.get(column));
            }
            for (int i = 0; i < rows; i++) {
                int row = i + 1;
                sheet.value(row, 0, "SKU-" + i);
                sheet.value(row, 1, "775" + String.format("%010d", i));
                sheet.value(row, 2, "Producto de prueba numero " + i);
                sheet.value(row, 3, "CATEGORIA-" + (i % 20));
                sheet.value(row, 4, "UNIDAD");
                sheet.value(row, 5, "3.50");
                sheet.value(row, 6, "6.90");
                sheet.value(row, 7, "LOTE-" + i);
                sheet.value(row, 8, "2026-01-10");
                sheet.value(row, 9, "2027-06-30");
                sheet.value(row, 10, 10 + (i % 90));

                // El escritor de fastexcel tambien acumula hasta que se le dice
                // que suelte. Sin esto, con el heap limitado revienta generando
                // el archivo, antes de llegar a lo que queremos medir.
                if (row % 1_000 == 0) {
                    sheet.flush();
                }
            }
            workbook.finish();
        }
        return Files.size(file);
    }

    private ExcelStreamingIngestService servicio() {
        // Cuenta las filas y las suelta: lo que se mide es el parser, y un doble
        // que se quedara con ellas convertiria la medicion en otra cosa.
        InventoryIngestRepository repository = new InventoryIngestRepository(null, null) {
            @Override
            public long findCompanyIdByStore(long storeId) {
                return COMPANY_ID;
            }

            @Override
            public int persistMicroBatch(long storeId, long companyId,
                                         List<SpreadsheetRowDto> rows, Long uploadId) {
                return rows.size();
            }
        };
        return new ExcelStreamingIngestService(repository, new IngestProperties(500, 100, 200_000));
    }

    /**
     * Memoria viva, no memoria usada.
     *
     * <p>Se recolecta antes de medir y se mide dos veces: la primera pasada
     * suele dejar objetos que solo la segunda alcanza, y la diferencia entre
     * ambas es justo la basura que no queremos contar como retencion.
     */
    private static long liveHeapMb() throws InterruptedException {
        for (int i = 0; i < 2; i++) {
            System.gc();
            Thread.sleep(80);
        }
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
    }
}
