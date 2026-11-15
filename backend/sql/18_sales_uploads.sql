-- ============================================================================
-- 18. La carga sabe de que era
--
-- Por que existe: con la carga de ventas por Excel, `inventory_uploads` deja de
-- registrar una sola clase de cosa. Y eso rompe su guarda de idempotencia tal
-- como esta escrita.
--
-- EL FALLO CONCRETO:
--
--   `uq_upload_hash_store_vigente` es UNIQUE (store_id, file_hash). La
--   plantilla de Kardo trae las dos hojas en el MISMO archivo: Inventario y
--   Ventas. El flujo que se le pide al evaluador es subirla una vez para cargar
--   el inventario y otra vez para registrar las ventas -mismo archivo, mismo
--   contenido, misma huella-, de modo que la segunda subida se rechazaria con
--   409 siendo una carga legitima y distinta.
--
--   Con `kind` en la clave, la huella sigue impidiendo lo que tiene que
--   impedir -subir dos veces el mismo archivo PARA LO MISMO, que duplicaria
--   lotes o ventas- y deja pasar lo que es otra operacion.
--
-- ADITIVO. La columna nace con DEFAULT, asi que las cargas que ya existen
-- quedan marcadas como lo que fueron: inventario. Idempotente.
-- ============================================================================

ALTER TABLE inventory_uploads
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'INVENTORY';

ALTER TABLE inventory_uploads
    DROP CONSTRAINT IF EXISTS chk_upload_kind;

ALTER TABLE inventory_uploads
    ADD CONSTRAINT chk_upload_kind CHECK (kind IN ('INVENTORY', 'SALES'));

-- El indice de sql/07 se reemplaza por el mismo con `kind` dentro. Se crea el
-- nuevo antes de retirar el viejo: entre las dos sentencias la tabla esta
-- protegida por los dos, nunca por ninguno.
CREATE UNIQUE INDEX IF NOT EXISTS uq_upload_hash_store_kind_vigente
    ON inventory_uploads (store_id, file_hash, kind)
    WHERE rolled_back_at IS NULL;

DROP INDEX IF EXISTS uq_upload_hash_store_vigente;

COMMENT ON COLUMN inventory_uploads.kind IS
    'INVENTORY carga lotes al inventario; SALES registra salidas FEFO. '
    'Entra en la guarda de unicidad porque la plantilla trae las dos hojas en '
    'el mismo archivo y subirlo para cada cosa son dos cargas distintas.';

-- ----------------------------------------------------------------------------
-- Por que las ventas NO llevan upload_id
-- ----------------------------------------------------------------------------
-- Una carga de inventario se puede revertir porque un lote sin vender es un
-- apunte que no ha pasado nada: se borra y el mundo queda igual. Una venta no.
-- Revertirla significa devolver unidades a un lote que a lo mejor ya se
-- agoto, que entretanto pudo ser dado de baja por merma o canjeado al
-- proveedor, y cuyo precio rebajado pudo cambiar. Deshacer eso no es un
-- `DELETE`: es reconstruir un estado que la base no guarda.
--
-- De ahi la decision: las ventas cargadas por Excel se protegen POR DELANTE
-- -la huella impide subir dos veces el mismo archivo- y no por detras. Una
-- venta mal registrada se corrige como se corrige en una tienda de verdad:
-- con el circuito de mermas o de canjes, que si dejan rastro de quien decidio
-- que.
