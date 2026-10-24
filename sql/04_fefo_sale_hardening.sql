-- ==============================================================================
-- Kardo — 04_fefo_sale_hardening.sql
-- Correccion de sp_process_fefo_sale.
--
-- ADITIVO: reemplaza el cuerpo del procedimiento sin tocar datos ni esquema.
-- La firma no cambia, de modo que es un reemplazo directo.
--
-- Que se corrige:
--
-- 1. AISLAMIENTO ENTRE COMPANIAS (el fallo grave).
--    La version anterior resolvia el producto asi:
--        SELECT product_id, retail_price INTO ... FROM products WHERE sku = p_sku;
--    El DDL permite el mismo SKU en companias distintas (UNIQUE (company_id, sku)),
--    asi que con dos clientes en la misma base ese SELECT descuenta stock del
--    producto equivocado o falla con "more than one row". Ahora el producto se
--    busca dentro de la compania dueña de la tienda.
--
-- 2. TIENDA INEXISTENTE.
--    El PERFORM ... FOR SHARE no encontraba nada y la ejecucion continuaba hasta
--    reventar con una violacion de clave foranea al insertar la venta. Ahora se
--    detecta al principio con un mensaje que dice que pasa.
--
-- 3. CANTIDAD NO POSITIVA.
--    Vender 0 o -5 unidades no entraba en el bucle y el procedimiento terminaba
--    sin error y sin registrar nada: una venta fantasma silenciosa.
--
-- Codigos de error (ERRCODE) que el backend traduce a HTTP:
--    P0001 producto inexistente      -> 404
--    P0002 stock insuficiente        -> 409
--    P0003 tienda inexistente        -> 404
--    P0004 cantidad no positiva      -> 400
-- ==============================================================================

CREATE OR REPLACE PROCEDURE sp_process_fefo_sale(
    p_store_id INT,
    p_sku VARCHAR(50),
    p_quantity INT,
    p_operator_id VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_company_id INT;
    v_product_id INT;
    v_unit_price NUMERIC(10, 2);
    v_remaining INT := p_quantity;
    v_batch RECORD;
    v_take INT;
BEGIN
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'La cantidad a vender debe ser mayor que 0 (recibido: %).', p_quantity
            USING ERRCODE = 'P0004';
    END IF;

    -- Candado compartido sobre la tienda: coordina con el bloqueo exclusivo del
    -- reset demo. De paso devuelve la compania, que acota el catalogo.
    SELECT company_id INTO v_company_id
    FROM stores
    WHERE store_id = p_store_id
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'La tienda % no existe.', p_store_id
            USING ERRCODE = 'P0003';
    END IF;

    -- El SKU solo es unico dentro de su compania: sin este filtro la venta
    -- podria descontar del producto de otro cliente.
    SELECT product_id, retail_price
    INTO v_product_id, v_unit_price
    FROM products
    WHERE company_id = v_company_id
      AND sku = p_sku;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Producto con SKU % no existe en el catalogo de la tienda %.', p_sku, p_store_id
            USING ERRCODE = 'P0001';
    END IF;

    -- Lotes activos por expiracion mas proxima (FEFO estricto) con bloqueo
    -- pesimista. Los vencidos quedan fuera: no se venden, se dan de baja.
    FOR v_batch IN
        SELECT batch_id, current_qty, unit_cost, expiration_date
        FROM inventory_batches
        WHERE store_id = p_store_id
          AND product_id = v_product_id
          AND batch_status = 'ACTIVE'
          AND current_qty > 0
          AND expiration_date >= CURRENT_DATE
        ORDER BY expiration_date ASC
        FOR UPDATE
    LOOP
        EXIT WHEN v_remaining <= 0;

        v_take := LEAST(v_remaining, v_batch.current_qty);

        UPDATE inventory_batches
        SET current_qty = current_qty - v_take,
            batch_status = CASE WHEN (current_qty - v_take) = 0 THEN 'DEPLETED' ELSE 'ACTIVE' END
        WHERE batch_id = v_batch.batch_id;

        -- La venta guarda el costo del lote consumido, no un promedio: es lo que
        -- permite calcular el margen real de cada salida.
        INSERT INTO daily_sales (
            store_id, batch_id, product_id, sale_timestamp,
            quantity, unit_retail_price, lot_unit_cogs, operator_id
        ) VALUES (
            p_store_id, v_batch.batch_id, v_product_id, CURRENT_TIMESTAMP,
            v_take, v_unit_price, v_batch.unit_cost, p_operator_id
        );

        v_remaining := v_remaining - v_take;
    END LOOP;

    IF v_remaining > 0 THEN
        RAISE EXCEPTION 'Stock insuficiente para SKU %. Faltaron % unidades.', p_sku, v_remaining
            USING ERRCODE = 'P0002';
    END IF;
END;
$$;
