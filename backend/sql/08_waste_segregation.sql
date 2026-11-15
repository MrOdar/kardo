-- ==============================================================================
-- Kardo — 08_waste_segregation.sql
-- Segregación de funciones en mermas: por persona, no solo por rol.
--
-- ADITIVO: reemplaza el cuerpo de dos procedimientos. No toca datos ni esquema.
--
-- Hasta aquí el control era que un OPERATOR no pudiera aprobar. Eso deja fuera
-- el caso que de verdad importa en una tienda pequeña: el gerente que declara
-- una merma y acto seguido se la aprueba a sí mismo. La separación entre quien
-- declara la pérdida y quien la autoriza es lo que hace que el registro sirva
-- como control antifraude y no como un trámite.
--
-- Código de error nuevo:
--   P0008 el autor de la merma no puede resolverla
-- ==============================================================================

CREATE OR REPLACE PROCEDURE sp_approve_waste(
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
    WHERE waste_id = p_waste_id AND approval_status = 'PENDING_APPROVAL'
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

CREATE OR REPLACE PROCEDURE sp_reject_waste(
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
    WHERE waste_id = p_waste_id AND approval_status = 'PENDING_APPROVAL'
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
