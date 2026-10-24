-- ==============================================================================
-- Kardo — 05_waste_workflow.sql
-- Circuito de mermas con cuarentena y aprobacion del gerente (Reto 1).
--
-- ADITIVO: solo crea procedimientos. No toca datos ni esquema.
--
-- El esquema ya traia las piezas (`quarantined_qty`, `chk_batch_mass_balance`,
-- `waste_log.approval_status`) pero nadie las usaba. Aqui se cierran.
--
-- Por que una cuarentena y no un descuento directo:
--   Entre que el operario declara una merma y el gerente la aprueba, esas
--   unidades no estan vendibles (ya se tiraron o estan apartadas) pero tampoco
--   son una perdida contable confirmada. Si se descontaran de current_qty sin
--   mas, un rechazo posterior tendria que "inventar" stock; si no se descontaran,
--   el motor FEFO seguiria vendiendo mercaderia que ya no esta en la estanteria.
--   quarantined_qty las mantiene inmovilizadas y el invariante
--   (current_qty + quarantined_qty) <= initial_qty impide que el lote crezca.
--
-- Codigos de error:
--   P0005 el lote no tiene suficientes unidades disponibles
--   P0006 el lote no existe o no es de esa tienda
--   P0007 la merma ya fue resuelta (aprobada o rechazada)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Declarar merma: reserva preventiva en cuarentena
-- ------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_report_waste(
    p_store_id INT,
    p_batch_id INT,
    p_quantity INT,
    p_reason VARCHAR(50),
    p_operator_id VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_product_id INT;
    v_unit_cost NUMERIC(10, 2);
    v_affected INT;
BEGIN
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'La cantidad a dar de baja debe ser mayor que 0 (recibido: %).', p_quantity
            USING ERRCODE = 'P0004';
    END IF;

    PERFORM 1 FROM stores WHERE store_id = p_store_id FOR SHARE;

    SELECT product_id, unit_cost
    INTO v_product_id, v_unit_cost
    FROM inventory_batches
    WHERE batch_id = p_batch_id AND store_id = p_store_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El lote % no existe en la tienda %.', p_batch_id, p_store_id
            USING ERRCODE = 'P0006';
    END IF;

    -- La guarda `current_qty >= p_quantity` hace el trabajo: dos operarios
    -- declarando mermas del mismo lote a la vez no pueden pasarse del stock,
    -- porque el segundo UPDATE no encuentra fila que cumplir.
    UPDATE inventory_batches
    SET current_qty     = current_qty - p_quantity,
        quarantined_qty = quarantined_qty + p_quantity,
        batch_status    = CASE WHEN (current_qty - p_quantity) = 0 THEN 'DEPLETED' ELSE batch_status END
    WHERE batch_id = p_batch_id
      AND current_qty >= p_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'El lote % no tiene % unidades disponibles.', p_batch_id, p_quantity
            USING ERRCODE = 'P0005';
    END IF;

    INSERT INTO waste_log (
        store_id, batch_id, product_id, quantity_discarded,
        unit_cost, waste_reason, approval_status, operator_id
    ) VALUES (
        p_store_id, p_batch_id, v_product_id, p_quantity,
        v_unit_cost, p_reason, 'PENDING_APPROVAL', p_operator_id
    );
END;
$$;

-- ------------------------------------------------------------------------------
-- 2. Aprobar: la merma se consolida como perdida contable
-- ------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_approve_waste(
    p_waste_id INT,
    p_authorized_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id INT;
    v_quantity INT;
    v_affected INT;
BEGIN
    -- El bloqueo de la fila de merma es lo que hace idempotente la resolucion:
    -- dos clics del gerente no pueden aprobarla dos veces.
    SELECT batch_id, quantity_discarded
    INTO v_batch_id, v_quantity
    FROM waste_log
    WHERE waste_id = p_waste_id AND approval_status = 'PENDING_APPROVAL'
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'La merma % no existe o ya fue resuelta.', p_waste_id
            USING ERRCODE = 'P0007';
    END IF;

    -- Las unidades salen de la cuarentena y no vuelven a current_qty: se
    -- perdieron. El lote queda con menos masa total que su initial_qty, que es
    -- justo lo que el invariante permite.
    UPDATE inventory_batches
    SET quarantined_qty = quarantined_qty - v_quantity
    WHERE batch_id = v_batch_id
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE waste_log
    SET approval_status = 'APPROVED',
        authorized_by   = p_authorized_by
    WHERE waste_id = p_waste_id;
END;
$$;

-- ------------------------------------------------------------------------------
-- 3. Rechazar: la mercaderia vuelve a estar disponible
-- ------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_reject_waste(
    p_waste_id INT,
    p_authorized_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id INT;
    v_quantity INT;
    v_affected INT;
BEGIN
    SELECT batch_id, quantity_discarded
    INTO v_batch_id, v_quantity
    FROM waste_log
    WHERE waste_id = p_waste_id AND approval_status = 'PENDING_APPROVAL'
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'La merma % no existe o ya fue resuelta.', p_waste_id
            USING ERRCODE = 'P0007';
    END IF;

    -- Vuelve a current_qty y, si el lote aun no ha vencido, vuelve a ser
    -- vendible. Un lote ya vencido se reactiva como EXPIRED: el stock existe
    -- pero el indice parcial de FEFO no lo tomara.
    UPDATE inventory_batches
    SET current_qty     = current_qty + v_quantity,
        quarantined_qty = quarantined_qty - v_quantity,
        batch_status    = CASE
                              WHEN expiration_date < CURRENT_DATE THEN 'EXPIRED'
                              ELSE 'ACTIVE'
                          END
    WHERE batch_id = v_batch_id
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE waste_log
    SET approval_status = 'REJECTED',
        authorized_by   = p_authorized_by
    WHERE waste_id = p_waste_id;
END;
$$;
