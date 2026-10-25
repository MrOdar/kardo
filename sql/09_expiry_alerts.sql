-- ==============================================================================
-- Kardo — 09_expiry_alerts.sql
-- Rastro de las decisiones de rebaja sobre mercaderia proxima a vencer.
--
-- ADITIVO: no borra ni modifica nada de 01–08. Se puede ejecutar varias veces.
--
-- Por que existe: el semaforo FEFO decia que un lote vence en dos dias, pero no
-- que hacer con el, y la alerta reaparecia en cada refresco aunque el operario
-- ya hubiera puesto el cartel de oferta. Una alerta que no se puede cerrar deja
-- de leerse. Aqui se guarda la decision para poder callarla.
--
-- La regla que hace esto util y no un simple "no volver a mostrar": la decision
-- vale para el peldaño que se vio, no para los siguientes. Descartar una rebaja
-- del 20% no silencia la del 40% cuando el lote se acerca al vencimiento,
-- porque esa ya es otra recomendacion y merece otra mirada.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS markdown_decisions (
    decision_id     SERIAL PRIMARY KEY,

    store_id        INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,

    -- ON DELETE CASCADE y no RESTRICT: revertir una carga borra sus lotes
    -- (sql/07), y una decision de rebaja no es motivo para bloquear esa
    -- reversion. El rastro muere con el lote al que se referia.
    batch_id        INT NOT NULL REFERENCES inventory_batches(batch_id) ON DELETE CASCADE,

    -- APPLIED: el operario puso el precio rebajado en el anaquel.
    -- DISMISSED: lo vio y decidio no rebajar (p. ej. ya lo devolvio al proveedor).
    decision        VARCHAR(20) NOT NULL CHECK (decision IN ('APPLIED', 'DISMISSED')),

    -- Peldaño de la escalera sobre el que se decidio. Lo calcula el servidor a
    -- partir de los dias que faltaban: si viniera del cliente, bastaria declarar
    -- un 100% para silenciar el lote hasta que venza.
    step_percent    INT NOT NULL CHECK (step_percent BETWEEN 0 AND 100),

    -- Precio sugerido en ese momento; queda para poder explicar despues por que
    -- se vendio a ese precio.
    suggested_price NUMERIC(10, 2),

    decided_by      VARCHAR(120) NOT NULL,
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- La consulta de alertas busca, por lote, la decision del peldaño mas profundo.
CREATE INDEX IF NOT EXISTS idx_markdown_batch_step
    ON markdown_decisions (batch_id, step_percent DESC);

CREATE INDEX IF NOT EXISTS idx_markdown_store_fecha
    ON markdown_decisions (store_id, decided_at DESC);
