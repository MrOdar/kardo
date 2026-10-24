package com.kardo.ingest;

import com.kardo.dto.RollbackResponseDto;
import com.kardo.exception.UploadNotRevertibleException;
import com.kardo.exception.UploadNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reversion de una carga de inventario.
 *
 * <p>Deshace los lotes que creo esa carga, y solo si siguen intactos. No toca el
 * catalogo: un SKU creado por la carga puede tener ya otros lotes de una
 * recepcion posterior, y borrarlo se llevaria por delante stock legitimo.
 */
@Service
public class UploadRollbackService {

    private static final Logger log = LoggerFactory.getLogger(UploadRollbackService.class);

    /**
     * Estado de cada lote de la carga, con el candado puesto.
     *
     * <p>{@code FOR UPDATE} sobre los lotes evita la carrera entre la
     * comprobacion y el borrado: una venta que llegue a la vez espera, y despues
     * encontrara los lotes ya eliminados o vera la reversion rechazada.
     */
    private static final String LOAD_BATCHES = """
            SELECT b.batch_id, b.batch_code, p.name AS product_name,
                   b.initial_qty, b.current_qty, b.quarantined_qty,
                   (EXISTS (SELECT 1 FROM daily_sales s WHERE s.batch_id = b.batch_id)
                    OR EXISTS (SELECT 1 FROM waste_log w WHERE w.batch_id = b.batch_id)) AS has_movements
            FROM inventory_batches b
            JOIN products p USING (product_id)
            WHERE b.upload_id = ?
            ORDER BY b.batch_id
            FOR UPDATE OF b
            """;

    private static final String FIND_UPLOAD = """
            SELECT file_name, rolled_back_at IS NOT NULL AS already_rolled_back
            FROM inventory_uploads
            WHERE upload_id = ? AND store_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public UploadRollbackService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param storeId tienda del usuario: una carga de otra tienda no existe para el
     * @throws UploadNotRevertibleException si algun lote ya fue usado
     */
    @Transactional
    public RollbackResponseDto rollback(long storeId, long uploadId, String requestedBy) {
        var upload = jdbcTemplate.query(FIND_UPLOAD,
                        rs -> rs.next()
                                ? new Upload(rs.getString("file_name"), rs.getBoolean("already_rolled_back"))
                                : null,
                        uploadId, storeId);

        if (upload == null) {
            throw new UploadNotFoundException(uploadId);
        }
        if (upload.alreadyRolledBack()) {
            throw new UploadNotRevertibleException(
                    "La carga " + uploadId + " ya fue revertida.", List.of());
        }

        List<UploadRollback.BatchState> batches = jdbcTemplate.query(LOAD_BATCHES,
                (rs, rowNum) -> new UploadRollback.BatchState(
                        rs.getLong("batch_id"),
                        rs.getString("batch_code"),
                        rs.getString("product_name"),
                        rs.getInt("initial_qty"),
                        rs.getInt("current_qty"),
                        rs.getInt("quarantined_qty"),
                        rs.getBoolean("has_movements")),
                uploadId);

        List<UploadRollback.BatchState> blockers = UploadRollback.blockers(batches);
        if (!blockers.isEmpty()) {
            log.warn("Reversion de la carga {} rechazada: {} lote(s) ya en uso", uploadId, blockers.size());
            throw new UploadNotRevertibleException(
                    "No se puede revertir la carga " + uploadId
                            + ": su mercaderia ya se movio. Registra los ajustes a mano.",
                    blockers.stream().map(UploadRollback.BatchState::describe).toList());
        }

        int removed = jdbcTemplate.update("DELETE FROM inventory_batches WHERE upload_id = ?", uploadId);

        jdbcTemplate.update("""
                UPDATE inventory_uploads
                SET rolled_back_at = CURRENT_TIMESTAMP, rolled_back_by = ?
                WHERE upload_id = ?
                """, requestedBy, uploadId);

        log.info("Carga {} ({}) revertida por {}: {} lote(s) eliminados",
                uploadId, upload.fileName(), requestedBy, removed);

        return new RollbackResponseDto(uploadId, upload.fileName(), removed,
                "Carga revertida: se eliminaron " + removed + " lote(s). El catalogo no se modifico.");
    }

    private record Upload(String fileName, boolean alreadyRolledBack) {
    }
}
