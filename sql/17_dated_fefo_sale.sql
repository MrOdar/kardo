-- ============================================================================
-- 17. La venta puede llevar su fecha
--
-- Por que existe: `sp_process_fefo_sale` sellaba siempre `CURRENT_TIMESTAMP`,
-- que es lo correcto cuando la venta se teclea en la caja en el momento. Con la
-- carga de ventas por Excel deja de serlo: una tienda sube las ventas del dia
-- al cerrar, o recupera el viernes una semana que se le quedo atrasada, y
-- fecharlas todas en el momento de la subida descuadra justo lo que el producto
-- usa para decidir. La ventana de 30 dias de `InventoryInsightsService` y la
-- cobertura que sale de ella se calculan sobre `sale_timestamp`: con las ventas
-- apiladas en un solo dia, el ritmo de salida de todo el catalogo queda mal.
--
-- ADITIVO en el esquema -no toca tablas ni datos- pero REEMPLAZA la firma del
-- procedimiento. Idempotente: se puede reaplicar.
--
-- POR QUE SUSTITUYE LA FIRMA EN VEZ DE AÑADIRSE AL LADO:
--
--   Un parametro nuevo con DEFAULT no conviviria con la version de 4
--   parametros: una llamada con 4 argumentos encajaria en las dos y PostgreSQL
--   la rechazaria por ambigua ("procedure is not unique"). Asi que la de 4 se
--   retira al final, igual que hizo sql/15 con las resoluciones por tienda.
--
--   El DEFAULT NULL deja que todo lo que ya llamaba con 4 argumentos -la venta
--   de una unidad desde la caja- siga funcionando sin cambios y siga fechando
--   en el momento, que es lo que debe hacer.
--
-- Codigo de error nuevo:
--   P0010 la fecha de la venta esta en el futuro
--
-- LIMITE CONOCIDO, Y ES DELIBERADO:
--
--   La fecha solo sella la venta. El FEFO sigue eligiendo lote con el criterio
--   de HOY, y por tanto sigue negandose a vender un lote ya vencido
--   (`expiration_date >= CURRENT_DATE`). Si se retrocede una venta a una semana
--   atras y el lote que la atendio venció entretanto, la fila se rechaza.
--
--   La alternativa -elegir lote segun el estado que el inventario tenia en esa
--   fecha- exige un historico de existencias por lote y por dia que esta base
--   no guarda, y reconstruirlo a posteriori seria inventar de donde salio cada
--   unidad. Mejor rechazar la fila y decir por que: esto sirve para recuperar
--   dias recientes, no para rehacer meses.
-- ============================================================================

CREATE OR REPLACE PROCEDURE sp_process_fefo_sale(
    p_store_id INT,
    p_sku VARCHAR(50),
    p_quantity INT,
    p_operator_id VARCHAR(50),
    p_sale_date DATE DEFAULT NULL
)
LANGUAGE plpgsql AS $$
DECLARE
    v_company_id INT;
    v_product_id INT;
    v_list_price NUMERIC(10, 2);
    v_remaining INT := p_quantity;
    v_batch RECORD;
    v_take INT;
    v_stamp TIMESTAMPTZ;
BEGIN
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'La cantidad a vender debe ser mayor que 0 (recibido: %).', p_quantity
            USING ERRCODE = 'P0004';
    END IF;

    -- Una venta futura no es un dedazo recuperable: falsea la ventana de
    -- rotacion hacia adelante y no hay forma de saber cual era la intencion.
    IF p_sale_date IS NOT NULL AND p_sale_date > CURRENT_DATE THEN
        RAISE EXCEPTION 'La fecha de la venta (%) esta en el futuro.', p_sale_date
            USING ERRCODE = 'P0010';
    END IF;

    -- Sin fecha, el momento. Con fecha, ese dia a la hora actual: la hora no la
    -- sabe nadie y poner medianoche amontonaria todas las ventas del dia en el
    -- mismo instante, que estorba al ordenar y no es mas cierto.
    v_stamp := COALESCE(p_sale_date + CURRENT_TIME, CURRENT_TIMESTAMP);

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

        -- La venta guarda el costo del lote consumido, no un promedio, y
        -- tambien el precio al que ese lote estaba de verdad en el anaquel: es
        -- lo que permite calcular el margen real de cada salida.
        INSERT INTO daily_sales (
            store_id, batch_id, product_id, sale_timestamp,
            quantity, unit_retail_price, lot_unit_cogs, operator_id
        ) VALUES (
            p_store_id, v_batch.batch_id, v_product_id, v_stamp,
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

COMMENT ON PROCEDURE sp_process_fefo_sale(INT, VARCHAR, INT, VARCHAR, DATE) IS
    'Salida FEFO. Sin p_sale_date sella el momento; con fecha, ese dia. '
    'El lote se elige con el criterio de hoy: un lote ya vencido no se vende '
    'aunque la fecha de la venta sea anterior a su vencimiento.';

-- Al final y no al principio: mientras exista la de 4 parametros, una llamada
-- con 4 argumentos es ambigua. Primero se crea la nueva, luego se retira la
-- vieja, de modo que no hay un instante sin procedimiento.
DROP PROCEDURE IF EXISTS sp_process_fefo_sale(INT, VARCHAR, INT, VARCHAR);
