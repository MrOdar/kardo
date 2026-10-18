# ==============================================================================
# SmartKardex AI — 01_schema_ddl_3nf.sql
# PostgreSQL 15 Relational DDL (3NF) con Extensiones, Invariantes y RLS
# ==============================================================================

-- 1. Extensiones requeridas para Entity Matching
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. Limpieza idempotente
DROP TABLE IF EXISTS waste_log CASCADE;
DROP TABLE IF EXISTS daily_sales CASCADE;
DROP TABLE IF EXISTS inventory_batches CASCADE;
DROP TABLE IF EXISTS goods_receipt_items CASCADE;
DROP TABLE IF EXISTS goods_receipt_headers CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS suppliers CASCADE;
DROP TABLE IF EXISTS stores CASCADE;
DROP TABLE IF EXISTS companies CASCADE;

-- 3. Entidades Core

CREATE TABLE companies (
    company_id SERIAL PRIMARY KEY,
    legal_name VARCHAR(150) NOT NULL,
    tax_id VARCHAR(20) NOT NULL UNIQUE, -- RUC en Perú
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stores (
    store_id SERIAL PRIMARY KEY,
    company_id INT NOT NULL REFERENCES companies(company_id) ON DELETE RESTRICT,
    store_code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    address TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE suppliers (
    supplier_id SERIAL PRIMARY KEY,
    company_id INT NOT NULL REFERENCES companies(company_id) ON DELETE RESTRICT,
    name VARCHAR(150) NOT NULL,
    tax_id VARCHAR(20) NOT NULL,
    contact_email VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_supplier_tax_company UNIQUE (company_id, tax_id)
);

CREATE TABLE products (
    product_id SERIAL PRIMARY KEY,
    company_id INT NOT NULL REFERENCES companies(company_id) ON DELETE RESTRICT,
    sku VARCHAR(50) NOT NULL,
    barcode VARCHAR(50),
    name VARCHAR(200) NOT NULL,
    category VARCHAR(80) NOT NULL,
    unit_of_measure VARCHAR(20) NOT NULL DEFAULT 'UNIDAD',
    unit_cost NUMERIC(10, 2) NOT NULL CHECK (unit_cost >= 0),
    retail_price NUMERIC(10, 2) NOT NULL CHECK (retail_price >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_sku_company UNIQUE (company_id, sku)
);

-- Índice GIN para matching trigram eficiente en OCR (operador %> con name a la izquierda)
CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);

-- 4. Documentos de Recepción de Mercadería (GRN)
CREATE TABLE goods_receipt_headers (
    receipt_id SERIAL PRIMARY KEY,
    store_id INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,
    supplier_id INT NOT NULL REFERENCES suppliers(supplier_id) ON DELETE RESTRICT,
    grn_number VARCHAR(50) NOT NULL, -- Número de Guía de Remisión o Factura
    issue_date DATE NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ocr_raw_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_grn_store UNIQUE (store_id, grn_number)
);

CREATE TABLE goods_receipt_items (
    item_id SERIAL PRIMARY KEY,
    receipt_id INT NOT NULL REFERENCES goods_receipt_headers(receipt_id) ON DELETE CASCADE,
    product_id INT NOT NULL REFERENCES products(product_id) ON DELETE RESTRICT,
    invoiced_qty NUMERIC(10, 2) NOT NULL CHECK (invoiced_qty >= 0),
    received_qty NUMERIC(10, 2) NOT NULL CHECK (received_qty >= 0),
    discrepancy_qty NUMERIC(10, 2) GENERATED ALWAYS AS (received_qty - invoiced_qty) STORED,
    unit_cost NUMERIC(10, 2) NOT NULL CHECK (unit_cost >= 0)
);

-- 5. Lotes de Inventario Físico (Rotación FEFO y Balances de Masa)
CREATE TABLE inventory_batches (
    batch_id SERIAL PRIMARY KEY,
    store_id INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,
    product_id INT NOT NULL REFERENCES products(product_id) ON DELETE RESTRICT,
    receipt_item_id INT REFERENCES goods_receipt_items(item_id) ON DELETE SET NULL,
    batch_code VARCHAR(80) NOT NULL,
    manufacturing_date DATE,
    expiration_date DATE NOT NULL,
    initial_qty INT NOT NULL CHECK (initial_qty > 0),
    current_qty INT NOT NULL,
    quarantined_qty INT NOT NULL DEFAULT 0,
    unit_cost NUMERIC(10, 2) NOT NULL CHECK (unit_cost >= 0),
    batch_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (batch_status IN ('ACTIVE', 'DEPLETED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Invariante estricto de balance de masa
    CONSTRAINT chk_batch_mass_balance CHECK (
        current_qty >= 0 AND 
        quarantined_qty >= 0 AND 
        (current_qty + quarantined_qty) <= initial_qty
    ),
    CONSTRAINT chk_batch_dates CHECK (manufacturing_date IS NULL OR manufacturing_date <= expiration_date)
);

-- Índice parcial optimizado para el algoritmo FEFO (ordena lotes activos por expiración más próxima)
CREATE INDEX idx_batches_fefo_active ON inventory_batches (store_id, product_id, expiration_date ASC)
WHERE batch_status = 'ACTIVE' AND current_qty > 0;

-- 6. Transacciones de Salida y Ventas Diarias
CREATE TABLE daily_sales (
    sale_id SERIAL PRIMARY KEY,
    store_id INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,
    batch_id INT NOT NULL REFERENCES inventory_batches(batch_id) ON DELETE RESTRICT,
    product_id INT NOT NULL REFERENCES products(product_id) ON DELETE RESTRICT,
    sale_timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_retail_price NUMERIC(10, 2) NOT NULL CHECK (unit_retail_price >= 0),
    lot_unit_cogs NUMERIC(10, 2) NOT NULL CHECK (lot_unit_cogs >= 0),
    operator_id VARCHAR(50) NOT NULL
);

-- 7. Registro de Mermas y Control Antifraude
CREATE TABLE waste_log (
    waste_id SERIAL PRIMARY KEY,
    store_id INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,
    batch_id INT NOT NULL REFERENCES inventory_batches(batch_id) ON DELETE RESTRICT,
    product_id INT NOT NULL REFERENCES products(product_id) ON DELETE RESTRICT,
    log_timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    quantity_discarded INT NOT NULL CHECK (quantity_discarded > 0),
    unit_cost NUMERIC(10, 2) NOT NULL CHECK (unit_cost >= 0),
    total_loss_amount NUMERIC(10, 2) GENERATED ALWAYS AS (quantity_discarded * unit_cost) STORED,
    waste_reason VARCHAR(50) NOT NULL CHECK (waste_reason IN ('EXPIRATION', 'DAMAGE', 'COLD_CHAIN_FAILURE', 'RECALL')),
    approval_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' CHECK (approval_status IN ('APPROVED', 'PENDING_APPROVAL', 'REJECTED')),
    operator_id VARCHAR(50) NOT NULL,
    authorized_by VARCHAR(50)
);

-- Índice para consultas analíticas de auditoría y mermas pendientes
CREATE INDEX idx_waste_approval ON waste_log (store_id, approval_status)
WHERE approval_status = 'PENDING_APPROVAL';
