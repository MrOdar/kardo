package com.kardo.sales;

import com.kardo.support.SqlErrors;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registra una venta de una carga masiva sin poder envenenar la transaccion.
 *
 * <p><b>El problema que resuelve.</b> La carga de ventas por Excel manda a
 * cuarentena la fila que no cuadra y deja pasar el resto, igual que la de
 * inventario. Pero una fila de ventas se rechaza <em>dentro de PostgreSQL</em>
 * -{@code sp_process_fefo_sale} levanta P0002 cuando no hay stock-, y en
 * PostgreSQL un error aborta la transaccion completa: a partir de ahi cualquier
 * sentencia responde <em>current transaction is aborted</em>. Sin esta clase, la
 * primera fila sin stock tumbaria todas las buenas que vinieran detras, y la
 * cuarentena seria una lista de victimas en vez de un diagnostico.
 *
 * <p><b>Como.</b> Cada llamada al procedimiento va dentro de un savepoint
 * ({@code PROPAGATION_NESTED}). Si falla, se deshace solo hasta ese savepoint y
 * la transaccion de la carga sigue viva y utilizable. El {@code try} esta
 * <em>alrededor</em> de {@code execute} y no dentro, que es lo que importa:
 * atrapar la excepcion dentro del savepoint lo dejaria sin deshacer y el
 * problema volveria entero.
 */
@Service
public class BulkSaleRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BulkSaleRegistrar.class);

    /**
     * Lo facturado por las ventas de esta carga, de una sola consulta.
     *
     * <p>Se calcula sobre el corte de {@code sale_id} en vez de preguntarlo fila
     * a fila: un archivo de 200 lineas haria 200 consultas de agregacion para
     * una cifra que sale de una.
     */
    private static final String REVENUE_SINCE = """
            SELECT COALESCE(sum(quantity * unit_retail_price), 0)
              FROM daily_sales
             WHERE store_id = ? AND sale_id > ?
            """;

    private static final String LAST_SALE_ID =
            "SELECT COALESCE(max(sale_id), 0) FROM daily_sales WHERE store_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate savepoint;

    public BulkSaleRegistrar(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * Por que esta fila no se pudo vender.
     *
     * @param code    el nombre del motivo, estable, para que la UI resalte el campo
     * @param message lo que lee el operario, tal como lo explica el procedimiento
     */
    public record Rejection(String code, String message) {
    }

    /**
     * @param saleDate null para fechar la venta en el momento
     * @return vacio si la venta quedo registrada; el motivo si no
     */
    public Optional<Rejection> register(long storeId, String sku, int quantity,
                                        LocalDate saleDate, String operatorId) {
        try {
            savepoint.executeWithoutResult(status ->
                    // Los casts son necesarios: en Java la tienda es long y el
                    // driver la envia como BIGINT, con lo que PostgreSQL no
                    // encuentra el procedimiento, que la declara INT.
                    jdbcTemplate.update(
                            "CALL sp_process_fefo_sale(?::int, ?::varchar, ?::int, ?::varchar, ?::date)",
                            storeId, sku, quantity, operatorId, saleDate));
            return Optional.empty();

        } catch (DataAccessException ex) {
            // Aqui ya se deshizo el savepoint: la transaccion de la carga sigue
            // sirviendo para las filas que vengan detras.
            Rejection rejection = describe(ex, sku);
            log.debug("Venta rechazada en la carga de la tienda {}: {} x{} -> {}",
                    storeId, sku, quantity, rejection.code());
            return Optional.of(rejection);
        }
    }

    public long lastSaleId(long storeId) {
        Long id = jdbcTemplate.queryForObject(LAST_SALE_ID, Long.class, storeId);
        return id == null ? 0L : id;
    }

    public BigDecimal revenueSince(long storeId, long saleIdWatermark) {
        BigDecimal revenue = jdbcTemplate.queryForObject(
                REVENUE_SINCE, BigDecimal.class, storeId, saleIdWatermark);
        return revenue == null ? BigDecimal.ZERO : revenue;
    }

    /**
     * Los ERRCODE del procedimiento, con el mensaje que el propio procedimiento
     * escribio.
     *
     * <p>No se reescriben: "Stock insuficiente para SKU X. Faltaron 7 unidades."
     * dice cuantas faltaron, que es justo lo que el operario necesita para
     * corregir la cantidad y reenviar la fila.
     *
     * <p>Lo que no se devuelve es el mensaje de un error <em>inesperado</em>: ahi
     * se corta con un texto propio, porque el mensaje de un
     * {@code DataAccessException} arrastra el SQL y los nombres de las tablas.
     */
    private static Rejection describe(DataAccessException exception, String sku) {
        String message = SqlErrors.message(exception);

        return switch (SqlErrors.sqlState(exception)) {
            case "P0001" -> new Rejection("SKU_DESCONOCIDO",
                    "El SKU " + sku + " no esta en el catalogo de esta tienda.");
            case "P0002" -> new Rejection("SIN_STOCK", message);
            case "P0004" -> new Rejection("CANTIDAD_NO_POSITIVA", message);
            case "P0010" -> new Rejection("FECHA_FUTURA", message);
            default -> {
                log.warn("Venta rechazada por un error no contemplado (SKU {})", sku, exception);
                yield new Rejection("ERROR_INESPERADO",
                        "No se pudo registrar esta linea. Revisa el SKU y la cantidad.");
            }
        };
    }
}
