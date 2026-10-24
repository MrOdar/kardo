import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Utilidad de desarrollo para probar la ingesta contra una base real.
 *
 * <p>No forma parte del artefacto desplegable: vive fuera de {@code src/} y se
 * ejecuta en modo single-file con los jars del repositorio local de Maven.
 * Sustituye a {@code psql}, que no esta instalado en la maquina de desarrollo.
 *
 * <p>Las credenciales se leen SIEMPRE de las variables de entorno
 * {@code DATABASE_URL}, {@code DATABASE_USERNAME} y {@code DATABASE_PASSWORD},
 * nunca de argumentos, para que no queden en el historial del shell.
 *
 * <pre>
 *   java -cp "&lt;jars&gt;" tools/KardoDevCli.java sql sql/01_schema_ddl_3nf.sql
 *   java -cp "&lt;jars&gt;" tools/KardoDevCli.java query "SELECT count(*) FROM products"
 *   java -cp "&lt;jars&gt;" tools/KardoDevCli.java excel target/inventario-demo.xlsx
 *   java -cp "&lt;jars&gt;" tools/KardoDevCli.java token 1
 * </pre>
 */
public class KardoDevCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: sql <archivo> | exec <sentencia> | query <sql> | excel <salida> | token <storeId>");
            System.exit(2);
        }
        switch (args[0]) {
            case "sql" -> execute(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
            case "exec" -> execute(args[1]);
            // query <sql> [setupSql]: el setup corre antes, en la MISMA conexion,
            // para poder reproducir ajustes de sesion como set_config.
            case "query" -> query(args[1], args.length > 2 ? args[2] : null);
            case "excel" -> writeSampleWorkbook(args[1]);
            case "excel-dirty" -> writeDirtyWorkbook(args[1], Integer.parseInt(args[2]));
            case "token" -> System.out.println(issueToken(Long.parseLong(args[1])));
            // Genera el hash BCrypt que se siembra en sql/03_users_and_auth.sql.
            case "bcrypt" -> System.out.println(
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(args[1]));
            default -> {
                System.err.println("Subcomando desconocido: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static Connection open() throws Exception {
        String url = requireEnv("DATABASE_URL");
        return DriverManager.getConnection(url, requireEnv("DATABASE_USERNAME"), requireEnv("DATABASE_PASSWORD"));
    }

    /** Propiedad de Spring equivalente a cada variable de entorno soportada. */
    private static String springKeyOf(String envName) {
        return switch (envName) {
            case "DATABASE_URL" -> "spring.datasource.url";
            case "DATABASE_USERNAME" -> "spring.datasource.username";
            case "DATABASE_PASSWORD" -> "spring.datasource.password";
            case "JWT_SECRET" -> "kardo.security.jwt.secret";
            default -> null;
        };
    }

    /**
     * Resuelve un secreto desde el entorno y, si no esta, desde
     * {@code src/main/resources/application-local.properties}, que esta ignorado
     * por Git. Asi las credenciales no viajan en argumentos ni en el historial.
     */
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value;
        }

        String springKey = springKeyOf(name);
        Path localProperties = Path.of("config", "application-local.properties");
        if (springKey != null && Files.isReadable(localProperties)) {
            try {
                var properties = new java.util.Properties();
                try (var reader = Files.newBufferedReader(localProperties, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                String fromFile = properties.getProperty(springKey);
                if (fromFile != null && !fromFile.isBlank()) {
                    return fromFile.trim();
                }
            } catch (Exception ex) {
                throw new IllegalStateException("No se pudo leer " + localProperties + ": " + ex.getMessage(), ex);
            }
        }

        throw new IllegalStateException("Falta " + name + " (o " + springKey + " en " + localProperties + ")");
    }

    /** PostgreSQL acepta varias sentencias en un solo execute, incluidos bloques $$. */
    private static void execute(String sql) throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("OK");
        }
    }

    private static void query(String sql, String setupSql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {

            if (setupSql != null && !setupSql.isBlank()) {
                statement.execute(setupSql);
            }

            ResultSet rs = statement.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                header.append(meta.getColumnLabel(i)).append(i < meta.getColumnCount() ? " | " : "");
            }
            System.out.println(header);
            System.out.println("-".repeat(header.length()));

            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    line.append(rs.getString(i)).append(i < meta.getColumnCount() ? " | " : "");
                }
                System.out.println(line);
            }
        }
    }

    private static String issueToken(long storeId) {
        String secret = requireEnv("JWT_SECRET");
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("kardo")
                .subject("dev-operator")
                .claim("roles", List.of("ROLE_OPERATOR"))
                .claim("storeId", storeId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /**
     * Archivo con todas las filas invalidas, para comprobar que el Circuit
     * Breaker de calidad corta la ingesta con HTTP 422.
     */
    private static void writeDirtyWorkbook(String output, int rowCount) throws Exception {
        List<String> headers = List.of("Codigo", "EAN", "Descripcion", "Familia", "UM",
                "Precio Compra", "Precio", "Nro Lote", "F. Fabricacion", "Vence", "Stock");

        try (OutputStream out = new FileOutputStream(output)) {
            Workbook workbook = new Workbook(out, "kardo-dev", "1.0");
            Worksheet sheet = workbook.newWorksheet("Inventario");
            for (int c = 0; c < headers.size(); c++) {
                sheet.value(0, c, headers.get(c));
            }
            for (int r = 1; r <= rowCount; r++) {
                // Sin SKU y con cantidad negativa: cada fila es corrupta
                sheet.value(r, 2, "Fila corrupta " + r);
                sheet.value(r, 3, "VARIOS");
                sheet.value(r, 5, new BigDecimal("1.00"));
                sheet.value(r, 6, new BigDecimal("2.00"));
                sheet.value(r, 9, LocalDate.now().plusMonths(6).toString());
                sheet.value(r, 10, -5);
            }
            workbook.finish();
        }
        System.out.println("Excel corrupto escrito en " + output + " (" + rowCount + " filas invalidas)");
    }

    /**
     * Excel de ejemplo con encabezados en alias reales y una mezcla deliberada de
     * filas limpias y sucias, para ver la cuarentena en accion.
     */
    private static void writeSampleWorkbook(String output) throws Exception {
        LocalDate today = LocalDate.now();

        List<List<Object>> rows = List.of(
                List.of("SKU-001", "7751234567890", "Cerveza Pilsen Callao Botella 650ml", "BEBIDAS", "BOTELLA",
                        new BigDecimal("4.50"), new BigDecimal("7.90"), "L-2026-A",
                        today.minusMonths(2).toString(), today.plusMonths(10).toString(), 24),
                List.of("SKU-002", "7751111111111", "Leche Gloria Evaporada 400g", "LACTEOS", "LATA",
                        new BigDecimal("3.20"), new BigDecimal("4.80"), "L-2026-B",
                        today.minusMonths(1).toString(), today.plusMonths(6).toString(), 48),
                List.of("SKU-003", "7752222222222", "Yogurt Gloria Fresa 1L", "LACTEOS", "BOTELLA",
                        new BigDecimal("6.10"), new BigDecimal("8.50"), "L-YOG-01",
                        today.minusDays(20).toString(), today.plusDays(2).toString(), 12),
                // Fila con costo negativo -> INVALID_COST
                List.of("SKU-004", "", "Pan Frances Bolsa", "PANADERIA", "BOLSA",
                        new BigDecimal("-1.00"), new BigDecimal("2.50"), "L-PAN-01",
                        "", today.plusDays(3).toString(), 30),
                // Fila con cantidad cero -> INVALID_QUANTITY
                List.of("SKU-005", "", "Galleta Soda Field", "ABARROTES", "PAQUETE",
                        new BigDecimal("1.20"), new BigDecimal("1.80"), "L-GAL-01",
                        "", today.plusMonths(8).toString(), 0),
                // Fila sin SKU -> MISSING_SKU
                List.of("", "", "Producto sin codigo", "VARIOS", "UNIDAD",
                        new BigDecimal("5.00"), new BigDecimal("9.00"), "L-X",
                        "", today.plusMonths(4).toString(), 5),
                // Fecha ilegible -> INVALID_DATE_FORMAT
                List.of("SKU-006", "", "Atun Florida Lata", "ABARROTES", "LATA",
                        new BigDecimal("4.00"), new BigDecimal("6.50"), "L-ATU-01",
                        "", "el proximo mes", 20),
                // Vence antes de fabricarse -> EXPIRATION_BEFORE_MANUFACTURING
                List.of("SKU-007", "", "Queso Fresco 500g", "LACTEOS", "UNIDAD",
                        new BigDecimal("12.00"), new BigDecimal("16.00"), "L-QUE-01",
                        today.plusMonths(2).toString(), today.plusDays(1).toString(), 8),
                // SKU repetido: el upsert debe actualizar el catalogo y crear un segundo lote
                List.of("SKU-001", "7751234567890", "Cerveza Pilsen Callao Botella 650ml", "BEBIDAS", "BOTELLA",
                        new BigDecimal("4.60"), new BigDecimal("8.10"), "L-2026-C",
                        today.minusDays(10).toString(), today.plusMonths(12).toString(), 36));

        List<String> headers = List.of("Codigo", "EAN", "Descripcion", "Familia", "UM",
                "Precio Compra", "Precio", "Nro Lote", "F. Fabricacion", "Vence", "Stock");

        try (OutputStream out = new FileOutputStream(output)) {
            Workbook workbook = new Workbook(out, "kardo-dev", "1.0");
            Worksheet sheet = workbook.newWorksheet("Inventario");
            for (int c = 0; c < headers.size(); c++) {
                sheet.value(0, c, headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                List<Object> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    Object value = row.get(c);
                    if (value instanceof Number number) {
                        sheet.value(r + 1, c, number);
                    } else {
                        String text = String.valueOf(value);
                        if (!text.isEmpty()) {
                            sheet.value(r + 1, c, text);
                        }
                    }
                }
            }
            workbook.finish();
        }
        System.out.println("Excel escrito en " + output + " (" + rows.size() + " filas de datos)");
    }
}
