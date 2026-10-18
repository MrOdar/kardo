# ==============================================================================
# SmartKardex AI — 02_procedures_and_triggers.sql
# Procedimientos Transaccionales FEFO, Control de Mermas y Escenario Demo
# ==============================================================================

-- 1. Procedimiento de Salida FEFO Atómica
CREATE OR REPLACE PROCEDURE sp_process_fefo_sale(
    p_store_id INT,
    p_sku VARCHAR(50),
    p_quantity INT,
    p_operator_id VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_product_id INT;
    v_unit_price NUMERIC(10, 2);
    v_remaining INT := p_quantity;
    v_batch RECORD;
    v_take INT;
BEGIN
    -- Candado compartido sobre la tienda: coordina con el bloqueo exclusivo del reset demo
    PERFORM 1 FROM stores WHERE store_id = p_store_id FOR SHARE;

    -- Obtener producto y validar existencia
    SELECT product_id, retail_price 
    INTO v_product_id, v_unit_price
    FROM products 
    WHERE sku = p_sku;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Producto con SKU % no existe en el catálogo.', p_sku
            USING ERRCODE = 'P0001';
    END IF;

    -- Iterar lotes activos por expiración más próxima (FEFO estricto) con bloqueo pesimista
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

        -- Actualizar lote
        UPDATE inventory_batches
        SET current_qty = current_qty - v_take,
            batch_status = CASE WHEN (current_qty - v_take) = 0 THEN 'DEPLETED' ELSE 'ACTIVE' END
        WHERE batch_id = v_batch.batch_id;

        -- Registrar venta con el costo real del lote consumido
        INSERT INTO daily_sales (
            store_id, batch_id, product_id, sale_timestamp, 
            quantity, unit_retail_price, lot_unit_cogs, operator_id
        ) VALUES (
            p_store_id, v_batch.batch_id, v_product_id, CURRENT_TIMESTAMP,
            v_take, v_unit_price, v_batch.unit_cost, p_operator_id
        );

        v_remaining := v_remaining - v_take;
    END LOOP;

    -- Si no hubo stock suficiente
    IF v_remaining > 0 THEN
        RAISE EXCEPTION 'Stock insuficiente para SKU %. Faltaron % unidades.', p_sku, v_remaining
            USING ERRCODE = 'P0002';
    END IF;
END;
$$;

-- 2. Procedimiento de Reinicio Idempotente de Tienda Demo (ID 999)
CREATE OR REPLACE PROCEDURE sp_reset_demo_environment()
LANGUAGE plpgsql AS $$
DECLARE
    v_demo_store_id CONSTANT INT := 999;
    v_demo_company_id CONSTANT INT := 999;
    v_supplier_id INT;
    v_receipt_id INT;
    v_p_leche INT;
    v_p_yogurt INT;
    v_p_galleta INT;
    v_item_id INT;
BEGIN
    -- Candado exclusivo sobre la tienda demo: espera a que terminen ventas en vuelo
    -- y bloquea nuevas ventas hasta concluir la reinicialización
    PERFORM store_id FROM stores WHERE store_id = v_demo_store_id FOR UPDATE;

    -- 1. Limpieza de tablas transaccionales en orden de dependencia
    DELETE FROM daily_sales WHERE store_id = v_demo_store_id;
    DELETE FROM waste_log WHERE store_id = v_demo_store_id;
    DELETE FROM inventory_batches WHERE store_id = v_demo_store_id;
    
    DELETE FROM goods_receipt_items WHERE receipt_id IN (
        SELECT receipt_id FROM goods_receipt_headers WHERE store_id = v_demo_store_id
    );
    DELETE FROM goods_receipt_headers WHERE store_id = v_demo_store_id;

    -- 2. Asegurar empresa y tienda demo
    INSERT INTO companies (company_id, legal_name, tax_id)
    VALUES (v_demo_company_id, 'Supermercados Demo Perú S.A.C.', '20999888771')
    ON CONFLICT (company_id) DO UPDATE SET legal_name = EXCLUDED.legal_name;

    INSERT INTO stores (store_id, company_id, store_code, name, address)
    VALUES (v_demo_store_id, v_demo_company_id, 'DEMO-LIMA-01', 'Tienda Demo Miraflores', 'Av. Larco 1234')
    ON CONFLICT (store_id) DO UPDATE SET name = EXCLUDED.name;

    -- 3. Proveedor Demo
    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'Distribuidora Lácteos del Centro S.A.', '20123456789', 'pedidos@lacteosdemo.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_supplier_id;

    -- 4. Catálogo de productos representativos
    INSERT INTO products (company_id, sku, barcode, name, category, unit_cost, retail_price)
    VALUES 
        (v_demo_company_id, 'SKU-LECHE-UHT-1L', '7750123456789', 'Leche Entera UHT 1L Caja', 'Lácteos y Derivados', 3.80, 5.20),
        (v_demo_company_id, 'SKU-YOG-FRESA-1L', '7750123456790', 'Yogurt Fresa Botella 1L', 'Lácteos y Derivados', 4.50, 6.80),
        (v_demo_company_id, 'SKU-GALLETA-CHOC-6P', '7750123456791', 'Galleta Rellena Chocolate Paquete x6', 'Snacks y Galletas', 2.10, 3.50)
    ON CONFLICT (company_id, sku) DO UPDATE SET retail_price = EXCLUDED.retail_price;

    SELECT product_id INTO v_p_leche FROM products WHERE company_id = v_demo_company_id AND sku = 'SKU-LECHE-UHT-1L';
    SELECT product_id INTO v_p_yogurt FROM products WHERE company_id = v_demo_company_id AND sku = 'SKU-YOG-FRESA-1L';
    SELECT product_id INTO v_p_galleta FROM products WHERE company_id = v_demo_company_id AND sku = 'SKU-GALLETA-CHOC-6P';

    -- 5. Guía de Remisión Demo (Simula una entrega con descuadre)
    INSERT INTO goods_receipt_headers (store_id, supplier_id, grn_number, issue_date)
    VALUES (v_demo_store_id, v_supplier_id, 'GRN-2026-DEMO-001', CURRENT_DATE - INTERVAL '2 days')
    RETURNING receipt_id INTO v_receipt_id;

    -- Leche: Facturado 50, Recibido 48 (Discrepancia de -2 unidades)
    INSERT INTO goods_receipt_items (receipt_id, product_id, invoiced_qty, received_qty, unit_cost)
    VALUES (v_receipt_id, v_p_leche, 50, 48, 3.80)
    RETURNING item_id INTO v_item_id;

    -- 6. Lotes con Alertas FEFO Críticas
    -- Lote 1: Leche UHT que vence en 48 horas (Alerta Roja FEFO)
    INSERT INTO inventory_batches (
        store_id, product_id, receipt_item_id, batch_code, 
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    ) VALUES (
        v_demo_store_id, v_p_leche, v_item_id, 'LOT-LCH-EXP-48H',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '2 days', 48, 20, 3.80, 'ACTIVE'
    );

    -- Lote 2: Leche UHT fresca (vence en 30 días)
    INSERT INTO inventory_batches (
        store_id, product_id, receipt_item_id, batch_code, 
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    ) VALUES (
        v_demo_store_id, v_p_leche, v_item_id, 'LOT-LCH-FRESCO-30D',
        CURRENT_DATE - INTERVAL '5 days', CURRENT_DATE + INTERVAL '30 days', 50, 50, 3.80, 'ACTIVE'
    );

    -- Lote 3: Yogurt que vence en 5 días
    INSERT INTO inventory_batches (
        store_id, product_id, batch_code, 
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    ) VALUES (
        v_demo_store_id, v_p_yogurt, 'LOT-YOG-EXP-05D',
        CURRENT_DATE - INTERVAL '15 days', CURRENT_DATE + INTERVAL '5 days', 30, 15, 4.50, 'ACTIVE'
    );
END;
$$;
