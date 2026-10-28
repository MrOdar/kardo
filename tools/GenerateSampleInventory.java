package tools;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

public class GenerateSampleInventory {

    private record Item(
            String sku,
            String barcode,
            String name,
            String category,
            String uom,
            String cost,
            String price,
            String batch,
            int mfgDaysAgo,
            int expDaysFromNow,
            int qty
    ) {}

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.now();

        List<Item> items = new ArrayList<>();

        // 1. LÁCTEOS Y DERIVADOS (12)
        items.add(new Item("LAC-GLO-001", "7750101001012", "Leche Evaporada Gloria Azul 400g", "LACTEOS", "LATA", "3.40", "4.50", "L-GLO-2610A", 60, 240, 72));
        items.add(new Item("LAC-GLO-002", "7750101001029", "Leche Evaporada Gloria Light Roja 400g", "LACTEOS", "LATA", "3.60", "4.70", "L-GLO-2610B", 50, 250, 48));
        items.add(new Item("LAC-GLO-003", "7750101001036", "Leche UHT Gloria Entera Caja 1L", "LACTEOS", "CAJA", "4.20", "5.60", "L-GLO-2609U", 40, 140, 36));
        items.add(new Item("LAC-LAI-004", "7750101001043", "Leche UHT Laive Sin Lactosa Caja 1L", "LACTEOS", "CAJA", "4.40", "5.90", "L-LAI-2609S", 35, 145, 24));
        items.add(new Item("LAC-GLO-005", "7750101001050", "Yogurt Gloria Batido Fresa Botella 1kg", "LACTEOS", "BOTELLA", "5.80", "7.90", "L-YOG-2610F", 18, 6, 18)); // FEFO CRÍTICO (6 días)
        items.add(new Item("LAC-GLO-006", "7750101001067", "Yogurt Gloria Batido Vainilla Botella 1kg", "LACTEOS", "BOTELLA", "5.80", "7.90", "L-YOG-2610V", 20, 8, 16)); // FEFO CRÍTICO (8 días)
        items.add(new Item("LAC-LAI-007", "7750101001074", "Yogurt Griego Laive Natural 120g", "LACTEOS", "VASO", "2.80", "3.90", "L-YOG-GR-01", 12, 18, 20)); // FEFO MEDIO (18 días)
        items.add(new Item("LAC-GLO-008", "7750101001081", "Mantequilla Gloria con Sal 200g", "LACTEOS", "PAQUETE", "5.20", "7.20", "L-MAN-2608G", 45, 120, 30));
        items.add(new Item("LAC-LAI-009", "7750101001098", "Mantequilla Laive Pote 225g", "LACTEOS", "POTE", "5.90", "8.20", "L-MAN-2608L", 40, 110, 24));
        items.add(new Item("LAC-BON-010", "7750101001104", "Queso Edam Bonle Rebanado 200g", "LACTEOS", "PAQUETE", "7.80", "10.50", "L-QED-2610B", 25, 10, 15)); // FEFO CRÍTICO (10 días)
        items.add(new Item("LAC-LAI-011", "7750101001111", "Queso Fresco Pasteurizado Laive 400g", "LACTEOS", "EMPAQUE", "9.20", "12.80", "L-QFR-2610L", 10, 4, 12)); // FEFO CRÍTICO (4 días)
        items.add(new Item("LAC-GLO-012", "7750101001128", "Crema de Leche Gloria UHT 200ml", "LACTEOS", "CAJA", "3.90", "5.20", "L-CRM-2607A", 60, 180, 24));

