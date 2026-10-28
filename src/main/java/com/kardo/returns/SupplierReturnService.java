package com.kardo.returns;

import com.kardo.dto.SupplierReturnDto;
import com.kardo.exception.BatchNotFoundException;
import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.NotReturnableException;
import com.kardo.exception.ReturnAlreadyResolvedException;
import com.kardo.support.SqlErrors;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canje de mercaderia proxima a vencer al distribuidor que la trajo.
 *
 * <p><b>Que cambia respecto a la merma.</b> El circuito es parecido -se declara,
 * las unidades quedan inmovilizadas, alguien resuelve- pero el signo del dinero
 * es el contrario: una merma consolida una perdida y un canje recupera el costo
 * entero. Por eso no comparten tabla: {@code waste_log.total_loss_amount} es una
 * columna generada, y meter ahi un canje haria que el informe de mermas contara
 * como perdido justo el dinero que se salvo.
 *
 * <p><b>Por que no hay segregacion de funciones.</b> En la merma, quien declara
 * no puede resolver: aprobarla destruye la evidencia y consolida una perdida
 * contra la propia tienda. Aqui es al reves. Resolver un canje es anotar algo
 * que ya hizo un tercero -el proveedor abono, o no quiso- y queda comprobable
 * contra su nota de credito. Exigir dos personas dejaria sin salida a una
 * tienda con un solo gerente sin proteger nada.
 *
 * <p>El proveedor nunca llega del cliente: sale de la trazabilidad del lote
 * (guia de remision), de modo que no se le puede reclamar a quien no trajo la
 * mercaderia. Los lotes cargados desde Excel no tienen guia y por eso no se
 * pueden canjear; la base lo rechaza con P0009.
 */
@Service
public class SupplierReturnService {

    private static final Logger log = LoggerFactory.getLogger(SupplierReturnService.class);

    private static final String LIST_BY_STORE = """
            SELECT r.return_id, r.batch_id, b.batch_code, p.sku, p.name AS product_name,
                   b.expiration_date, s.name AS supplier_name, r.quantity, r.unit_cost,
                   r.recovered_amount, r.status, r.declared_by, r.resolved_by, r.declared_at
              FROM supplier_returns r
              JOIN inventory_batches b ON b.batch_id = r.batch_id
              JOIN products p ON p.product_id = r.product_id
              JOIN suppliers s ON s.supplier_id = r.supplier_id
             WHERE r.store_id = ?
               -- El tipo va explicito por la misma razon que en las mermas: un
               -- '?' suelto dentro de un IS NULL no lo puede inferir PostgreSQL.
               AND (?::varchar IS NULL OR r.status = ?::varchar)
             ORDER BY r.declared_at DESC
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public SupplierReturnService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Declara el canje: las unidades dejan de venderse mientras esperan al camion. */
    @Transactional
    public void declare(long storeId, long batchId, int quantity, String declaredBy) {
        try {
            jdbcTemplate.update("CALL sp_declare_return(?::int, ?::int, ?::int, ?::varchar)",
                    storeId, batchId, quantity, declaredBy);
        } catch (DataAccessException ex) {
            throw translate(ex, batchId, 0L);
        }
        log.info("Canje declarado: {} unidades del lote {} en la tienda {} por {}",
                quantity, batchId, storeId, declaredBy);
    }

    /** El proveedor se la llevo y abono: el costo se recupera, no se pierde. */
    @Transactional
    public void credit(long returnId, String resolvedBy) {
        try {
            jdbcTemplate.update("CALL sp_credit_return(?::int, ?::varchar)", returnId, resolvedBy);
        } catch (DataAccessException ex) {
            throw translate(ex, 0L, returnId);
        }
        log.info("Canje {} abonado por el proveedor, resuelto por {}", returnId, resolvedBy);
    }

    /**
     * El proveedor no lo acepto.
     *
     * <p>La mercaderia vuelve al stock tal y como esta. Si ya vencio, el lote
     * queda EXPIRED y el semaforo la empuja al circuito de mermas: este flujo
     * no inventa contabilidad de perdidas, se la devuelve a quien ya la lleva.
     */
    @Transactional
    public void refuse(long returnId, String resolvedBy) {
        try {
            jdbcTemplate.update("CALL sp_refuse_return(?::int, ?::varchar)", returnId, resolvedBy);
        } catch (DataAccessException ex) {
            throw translate(ex, 0L, returnId);
        }
        log.info("Canje {} rechazado por el proveedor; la mercaderia vuelve al stock", returnId);
    }

    /** @param status filtro opcional; {@code null} devuelve todo el historial */
    @Transactional(readOnly = true)
    public List<SupplierReturnDto> list(long storeId, String status, int limit) {
        return jdbcTemplate.query(LIST_BY_STORE, (rs, rowNum) -> new SupplierReturnDto(
                        rs.getLong("return_id"),
                        rs.getLong("batch_id"),
                        rs.getString("batch_code"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getObject("expiration_date", LocalDate.class),
                        rs.getString("supplier_name"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("recovered_amount"),
                        rs.getString("status"),
                        rs.getString("declared_by"),
                        rs.getString("resolved_by"),
                        rs.getObject("declared_at", OffsetDateTime.class).toInstant()),
                storeId, status, status, limit);
    }

    /** Los ERRCODE del procedimiento, traducidos al HTTP que le toca a cada uno. */
    static RuntimeException translate(DataAccessException exception, long batchId, long returnId) {
        String message = SqlErrors.message(exception);

        return switch (SqlErrors.sqlState(exception)) {
            case "P0004" -> new InvalidQuantityException(message);
            case "P0005" -> new InsufficientStockException(message);
            case "P0006" -> new BatchNotFoundException(batchId);
            // El lote existe y la cantidad es valida: lo que no hay es a quien
            // reclamarle. El mensaje del procedimiento ya lo explica, asi que
            // viaja tal cual en vez de sustituirse por uno generico.
            case "P0009" -> new NotReturnableException(message);
            case "P0007" -> new ReturnAlreadyResolvedException(returnId);
            default -> exception;
        };
    }
}
