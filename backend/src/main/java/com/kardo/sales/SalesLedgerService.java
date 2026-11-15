package com.kardo.sales;

import com.kardo.dto.SaleRecordDto;
import com.kardo.dto.SalesLedgerDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las ventas que de verdad se registraron, para poder mirarlas.
 *
 * <p><b>Por que existe.</b> {@code daily_sales} se escribia desde tres sitios y
 * se leia desde uno solo -{@code InventoryInsightsService}, y ahi como
 * <em>insumo</em> del ritmo de salida-, de modo que no habia ninguna pantalla
 * ni ningun endpoint que las mostrara. Se podian registrar 72 ventas de una
 * carga y luego no encontrarlas en ninguna parte: solo se notaba su efecto, en
 * el stock que bajaba y en la cobertura que aparecia.
 *
 * <p><b>Lo que esta tabla ensena y ninguna otra pantalla puede.</b> Cada fila
 * trae el lote que atendio la venta. Eso es el FEFO en accion, fila a fila: se
 * ve que la unidad salio del lote que vencia antes, y que el precio cobrado fue
 * el de ese lote -con su rebaja si la tenia- y no el del catalogo. Es tambien
 * la unica vista donde el margen aparece por venta y no agregado.
 */
@Service
public class SalesLedgerService {

    /**
     * El margen se calcula aqui y no se guarda: {@code unit_retail_price} y
     * {@code lot_unit_cogs} ya estan congelados en la fila, de modo que la resta
     * da siempre lo mismo. Una columna generada mas seria un dato que mantener.
     */
    private static final String RECENT = """
            SELECT s.sale_id,
                   s.sale_timestamp,
                   p.sku,
                   p.name AS producto,
                   p.category AS categoria,
                   b.batch_code AS lote,
                   b.expiration_date AS vence,
                   s.quantity AS unidades,
                   s.unit_retail_price AS precio,
                   s.lot_unit_cogs AS costo,
                   (s.quantity * s.unit_retail_price) AS importe,
                   (s.quantity * (s.unit_retail_price - s.lot_unit_cogs)) AS margen,
                   -- Lo que delata una rebaja: el precio cobrado por debajo del
                   -- de catalogo. Se compara aqui y no en la interfaz para que
                   -- la pantalla no tenga que conocer la regla.
                   (s.unit_retail_price < p.retail_price) AS rebajado,
                   s.operator_id AS operario
              FROM daily_sales s
              JOIN products p USING (product_id)
              JOIN inventory_batches b ON b.batch_id = s.batch_id
             WHERE s.store_id = ?
               AND s.sale_timestamp >= CURRENT_TIMESTAMP - make_interval(days => ?)
             -- sale_id desempata: dos ventas del mismo instante -las de una
             -- carga de Excel comparten hora- se ordenarian al azar entre
             -- paginas, y una fila podria salir dos veces o ninguna.
             ORDER BY s.sale_timestamp DESC, s.sale_id DESC
             LIMIT ? OFFSET ?
            """;

    /**
     * El resumen va aparte y no se deduce de las filas devueltas: estas vienen
     * acotadas por {@code LIMIT}, asi que sumarlas daria el total de lo que cabe
     * en pantalla en vez del de la ventana. Dos cifras que no coinciden y nadie
     * sabe cual mirar es peor que una sola consulta de mas.
     */
    private static final String TOTALS = """
            SELECT count(*) AS ventas,
                   COALESCE(sum(s.quantity), 0) AS unidades,
                   COALESCE(sum(s.quantity * s.unit_retail_price), 0) AS importe,
                   COALESCE(sum(s.quantity * (s.unit_retail_price - s.lot_unit_cogs)), 0) AS margen,
                   min(s.sale_timestamp)::date AS desde,
                   max(s.sale_timestamp)::date AS hasta
              FROM daily_sales s
             WHERE s.store_id = ?
               AND s.sale_timestamp >= CURRENT_TIMESTAMP - make_interval(days => ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public SalesLedgerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param days  ventana hacia atras; la misma que mira el analisis por defecto
     * @param limit  tope de filas devueltas, para no volcar un mes entero
     * @param offset cuantas saltar: es lo que permite seguir leyendo hacia
     *               atras sin tener que ensanchar la ventana
     */
    @Transactional(readOnly = true)
    public SalesLedgerDto recent(long storeId, int days, int limit, int offset) {
        List<SaleRecordDto> ventas = jdbcTemplate.query(RECENT,
                (rs, rowNum) -> new SaleRecordDto(
                        rs.getLong("sale_id"),
                        rs.getObject("sale_timestamp", OffsetDateTime.class).toInstant(),
                        rs.getString("sku"),
                        rs.getString("producto"),
                        rs.getString("categoria"),
                        rs.getString("lote"),
                        rs.getObject("vence", LocalDate.class),
                        rs.getInt("unidades"),
                        rs.getBigDecimal("precio"),
                        rs.getBigDecimal("costo"),
                        rs.getBigDecimal("importe"),
                        rs.getBigDecimal("margen"),
                        rs.getBoolean("rebajado"),
                        rs.getString("operario")),
                storeId, days, limit, offset);

        return jdbcTemplate.queryForObject(TOTALS, (rs, rowNum) -> new SalesLedgerDto(
                days,
                offset,
                rs.getInt("ventas"),
                rs.getInt("unidades"),
                rs.getBigDecimal("importe"),
                rs.getBigDecimal("margen"),
                rs.getObject("desde", LocalDate.class),
                rs.getObject("hasta", LocalDate.class),
                ventas), storeId, days);
    }

    /** Ventana por defecto: la misma que usa el analisis para el ritmo de salida. */
    public static int defaultWindowDays() {
        return 30;
    }

    /** Para que la interfaz no tenga que repetir el calculo del ticket medio. */
    public static BigDecimal averageTicket(SalesLedgerDto ledger) {
        return ledger.totalSales() == 0
                ? BigDecimal.ZERO
                : ledger.totalAmount().divide(BigDecimal.valueOf(ledger.totalSales()), 2,
                        java.math.RoundingMode.HALF_UP);
    }
}