        // 2. BEBIDAS Y GASEOSAS (12)
        items.add(new Item("BEB-INC-001", "7750102002019", "Gaseosa Inca Kola Sabor Original 500ml", "BEBIDAS", "BOTELLA", "2.40", "3.50", "L-IK-500-26A", 30, 270, 96));
        items.add(new Item("BEB-INC-002", "7750102002026", "Gaseosa Inca Kola Sin Azucar 500ml", "BEBIDAS", "BOTELLA", "2.40", "3.50", "L-IK-ZERO-26B", 25, 275, 48));
        items.add(new Item("BEB-COC-003", "7750102002033", "Gaseosa Coca Cola Sabor Original 500ml", "BEBIDAS", "BOTELLA", "2.40", "3.50", "L-CC-500-26A", 30, 270, 96));
        items.add(new Item("BEB-COC-004", "7750102002040", "Gaseosa Coca Cola Sin Azucar 500ml", "BEBIDAS", "BOTELLA", "2.40", "3.50", "L-CC-ZERO-26B", 20, 280, 48));
        items.add(new Item("BEB-INC-005", "7750102002057", "Gaseosa Inca Kola Gordita Vidrio 750ml", "BEBIDAS", "BOTELLA", "3.20", "4.50", "L-IK-GOR-26C", 15, 300, 36));
        items.add(new Item("BEB-SPR-006", "7750102002064", "Gaseosa Sprite Lima-Limon 500ml", "BEBIDAS", "BOTELLA", "2.30", "3.20", "L-SP-500-26A", 40, 260, 36));
        items.add(new Item("BEB-SLU-007", "7750102002071", "Agua Mineral San Luis Sin Gas 625ml", "BEBIDAS", "BOTELLA", "1.20", "2.00", "L-AG-SL-625", 20, 365, 120));
        items.add(new Item("BEB-SMA-008", "7750102002088", "Agua Mineral San Mateo con Gas 600ml", "BEBIDAS", "BOTELLA", "1.50", "2.50", "L-AG-SM-600", 30, 365, 72));
        items.add(new Item("BEB-SPO-009", "7750102002095", "Bebida Rehidratante Sporade Frutos Rojos 500ml", "BEBIDAS", "BOTELLA", "2.00", "3.00", "L-SPO-FR-500", 45, 240, 60));
        items.add(new Item("BEB-GAT-010", "7750102002101", "Bebida Rehidratante Gatorade Cool Blue 500ml", "BEBIDAS", "BOTELLA", "2.80", "4.00", "L-GAT-CB-500", 35, 250, 48));
        items.add(new Item("BEB-CIF-011", "7750102002118", "Bebida Citrica Cifrut Granadilla 500ml", "BEBIDAS", "BOTELLA", "1.30", "2.00", "L-CIF-GR-500", 25, 210, 48));
        items.add(new Item("BEB-FRU-012", "7750102002125", "Nectar Frugos del Valle Durazno 1L", "BEBIDAS", "CAJA", "3.60", "5.00", "L-FRU-DZ-1L", 30, 180, 30));

