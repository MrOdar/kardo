package com.kardo.inventory;

import com.kardo.dto.ExpiryAlertBoardDto;
import com.kardo.dto.ExpiryAlertDto;
import com.kardo.exception.BatchNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alertas de vencimiento con una accion concreta detras.
 *
 * <p>Hasta aqui el sistema sabia que un lote vence pronto y lo pintaba de rojo.
 * Un color no evita una merma: alguien tiene que decidir a que precio sale esa
 * mercaderia hoy. Este servicio une el semaforo con la escalera de descuentos
 * ({@link DiscountLadder}) y con la memoria de lo que ya se decidio, que es lo
 * que permite que el panel se pueda vaciar y por tanto se lea.
 */
@Service
public class ExpiryAlertService {

    /**
     * Candidatos: todo lo que le queda poco y aun se puede vender, con la
     * decision mas profunda que alguien haya tomado sobre el lote.
     *
     * <p>El LATERAL trae una sola fila por lote -la del peldaño mas profundo- en
     * lugar de arrastrar todo el historial de decisiones a Java para descartarlo
     * alli, que es el error clasico que convierte un panel en una consulta cara.
     */
    private static final String CANDIDATES = """
            SELECT b.batch_id,
                   p.sku,
                   p.name AS product_name,
                   b.batch_code,
                   b.expiration_date,
                   (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                   b.current_qty,
                   b.unit_cost,
                   b.markdown_price,
                   p.retail_price,
                   d.decision,
                   d.step_percent,
                   d.decided_by,
                   d.decided_at
            FROM inventory_batches b
            JOIN products p USING (product_id)
            LEFT JOIN LATERAL (
                SELECT md.decision, md.step_percent, md.decided_by, md.decided_at
                FROM markdown_decisions md
                WHERE md.batch_id = b.batch_id
                ORDER BY md.step_percent DESC, md.decided_at DESC
                LIMIT 1
            ) d ON TRUE
            WHERE b.store_id = ?
              AND b.batch_status <> 'DEPLETED'
              AND b.current_qty > 0
              AND (b.expiration_date - CURRENT_DATE) <= ?
            ORDER BY b.expiration_date ASC, p.name
            """;

    private final JdbcTemplate jdbcTemplate;

    public ExpiryAlertService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** El panel tal como lo ve la tienda del token. */
    public ExpiryAlertBoardDto board(long storeId) {
        List<Candidate> candidates =
                jdbcTemplate.query(CANDIDATES, CANDIDATE_MAPPER, storeId, DiscountLadder.HORIZON_DAYS);

        List<ExpiryAlertDto> alerts = new ArrayList<>();
        BigDecimal valueAtRisk = BigDecimal.ZERO;
        BigDecimal marginSacrificed = BigDecimal.ZERO;
        int silenced = 0;

        for (Candidate c : candidates) {
            MarkdownSuggestion suggestion = DiscountLadder.suggest(
                    c.daysToExpiry(), c.sellableQty(), c.unitCost(), c.retailPrice());

            if (suggestion == null) {
                continue;
            }

            // El riesgo se cuenta aunque la alerta este callada: el dinero sigue
            // en el anaquel, y el total del panel seria mentira sin el.
            valueAtRisk = valueAtRisk.add(suggestion.valueAtRisk());

            if (c.decidedStep() != null && c.decidedStep() >= suggestion.stepPercent()) {
                silenced++;
                continue;
            }

            // Ya esta a un precio igual o mejor que el que se iba a sugerir.
            // Pasa cuando el piso del costo muerde: el lote baja de peldaño pero
            // no puede bajar de precio, y repetir la alerta solo enseña a
            // ignorarla. No hay nada que hacer, asi que no se pide nada.
            if (c.markdownPrice() != null && suggestion.suggestedPrice() != null
                    && c.markdownPrice().compareTo(suggestion.suggestedPrice()) <= 0) {
                silenced++;
                continue;
            }

            marginSacrificed = marginSacrificed.add(suggestion.marginSacrificed());
            alerts.add(toDto(c, suggestion));
        }

        return new ExpiryAlertBoardDto(
                valueAtRisk.setScale(2, RoundingMode.HALF_UP),
                marginSacrificed.setScale(2, RoundingMode.HALF_UP),
                silenced,
                alerts);
    }

