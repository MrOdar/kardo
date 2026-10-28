-- ============================================================================
-- 14. Canje al proveedor
--
-- Por que existe: en el retail peruano, los distribuidores grandes -lacteos,
-- embutidos, panaderia industrial- canjean la mercaderia proxima a vencer si
-- se avisa con dias de antelacion. La tienda recupera el 100% del costo. Hasta
-- ahora Kardo solo sabia perder menos; esto es recuperar.
--
-- ADITIVO. Tabla y procedimientos nuevos; no toca nada de 01 ni de 05/08.
--
-- POR QUE NO ES UN MOTIVO MAS DE `waste_log`:
--
--   `waste_log.total_loss_amount` es una columna GENERADA: todo lo que entra
--   ahi es perdida por definicion. Un canje recupera el costo, de modo que
--   meterlo en esa tabla haria que el informe de mermas -y el reparto por
--   proveedor- contaran como perdida justo el dinero que se salvo, y acusaran
--   al proveedor que mejor se porta. El signo contable es el contrario, asi
--   que la tabla es otra.
--
-- Estados: PENDING -> CREDITED | REFUSED
--
--   PENDING   declarado; las unidades quedan inmovilizadas en cuarentena, que
--             es lo que impide venderlas mientras esperan al camion.
--   CREDITED  el proveedor se las llevo y abono el costo. Salen del stock sin
--             ser perdida.
--   REFUSED   el proveedor no las acepto. Vuelven al stock, y como siguen
--             venciendo, el semaforo las empuja al circuito de mermas, que ya
--             existe. Este flujo no inventa contabilidad de perdidas: se la
--             devuelve a quien ya la lleva.
--
-- Errores (mismos codigos que el circuito de mermas, mismo significado):
--   P0004 cantidad invalida
--   P0005 el lote no tiene suficientes unidades disponibles
--   P0006 el lote no existe o no es de esa tienda
--   P0009 el lote no vino por una guia: no hay proveedor a quien canjearlo
--   P0007 el canje no existe o ya fue resuelto
-- ============================================================================

CREATE TABLE IF NOT EXISTS supplier_returns (
    return_id    SERIAL PRIMARY KEY,
    store_id     INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,
    batch_id     INT NOT NULL REFERENCES inventory_batches(batch_id) ON DELETE RESTRICT,
    product_id   INT NOT NULL REFERENCES products(product_id) ON DELETE RESTRICT,

    -- De quien se reclama. Es obligatorio: un canje sin proveedor no se puede
    -- reclamar a nadie, y por eso solo se puede declarar sobre lotes que
    -- entraron por una guia de remision.
    supplier_id  INT NOT NULL REFERENCES suppliers(supplier_id) ON DELETE RESTRICT,

    quantity     INT NOT NULL CHECK (quantity > 0),
    unit_cost    NUMERIC(10, 2) NOT NULL CHECK (unit_cost >= 0),

    -- Lo que se recupera si el proveedor acepta. Generada, igual que la
    -- perdida de `waste_log`, para que nadie pueda escribir una cifra que no
    -- cuadre con las unidades.
    recovered_amount NUMERIC(10, 2) GENERATED ALWAYS AS (quantity * unit_cost) STORED,

    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'CREDITED', 'REFUSED')),

    declared_by  VARCHAR(50) NOT NULL,
    declared_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by  VARCHAR(50),
    resolved_at  TIMESTAMPTZ,

    -- Un canje resuelto tiene que decir quien y cuando; uno pendiente, no.
    CONSTRAINT chk_return_resolution CHECK (
        (status = 'PENDING'  AND resolved_by IS NULL AND resolved_at IS NULL) OR
        (status <> 'PENDING' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
    )
);

COMMENT ON TABLE supplier_returns IS
    'Mercaderia devuelta al distribuidor antes de vencer. Recupera costo; no es merma.';

CREATE INDEX IF NOT EXISTS idx_returns_store_status ON supplier_returns (store_id, status);
CREATE INDEX IF NOT EXISTS idx_returns_supplier ON supplier_returns (supplier_id);

-- ------------------------------------------------------------------------------
-- 1. Declarar el canje: las unidades quedan inmovilizadas
-- ------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_declare_return(
    p_store_id INT,
    p_batch_id INT,
    p_quantity INT,
    p_declared_by VARCHAR(50)
)
LANGUAGE plpgsql AS $$
DECLARE
    v_product_id INT;
    v_unit_cost NUMERIC(10, 2);
    v_supplier_id INT;
    v_affected INT;
BEGIN
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'La cantidad a canjear debe ser mayor que 0 (recibido: %).', p_quantity
            USING ERRCODE = 'P0004';
    END IF;

    PERFORM 1 FROM stores WHERE store_id = p_store_id FOR SHARE;

    -- El proveedor sale de la trazabilidad del lote: guia -> cabecera ->
    -- proveedor. Los lotes cargados desde Excel no la tienen, y ahi el canje
    -- no se puede ofrecer porque no hay a quien reclamarle.
    SELECT b.product_id, b.unit_cost, gh.supplier_id
    INTO v_product_id, v_unit_cost, v_supplier_id
    FROM inventory_batches b
    LEFT JOIN goods_receipt_items gi ON gi.item_id = b.receipt_item_id
    LEFT JOIN goods_receipt_headers gh ON gh.receipt_id = gi.receipt_id
    WHERE b.batch_id = p_batch_id AND b.store_id = p_store_id
    FOR UPDATE OF b;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El lote % no existe en la tienda %.', p_batch_id, p_store_id
            USING ERRCODE = 'P0006';
    END IF;

    IF v_supplier_id IS NULL THEN
        RAISE EXCEPTION 'El lote % no vino por una guia de remision: no hay proveedor a quien canjearlo.',
            p_batch_id USING ERRCODE = 'P0009';
    END IF;

    -- Misma guarda que la merma: la condicion en el UPDATE es lo que impide
    -- que dos declaraciones simultaneas se pasen del stock.
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

    INSERT INTO supplier_returns (
        store_id, batch_id, product_id, supplier_id, quantity, unit_cost, declared_by
    ) VALUES (
        p_store_id, p_batch_id, v_product_id, v_supplier_id, p_quantity, v_unit_cost, p_declared_by
    );
END;
$$;

-- ------------------------------------------------------------------------------
-- 2. El proveedor abono: la mercaderia sale y el costo se recupera
-- ------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_credit_return(
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
    WHERE return_id = p_return_id AND status = 'PENDING'
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
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE supplier_returns
    SET status = 'CREDITED', resolved_by = p_resolved_by, resolved_at = CURRENT_TIMESTAMP
    WHERE return_id = p_return_id;
END;
$$;

-- ------------------------------------------------------------------------------
-- 3. El proveedor no lo acepto: vuelve al stock
-- ------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_refuse_return(
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
    WHERE return_id = p_return_id AND status = 'PENDING'
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
      AND quarantined_qty >= v_quantity;

    GET DIAGNOSTICS v_affected = ROW_COUNT;
    IF v_affected = 0 THEN
        RAISE EXCEPTION 'Inconsistencia en la cuarentena del lote %.', v_batch_id
            USING ERRCODE = 'P0005';
    END IF;

    UPDATE supplier_returns
    SET status = 'REFUSED', resolved_by = p_resolved_by, resolved_at = CURRENT_TIMESTAMP
    WHERE return_id = p_return_id;
END;
$$;