        // 3. CERVEZAS Y LICORES (10)
        items.add(new Item("LIC-CUS-001", "7750103003016", "Cerveza Cusquena Dorada Botella 330ml", "LICORES", "BOTELLA", "4.20", "6.50", "L-CUS-DOR-26", 45, 270, 72));
        items.add(new Item("LIC-CUS-002", "7750103003023", "Cerveza Cusquena Negra Botella 330ml", "LICORES", "BOTELLA", "4.40", "6.80", "L-CUS-NEG-26", 40, 275, 48));
        items.add(new Item("LIC-PIL-003", "7750103003030", "Cerveza Pilsen Callao Botella 630ml", "LICORES", "BOTELLA", "5.20", "7.80", "L-PIL-630-26A", 20, 280, 84));
        items.add(new Item("LIC-PIL-004", "7750103003047", "Cerveza Pilsen Callao Six Pack Lata 355ml", "LICORES", "PACK", "19.50", "28.00", "L-PIL-SIX-26B", 25, 290, 24));
        items.add(new Item("LIC-CRI-005", "7750103003054", "Cerveza Cristal Botella 650ml", "LICORES", "BOTELLA", "4.90", "7.20", "L-CRI-650-26", 30, 260, 60));
        items.add(new Item("LIC-COR-006", "7750103003061", "Cerveza Corona Extra Botella 355ml", "LICORES", "BOTELLA", "5.50", "8.50", "L-COR-355-MEX", 60, 300, 48));
        items.add(new Item("LIC-STE-007", "7750103003078", "Cerveza Stella Artois Botella 330ml", "LICORES", "BOTELLA", "5.80", "8.90", "L-STE-330-BEL", 55, 310, 36));
        items.add(new Item("LIC-CAR-008", "7750103003085", "Ron Cartavio Black Barrel 750ml", "LICORES", "BOTELLA", "24.50", "34.90", "L-RON-CAR-BB", 90, 730, 15));
        items.add(new Item("LIC-GAL-009", "7750103003092", "Pisco Cuatro Gallos Quebranta 700ml", "LICORES", "BOTELLA", "32.00", "46.90", "L-PIS-4G-QUE", 120, 730, 12));
        items.add(new Item("LIC-JOH-010", "7750103003108", "Whisky Johnnie Walker Red Label 750ml", "LICORES", "BOTELLA", "45.00", "65.00", "L-JW-RED-750", 180, 1000, 10));

        // 4. SNACKS Y GOLOSINAS (14)
        items.add(new Item("SNK-LAY-001", "7750104004013", "Papas Fritas Lays Clasicas 160g", "SNACKS", "BOLSA", "4.80", "6.90", "L-LAY-CLA-26", 20, 75, 40));
        items.add(new Item("SNK-LAY-002", "7750104004020", "Papas Fritas Lays Onduladas 150g", "SNACKS", "BOLSA", "4.80", "6.90", "L-LAY-OND-26", 22, 70, 30));
        items.add(new Item("SNK-DOR-003", "7750104004037", "Tortillas de Maiz Doritos Mega Queso 150g", "SNACKS", "BOLSA", "4.60", "6.70", "L-DOR-QSO-26", 25, 80, 35));
        items.add(new Item("SNK-CHI-004", "7750104004044", "Snack de Maiz Chizitos Fiesta 140g", "SNACKS", "BOLSA", "3.60", "5.20", "L-CHZ-FIE-26", 30, 65, 30));
        items.add(new Item("SNK-PIQ-005", "7750104004051", "Piqueo Snax Familiar 200g", "SNACKS", "BOLSA", "5.50", "7.90", "L-PIQ-SNX-26", 15, 85, 25));
        items.add(new Item("SNK-CHI-006", "7750104004068", "Chifle Piurano Salado Karinto 120g", "SNACKS", "BOLSA", "3.20", "4.80", "L-CHF-KAR-26", 18, 55, 20));
        items.add(new Item("SNK-SUB-007", "7750104004075", "Chocolate Sublime Clasico Tableta 30g", "SNACKS", "UNIDAD", "1.50", "2.20", "L-SUB-CLA-26", 40, 200, 100));
        items.add(new Item("SNK-PRI-008", "7750104004082", "Chocolate Princesa Crema de Mani 30g", "SNACKS", "UNIDAD", "1.60", "2.40", "L-PRI-CRE-26", 35, 190, 80));
        items.add(new Item("SNK-TRI-009", "7750104004099", "Chocolate Triangulo DOnofrio 30g", "SNACKS", "UNIDAD", "1.50", "2.20", "L-TRI-DON-26", 45, 180, 80));
        items.add(new Item("SNK-MOR-010", "7750104004105", "Galletas Morochas Rellenas Paquete 6 unid", "SNACKS", "PAQUETE", "2.80", "4.00", "L-MOR-PAQ-26", 30, 150, 48));
        items.add(new Item("SNK-CAS-011", "7750104004112", "Galletas Casino Menta Paquete 6 unid", "SNACKS", "PAQUETE", "2.50", "3.80", "L-CAS-MEN-26", 25, 160, 48));
        items.add(new Item("SNK-PEP-012", "7750104004129", "Galletas Dona Pepa Field Paquete 6 unid", "SNACKS", "PAQUETE", "2.90", "4.20", "L-DNP-PAQ-26", 20, 140, 48));
        items.add(new Item("SNK-SOD-013", "7750104004136", "Galletas Soda Field Paquete 6 unid", "SNACKS", "PAQUETE", "2.40", "3.50", "L-SOD-FIE-26", 40, 210, 60));
        items.add(new Item("SNK-GOM-014", "7750104004143", "Gomitas Ambrosoli Ositos Dulces 100g", "SNACKS", "BOLSA", "2.20", "3.40", "L-GOM-OSI-26", 50, 240, 40));