    /**
     * Registra lo que el operario decidio y devuelve el panel ya sin esa alerta.
     *
     * <p>El peldaño se recalcula aqui con los dias que le quedan al lote ahora
     * mismo; lo que el cliente mando se ignora deliberadamente.
     *
     * @throws BatchNotFoundException si el lote no es de esta tienda
     */
    @Transactional
    public ExpiryAlertBoardDto decide(long storeId, long batchId, String decision, String decidedBy) {
        Candidate batch = findInStore(storeId, batchId);

        MarkdownSuggestion suggestion = DiscountLadder.suggest(
                batch.daysToExpiry(), batch.sellableQty(), batch.unitCost(), batch.retailPrice());

        // Un lote que ya no necesita decision (se vendio, o aun le sobran dias)
        // no deja rastro: guardar un peldaño inventado silenciaria la alerta de
        // verdad cuando llegue.
        if (suggestion != null) {
            jdbcTemplate.update("""
                    INSERT INTO markdown_decisions
                        (store_id, batch_id, decision, step_percent, suggested_price, decided_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    storeId, batchId, decision, suggestion.stepPercent(),
                    suggestion.suggestedPrice(), decidedBy);

            applyPrice(storeId, batchId, decision, suggestion);
        }

        return board(storeId);
    }

    /**
     * Lleva la decision al precio del lote.
     *
     * <p>Es lo que convierte la recomendacion en un hecho: hasta que esto
     * existio, la escalera sugeria S/ 3.80 y la venta seguia cobrando S/ 5.20,
     * de modo que el margen quedaba mal justo en los lotes que el sistema
     * mandaba rebajar.
     *
     * <p>Descartar <b>retira</b> la oferta si la habia. Un operario que pulsa
     * por error no puede quedarse sin forma de volver al precio de lista, y
     * "descartar la rebaja" es la lectura natural de ese boton.
     */
    private void applyPrice(long storeId, long batchId, String decision, MarkdownSuggestion suggestion) {
        BigDecimal price = "APPLIED".equals(decision) ? suggestion.suggestedPrice() : null;

        // Solo REBAJAR fija precio: retirar un vencido o avisar de que no hay
        // margen no son ofertas, y escribir null en el primer caso borraria una
        // oferta vigente sin que nadie lo haya pedido.
        if (price == null && suggestion.action() != MarkdownAction.REBAJAR) {
            return;
        }

        jdbcTemplate.update(
                "UPDATE inventory_batches SET markdown_price = ? WHERE batch_id = ? AND store_id = ?",
                price, batchId, storeId);
    }

    /** Historial de rebajas de la tienda: por que se vendio a ese precio. */
    public List<java.util.Map<String, Object>> history(long storeId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT md.decided_at,
                       md.decision,
                       md.step_percent,
                       md.suggested_price,
                       md.decided_by,
                       p.sku,
                       p.name AS product_name,
                       b.batch_code
                FROM markdown_decisions md
                JOIN inventory_batches b USING (batch_id)
                JOIN products p USING (product_id)
                WHERE md.store_id = ?
                ORDER BY md.decided_at DESC
                LIMIT ?
                """, storeId, limit);
    }

    private Candidate findInStore(long storeId, long batchId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT b.batch_id,
                           p.sku,
                           p.name AS product_name,
                           b.batch_code,
                           b.expiration_date,
                           (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                           b.current_qty,
                           b.unit_cost,
                           b.markdown_price,
                           p.retail_price,
                           NULL::varchar AS decision,
                           NULL::int AS step_percent,
                           NULL::varchar AS decided_by,
                           NULL::timestamptz AS decided_at
                    FROM inventory_batches b
                    JOIN products p USING (product_id)
                    WHERE b.store_id = ? AND b.batch_id = ?
                    """, CANDIDATE_MAPPER, storeId, batchId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BatchNotFoundException(batchId);
        }
    }

    private static ExpiryAlertDto toDto(Candidate c, MarkdownSuggestion s) {
        return new ExpiryAlertDto(
                c.batchId(), c.sku(), c.productName(), c.batchCode(),
                c.expirationDate(), c.daysToExpiry(), c.sellableQty(),
                c.unitCost(), c.markdownPrice(), c.retailPrice(),
                s.action(), s.stepPercent(), s.discountPercent(), s.suggestedPrice(),
                s.marginSacrificed(), s.valueAtRisk(), s.rationale(),
                c.decision(), c.decidedStep(), c.decidedBy(), c.decidedAt());
    }

    /** Fila cruda: el lote y lo ultimo que se decidio sobre el. */
    private record Candidate(
            long batchId, String sku, String productName, String batchCode,
            LocalDate expirationDate, int daysToExpiry, int sellableQty,
            BigDecimal unitCost, BigDecimal markdownPrice, BigDecimal retailPrice,
            String decision, Integer decidedStep, String decidedBy, Instant decidedAt) {
    }

    private static final RowMapper<Candidate> CANDIDATE_MAPPER = ExpiryAlertService::mapCandidate;

    private static Candidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        // Cada lectura anulable se resuelve en el acto: wasNull() se refiere a
        // la ultima columna leida, de modo que consultarlo despues de leer otra
        // devolveria la respuesta de la columna equivocada.
        int step = rs.getInt("step_percent");
        Integer decidedStep = rs.wasNull() ? null : step;

        var decidedAt = rs.getObject("decided_at", java.time.OffsetDateTime.class);

        return new Candidate(
                rs.getLong("batch_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("batch_code"),
                rs.getObject("expiration_date", LocalDate.class),
                rs.getInt("dias_restantes"),
                rs.getInt("current_qty"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("markdown_price"),
                rs.getBigDecimal("retail_price"),
                rs.getString("decision"),
                decidedStep,
                rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant());
    }
}
