-- ==============================================================================
-- Kardo — 06_upload_audit.sql
-- Rastro de auditoria de cargas de inventario e idempotencia por contenido.
--
-- ADITIVO e idempotente: crea una tabla nueva y añade columnas con IF NOT EXISTS.
--
-- Dos problemas que resuelve la misma tabla:
--
-- 1. AUDITORIA. Hasta ahora una carga de Excel no dejaba rastro de quien la
--    hizo: el inventario cambiaba y no habia a quien preguntar. Cada carga
--    registra ahora operario, archivo, metricas y momento exacto.
--
-- 2. RECARGA DUPLICADA. Subir dos veces el mismo archivo duplicaba los lotes en
--    silencio (se comprobo: un SKU repetido genero dos lotes identicos). Con
--    conexiones inestables, reintentar una carga que si llego es el error mas
--    comun del operario. La unicidad por (tienda, hash del contenido) lo impide,
--    y el hash se calcula sobre los bytes: renombrar el archivo no lo esquiva.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS inventory_uploads (
    upload_id        SERIAL PRIMARY KEY,
    store_id         INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,

    -- SHA-256 en hexadecimal del contenido del archivo.
    file_hash        CHAR(64) NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_size_bytes  BIGINT NOT NULL CHECK (file_size_bytes >= 0),

    total_rows       INT NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    inserted_rows    INT NOT NULL DEFAULT 0 CHECK (inserted_rows >= 0),
    quarantined_rows INT NOT NULL DEFAULT 0 CHECK (quarantined_rows >= 0),

    uploaded_by      VARCHAR(120) NOT NULL,

    -- Dato personal: conviene definir una politica de retencion antes de
    -- explotarlo (Ley 29733). Se guarda para poder rastrear un acceso indebido.
    client_ip        VARCHAR(45),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- La guarda de idempotencia. Es por tienda: dos locales pueden cargar
    -- legitimamente el mismo catalogo del proveedor.
    CONSTRAINT uq_upload_hash_store UNIQUE (store_id, file_hash)
);

CREATE INDEX IF NOT EXISTS idx_uploads_store_recent
    ON inventory_uploads (store_id, created_at DESC);

-- ------------------------------------------------------------------------------
-- Autoria de las recepciones de mercaderia.
-- La guia ya guardaba el JSON crudo de la IA en ocr_raw_json, pero no quien
-- confirmo la conciliacion, que es la decision humana que mueve el inventario.
-- ------------------------------------------------------------------------------
ALTER TABLE goods_receipt_headers
    ADD COLUMN IF NOT EXISTS confirmed_by VARCHAR(120);

ALTER TABLE goods_receipt_headers
    ADD COLUMN IF NOT EXISTS client_ip VARCHAR(45);
