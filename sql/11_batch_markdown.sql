-- ==============================================================================
-- Kardo — 11_batch_markdown.sql
-- El precio rebajado de un lote, y la venta que lo respeta.
--
-- ADITIVO: no borra ni modifica datos de 01–10. Se puede ejecutar varias veces.
--
-- Por que existe: hasta aqui la escalera de descuentos (sql/09) recomendaba un
-- precio y guardaba quien lo decidio, pero `sp_process_fefo_sale` seguia
-- cobrando el precio de lista del catalogo. Es decir: el operario rebajaba la
-- leche a S/ 3.80 en el anaquel y Kardo la registraba vendida a S/ 5.20.
--
-- Eso rompia dos cosas a la vez. El margen quedaba mal justo en los lotes que
-- el propio sistema mandaba rebajar, y la frase con la que se justifica una
-- rebaja -"cedes X de margen para no perder Y de costo"- no se podia comprobar
-- despues, porque los libros decian que no se habia cedido nada.
--
-- El precio vive en el LOTE y no en el producto porque eso es lo que se rebaja:
-- el yogurt que vence el jueves, no el yogurt. Dos lotes del mismo SKU pueden
-- estar a precios distintos, y de hecho es lo normal mientras dura la oferta.
-- ==============================================================================

ALTER TABLE inventory_batches
    ADD COLUMN IF NOT EXISTS markdown_price NUMERIC(10, 2);

COMMENT ON COLUMN inventory_batches.markdown_price IS
    'Precio de oferta vigente para este lote. NULL = se vende al precio de lista del catalogo.';

-- ------------------------------------------------------------------------------
-- El piso del costo deja de ser una regla de Java y pasa a ser un invariante.
--
-- DiscountLadder ya nunca sugiere por debajo del costo, pero esa es la unica
-- puerta que lo respeta hoy; manana una carga masiva de ofertas o una correccion
-- a mano entrarian por otro lado. En la merma pierdes el costo una vez;
-- vendiendo por debajo del costo lo pierdes y ademas regalas la unidad.
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_markdown_not_below_cost'
    ) THEN
        ALTER TABLE inventory_batches
            ADD CONSTRAINT chk_markdown_not_below_cost
            CHECK (markdown_price IS NULL OR markdown_price >= unit_cost);
    END IF;
END $$;

-- ==============================================================================
-- La venta cobra el precio vigente de CADA lote.
--
-- Unico cambio real frente a sql/04: el cursor trae `markdown_price` y el
-- asiento usa COALESCE(lote, catalogo). Una venta que cruza dos lotes -uno en
-- oferta y otro no- genera dos asientos con precios distintos, que es
-- exactamente lo que ocurrio en el anaquel.
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
    v_list_price NUMERIC(10, 2);
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
    INTO v_product_id, v_list_price
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
        SELECT batch_id, current_qty, unit_cost, expiration_date, markdown_price
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

        -- La venta guarda el costo del lote consumido, no un promedio, y ahora
        -- tambien el precio al que ese lote estaba de verdad en el anaquel: es
        -- lo que permite calcular el margen real de cada salida.
        INSERT INTO daily_sales (
            store_id, batch_id, product_id, sale_timestamp,
            quantity, unit_retail_price, lot_unit_cogs, operator_id
        ) VALUES (
            p_store_id, v_batch.batch_id, v_product_id, CURRENT_TIMESTAMP,
            v_take, COALESCE(v_batch.markdown_price, v_list_price), v_batch.unit_cost, p_operator_id
        );

        v_remaining := v_remaining - v_take;
    END LOOP;

    IF v_remaining > 0 THEN
        RAISE EXCEPTION 'Stock insuficiente para SKU %. Faltaron % unidades.', p_sku, v_remaining
            USING ERRCODE = 'P0002';
    END IF;
END;
$$;
