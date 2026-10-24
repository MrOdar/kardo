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
                   rolled_back_at, rolled_back_by
            FROM inventory_uploads
            WHERE store_id = ? AND file_hash = ? AND rolled_back_at IS NULL
            """;

    private static final String INSERT = """
            INSERT INTO inventory_uploads (store_id, file_hash, file_name, file_size_bytes,
                                           uploaded_by, client_ip)
            VALUES (?, ?, ?, ?, ?, ?)
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
                   rolled_back_at, rolled_back_by
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
     * @return la carga previa VIGENTE con ese contenido. Una carga revertida no
     *         cuenta: tras deshacerla, volver a subir el archivo es legitimo.
     */
    public Optional<UploadAuditDto> findByHash(long storeId, String fileHash) {
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(FIND_BY_HASH, InventoryUploadRepository::toDto, storeId, fileHash));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Deja constancia de la carga.
     *
     * <p>La restriccion {@code uq_upload_hash_store} es la ultima palabra: si dos
     * peticiones identicas llegan a la vez, la segunda falla aqui y su
     * transaccion entera se deshace, de modo que no quedan lotes duplicados.
     */
    public long record(long storeId, String fileHash, String fileName, long fileSizeBytes,
                       String uploadedBy, String clientIp) {
        Long uploadId = jdbcTemplate.queryForObject(INSERT, Long.class,
                storeId, fileHash, fileName, fileSizeBytes, uploadedBy, clientIp);
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
                rs.getString("rolled_back_by"));
    }

    private static java.time.Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
