package com.kardo.inventory;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serie diaria del valor en riesgo.
 *
 * <p><b>Por que existe.</b> El panel responde "cuanto hay en riesgo hoy", y esa
 * cifra sola no se puede juzgar: S/ 143.50 puede ser el mejor dia del mes o el
 * peor. Con la serie detras, la pregunta que de verdad importa -si el sistema
 * esta sirviendo para algo- tiene respuesta.
 *
 * <p>La tabla es derivada: se reconstruye capturando de nuevo y nadie decide
 * nada a partir de ella, de modo que un hueco en la serie es una molestia y no
 * una inconsistencia.
 */
@Service
public class RiskHistoryService {

    private static final Logger log = LoggerFactory.getLogger(RiskHistoryService.class);

    private static final String HISTORY = """
            SELECT snapshot_date,
                   value_at_risk,
                   inventory_value,
                   red_batches,
                   amber_batches,
                   expired_batches
              FROM risk_snapshots
             WHERE store_id = ?
               AND snapshot_date > (CURRENT_TIMESTAMP AT TIME ZONE 'America/Lima')::date - ?::int
             ORDER BY snapshot_date
            """;

    private final JdbcTemplate jdbcTemplate;

    public RiskHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Toma la foto del dia para todas las tiendas.
     *
     * <p>Reentrante: volver a llamarla el mismo dia sustituye la foto en lugar
     * de duplicarla, asi que un reinicio del contenedor no deja dos puntos.
     */
    @Transactional
    public void capture() {
        jdbcTemplate.execute("CALL sp_capture_risk_snapshot()");
        log.debug("Foto diaria del valor en riesgo capturada");
    }

    /** La serie de los ultimos {@code days} dias, en orden cronologico. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long storeId, int days) {
        return jdbcTemplate.queryForList(HISTORY, storeId, days);
    }
}
