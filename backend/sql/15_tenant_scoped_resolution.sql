-- ============================================================================
-- 15. La tienda entra en la resolucion de mermas y canjes
--
-- ADITIVO: redefine cuatro procedimientos y retira sus firmas antiguas. No
-- toca esquema ni datos.
--
-- QUE ESTABA ROTO:
--
--   `sp_approve_waste(waste_id, por_quien)` y sus tres hermanos resolvian por
--   la clave primaria de la merma, que es un SERIAL global. La tienda del token
--   nunca entraba en la sentencia. Un gerente de la empresa B autenticado
--   legitimamente podia mandar
--
--       POST /api/v1/inventory/waste/41/approve
--
--   y consolidar la merma 41 de la empresa A: la autorizacion por rol pasaba
--   (es MANAGER), la de tienda no existia, y el UPDATE escribia su correo en
--   `authorized_by` de una tienda que no es la suya. Lo mismo en el canje, que
--   ademas mueve dinero: `sp_credit_return` saca las unidades de la cuarentena
--   y da el costo por recuperado.
--
--   No bastaba con filtrar en Java por `store_id` antes de llamar, porque entre
--   la comprobacion y el CALL cabe otra transaccion. El filtro va dentro del
--   SELECT ... FOR UPDATE que ya existia, donde el candado lo protege.
--
-- POR QUE SE RETIRAN LAS FIRMAS VIEJAS:
--
--   `CREATE OR REPLACE` con un parametro mas crea una SOBRECARGA, no sustituye
--   nada. Si la de dos argumentos sigue instalada, el agujero sigue abierto
--   para cualquiera que la llame -incluida una version anterior de este mismo
--   jar durante un despliegue-, asi que se borra explicitamente. Borrar un
--   procedimiento no toca datos.
--
-- UNA MERMA O UN CANJE DE OTRA TIENDA AHORA NO EXISTE, en lugar de dar un 403:
--   se responde P0007, el mismo codigo que un id inventado. Decirle "existe
--   pero no es tuya" a quien prueba numeros al azar le dibuja el inventario
--   ajeno.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Mermas
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_approve_waste(
    p_store_id INT,
    p_waste_id INT,
    p_authorized_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id INT;
    v_quantity INT;
    v_operator VARCHAR(50);
    v_affected INT;
BEGIN
    SELECT batch_id, quantity_discarded, operator_id
    INTO v_batch_id, v_quantity, v_operator
    FROM waste_log
    WHERE waste_id = p_waste_id
      AND store_id = p_store_id
      AND approval_status = 'PENDING_APPROVAL'
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'La merma % no existe o ya fue resuelta.', p_waste_id
            USING ERRCODE = 'P0007';
    END IF;

    IF v_operator = p_authorized_by THEN
        RAISE EXCEPTION 'La merma % la declaro %, que no puede resolverla.', p_waste_id, v_operator
            USING ERRCODE = 'P0008';
    END IF;

    UPDATE inventory_batches
    SET quarantined_qty = quarantined_qty - v_quantity
    WHERE batch_id = v_batch_id
      AND store_id = p_store_id
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE waste_log
    SET approval_status = 'APPROVED',
        authorized_by   = p_authorized_by
    WHERE waste_id = p_waste_id
      AND store_id = p_store_id;
END;
$$;

CREATE OR REPLACE PROCEDURE sp_reject_waste(
    p_store_id INT,
    p_waste_id INT,
    p_authorized_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id INT;
    v_quantity INT;
    v_operator VARCHAR(50);
    v_affected INT;
BEGIN
    SELECT batch_id, quantity_discarded, operator_id
    INTO v_batch_id, v_quantity, v_operator
    FROM waste_log
    WHERE waste_id = p_waste_id
      AND store_id = p_store_id
      AND approval_status = 'PENDING_APPROVAL'
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'La merma % no existe o ya fue resuelta.', p_waste_id
            USING ERRCODE = 'P0007';
    END IF;

    -- Rechazar tambien es resolver: devolver la mercaderia al stock sin que un
    -- tercero lo revise permitiria deshacer la propia declaracion sin rastro.
    IF v_operator = p_authorized_by THEN
        RAISE EXCEPTION 'La merma % la declaro %, que no puede resolverla.', p_waste_id, v_operator
            USING ERRCODE = 'P0008';
    END IF;

    UPDATE inventory_batches
    SET current_qty     = current_qty + v_quantity,
        quarantined_qty = quarantined_qty - v_quantity,
        batch_status    = CASE
                              WHEN expiration_date < CURRENT_DATE THEN 'EXPIRED'
                              ELSE 'ACTIVE'
                          END
    WHERE batch_id = v_batch_id
      AND store_id = p_store_id
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE waste_log
    SET approval_status = 'REJECTED',
        authorized_by   = p_authorized_by
    WHERE waste_id = p_waste_id
      AND store_id = p_store_id;
END;
$$;

-- ----------------------------------------------------------------------------
-- 2. Canjes
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_credit_return(
    p_store_id INT,
    p_return_id INT,
    p_resolved_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id INT;
    v_quantity INT;
    v_affected INT;
BEGIN
    SELECT batch_id, quantity
    INTO v_batch_id, v_quantity
    FROM supplier_returns
    WHERE return_id = p_return_id
      AND store_id = p_store_id
      AND status = 'PENDING'
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El canje % no existe o ya fue resuelto.', p_return_id
            USING ERRCODE = 'P0007';
    END IF;

    -- Las unidades salen de la cuarentena y no vuelven: se las llevo el
    -- proveedor. A diferencia de la merma, el dinero no se perdio.
    UPDATE inventory_batches
    SET quarantined_qty = quarantined_qty - v_quantity
    WHERE batch_id = v_batch_id
      AND store_id = p_store_id
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE supplier_returns
    SET status = 'CREDITED', resolved_by = p_resolved_by, resolved_at = CURRENT_TIMESTAMP
    WHERE return_id = p_return_id
      AND store_id = p_store_id;
END;
$$;

CREATE OR REPLACE PROCEDURE sp_refuse_return(
    p_store_id INT,
    p_return_id INT,
    p_resolved_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id INT;
    v_quantity INT;
    v_affected INT;
BEGIN
    SELECT batch_id, quantity
    INTO v_batch_id, v_quantity
    FROM supplier_returns
    WHERE return_id = p_return_id
      AND store_id = p_store_id
      AND status = 'PENDING'
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El canje % no existe o ya fue resuelto.', p_return_id
            USING ERRCODE = 'P0007';
    END IF;

    -- Vuelven al stock tal y como estan: si ya vencieron, el lote queda
    -- EXPIRED y el semaforo las empujara al circuito de mermas, que es donde
    -- de verdad se contabiliza una perdida.
    UPDATE inventory_batches
    SET current_qty     = current_qty + v_quantity,
        quarantined_qty = quarantined_qty - v_quantity,
        batch_status    = CASE
                              WHEN expiration_date < CURRENT_DATE THEN 'EXPIRED'
                              ELSE 'ACTIVE'
                          END
    WHERE batch_id = v_batch_id
      AND store_id = p_store_id
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE supplier_returns
    SET status = 'REFUSED', resolved_by = p_resolved_by, resolved_at = CURRENT_TIMESTAMP
    WHERE return_id = p_return_id
      AND store_id = p_store_id;
END;
$$;

-- ----------------------------------------------------------------------------
-- 3. Se retiran las firmas sin tienda
--
-- Va al final a proposito: si algo de arriba falla, el script entero se
-- deshace y la base se queda con los procedimientos viejos, que funcionan,
-- en lugar de sin ninguno.
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_approve_waste(INT, VARCHAR);
DROP PROCEDURE IF EXISTS sp_reject_waste(INT, VARCHAR);
DROP PROCEDURE IF EXISTS sp_credit_return(INT, VARCHAR);
DROP PROCEDURE IF EXISTS sp_refuse_return(INT, VARCHAR);
