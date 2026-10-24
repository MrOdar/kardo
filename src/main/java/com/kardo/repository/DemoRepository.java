package com.kardo.repository;

import com.kardo.dto.DemoDashboardDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Escenario de demostracion: reinicio idempotente y metricas del semaforo FEFO. */
@Repository
public class DemoRepository {

    /** Tienda reservada para la demo; el procedimiento la bloquea con FOR UPDATE. */
    public static final long DEMO_STORE_ID = 999L;

    /** Umbral de alerta roja: la mercaderia vence dentro de 48 horas. */
    private static final int RED_ALERT_DAYS = 2;

    /** Umbral de alerta amarilla: vence dentro de 5 dias. */
    private static final int YELLOW_ALERT_DAYS = 5;

    /**
     * Metricas del tablero en una sola consulta.
     *
     * <p>Se calculan en PostgreSQL y no en Java: traer los lotes para contarlos
     * gastaria memoria del contenedor y ancho de banda del tier gratuito.
     *
     * <p>El valor en riesgo es dinero real: unidades que caducan pronto
     * multiplicadas por su costo de lote.
     */
    private static final String DASHBOARD = """
            WITH lotes AS (
                SELECT b.current_qty,
                       b.unit_cost,
                       b.expiration_date,
                       (b.expiration_date - CURRENT_DATE) AS dias_restantes
                FROM inventory_batches b
                WHERE b.store_id = ?
                  AND b.batch_status = 'ACTIVE'
                  AND b.current_qty > 0
            )
            SELECT
                (SELECT count(*) FROM lotes) AS lotes_activos,
                (SELECT count(*) FROM lotes WHERE dias_restantes BETWEEN 0 AND ?) AS alerta_roja,
                (SELECT count(*) FROM lotes WHERE dias_restantes > ? AND dias_restantes <= ?) AS alerta_amarilla,
                (SELECT count(*) FROM lotes WHERE dias_restantes < 0) AS vencidos,
                (SELECT COALESCE(sum(current_qty * unit_cost), 0) FROM lotes
                  WHERE dias_restantes <= ?) AS valor_en_riesgo,
                (SELECT COALESCE(sum(current_qty * unit_cost), 0) FROM lotes) AS valor_inventario,
                (SELECT count(*)
                   FROM goods_receipt_items i
                   JOIN goods_receipt_headers h USING (receipt_id)
                  WHERE h.store_id = ? AND i.discrepancy_qty <> 0) AS guias_con_discrepancia
            """;

    private final JdbcTemplate jdbcTemplate;

    public DemoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Ejecuta {@code sp_reset_demo_environment()}.
     *
     * <p>El procedimiento toma un candado exclusivo sobre la tienda 999, espera a
     * que terminen las ventas en vuelo y repuebla el escenario. Por eso se invoca
     * con {@code CALL} y no se parte en sentencias sueltas: la atomicidad vive
     * dentro del procedimiento.
     */
    public void resetDemoEnvironment() {
        // El procedimiento borra lotes, guias y ventas, pero no el rastro de
        // cargas. Sin esto, tras restaurar la demo el evaluador no podria volver
        // a subir el mismo Excel: la huella seguiria registrada y la carga se
        // rechazaria por duplicada aunque sus lotes ya no existan.
        jdbcTemplate.update("DELETE FROM inventory_uploads WHERE store_id = ?", DEMO_STORE_ID);
        jdbcTemplate.execute("CALL sp_reset_demo_environment()");
    }

    public DemoDashboardDto loadDashboard() {
        return jdbcTemplate.queryForObject(DASHBOARD, (rs, rowNum) -> new DemoDashboardDto(
                DEMO_STORE_ID,
                rs.getInt("lotes_activos"),
                rs.getInt("alerta_roja"),
                rs.getInt("alerta_amarilla"),
                rs.getInt("vencidos"),
                rs.getBigDecimal("valor_en_riesgo").setScale(2, java.math.RoundingMode.HALF_UP),
                rs.getBigDecimal("valor_inventario").setScale(2, java.math.RoundingMode.HALF_UP),
                rs.getInt("guias_con_discrepancia")),
                DEMO_STORE_ID,
                RED_ALERT_DAYS,
                RED_ALERT_DAYS,
                YELLOW_ALERT_DAYS,
                YELLOW_ALERT_DAYS,
                DEMO_STORE_ID);
    }

    /** Lotes activos ordenados por vencimiento: el orden exacto en que FEFO los vendera. */
    public java.util.List<java.util.Map<String, Object>> loadFefoQueue(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT b.batch_id,
                       p.sku,
                       p.name,
                       b.batch_code,
                       b.expiration_date,
                       (b.expiration_date - CURRENT_DATE) AS dias_restantes,
                       b.current_qty,
                       b.unit_cost,
                       (b.current_qty * b.unit_cost) AS valor
                FROM inventory_batches b
                JOIN products p USING (product_id)
                WHERE b.store_id = ?
                  AND b.batch_status = 'ACTIVE'
                  AND b.current_qty > 0
                ORDER BY b.expiration_date ASC
                LIMIT ?
                """, DEMO_STORE_ID, limit);
    }
}
