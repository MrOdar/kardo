package com.kardo.vision;

import com.kardo.config.VisionProperties;
import com.kardo.dto.VisionQuotaDto;
import com.kardo.exception.VisionQuotaExceededException;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tope diario de llamadas a la vision por computadora.
 *
 * <p>Es la unica parte del sistema que gasta dinero fuera de nuestra
 * infraestructura, y la API key es una sola para todo el despliegue: sin tope,
 * una tienda en bucle deja sin recepcion documental a las demas.
 *
 * <p>El contador esta en PostgreSQL y no en memoria porque el contenedor de
 * Render se reinicia y hiberna, y un tope que se olvida con cada reinicio no es
 * un tope.
 */
@Service
public class VisionQuotaService {

    private static final Logger log = LoggerFactory.getLogger(VisionQuotaService.class);

    /** El dia del operario, no el del servidor: Supabase corre en UTC. */
    private static final String TODAY = "(CURRENT_TIMESTAMP AT TIME ZONE 'America/Lima')::date";

    /**
     * Reserva atomica.
     *
     * <p>El {@code WHERE} del {@code DO UPDATE} es lo que hace el tope fiable:
     * PostgreSQL bloquea la fila en conflicto, de modo que dos escaneos
     * simultaneos de la misma tienda no pueden leer el mismo contador y
     * pasarse. Si la condicion no se cumple no vuelve ninguna fila, y eso -y no
     * una lectura previa- es la señal de cupo agotado.
     */
    private static final String RESERVE = """
            INSERT INTO vision_usage (usage_date, store_id, scans, last_scan_at, last_scan_by)
            VALUES (%s, ?, 1, CURRENT_TIMESTAMP, ?)
            ON CONFLICT (usage_date, store_id) DO UPDATE
               SET scans = vision_usage.scans + 1,
                   last_scan_at = CURRENT_TIMESTAMP,
                   last_scan_by = EXCLUDED.last_scan_by
             WHERE vision_usage.scans < ?
            RETURNING scans
            """.formatted(TODAY);

    private static final String USAGE = """
            SELECT COALESCE((SELECT scans FROM vision_usage
                              WHERE usage_date = %1$s AND store_id = ?), 0) AS tienda,
                   COALESCE((SELECT sum(scans) FROM vision_usage WHERE usage_date = %1$s), 0) AS global,
                   %1$s AS dia
            """.formatted(TODAY);

    private final JdbcTemplate jdbcTemplate;
    private final VisionProperties.Quota quota;

    public VisionQuotaService(JdbcTemplate jdbcTemplate, VisionProperties visionProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.quota = visionProperties.quota();
    }

    /**
     * Descuenta un escaneo antes de llamar a Gemini.
     *
     * <p>Se cuentan intentos y no exitos: una llamada que Gemini rechaza ya
     * gasto cuota suya, asi que devolverla al operario seria contar de menos
     * justo cuando la cuota aprieta.
     *
     * @throws VisionQuotaExceededException si no queda cupo
     */
    public VisionQuotaDto reserve(long storeId, String requestedBy) {
        VisionQuotaDto before = status(storeId);

        // El tope global se comprueba leyendo; bajo concurrencia real podrian
        // colarse tantos escaneos como peticiones simultaneas haya. Se acepta:
        // protege de un bucle, que es el riesgo verdadero, y apretarlo exigiria
        // serializar todos los escaneos del despliegue por una sola fila.
        if (before.globalUsedToday() >= quota.globalDaily()) {
            log.warn("Tope global de vision alcanzado ({} escaneos); tienda {} rechazada",
                    quota.globalDaily(), storeId);
            throw VisionQuotaExceededException.forDeployment(quota.globalDaily());
        }

        try {
            Integer used = jdbcTemplate.queryForObject(
                    RESERVE, Integer.class, storeId, requestedBy, quota.perStoreDaily());

            int remaining = quota.perStoreDaily() - used;
            if (remaining <= 3) {
                log.warn("A la tienda {} le quedan {} escaneos hoy", storeId, remaining);
            }

            return new VisionQuotaDto(before.day(), used, quota.perStoreDaily(), remaining,
                    before.globalUsedToday() + 1, quota.globalDaily());

        } catch (EmptyResultDataAccessException agotado) {
            log.warn("Tienda {} sin cupo de vision: {} escaneos hoy", storeId, quota.perStoreDaily());
            throw VisionQuotaExceededException.forStore(quota.perStoreDaily());
        }
    }

    /** Consumo de hoy sin gastar nada: lo que la interfaz muestra junto al boton. */
    public VisionQuotaDto status(long storeId) {
        return jdbcTemplate.queryForObject(USAGE, (rs, rowNum) -> {
            int tienda = rs.getInt("tienda");
            return new VisionQuotaDto(
                    rs.getObject("dia", LocalDate.class),
                    tienda,
                    quota.perStoreDaily(),
                    Math.max(0, quota.perStoreDaily() - tienda),
                    rs.getInt("global"),
                    quota.globalDaily());
        }, storeId);
    }
}