        // 5. EMBUTIDOS Y FIAMBRES (8)
        items.add(new Item("EMB-SFE-001", "7750105005010", "Hot Dog de Pollo San Fernando 500g", "EMBUTIDOS", "PAQUETE", "6.20", "8.80", "L-HD-POL-2610", 12, 7, 30)); // FEFO CRÍTICO (7 días)
        items.add(new Item("EMB-SFE-002", "7750105005027", "Jamonada Especial San Fernando 250g", "EMBUTIDOS", "PAQUETE", "4.80", "6.90", "L-JAM-SFE-26", 14, 9, 25)); // FEFO CRÍTICO (9 días)
        items.add(new Item("EMB-BRA-003", "7750105005034", "Jamonada de Cerdo Braedt 200g", "EMBUTIDOS", "PAQUETE", "6.50", "9.20", "L-JAM-BRA-26", 15, 11, 20)); // FEFO CRÍTICO (11 días)
        items.add(new Item("EMB-OTT-004", "7750105005041", "Tocino Ahumado Tiras Otto Kunz 180g", "EMBUTIDOS", "PAQUETE", "9.80", "13.90", "L-TOC-OTT-26", 16, 13, 18)); // FEFO CRÍTICO (13 días)
        items.add(new Item("EMB-SFE-005", "7750105005058", "Chorizo Parrillero San Fernando 400g", "EMBUTIDOS", "PAQUETE", "11.20", "15.50", "L-CHO-PAR-26", 15, 22, 20)); // FEFO MEDIO (22 días)
        items.add(new Item("EMB-PRE-006", "7750105005065", "Salchicha Huachana La Preferida 250g", "EMBUTIDOS", "PAQUETE", "5.40", "7.80", "L-SAL-HUA-26", 10, 5, 15)); // FEFO CRÍTICO (5 días)
        items.add(new Item("EMB-OTT-007", "7750105005072", "Cabanossi Clasico Otto Kunz 100g", "EMBUTIDOS", "PAQUETE", "6.20", "8.90", "L-CAB-OTT-26", 25, 45, 25)); // FEFO MEDIO (45 días)
        items.add(new Item("EMB-BRA-008", "7750105005089", "Jamon del Pais Braedt Laminado 150g", "EMBUTIDOS", "PAQUETE", "8.90", "12.50", "L-JPA-BRA-26", 11, 7, 16)); // FEFO CRÍTICO (7 días)

