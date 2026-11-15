-- ==============================================================================
-- Kardo — 07_upload_rollback.sql
-- Trazabilidad lote -> carga y marcas de reversion.
--
-- ADITIVO e idempotente: solo añade columnas e indices.
--
-- Por que una columna y no una tabla de snapshots:
--   Guardar una foto del inventario entero por cada carga multiplicaria los
--   datos sin aportar nada: lo unico que hay que poder deshacer es lo que ESA
--   carga creo. Con `upload_id` en el lote, el "snapshot" es una consulta, no
--   una copia, y no consume los 500 MB del plan gratuito de Supabase.
--
-- Que NO se revierte:
--   Los productos del catalogo. Un SKU creado por la carga puede tener ya otros
--   lotes de una recepcion posterior; borrarlo se llevaria por delante stock
--   legitimo. La reversion deshace el movimiento de inventario, no el catalogo.
-- ==============================================================================

ALTER TABLE inventory_batches
    ADD COLUMN IF NOT EXISTS upload_id INT REFERENCES inventory_uploads(upload_id) ON DELETE SET NULL;

-- Localiza en un paso los lotes de una carga concreta.
CREATE INDEX IF NOT EXISTS idx_batches_upload
    ON inventory_batches (upload_id)
    WHERE upload_id IS NOT NULL;

ALTER TABLE inventory_uploads
    ADD COLUMN IF NOT EXISTS rolled_back_at TIMESTAMPTZ;

ALTER TABLE inventory_uploads
    ADD COLUMN IF NOT EXISTS rolled_back_by VARCHAR(120);

-- ------------------------------------------------------------------------------
-- La unicidad pasa a ser parcial: solo entre cargas vigentes.
--
-- Una carga revertida ya no existe a efectos de inventario, asi que no debe
-- bloquear la recarga del mismo archivo. El flujo natural tras un error es
-- revertir y volver a subir; con una unicidad total, ese segundo intento se
-- rechazaria como duplicado y el gerente quedaria atascado.
-- ------------------------------------------------------------------------------
ALTER TABLE inventory_uploads
    DROP CONSTRAINT IF EXISTS uq_upload_hash_store;

CREATE UNIQUE INDEX IF NOT EXISTS uq_upload_hash_store_vigente
    ON inventory_uploads (store_id, file_hash)
    WHERE rolled_back_at IS NULL;
