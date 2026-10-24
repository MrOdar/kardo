package com.kardo.waste;

import com.kardo.dto.WasteEntryDto;
import com.kardo.exception.BatchNotFoundException;
import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.WasteAlreadyResolvedException;
import com.kardo.support.SqlErrors;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Circuito de mermas con cuarentena y aprobacion.
 *
 * <p>El operario declara la baja y las unidades quedan inmovilizadas en
 * {@code quarantined_qty}: ya no se venden, pero todavia no son una perdida
 * contable. El gerente decide. Aprobar consolida la merma; rechazar devuelve la
 * mercaderia al stock vendible.
 *
 * <p>Las tres operaciones viven en procedimientos porque cada una toca el lote y
 * el registro de merma a la vez, y el invariante
 * {@code current_qty + quarantined_qty <= initial_qty} debe cumplirse en todo
 * momento, tambien con dos operarios trabajando en paralelo.
 */
@Service
public class WasteService {

    private static final Logger log = LoggerFactory.getLogger(WasteService.class);

    private static final String LIST_BY_STORE = """
            SELECT w.waste_id, w.batch_id, b.batch_code, p.sku, p.name AS product_name,
                   b.expiration_date, w.quantity_discarded, w.unit_cost, w.total_loss_amount,
                   w.waste_reason, w.approval_status, w.operator_id, w.authorized_by, w.log_timestamp
            FROM waste_log w
            JOIN inventory_batches b ON b.batch_id = w.batch_id
            JOIN products p ON p.product_id = w.product_id
            WHERE w.store_id = ?
              AND (? IS NULL OR w.approval_status = ?)
            ORDER BY w.log_timestamp DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public WasteService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Declara la merma y deja las unidades en cuarentena a la espera del gerente. */
    @Transactional
    public void report(long storeId, long batchId, int quantity, String reason, String operatorId) {
        try {
            jdbcTemplate.update("CALL sp_report_waste(?::int, ?::int, ?::int, ?::varchar, ?::varchar)",
                    storeId, batchId, quantity, reason, operatorId);
        } catch (DataAccessException ex) {
            throw translate(ex, batchId, 0L);
        }
        log.info("Merma declarada: {} unidades del lote {} en la tienda {} por {} ({})",
                quantity, batchId, storeId, operatorId, reason);
    }

    /** Consolida la perdida: las unidades salen de la cuarentena y no vuelven. */
    @Transactional
    public void approve(long wasteId, String authorizedBy) {
        try {
            jdbcTemplate.update("CALL sp_approve_waste(?::int, ?::varchar)", wasteId, authorizedBy);
        } catch (DataAccessException ex) {
            throw translate(ex, 0L, wasteId);
        }
        log.info("Merma {} aprobada por {}", wasteId, authorizedBy);
    }

    /** Devuelve la mercaderia al stock vendible, si el lote no ha vencido entretanto. */
    @Transactional
    public void reject(long wasteId, String authorizedBy) {
        try {
            jdbcTemplate.update("CALL sp_reject_waste(?::int, ?::varchar)", wasteId, authorizedBy);
        } catch (DataAccessException ex) {
            throw translate(ex, 0L, wasteId);
        }
        log.info("Merma {} rechazada por {}; la mercaderia vuelve al stock", wasteId, authorizedBy);
    }

    /**
     * @param status filtro opcional; {@code null} devuelve todo el historial
     */
    public List<WasteEntryDto> list(long storeId, String status, int limit) {
        return jdbcTemplate.query(LIST_BY_STORE, (rs, rowNum) -> new WasteEntryDto(
                        rs.getLong("waste_id"),
                        rs.getLong("batch_id"),
                        rs.getString("batch_code"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getObject("expiration_date", java.time.LocalDate.class),
                        rs.getInt("quantity_discarded"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("total_loss_amount"),
                        rs.getString("waste_reason"),
                        rs.getString("approval_status"),
                        rs.getString("operator_id"),
                        rs.getString("authorized_by"),
                        rs.getObject("log_timestamp", java.time.OffsetDateTime.class).toInstant()),
                storeId, status, status, limit);
    }

    /** Los ERRCODE de los procedimientos, traducidos al HTTP que le toca a cada uno. */
    static RuntimeException translate(DataAccessException exception, long batchId, long wasteId) {
        String message = SqlErrors.message(exception);

        return switch (SqlErrors.sqlState(exception)) {
            case "P0004" -> new InvalidQuantityException(message);
            case "P0005" -> new InsufficientStockException(message);
            case "P0006" -> new BatchNotFoundException(batchId);
            case "P0007" -> new WasteAlreadyResolvedException(wasteId);
            default -> exception;
        };
    }
}