        // 6. PANADERÍA Y PASTELERÍA (8)
        items.add(new Item("PAN-BIM-001", "7750106006017", "Pan de Molde Blanco Bimbo Grande 750g", "PANADERIA", "BOLSA", "6.50", "8.90", "L-PAN-BLA-2610", 8, 4, 25)); // FEFO CRÍTICO (4 días)
        items.add(new Item("PAN-BIM-002", "7750106006024", "Pan de Molde Integral Bimbo 700g", "PANADERIA", "BOLSA", "7.20", "9.80", "L-PAN-INT-2610", 7, 5, 20)); // FEFO CRÍTICO (5 días)
        items.add(new Item("PAN-BIM-003", "7750106006031", "Pan Artesano Original Bimbo 550g", "PANADERIA", "BOLSA", "7.80", "10.50", "L-PAN-ART-2610", 6, 6, 18)); // FEFO CRÍTICO (6 días)
        items.add(new Item("PAN-BIM-004", "7750106006048", "Pan Hamburguesa con Ajonjoli Bimbo 4 unid", "PANADERIA", "BOLSA", "4.10", "5.80", "L-PAN-HAM-2610", 9, 7, 24)); // FEFO CRÍTICO (7 días)
        items.add(new Item("PAN-BIM-005", "7750106006055", "Pan Hot Dog Bimbo 6 unid", "PANADERIA", "BOLSA", "4.20", "5.90", "L-PAN-HD-2610", 8, 8, 24)); // FEFO CRÍTICO (8 días)
        items.add(new Item("PAN-BIM-006", "7750106006062", "Queque Bimbolete Vainilla Bimbo 6 unid", "PANADERIA", "PAQUETE", "2.80", "4.00", "L-BIM-VAI-26", 15, 14, 30)); // FEFO CRÍTICO (14 días)
        items.add(new Item("PAN-BIM-007", "7750106006079", "Tostadas Clasicas Bimbo 180g", "PANADERIA", "PAQUETE", "3.90", "5.50", "L-TOS-CLA-26", 30, 90, 30));
        items.add(new Item("PAN-CHA-008", "7750106006086", "Bizcocho Chancay Tradicional 4 unid", "PANADERIA", "BOLSA", "3.00", "4.50", "L-BIZ-CHA-2610", 4, 3, 20)); // FEFO CRÍTICO (3 días)

        // 7. ABARROTES Y CONSERVAS (12)
        items.add(new Item("ABA-COS-001", "7750107007014", "Arroz Superior Costeno Bolsa 1kg", "ABARROTES", "BOLSA", "4.10", "5.30", "L-ARR-SUP-26", 45, 365, 100));
        items.add(new Item("ABA-COS-002", "7750107007021", "Arroz Extra Costeno Bolsa 1kg", "ABARROTES", "BOLSA", "4.60", "5.90", "L-ARR-EXT-26", 40, 365, 80));
        items.add(new Item("ABA-PRI-003", "7750107007038", "Aceite Vegetal Primor Clasico Botella 900ml", "ABARROTES", "BOTELLA", "8.20", "10.90", "L-ACE-PRI-900", 60, 365, 60));
        items.add(new Item("ABA-OLI-004", "7750107007045", "Aceite de Oliva Extra Virgen Huerto Alamein 500ml", "ABARROTES", "BOTELLA", "22.00", "29.90", "L-ACE-OLI-500", 90, 500, 20));
        items.add(new Item("ABA-VIT-005", "7750107007052", "Fideos Spaghetti Don Vittorio 450g", "ABARROTES", "PAQUETE", "2.30", "3.20", "L-FID-SPA-450", 50, 400, 80));
        items.add(new Item("ABA-LAV-006", "7750107007069", "Fideos Tornillo Lavaggi 450g", "ABARROTES", "PAQUETE", "2.00", "2.80", "L-FID-TOR-450", 45, 400, 60));
        items.add(new Item("ABA-FLO-007", "7750107007076", "Trozos de Atun en Aceite Vegetal Florida 140g", "ABARROTES", "LATA", "4.80", "6.50", "L-ATU-FLO-140", 90, 730, 96));
        items.add(new Item("ABA-GLO-008", "7750107007083", "Filete de Atun en Aceite de Oliva Gloria 140g", "ABARROTES", "LATA", "5.60", "7.80", "L-ATU-GLO-140", 85, 730, 60));
        items.add(new Item("ABA-ALA-009", "7750107007090", "Mayonesa Alacena Doypack 475g", "ABARROTES", "DOYPACK", "6.40", "8.90", "L-MAY-ALA-475", 35, 180, 48));
        items.add(new Item("ABA-ALA-010", "7750107007106", "Ketchup Alacena Doypack 380g", "ABARROTES", "DOYPACK", "4.20", "5.90", "L-KET-ALA-380", 40, 200, 36));
        items.add(new Item("ABA-TAR-011", "7750107007113", "Crema de Aji Tari Alacena Doypack 400g", "ABARROTES", "DOYPACK", "6.80", "9.50", "L-TAR-ALA-400", 30, 160, 40));
        items.add(new Item("ABA-NES-012", "7750107007120", "Cafe Instantaneo Nescafe Tradicion Frasco 100g", "ABARROTES", "FRASCO", "12.50", "16.90", "L-CAF-NES-100", 80, 540, 30));

