package com.kardo.inventory;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las dos preguntas que vienen antes y despues de una rebaja.
 *
 * <p><b>Cobertura.</b> Una rebaja es el remedio; la pregunta anterior es por
 * que hubo que rebajar. Con el ritmo de salida de los ultimos 30 dias y el
 * stock actual se sabe cuantos dias de venta hay en el anaquel, y comparandolo
 * con lo que le queda al lote mas proximo sale la unica conclusion que importa:
 * <em>a este ritmo no te da tiempo</em>. Eso se decide al comprar, no al
 * vencer.
 *
 * <p><b>Proveedor.</b> Los lotes que entraron por una guia de remision
 * conservan de quien vinieron. Agregando por ahi se ve quien trae la
 * mercaderia que acaba en riesgo y quien la que se vende. Los lotes cargados
 * desde Excel no tienen guia y por tanto no tienen proveedor: se agrupan
 * aparte y se dicen como lo que son, no se reparten a ojo entre los demas.
 */
@Service
public class InventoryInsightsService {

    /** Ventana del ritmo de salida. Mas corta es ruido; mas larga, otra temporada. */
    private static final int SALES_WINDOW_DAYS = 30;

    /** Lo mismo que el semaforo entiende por "en riesgo". */
    private static final int RISK_DAYS = 5;

    private static final String COVERAGE = """
            WITH ventas AS (
                SELECT product_id,
                       sum(quantity)::numeric AS vendidas
                  FROM daily_sales
                 WHERE store_id = ?
                   AND sale_timestamp >= CURRENT_TIMESTAMP - make_interval(days => ?)
                 GROUP BY product_id
            ), stock AS (
                SELECT product_id,
                       sum(current_qty) AS unidades,
                       sum(current_qty * unit_cost) AS valor,
                       min(expiration_date) AS primer_vencimiento
                  FROM inventory_batches
                 WHERE store_id = ?
                   AND current_qty > 0
                 GROUP BY product_id
            )
            SELECT p.sku,
                   p.name,
                   s.unidades,
                   s.valor,
                   (s.primer_vencimiento - CURRENT_DATE) AS dias_hasta_vencer,
                   COALESCE(v.vendidas, 0) AS vendidas_ventana,
                   CASE WHEN COALESCE(v.vendidas, 0) > 0
                        THEN round(s.unidades / (v.vendidas / ?::numeric), 1)
                   END AS dias_cobertura
              FROM stock s
              JOIN products p USING (product_id)
              LEFT JOIN ventas v USING (product_id)
             ORDER BY (s.primer_vencimiento - CURRENT_DATE) ASC, s.valor DESC
             LIMIT 50
            """;

    /**
     * Un lote puede tener varias bajas; sumarlas en el mismo JOIN que el resto
     * multiplicaria el valor del lote por el numero de bajas. El LATERAL las
     * reduce a una cifra antes de agregar.
     */
    private static final String BY_SUPPLIER = """
            SELECT COALESCE(su.name, 'Cargados desde Excel, sin guía') AS proveedor,
                   count(*) FILTER (WHERE b.current_qty > 0) AS lotes,
                   COALESCE(sum(b.current_qty * b.unit_cost), 0) AS valor,
                   COALESCE(sum(b.current_qty * b.unit_cost)
                            FILTER (WHERE (b.expiration_date - CURRENT_DATE) BETWEEN 0 AND ?), 0) AS en_riesgo,
                   COALESCE(sum(b.current_qty * b.unit_cost)
                            FILTER (WHERE (b.expiration_date - CURRENT_DATE) < 0), 0) AS vencido,
                   COALESCE(sum(m.perdida), 0) AS merma,
                   COALESCE(sum(c.recuperado), 0) AS canjeado,
                   (su.supplier_id IS NOT NULL) AS con_guia
              FROM inventory_batches b
              LEFT JOIN goods_receipt_items gi ON gi.item_id = b.receipt_item_id
              LEFT JOIN goods_receipt_headers gh ON gh.receipt_id = gi.receipt_id
              LEFT JOIN suppliers su ON su.supplier_id = gh.supplier_id
              LEFT JOIN LATERAL (
                  SELECT COALESCE(sum(w.total_loss_amount), 0) AS perdida
                    FROM waste_log w
                   WHERE w.batch_id = b.batch_id
                     AND w.approval_status = 'APPROVED'
              ) m ON TRUE
              -- Lo canjeado va aparte de lo perdido y nunca sumado a ello: un
              -- proveedor que acepta devoluciones es el que mejor se porta, y
              -- contarlo como merma lo dejaria como el peor.
              LEFT JOIN LATERAL (
                  SELECT COALESCE(sum(r.recovered_amount), 0) AS recuperado
                    FROM supplier_returns r
                   WHERE r.batch_id = b.batch_id
                     AND r.status = 'CREDITED'
              ) c ON TRUE
             WHERE b.store_id = ?
             GROUP BY su.supplier_id, su.name
             ORDER BY en_riesgo DESC, merma DESC, valor DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public InventoryInsightsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Dias de venta que hay en el anaquel, por producto.
     *
     * <p>Un producto sin ventas en la ventana devuelve {@code null} en
     * {@code dias_cobertura} en lugar de infinito o cero: no se sabe, y decirlo
     * es mas util que inventar un numero que alguien usaria para comprar.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> coverage(long storeId) {
        return jdbcTemplate.queryForList(
                COVERAGE, storeId, SALES_WINDOW_DAYS, storeId, SALES_WINDOW_DAYS);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> bySupplier(long storeId) {
        return jdbcTemplate.queryForList(BY_SUPPLIER, RISK_DAYS, storeId);
    }

    /** Ventana usada, para que la interfaz no tenga que repetir la constante. */
    public int salesWindowDays() {
        return SALES_WINDOW_DAYS;
    }
}
