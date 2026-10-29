package com.kardo.repository;

import com.kardo.dto.UploadAuditDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Rastro de auditoria de las cargas de inventario. */
@Repository
public class InventoryUploadRepository {

    private static final String FIND_BY_HASH = """
            SELECT upload_id, store_id, file_hash, file_name, file_size_bytes,
                   total_rows, inserted_rows, quarantined_rows, uploaded_by, client_ip, created_at,
                   rolled_back_at, rolled_back_by, kind
            FROM inventory_uploads
            WHERE store_id = ? AND file_hash = ? AND kind = ? AND rolled_back_at IS NULL
            """;

    private static final String INSERT = """
            INSERT INTO inventory_uploads (store_id, file_hash, file_name, file_size_bytes,
                                           uploaded_by, client_ip, kind)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING upload_id
            """;

    private static final String UPDATE_COUNTS = """
            UPDATE inventory_uploads
            SET total_rows = ?, inserted_rows = ?, quarantined_rows = ?
            WHERE upload_id = ?
            """;

    private static final String LIST_RECENT = """
            SELECT upload_id, store_id, file_hash, file_name, file_size_bytes,
                   total_rows, inserted_rows, quarantined_rows, uploaded_by, client_ip, created_at,
                   rolled_back_at, rolled_back_by, kind
            FROM inventory_uploads
            WHERE store_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public InventoryUploadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Para que sirvio una carga.
     *
     * <p>Entra en la guarda de unicidad porque la plantilla de Kardo trae las
     * dos hojas en el MISMO archivo: subirla para cargar el inventario y
     * volver a subirla para registrar las ventas son dos operaciones distintas
     * con la misma huella. Sin esto, la segunda daria 409.
     */
    public enum Kind {
        INVENTORY, SALES
    }

    /**
     * @return la carga previa VIGENTE con ese contenido Y para lo mismo. Una
     *         carga revertida no cuenta: tras deshacerla, volver a subir el
     *         archivo es legitimo.
     */
    public Optional<UploadAuditDto> findByHash(long storeId, String fileHash, Kind kind) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_BY_HASH, InventoryUploadRepository::toDto, storeId, fileHash, kind.name()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Deja constancia de la carga.
     *
     * <p>El indice {@code uq_upload_hash_store_kind_vigente} es la ultima
     * palabra: si dos peticiones identicas llegan a la vez, la segunda falla
     * aqui y su transaccion entera se deshace, de modo que no quedan lotes ni
     * ventas duplicadas.
     */
    public long record(long storeId, String fileHash, String fileName, long fileSizeBytes,
                       String uploadedBy, String clientIp, Kind kind) {
        Long uploadId = jdbcTemplate.queryForObject(INSERT, Long.class,
                storeId, fileHash, fileName, fileSizeBytes, uploadedBy, clientIp, kind.name());
        if (uploadId == null) {
            throw new IllegalStateException("No se pudo registrar la carga " + fileName);
        }
        return uploadId;
    }

    /** Las metricas se conocen al terminar de recorrer el archivo. */
    public void updateCounts(long uploadId, int totalRows, int insertedRows, int quarantinedRows) {
        jdbcTemplate.update(UPDATE_COUNTS, totalRows, insertedRows, quarantinedRows, uploadId);
    }

    public List<UploadAuditDto> listRecent(long storeId, int limit) {
        return jdbcTemplate.query(LIST_RECENT, InventoryUploadRepository::toDto, storeId, limit);
    }

    private static UploadAuditDto toDto(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UploadAuditDto(
                rs.getLong("upload_id"),
                rs.getString("file_name"),
                rs.getString("file_hash"),
                rs.getLong("file_size_bytes"),
                rs.getInt("total_rows"),
                rs.getInt("inserted_rows"),
                rs.getInt("quarantined_rows"),
                rs.getString("uploaded_by"),
                rs.getString("client_ip"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                instantOrNull(rs.getObject("rolled_back_at", OffsetDateTime.class)),
                rs.getString("rolled_back_by"),
                rs.getString("kind"));
    }

    private static java.time.Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