        // 8. HIGIENE, CUIDADO PERSONAL Y LIMPIEZA (12)
        items.add(new Item("LIM-SUA-001", "7750108008011", "Papel Higienico Suave Rendidor Doble Hoja 4 rollos", "LIMPIEZA", "PACK", "5.50", "7.50", "L-PH-SUA-4R", 40, 730, 48));
        items.add(new Item("LIM-ELI-002", "7750108008028", "Papel Higienico Elite Duplex 4 rollos", "LIMPIEZA", "PACK", "6.20", "8.50", "L-PH-ELI-4R", 45, 730, 48));
        items.add(new Item("LIM-ELI-003", "7750108008035", "Papel Toalla Elite Ultra 2 rollos", "LIMPIEZA", "PACK", "4.80", "6.80", "L-PT-ELI-2R", 30, 730, 36));
        items.add(new Item("LIM-DOV-004", "7750108008042", "Jabon de Tocador Dove Original Barra 90g", "LIMPIEZA", "UNIDAD", "3.20", "4.50", "L-JAB-DOV-90", 60, 730, 60));
        items.add(new Item("LIM-PRO-005", "7750108008059", "Jabon Antibacterial Protex Aloe Barra 110g", "LIMPIEZA", "UNIDAD", "2.80", "4.00", "L-JAB-PRO-110", 55, 730, 60));
        items.add(new Item("LIM-SED-006", "7750108008066", "Shampoo Sedal Restauracion Instantanea 340ml", "LIMPIEZA", "BOTELLA", "10.50", "14.90", "L-SHA-SED-340", 70, 730, 24));
        items.add(new Item("LIM-COL-007", "7750108008073", "Crema Dental Colgate Triple Accion Tubo 75ml", "LIMPIEZA", "TUBO", "3.50", "5.00", "L-DEN-COL-75", 60, 730, 48));
        items.add(new Item("LIM-CLO-008", "7750108008080", "Lejia Tradicional Clorox Botella 1L", "LIMPIEZA", "BOTELLA", "3.20", "4.50", "L-LEJ-CLO-1L", 30, 365, 48));
        items.add(new Item("LIM-SAP-009", "7750108008097", "Lavavajillas en Crema Sapolio Limon Pote 450g", "LIMPIEZA", "POTE", "3.60", "5.00", "L-LAV-SAP-450", 45, 540, 36));
        items.add(new Item("LIM-POE-010", "7750108008103", "Limpiador Liquido Poett Lavanda Botella 900ml", "LIMPIEZA", "BOTELLA", "4.20", "5.90", "L-POE-LAV-900", 35, 540, 36));
        items.add(new Item("LIM-ACE-011", "7750108008110", "Detergente en Polvo Ace Floral Bolsa 800g", "LIMPIEZA", "BOLSA", "6.80", "9.20", "L-DET-ACE-800", 40, 540, 30));
        items.add(new Item("LIM-AYU-012", "7750108008127", "Lavavajillas Liquido Ayudin Limon Botella 500ml", "LIMPIEZA", "BOTELLA", "5.10", "7.20", "L-AYU-LIQ-500", 30, 540, 30));

