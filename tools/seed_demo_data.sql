-- ==============================================================================
-- Semilla minima para probar la ingesta de inventario en un entorno de desarrollo.
-- Crea la compania y la tienda a las que apuntara store_id en las pruebas.
-- Es idempotente: se puede ejecutar varias veces sin duplicar filas.
-- ==============================================================================

INSERT INTO companies (legal_name, tax_id)
VALUES ('Minimarket Kardo Demo S.A.C.', '20123456789')
ON CONFLICT (tax_id) DO NOTHING;

INSERT INTO stores (company_id, store_code, name, address)
SELECT company_id, 'KARDO-DEV-01', 'Tienda de Desarrollo', 'Av. Siempre Viva 742'
FROM companies
WHERE tax_id = '20123456789'
ON CONFLICT (store_code) DO NOTHING;

SELECT s.store_id, s.store_code, c.company_id, c.legal_name
FROM stores s
JOIN companies c ON c.company_id = s.company_id
WHERE s.store_code = 'KARDO-DEV-01';
