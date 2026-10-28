-- ============================================================================
-- 16. Catalogo y lotes expandidos de la tienda demo (88 productos del Excel simulado)
--
-- Redefine sp_reset_demo_environment() para sembrar exactamente el inventario
-- representativo de 88 productos (Lacteos, Bebidas, Licores, Snacks, Embutidos,
-- Panaderia, Abarrotes y Limpieza) con fechas relativas a CURRENT_DATE, alertas
-- FEFO inmediatas y registro de carga para la auditoria de Excel.
-- ============================================================================

CREATE OR REPLACE PROCEDURE sp_reset_demo_environment()
LANGUAGE plpgsql AS $$
DECLARE
    v_demo_store_id CONSTANT INT := 999;
    v_demo_company_id CONSTANT INT := 999;
    v_upload_id INT;
    v_sup_gloria INT;
    v_sup_sanfernando INT;
    v_sup_bimbo INT;
    v_sup_backus INT;
    v_sup_lindley INT;
    v_sup_alicorp INT;
    v_receipt_id INT;
    v_item_id INT;
BEGIN
    -- Candado exclusivo sobre la tienda demo
    PERFORM store_id FROM stores WHERE store_id = v_demo_store_id FOR UPDATE;

    -- 1. Limpieza de tablas dependientes en orden
    DELETE FROM daily_sales WHERE store_id = v_demo_store_id;
    DELETE FROM waste_log WHERE store_id = v_demo_store_id;
    DELETE FROM supplier_returns WHERE store_id = v_demo_store_id;
    DELETE FROM inventory_batches WHERE store_id = v_demo_store_id;
    DELETE FROM goods_receipt_items WHERE receipt_id IN (
        SELECT receipt_id FROM goods_receipt_headers WHERE store_id = v_demo_store_id
    );
    DELETE FROM goods_receipt_headers WHERE store_id = v_demo_store_id;
    DELETE FROM inventory_uploads WHERE store_id = v_demo_store_id;

    -- 2. Asegurar empresa y tienda demo
    INSERT INTO companies (company_id, legal_name, tax_id)
    VALUES (v_demo_company_id, 'Supermercados Demo Peru S.A.C.', '20999888771')
    ON CONFLICT (company_id) DO UPDATE SET legal_name = EXCLUDED.legal_name;

    INSERT INTO stores (store_id, company_id, store_code, name, address)
    VALUES (v_demo_store_id, v_demo_company_id, 'DEMO-LIMA-01', 'Tienda Demo Miraflores', 'Av. Larco 1234')
    ON CONFLICT (store_id) DO UPDATE SET name = EXCLUDED.name;

    -- 3. Proveedores representativos
    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'Leche Gloria S.A.', '20100190797', 'pedidos@gloria.com.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_sup_gloria;

    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'San Fernando S.A.', '20100154308', 'ventas@san-fernando.com.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_sup_sanfernando;

    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'Bimbo del Peru S.A.', '20383796590', 'pedidos@bimbo.com.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_sup_bimbo;

    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'Union de Cervecerias Backus y Johnston S.A.A.', '20100113610', 'contacto@backus.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_sup_backus;

    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'Arca Continental Lindley S.A.', '20100010721', 'ventas@lindley.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_sup_lindley;

    INSERT INTO suppliers (company_id, name, tax_id, contact_email)
    VALUES (v_demo_company_id, 'Alicorp S.A.A.', '20100055237', 'atencionclientes@alicorp.com.pe')
    ON CONFLICT (company_id, tax_id) DO UPDATE SET name = EXCLUDED.name
    RETURNING supplier_id INTO v_sup_alicorp;

    -- 4. Registrar la carga del Excel en inventory_uploads para trazabilidad
    -- Carga de atrezo: da origen a los 88 lotes para que la pestaña de Excel y
    -- "Ultimos movimientos" cuenten de donde salio este inventario.
    --
    -- DOS COSAS QUE NO SON ARBITRARIAS:
    --
    -- 1. El nombre NO es el del archivo que se reparte en
    --    static/assets/inventario_ejemplo_kardo.xlsx, y la huella tampoco. La
    --    ingesta rechaza por duplicado comparando (store_id, file_hash), de
    --    modo que sembrar aqui la huella real de ese archivo dejaria al
    --    evaluador sin poder subirlo: la demostracion de la ingesta en
    --    streaming -que es la cabecera del proyecto- moriria en un 409. Son dos
    --    cargas distintas a proposito: esta es el historico, aquella es la que
    --    el evaluador hace con sus manos.
    --
    -- 2. La huella es un SHA-256 de verdad (el de la cadena que identifica a
    --    este fixture), no un relleno. Antes aqui habia
    --    e3b0c44298fc...b855, que es el SHA-256 de la cadena vacia: una de las
    --    constantes mas reconocibles que hay, y la interfaz la enseña en la
    --    columna HUELLA. En un proyecto cuyo argumento es la trazabilidad,
    --    exhibir una huella que cualquiera reconoce como falsa desmiente justo
    --    lo que se esta enseñando.
    INSERT INTO inventory_uploads (
        store_id, file_name, file_hash, file_size_bytes, total_rows, inserted_rows, quarantined_rows, uploaded_by, client_ip
    ) VALUES (
        v_demo_store_id, 'carga-inicial-demo.xlsx', 'd204e5c58af400f8ff391831a63755eb2904871cd30a053da61167dc88cf5e17',
        11940, 88, 88, 0, 'demo@kardo.pe', '127.0.0.1'
    ) RETURNING upload_id INTO v_upload_id;

    -- 5. Guia de Remision Demo (Simula una entrega de Gloria con discrepancia)
    INSERT INTO goods_receipt_headers (store_id, supplier_id, grn_number, issue_date)
    VALUES (v_demo_store_id, v_sup_gloria, 'GRN-2026-DEMO-001', CURRENT_DATE - INTERVAL '2 days')
    RETURNING receipt_id INTO v_receipt_id;

    -- 6. Catalogo de los 88 productos
    INSERT INTO products (company_id, sku, barcode, name, category, unit_of_measure, unit_cost, retail_price)
    VALUES
        (v_demo_company_id, 'LAC-GLO-001', '7750101001012', 'Leche Evaporada Gloria Azul 400g', 'LACTEOS', 'LATA', 3.40, 4.50),
        (v_demo_company_id, 'LAC-GLO-002', '7750101001029', 'Leche Evaporada Gloria Light Roja 400g', 'LACTEOS', 'LATA', 3.60, 4.70),
        (v_demo_company_id, 'LAC-GLO-003', '7750101001036', 'Leche UHT Gloria Entera Caja 1L', 'LACTEOS', 'CAJA', 4.20, 5.60),
        (v_demo_company_id, 'LAC-LAI-004', '7750101001043', 'Leche UHT Laive Sin Lactosa Caja 1L', 'LACTEOS', 'CAJA', 4.40, 5.90),
        (v_demo_company_id, 'LAC-GLO-005', '7750101001050', 'Yogurt Gloria Batido Fresa Botella 1kg', 'LACTEOS', 'BOTELLA', 5.80, 7.90),
        (v_demo_company_id, 'LAC-GLO-006', '7750101001067', 'Yogurt Gloria Batido Vainilla Botella 1kg', 'LACTEOS', 'BOTELLA', 5.80, 7.90),
        (v_demo_company_id, 'LAC-LAI-007', '7750101001074', 'Yogurt Griego Laive Natural 120g', 'LACTEOS', 'VASO', 2.80, 3.90),
        (v_demo_company_id, 'LAC-GLO-008', '7750101001081', 'Mantequilla Gloria con Sal 200g', 'LACTEOS', 'PAQUETE', 5.20, 7.20),
        (v_demo_company_id, 'LAC-LAI-009', '7750101001098', 'Mantequilla Laive Pote 225g', 'LACTEOS', 'POTE', 5.90, 8.20),
        (v_demo_company_id, 'LAC-BON-010', '7750101001104', 'Queso Edam Bonle Rebanado 200g', 'LACTEOS', 'PAQUETE', 7.80, 10.50),
        (v_demo_company_id, 'LAC-LAI-011', '7750101001111', 'Queso Fresco Pasteurizado Laive 400g', 'LACTEOS', 'EMPAQUE', 9.20, 12.80),
        (v_demo_company_id, 'LAC-GLO-012', '7750101001128', 'Crema de Leche Gloria UHT 200ml', 'LACTEOS', 'CAJA', 3.90, 5.20),
        (v_demo_company_id, 'BEB-INC-001', '7750102002019', 'Gaseosa Inca Kola Sabor Original 500ml', 'BEBIDAS', 'BOTELLA', 2.40, 3.50),
        (v_demo_company_id, 'BEB-INC-002', '7750102002026', 'Gaseosa Inca Kola Sin Azucar 500ml', 'BEBIDAS', 'BOTELLA', 2.40, 3.50),
        (v_demo_company_id, 'BEB-COC-003', '7750102002033', 'Gaseosa Coca Cola Sabor Original 500ml', 'BEBIDAS', 'BOTELLA', 2.40, 3.50),
        (v_demo_company_id, 'BEB-COC-004', '7750102002040', 'Gaseosa Coca Cola Sin Azucar 500ml', 'BEBIDAS', 'BOTELLA', 2.40, 3.50),
        (v_demo_company_id, 'BEB-INC-005', '7750102002057', 'Gaseosa Inca Kola Gordita Vidrio 750ml', 'BEBIDAS', 'BOTELLA', 3.20, 4.50),
        (v_demo_company_id, 'BEB-SPR-006', '7750102002064', 'Gaseosa Sprite Lima-Limon 500ml', 'BEBIDAS', 'BOTELLA', 2.30, 3.20),
        (v_demo_company_id, 'BEB-SLU-007', '7750102002071', 'Agua Mineral San Luis Sin Gas 625ml', 'BEBIDAS', 'BOTELLA', 1.20, 2.00),
        (v_demo_company_id, 'BEB-SMA-008', '7750102002088', 'Agua Mineral San Mateo con Gas 600ml', 'BEBIDAS', 'BOTELLA', 1.50, 2.50),
        (v_demo_company_id, 'BEB-SPO-009', '7750102002095', 'Bebida Rehidratante Sporade Frutos Rojos 500ml', 'BEBIDAS', 'BOTELLA', 2.00, 3.00),
        (v_demo_company_id, 'BEB-GAT-010', '7750102002101', 'Bebida Rehidratante Gatorade Cool Blue 500ml', 'BEBIDAS', 'BOTELLA', 2.80, 4.00),
        (v_demo_company_id, 'BEB-CIF-011', '7750102002118', 'Bebida Citrica Cifrut Granadilla 500ml', 'BEBIDAS', 'BOTELLA', 1.30, 2.00),
        (v_demo_company_id, 'BEB-FRU-012', '7750102002125', 'Nectar Frugos del Valle Durazno 1L', 'BEBIDAS', 'CAJA', 3.60, 5.00),
        (v_demo_company_id, 'LIC-CUS-001', '7750103003016', 'Cerveza Cusquena Dorada Botella 330ml', 'LICORES', 'BOTELLA', 4.20, 6.50),
        (v_demo_company_id, 'LIC-CUS-002', '7750103003023', 'Cerveza Cusquena Negra Botella 330ml', 'LICORES', 'BOTELLA', 4.40, 6.80),
        (v_demo_company_id, 'LIC-PIL-003', '7750103003030', 'Cerveza Pilsen Callao Botella 630ml', 'LICORES', 'BOTELLA', 5.20, 7.80),
        (v_demo_company_id, 'LIC-PIL-004', '7750103003047', 'Cerveza Pilsen Callao Six Pack Lata 355ml', 'LICORES', 'PACK', 19.50, 28.00),
        (v_demo_company_id, 'LIC-CRI-005', '7750103003054', 'Cerveza Cristal Botella 650ml', 'LICORES', 'BOTELLA', 4.90, 7.20),
        (v_demo_company_id, 'LIC-COR-006', '7750103003061', 'Cerveza Corona Extra Botella 355ml', 'LICORES', 'BOTELLA', 5.50, 8.50),
        (v_demo_company_id, 'LIC-STE-007', '7750103003078', 'Cerveza Stella Artois Botella 330ml', 'LICORES', 'BOTELLA', 5.80, 8.90),
        (v_demo_company_id, 'LIC-CAR-008', '7750103003085', 'Ron Cartavio Black Barrel 750ml', 'LICORES', 'BOTELLA', 24.50, 34.90),
        (v_demo_company_id, 'LIC-GAL-009', '7750103003092', 'Pisco Cuatro Gallos Quebranta 700ml', 'LICORES', 'BOTELLA', 32.00, 46.90),
        (v_demo_company_id, 'LIC-JOH-010', '7750103003108', 'Whisky Johnnie Walker Red Label 750ml', 'LICORES', 'BOTELLA', 45.00, 65.00),
        (v_demo_company_id, 'SNK-LAY-001', '7750104004013', 'Papas Fritas Lays Clasicas 160g', 'SNACKS', 'BOLSA', 4.80, 6.90),
        (v_demo_company_id, 'SNK-LAY-002', '7750104004020', 'Papas Fritas Lays Onduladas 150g', 'SNACKS', 'BOLSA', 4.80, 6.90),
        (v_demo_company_id, 'SNK-DOR-003', '7750104004037', 'Tortillas de Maiz Doritos Mega Queso 150g', 'SNACKS', 'BOLSA', 4.60, 6.70),
        (v_demo_company_id, 'SNK-CHI-004', '7750104004044', 'Snack de Maiz Chizitos Fiesta 140g', 'SNACKS', 'BOLSA', 3.60, 5.20),
        (v_demo_company_id, 'SNK-PIQ-005', '7750104004051', 'Piqueo Snax Familiar 200g', 'SNACKS', 'BOLSA', 5.50, 7.90),
        (v_demo_company_id, 'SNK-CHI-006', '7750104004068', 'Chifle Piurano Salado Karinto 120g', 'SNACKS', 'BOLSA', 3.20, 4.80),
        (v_demo_company_id, 'SNK-SUB-007', '7750104004075', 'Chocolate Sublime Clasico Tableta 30g', 'SNACKS', 'UNIDAD', 1.50, 2.20),
        (v_demo_company_id, 'SNK-PRI-008', '7750104004082', 'Chocolate Princesa Crema de Mani 30g', 'SNACKS', 'UNIDAD', 1.60, 2.40),
        (v_demo_company_id, 'SNK-TRI-009', '7750104004099', 'Chocolate Triangulo DOnofrio 30g', 'SNACKS', 'UNIDAD', 1.50, 2.20),
        (v_demo_company_id, 'SNK-MOR-010', '7750104004105', 'Galletas Morochas Rellenas Paquete 6 unid', 'SNACKS', 'PAQUETE', 2.80, 4.00),
        (v_demo_company_id, 'SNK-CAS-011', '7750104004112', 'Galletas Casino Menta Paquete 6 unid', 'SNACKS', 'PAQUETE', 2.50, 3.80),
        (v_demo_company_id, 'SNK-PEP-012', '7750104004129', 'Galletas Dona Pepa Field Paquete 6 unid', 'SNACKS', 'PAQUETE', 2.90, 4.20),
        (v_demo_company_id, 'SNK-SOD-013', '7750104004136', 'Galletas Soda Field Paquete 6 unid', 'SNACKS', 'PAQUETE', 2.40, 3.50),
        (v_demo_company_id, 'SNK-GOM-014', '7750104004143', 'Gomitas Ambrosoli Ositos Dulces 100g', 'SNACKS', 'BOLSA', 2.20, 3.40),
        (v_demo_company_id, 'EMB-SFE-001', '7750105005010', 'Hot Dog de Pollo San Fernando 500g', 'EMBUTIDOS', 'PAQUETE', 6.20, 8.80),
        (v_demo_company_id, 'EMB-SFE-002', '7750105005027', 'Jamonada Especial San Fernando 250g', 'EMBUTIDOS', 'PAQUETE', 4.80, 6.90),
        (v_demo_company_id, 'EMB-BRA-003', '7750105005034', 'Jamonada de Cerdo Braedt 200g', 'EMBUTIDOS', 'PAQUETE', 6.50, 9.20),
        (v_demo_company_id, 'EMB-OTT-004', '7750105005041', 'Tocino Ahumado Tiras Otto Kunz 180g', 'EMBUTIDOS', 'PAQUETE', 9.80, 13.90),
        (v_demo_company_id, 'EMB-SFE-005', '7750105005058', 'Chorizo Parrillero San Fernando 400g', 'EMBUTIDOS', 'PAQUETE', 11.20, 15.50),
        (v_demo_company_id, 'EMB-PRE-006', '7750105005065', 'Salchicha Huachana La Preferida 250g', 'EMBUTIDOS', 'PAQUETE', 5.40, 7.80),
        (v_demo_company_id, 'EMB-OTT-007', '7750105005072', 'Cabanossi Clasico Otto Kunz 100g', 'EMBUTIDOS', 'PAQUETE', 6.20, 8.90),
        (v_demo_company_id, 'EMB-BRA-008', '7750105005089', 'Jamon del Pais Braedt Laminado 150g', 'EMBUTIDOS', 'PAQUETE', 8.90, 12.50),
        (v_demo_company_id, 'PAN-BIM-001', '7750106006017', 'Pan de Molde Blanco Bimbo Grande 750g', 'PANADERIA', 'BOLSA', 6.50, 8.90),
        (v_demo_company_id, 'PAN-BIM-002', '7750106006024', 'Pan de Molde Integral Bimbo 700g', 'PANADERIA', 'BOLSA', 7.20, 9.80),
        (v_demo_company_id, 'PAN-BIM-003', '7750106006031', 'Pan Artesano Original Bimbo 550g', 'PANADERIA', 'BOLSA', 7.80, 10.50),
        (v_demo_company_id, 'PAN-BIM-004', '7750106006048', 'Pan Hamburguesa con Ajonjoli Bimbo 4 unid', 'PANADERIA', 'BOLSA', 4.10, 5.80),
        (v_demo_company_id, 'PAN-BIM-005', '7750106006055', 'Pan Hot Dog Bimbo 6 unid', 'PANADERIA', 'BOLSA', 4.20, 5.90),
        (v_demo_company_id, 'PAN-BIM-006', '7750106006062', 'Queque Bimbolete Vainilla Bimbo 6 unid', 'PANADERIA', 'PAQUETE', 2.80, 4.00),
        (v_demo_company_id, 'PAN-BIM-007', '7750106006079', 'Tostadas Clasicas Bimbo 180g', 'PANADERIA', 'PAQUETE', 3.90, 5.50),
        (v_demo_company_id, 'PAN-CHA-008', '7750106006086', 'Bizcocho Chancay Tradicional 4 unid', 'PANADERIA', 'BOLSA', 3.00, 4.50),
        (v_demo_company_id, 'ABA-COS-001', '7750107007014', 'Arroz Superior Costeno Bolsa 1kg', 'ABARROTES', 'BOLSA', 4.10, 5.30),
        (v_demo_company_id, 'ABA-COS-002', '7750107007021', 'Arroz Extra Costeno Bolsa 1kg', 'ABARROTES', 'BOLSA', 4.60, 5.90),
        (v_demo_company_id, 'ABA-PRI-003', '7750107007038', 'Aceite Vegetal Primor Clasico Botella 900ml', 'ABARROTES', 'BOTELLA', 8.20, 10.90),
        (v_demo_company_id, 'ABA-OLI-004', '7750107007045', 'Aceite de Oliva Extra Virgen Huerto Alamein 500ml', 'ABARROTES', 'BOTELLA', 22.00, 29.90),
        (v_demo_company_id, 'ABA-VIT-005', '7750107007052', 'Fideos Spaghetti Don Vittorio 450g', 'ABARROTES', 'PAQUETE', 2.30, 3.20),
        (v_demo_company_id, 'ABA-LAV-006', '7750107007069', 'Fideos Tornillo Lavaggi 450g', 'ABARROTES', 'PAQUETE', 2.00, 2.80),
        (v_demo_company_id, 'ABA-FLO-007', '7750107007076', 'Trozos de Atun en Aceite Vegetal Florida 140g', 'ABARROTES', 'LATA', 4.80, 6.50),
        (v_demo_company_id, 'ABA-GLO-008', '7750107007083', 'Filete de Atun en Aceite de Oliva Gloria 140g', 'ABARROTES', 'LATA', 5.60, 7.80),
        (v_demo_company_id, 'ABA-ALA-009', '7750107007090', 'Mayonesa Alacena Doypack 475g', 'ABARROTES', 'DOYPACK', 6.40, 8.90),
        (v_demo_company_id, 'ABA-ALA-010', '7750107007106', 'Ketchup Alacena Doypack 380g', 'ABARROTES', 'DOYPACK', 4.20, 5.90),
        (v_demo_company_id, 'ABA-TAR-011', '7750107007113', 'Crema de Aji Tari Alacena Doypack 400g', 'ABARROTES', 'DOYPACK', 6.80, 9.50),
        (v_demo_company_id, 'ABA-NES-012', '7750107007120', 'Cafe Instantaneo Nescafe Tradicion Frasco 100g', 'ABARROTES', 'FRASCO', 12.50, 16.90),
        (v_demo_company_id, 'LIM-SUA-001', '7750108008011', 'Papel Higienico Suave Rendidor Doble Hoja 4 rollos', 'LIMPIEZA', 'PACK', 5.50, 7.50),
        (v_demo_company_id, 'LIM-ELI-002', '7750108008028', 'Papel Higienico Elite Duplex 4 rollos', 'LIMPIEZA', 'PACK', 6.20, 8.50),
        (v_demo_company_id, 'LIM-ELI-003', '7750108008035', 'Papel Toalla Elite Ultra 2 rollos', 'LIMPIEZA', 'PACK', 4.80, 6.80),
        (v_demo_company_id, 'LIM-DOV-004', '7750108008042', 'Jabon de Tocador Dove Original Barra 90g', 'LIMPIEZA', 'UNIDAD', 3.20, 4.50),
        (v_demo_company_id, 'LIM-PRO-005', '7750108008059', 'Jabon Antibacterial Protex Aloe Barra 110g', 'LIMPIEZA', 'UNIDAD', 2.80, 4.00),
        (v_demo_company_id, 'LIM-SED-006', '7750108008066', 'Shampoo Sedal Restauracion Instantanea 340ml', 'LIMPIEZA', 'BOTELLA', 10.50, 14.90),
        (v_demo_company_id, 'LIM-COL-007', '7750108008073', 'Crema Dental Colgate Triple Accion Tubo 75ml', 'LIMPIEZA', 'TUBO', 3.50, 5.00),
        (v_demo_company_id, 'LIM-CLO-008', '7750108008080', 'Lejia Tradicional Clorox Botella 1L', 'LIMPIEZA', 'BOTELLA', 3.20, 4.50),
        (v_demo_company_id, 'LIM-SAP-009', '7750108008097', 'Lavavajillas en Crema Sapolio Limon Pote 450g', 'LIMPIEZA', 'POTE', 3.60, 5.00),
        (v_demo_company_id, 'LIM-POE-010', '7750108008103', 'Limpiador Liquido Poett Lavanda Botella 900ml', 'LIMPIEZA', 'BOTELLA', 4.20, 5.90),
        (v_demo_company_id, 'LIM-ACE-011', '7750108008110', 'Detergente en Polvo Ace Floral Bolsa 800g', 'LIMPIEZA', 'BOLSA', 6.80, 9.20),
        (v_demo_company_id, 'LIM-AYU-012', '7750108008127', 'Lavavajillas Liquido Ayudin Limon Botella 500ml', 'LIMPIEZA', 'BOTELLA', 5.10, 7.20)
    ON CONFLICT (company_id, sku) DO UPDATE SET
        barcode = EXCLUDED.barcode,
        name = EXCLUDED.name,
        category = EXCLUDED.category,
        unit_of_measure = EXCLUDED.unit_of_measure,
        unit_cost = EXCLUDED.unit_cost,
        retail_price = EXCLUDED.retail_price;

    -- 7. Lotes de inventario (88 lotes con fechas relativas)
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-GLO-2610A',
        CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '240 days', 72, 72, 3.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-GLO-2610B',
        CURRENT_DATE - INTERVAL '50 days', CURRENT_DATE + INTERVAL '250 days', 48, 48, 3.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-GLO-2609U',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '140 days', 36, 36, 4.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-LAI-2609S',
        CURRENT_DATE - INTERVAL '35 days', CURRENT_DATE + INTERVAL '145 days', 24, 24, 4.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-LAI-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-YOG-2610F',
        CURRENT_DATE - INTERVAL '18 days', CURRENT_DATE + INTERVAL '5 days', 18, 18, 5.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-YOG-2610V',
        CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE + INTERVAL '8 days', 16, 16, 5.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-YOG-GR-01',
        CURRENT_DATE - INTERVAL '12 days', CURRENT_DATE + INTERVAL '18 days', 20, 20, 2.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-LAI-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-MAN-2608G',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '120 days', 30, 30, 5.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-MAN-2608L',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '110 days', 24, 24, 5.90, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-LAI-009';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-QED-2610B',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '10 days', 15, 15, 7.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-BON-010';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-QFR-2610L',
        CURRENT_DATE - INTERVAL '10 days', CURRENT_DATE + INTERVAL '2 days', 12, 12, 9.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-LAI-011';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CRM-2607A',
        CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '180 days', 24, 24, 3.90, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-012';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-IK-500-26A',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '270 days', 96, 96, 2.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-INC-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-IK-ZERO-26B',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '275 days', 48, 48, 2.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-INC-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CC-500-26A',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '270 days', 96, 96, 2.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-COC-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CC-ZERO-26B',
        CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE + INTERVAL '280 days', 48, 48, 2.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-COC-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-IK-GOR-26C',
        CURRENT_DATE - INTERVAL '15 days', CURRENT_DATE + INTERVAL '300 days', 36, 36, 3.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-INC-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-SP-500-26A',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '260 days', 36, 36, 2.30, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-SPR-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-AG-SL-625',
        CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE + INTERVAL '365 days', 120, 120, 1.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-SLU-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-AG-SM-600',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '365 days', 72, 72, 1.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-SMA-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-SPO-FR-500',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '240 days', 60, 60, 2.00, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-SPO-009';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-GAT-CB-500',
        CURRENT_DATE - INTERVAL '35 days', CURRENT_DATE + INTERVAL '250 days', 48, 48, 2.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-GAT-010';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CIF-GR-500',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '210 days', 48, 48, 1.30, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-CIF-011';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-FRU-DZ-1L',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '180 days', 30, 30, 3.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'BEB-FRU-012';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CUS-DOR-26',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '270 days', 72, 72, 4.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-CUS-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CUS-NEG-26',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '275 days', 48, 48, 4.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-CUS-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PIL-630-26A',
        CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE + INTERVAL '280 days', 84, 84, 5.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-PIL-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PIL-SIX-26B',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '290 days', 24, 24, 19.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-PIL-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CRI-650-26',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '260 days', 60, 60, 4.90, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-CRI-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-COR-355-MEX',
        CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '300 days', 48, 48, 5.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-COR-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-STE-330-BEL',
        CURRENT_DATE - INTERVAL '55 days', CURRENT_DATE + INTERVAL '310 days', 36, 36, 5.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-STE-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-RON-CAR-BB',
        CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE + INTERVAL '730 days', 15, 15, 24.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-CAR-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PIS-4G-QUE',
        CURRENT_DATE - INTERVAL '120 days', CURRENT_DATE + INTERVAL '730 days', 12, 12, 32.00, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-GAL-009';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-JW-RED-750',
        CURRENT_DATE - INTERVAL '180 days', CURRENT_DATE + INTERVAL '1000 days', 10, 10, 45.00, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIC-JOH-010';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-LAY-CLA-26',
        CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE + INTERVAL '75 days', 40, 40, 4.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-LAY-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-LAY-OND-26',
        CURRENT_DATE - INTERVAL '22 days', CURRENT_DATE + INTERVAL '70 days', 30, 30, 4.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-LAY-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-DOR-QSO-26',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '80 days', 35, 35, 4.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-DOR-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CHZ-FIE-26',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '65 days', 30, 30, 3.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-CHI-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PIQ-SNX-26',
        CURRENT_DATE - INTERVAL '15 days', CURRENT_DATE + INTERVAL '85 days', 25, 25, 5.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-PIQ-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CHF-KAR-26',
        CURRENT_DATE - INTERVAL '18 days', CURRENT_DATE + INTERVAL '55 days', 20, 20, 3.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-CHI-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-SUB-CLA-26',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '200 days', 100, 100, 1.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-SUB-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PRI-CRE-26',
        CURRENT_DATE - INTERVAL '35 days', CURRENT_DATE + INTERVAL '190 days', 80, 80, 1.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-PRI-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-TRI-DON-26',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '180 days', 80, 80, 1.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-TRI-009';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-MOR-PAQ-26',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '150 days', 48, 48, 2.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-MOR-010';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CAS-MEN-26',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '160 days', 48, 48, 2.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-CAS-011';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-DNP-PAQ-26',
        CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE + INTERVAL '140 days', 48, 48, 2.90, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-PEP-012';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-SOD-FIE-26',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '210 days', 60, 60, 2.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-SOD-013';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-GOM-OSI-26',
        CURRENT_DATE - INTERVAL '50 days', CURRENT_DATE + INTERVAL '240 days', 40, 40, 2.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'SNK-GOM-014';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-HD-POL-2610',
        CURRENT_DATE - INTERVAL '12 days', CURRENT_DATE + INTERVAL '7 days', 30, 30, 6.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-SFE-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-JAM-SFE-26',
        CURRENT_DATE - INTERVAL '14 days', CURRENT_DATE + INTERVAL '9 days', 25, 25, 4.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-SFE-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-JAM-BRA-26',
        CURRENT_DATE - INTERVAL '15 days', CURRENT_DATE + INTERVAL '11 days', 20, 20, 6.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-BRA-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-TOC-OTT-26',
        CURRENT_DATE - INTERVAL '16 days', CURRENT_DATE + INTERVAL '13 days', 18, 18, 9.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-OTT-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CHO-PAR-26',
        CURRENT_DATE - INTERVAL '15 days', CURRENT_DATE + INTERVAL '22 days', 20, 20, 11.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-SFE-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-SAL-HUA-26',
        CURRENT_DATE - INTERVAL '10 days', CURRENT_DATE + INTERVAL '4 days', 15, 15, 5.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-PRE-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CAB-OTT-26',
        CURRENT_DATE - INTERVAL '25 days', CURRENT_DATE + INTERVAL '45 days', 25, 25, 6.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-OTT-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-JPA-BRA-26',
        CURRENT_DATE - INTERVAL '11 days', CURRENT_DATE + INTERVAL '7 days', 16, 16, 8.90, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-BRA-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PAN-BLA-2610',
        CURRENT_DATE - INTERVAL '8 days', CURRENT_DATE + INTERVAL '2 days', 25, 25, 6.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PAN-INT-2610',
        CURRENT_DATE - INTERVAL '7 days', CURRENT_DATE + INTERVAL '3 days', 20, 20, 7.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PAN-ART-2610',
        CURRENT_DATE - INTERVAL '6 days', CURRENT_DATE + INTERVAL '4 days', 18, 18, 7.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PAN-HAM-2610',
        CURRENT_DATE - INTERVAL '9 days', CURRENT_DATE + INTERVAL '5 days', 24, 24, 4.10, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PAN-HD-2610',
        CURRENT_DATE - INTERVAL '8 days', CURRENT_DATE + INTERVAL '8 days', 24, 24, 4.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-BIM-VAI-26',
        CURRENT_DATE - INTERVAL '15 days', CURRENT_DATE + INTERVAL '14 days', 30, 30, 2.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-TOS-CLA-26',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '90 days', 30, 30, 3.90, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-BIM-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-BIZ-CHA-2610',
        CURRENT_DATE - INTERVAL '4 days', CURRENT_DATE + INTERVAL '1 days', 20, 20, 3.00, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'PAN-CHA-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-ARR-SUP-26',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '365 days', 100, 100, 4.10, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-COS-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-ARR-EXT-26',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '365 days', 80, 80, 4.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-COS-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-ACE-PRI-900',
        CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '365 days', 60, 60, 8.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-PRI-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-ACE-OLI-500',
        CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE + INTERVAL '500 days', 20, 20, 22.00, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-OLI-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-FID-SPA-450',
        CURRENT_DATE - INTERVAL '50 days', CURRENT_DATE + INTERVAL '400 days', 80, 80, 2.30, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-VIT-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-FID-TOR-450',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '400 days', 60, 60, 2.00, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-LAV-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-ATU-FLO-140',
        CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE + INTERVAL '730 days', 96, 96, 4.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-FLO-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-ATU-GLO-140',
        CURRENT_DATE - INTERVAL '85 days', CURRENT_DATE + INTERVAL '730 days', 60, 60, 5.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-GLO-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-MAY-ALA-475',
        CURRENT_DATE - INTERVAL '35 days', CURRENT_DATE + INTERVAL '180 days', 48, 48, 6.40, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-ALA-009';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-KET-ALA-380',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '200 days', 36, 36, 4.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-ALA-010';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-TAR-ALA-400',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '160 days', 40, 40, 6.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-TAR-011';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-CAF-NES-100',
        CURRENT_DATE - INTERVAL '80 days', CURRENT_DATE + INTERVAL '540 days', 30, 30, 12.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'ABA-NES-012';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PH-SUA-4R',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '730 days', 48, 48, 5.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-SUA-001';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PH-ELI-4R',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '730 days', 48, 48, 6.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-ELI-002';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-PT-ELI-2R',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '730 days', 36, 36, 4.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-ELI-003';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-JAB-DOV-90',
        CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '730 days', 60, 60, 3.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-DOV-004';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-JAB-PRO-110',
        CURRENT_DATE - INTERVAL '55 days', CURRENT_DATE + INTERVAL '730 days', 60, 60, 2.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-PRO-005';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-SHA-SED-340',
        CURRENT_DATE - INTERVAL '70 days', CURRENT_DATE + INTERVAL '730 days', 24, 24, 10.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-SED-006';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-DEN-COL-75',
        CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '730 days', 48, 48, 3.50, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-COL-007';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-LEJ-CLO-1L',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '365 days', 48, 48, 3.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-CLO-008';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-LAV-SAP-450',
        CURRENT_DATE - INTERVAL '45 days', CURRENT_DATE + INTERVAL '540 days', 36, 36, 3.60, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-SAP-009';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-POE-LAV-900',
        CURRENT_DATE - INTERVAL '35 days', CURRENT_DATE + INTERVAL '540 days', 36, 36, 4.20, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-POE-010';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-DET-ACE-800',
        CURRENT_DATE - INTERVAL '40 days', CURRENT_DATE + INTERVAL '540 days', 30, 30, 6.80, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-ACE-011';
    INSERT INTO inventory_batches (
        store_id, product_id, upload_id, batch_code,
        manufacturing_date, expiration_date, initial_qty, current_qty, unit_cost, batch_status
    )
    SELECT
        v_demo_store_id, p.product_id, v_upload_id, 'L-AYU-LIQ-500',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '540 days', 30, 30, 5.10, 'ACTIVE'
    FROM products p
    WHERE p.company_id = v_demo_company_id AND p.sku = 'LIM-AYU-012';
    -- 8. Discrepancia en guia de remision para recepcion con IA
    INSERT INTO goods_receipt_items (receipt_id, product_id, invoiced_qty, received_qty, unit_cost)
    SELECT v_receipt_id, p.product_id, 74, 72, 3.40
    FROM products p WHERE p.company_id = v_demo_company_id AND p.sku = 'LAC-GLO-001'
    RETURNING item_id INTO v_item_id;

    UPDATE inventory_batches
    SET receipt_item_id = v_item_id
    WHERE store_id = v_demo_store_id AND batch_code = 'L-GLO-2610A';

    -- Guia de San Fernando para canjes
    INSERT INTO goods_receipt_headers (store_id, supplier_id, grn_number, issue_date)
    VALUES (v_demo_store_id, v_sup_sanfernando, 'GRN-2026-DEMO-002', CURRENT_DATE - INTERVAL '5 days')
    RETURNING receipt_id INTO v_receipt_id;

    INSERT INTO goods_receipt_items (receipt_id, product_id, invoiced_qty, received_qty, unit_cost)
    SELECT v_receipt_id, p.product_id, 30, 30, 6.20
    FROM products p WHERE p.company_id = v_demo_company_id AND p.sku = 'EMB-SFE-001'
    RETURNING item_id INTO v_item_id;

    UPDATE inventory_batches
    SET receipt_item_id = v_item_id
    WHERE store_id = v_demo_store_id AND batch_code = 'L-HD-POL-2610';

END;
$$;