        // Generar archivo limpio de 88 filas
        String cleanPath1 = "inventario_ejemplo_kardo.xlsx";
        String cleanPath2 = "../inventario_ejemplo_kardo.xlsx";
        String cleanPath3 = "src/main/resources/static/assets/inventario_ejemplo_kardo.xlsx";
        writeWorkbook(cleanPath1, items, today);
        writeWorkbook(cleanPath2, items, today);
        writeWorkbook(cleanPath3, items, today);

        System.out.println("EXITO: Generados " + items.size() + " productos en " + cleanPath1);

        // Verificación con el parser real de Kardo
        verifyWithKardoParser(cleanPath1, items.size());
        System.out.println("VERIFICACION COMPLETADA: El archivo cumple todas las reglas de negocio de Kardo.");
    }

    private static void verifyWithKardoParser(String path, int expectedInserted) throws Exception {
        com.kardo.repository.InventoryIngestRepository dummyRepo = new com.kardo.repository.InventoryIngestRepository(null, null) {
            @Override
            public long findCompanyIdByStore(long storeId) {
                return 1L;
            }

            @Override
            public int persistMicroBatch(long storeId, long companyId, List<com.kardo.dto.SpreadsheetRowDto> rows, Long uploadId) {
                return rows.size();
            }
        };

        com.kardo.ingest.ExcelStreamingIngestService ingestService =
                new com.kardo.ingest.ExcelStreamingIngestService(dummyRepo, new com.kardo.config.IngestProperties(500, 100, 200_000));

        try (java.io.InputStream in = Files.newInputStream(Path.of(path))) {
            com.kardo.dto.IngestSummaryResponseDto summary = ingestService.ingest(in, 1L, null);
            System.out.printf("Validando [%s]: Leidos=%d, Insertados=%d, Cuarentena=%d%n",
                    path, summary.totalRowsProcessed(), summary.totalInserted(), summary.totalQuarantined());

            if (summary.totalInserted() != expectedInserted) {
                throw new IllegalStateException("Error en insertados: esperado " + expectedInserted + " pero fue " + summary.totalInserted());
            }
            if (summary.totalQuarantined() != 0) {
                throw new IllegalStateException("Error en cuarentena: esperado 0 pero fue " + summary.totalQuarantined());
            }
        }
    }

    private static void writeWorkbook(String filePath, List<Item> items, LocalDate today) throws Exception {
        List<String> headers = List.of(
                "SKU", "Codigo de barras", "Producto", "Categoria", "Unidad",
                "Costo", "Precio", "Lote", "Fabricacion", "Vencimiento", "Cantidad"
        );

        try (OutputStream out = new FileOutputStream(filePath)) {
            Workbook workbook = new Workbook(out, "Kardo", "1.0");
            Worksheet sheet = workbook.newWorksheet("Inventario");

            // Cabecera
            for (int col = 0; col < headers.size(); col++) {
                sheet.value(0, col, headers.get(col));
            }
            sheet.range(0, 0, 0, headers.size() - 1).style().bold().set();

            int rowIdx = 1;
            for (Item it : items) {
                LocalDate mfg = today.minusDays(it.mfgDaysAgo());
                LocalDate exp = today.plusDays(it.expDaysFromNow());

                sheet.value(rowIdx, 0, it.sku());
                sheet.value(rowIdx, 1, it.barcode());
                sheet.value(rowIdx, 2, it.name());
                sheet.value(rowIdx, 3, it.category());
                sheet.value(rowIdx, 4, it.uom());
                sheet.value(rowIdx, 5, new BigDecimal(it.cost()));
                sheet.value(rowIdx, 6, new BigDecimal(it.price()));
                sheet.value(rowIdx, 7, it.batch());
                sheet.value(rowIdx, 8, mfg.toString());
                sheet.value(rowIdx, 9, exp.toString());
                sheet.value(rowIdx, 10, it.qty());

                rowIdx++;
            }

            workbook.finish();
        }
    }
}
