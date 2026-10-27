-- ============================================================================
-- 12. Historico diario del valor en riesgo
--
-- El panel sabe cuanto hay en riesgo HOY, pero no si eso es bueno o malo. Sin
-- una serie detras, "S/ 143.50 en riesgo" no se puede juzgar: puede ser el
-- mejor dia del mes o el peor. Una foto diaria por tienda convierte la cifra en
-- una tendencia, que es lo unico que demuestra que el sistema sirve para algo.
--
-- Es una tabla derivada: se puede vaciar y volver a llenar desde cero sin
-- perder nada que no sea historia. De ahi el ON DELETE CASCADE, al reves que
-- las tablas transaccionales, donde un borrado de tienda debe fallar.
--
-- Aditivo e idempotente: se puede reaplicar sin miedo.
-- ============================================================================

CREATE TABLE IF NOT EXISTS risk_snapshots (
    snapshot_date   DATE NOT NULL,
    store_id        INT NOT NULL REFERENCES stores(store_id) ON DELETE CASCADE,
    value_at_risk   NUMERIC(12, 2) NOT NULL CHECK (value_at_risk >= 0),
    inventory_value NUMERIC(12, 2) NOT NULL CHECK (inventory_value >= 0),
    red_batches     INT NOT NULL CHECK (red_batches >= 0),
    amber_batches   INT NOT NULL CHECK (amber_batches >= 0),
    expired_batches INT NOT NULL CHECK (expired_batches >= 0),
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_risk_snapshots PRIMARY KEY (snapshot_date, store_id)
);

COMMENT ON TABLE risk_snapshots IS
    'Foto diaria del valor en riesgo por tienda. Tabla derivada: reconstruible, nunca fuente de verdad.';

-- Una sola consulta para todas las tiendas, y una fila por tienda y dia.
--
-- La fecha sale de la hora de Lima y no de la del servidor: Render corre en UTC
-- y una captura a las 23:50 de Lima caeria en el dia siguiente, dejando el
-- grafico con un salto de un dia cada vez.
CREATE OR REPLACE PROCEDURE sp_capture_risk_snapshot()
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO risk_snapshots (snapshot_date, store_id, value_at_risk, inventory_value,
                                red_batches, amber_batches, expired_batches)
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Lima')::date,
           b.store_id,
           COALESCE(sum(b.current_qty * b.unit_cost)
                    FILTER (WHERE (b.expiration_date - CURRENT_DATE) BETWEEN 0 AND 5), 0),
           COALESCE(sum(b.current_qty * b.unit_cost), 0),
           count(*) FILTER (WHERE (b.expiration_date - CURRENT_DATE) BETWEEN 0 AND 2),
           count(*) FILTER (WHERE (b.expiration_date - CURRENT_DATE) BETWEEN 3 AND 5),
           count(*) FILTER (WHERE (b.expiration_date - CURRENT_DATE) < 0)
      FROM inventory_batches b
     WHERE b.current_qty > 0
     GROUP BY b.store_id
        ON CONFLICT (snapshot_date, store_id) DO UPDATE
       SET value_at_risk   = EXCLUDED.value_at_risk,
           inventory_value = EXCLUDED.inventory_value,
           red_batches     = EXCLUDED.red_batches,
           amber_batches   = EXCLUDED.amber_batches,
           expired_batches = EXCLUDED.expired_batches,
           captured_at     = CURRENT_TIMESTAMP;
END;
$$;
