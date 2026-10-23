# 📦 Kardo

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange?style=flat&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.4-6DB33F?style=flat&logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL 15](https://img.shields.io/badge/Database-PostgreSQL_15_(3NF)-336791?style=flat&logo=postgresql)](https://www.postgresql.org/)
[![Gemini 2.0 Flash](https://img.shields.io/badge/AI-Gemini_2.0_Flash_Vision-4285F4?style=flat&logo=google)](https://ai.google.dev/)
[![fastexcel](https://img.shields.io/badge/Parser-fastexcel_(Streaming)-blue)](https://github.com/dhatim/fastexcel)

Plataforma SaaS B2B de migración elástica de inventarios desde Excel en streaming, recepción de mercadería mediante Visión Multimodal (Gemini 2.0 Flash) y control transaccional FEFO con auditoría antifraude en PostgreSQL 15.

---

## 🏛️ Arquitectura y Decisiones de Ingeniería
* **Parser Elástico en Streaming (`fastexcel`):** Reemplaza el modelo DOM de Apache POI para procesar hojas de cálculo fila por fila consumiendo < 20 MB de heap en servidores con límite de 512 MB de RAM.
* **Persistencia Relacional en 3NF:** Esquema normalizado en PostgreSQL 15 alojado en Supabase, con índices GIN (`idx_products_name_trgm`) para matching de texto informal con `word_similarity`.
* **Motor Transaccional FEFO:** Procedimiento `sp_process_fefo_sale` con candados jerárquicos `FOR SHARE` en tiendas y bloqueo pesimista `FOR UPDATE` en lotes activos.
* **Control Antifraude y Balance de Masa:** Invariante estricto `chk_batch_mass_balance` y cuarentena preventiva de mermas pendientes para evitar ventas fantasmas en caja.
* **Modo Demo Idempotente (1 Clic):** Procedimiento `sp_reset_demo_environment()` para evaluadores técnicos, restaurando el escenario en < 200 ms.

---

## 🚀 Puesta en marcha local (Sprint 1 / 2)

Requisitos: **JDK 21 LTS** y **Apache Maven 3.9+**.

```bash
# Variables de entorno obligatorias (nunca se versionan)
export DATABASE_URL=jdbc:postgresql://db.<proyecto>.supabase.co:5432/postgres
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=<password>
export JWT_SECRET=<minimo 32 bytes aleatorios>
export CORS_ALLOWED_ORIGINS=https://kardo.vercel.app
export GEMINI_API_KEY=<api key de Google AI Studio>

mvn clean verify     # compila y ejecuta las pruebas
mvn spring-boot:run  # arranca en http://localhost:8080
```

Alternativa en local: copia `src/main/resources/application-local.properties.example`
a `application-local.properties` y arranca con `-Dspring.profiles.active=local`.

### Esquema de base de datos
El DDL se aplica manualmente en Supabase (la aplicación no ejecuta migraciones):

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- luego: sql/01_schema_ddl_3nf.sql y sql/02_procedures_and_triggers.sql
```

### Disponibilidad 24/7 (estrategia híbrida)
| Capa | Mecanismo | Frecuencia | Coste en BD |
|---|---|---|---|
| Render despierto | `GET /api/v1/ping` desde UptimeRobot | cada 5 min | 0 consultas |
| Supabase activo | `@Scheduled` interno con `SELECT 1` | 1 vez al día (12:00) | 1 consulta |

```bash
curl -i http://localhost:8080/api/v1/ping   # -> 200 pong
```

El health check de Render debe apuntar a `/api/v1/ping`, no a `/actuator/health`:
`ping` responde desde memoria mientras que el health indicator consulta la BD.
Ademas `JdbcConfig` fija el dialecto PostgreSQL para que el contenedor arranque
aunque Supabase este pausado (de lo contrario Spring Data JDBC abre una conexion
durante el refresco del contexto y la aplicacion no levanta).

### Estructura del backend
```
src/main/java/com/kardo/
├── KardoApplication.java        # @EnableScheduling
├── config/                      # SecurityConfig (CORS + JWT stateless) y @ConfigurationProperties
├── security/                    # JwtService y JwtAuthenticationFilter
├── web/                         # PingController (keep-alive en memoria)
├── scheduler/                   # DatabaseKeepAliveScheduler (heartbeat diario)
└── exception/                   # @RestControllerAdvice -> 409 / 400 / 422
```

---

## 📥 Ingesta de inventario en streaming (Sprint 2)

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/inventory/ingest/excel?store_id={id}` | `multipart/form-data` con la parte `file` (.xlsx, máx 10MB) |
| `POST` | `/api/v1/inventory/ingest/bulk-fix` | JSON con `storeId` y las filas corregidas (máx 100) |

Ambos requieren `Authorization: Bearer <token>`.

**Cabeceras flexibles:** la fila 1 se mapea por alias normalizados (sin tildes ni
puntuación), así que `Vence`, `F. Vencimiento` y `FECHA DE VENCIMIENTO` son
equivalentes. Si falta una columna requerida (SKU, nombre, categoría, costo,
precio, vencimiento, cantidad) se aborta con **HTTP 422** sin recorrer el archivo.

**Compuerta de calidad:** cada fila se valida contra los mismos `CHECK` del DDL
(`unit_cost >= 0`, `retail_price >= 0`, `initial_qty > 0`, vencimiento ≥ fabricación).
Las rechazadas se devuelven en `quarantinedRows` con su `errorCode` para que el
operario las corrija en el navegador y las reenvíe a `/bulk-fix` — que **revalida
en servidor**, porque el cliente no es una fuente de confianza.

**Circuit Breaker:** al superar `kardo.ingest.max-corrupt-rows` (100) la lectura
se corta con **HTTP 422** y no se persiste nada de ese archivo.

**Memoria constante:** Tomcat spoolea a disco (64KB), `fastexcel` recorre la hoja
fila por fila y solo se retiene el micro-lote en curso (`kardo.ingest.batch-size`,
500 filas) que se envía con `JdbcTemplate.batchUpdate`. Cada micro-lote hace
1 upsert en `products`, 1 `SELECT ... = ANY(array)` para resolver los `product_id`
sin N+1 y 1 inserción en `inventory_batches` con `current_qty = initial_qty`.

```bash
curl -X POST "http://localhost:8080/api/v1/inventory/ingest/excel?store_id=1" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@inventario.xlsx"
```

---

## 🧪 Prueba de extremo a extremo contra Supabase

La configuración local vive en `config/application-local.properties` (ignorado por
Git y **fuera** de `src/main/resources`, para que las credenciales no entren en el
jar que se despliega). Parte de `config/application-local.properties.example`.

`tools/KardoDevCli.java` sustituye a `psql` y a un cliente HTTP: se ejecuta en modo
single-file con el classpath del propio proyecto y lee las credenciales del mismo
archivo local.

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=runtime
CP=$(cat cp.txt)

java -cp "$CP" tools/KardoDevCli.java query "SELECT version()"        # conectividad
java -cp "$CP" tools/KardoDevCli.java excel target/inventario.xlsx     # Excel de ejemplo
java -cp "$CP" tools/KardoDevCli.java excel-dirty target/sucio.xlsx 150 # fuerza el 422
java -cp "$CP" tools/KardoDevCli.java token 999                        # JWT de desarrollo

java -jar target/kardo-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Resultados verificados en una base Supabase real (tienda 999):

| Caso | Resultado |
|---|---|
| Excel de 9 filas (5 limpias, 4 sucias + 1 SKU repetido) | `200` — 4 lotes insertados, 5 en cuarentena |
| Upsert de catálogo con SKU repetido | `products.unit_cost` actualizado y 2 lotes creados |
| `bulk-fix` con 2 filas corregidas + 1 incoherente | `200` — 2 insertadas, 1 revalidada y rechazada |
| 150 filas corruptas (umbral 100) | `422` — nada persistido |
| `store_id` inexistente | `404` |
| Petición sin token | `401` |

> **Nota de infraestructura:** Supabase provisiona hoy **PostgreSQL 17.11**, no 15.
> El DDL y los procedimientos funcionan sin cambios, pero conviene actualizar la
> referencia en `docs/ARCHITECTURE_SPEC.md`.

> ⚠️ `sql/01_schema_ddl_3nf.sql` comienza con `DROP TABLE ... CASCADE`: aplicarlo
> sobre una base con datos los borra. Úsalo solo para inicializar.

---

## 👁️ Visión multimodal y conciliación documental (Sprint 3)

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/receipts/scan?store_id={id}` | `multipart/form-data` con la foto de la guía (parte `file`) |
| `POST` | `/api/v1/receipts/confirm` | JSON con la conciliación validada por el operario |

`scan` **solo lee**: imagen → Gemini → matching. `confirm` es el único que escribe,
y lo hace en una sola transacción sobre `goods_receipt_headers`,
`goods_receipt_items` e `inventory_batches`. Entre ambas peticiones no hay estado
en el servidor.

**Preprocesado (`ImageProcessingService`):** lado mayor a 1800 px con
`VALUE_INTERPOLATION_BICUBIC` y JPEG al 85%. Nunca amplía una imagen que ya cabe,
y aplana la transparencia sobre blanco (un fondo negro arruinaría el OCR).

**Resiliencia (`GeminiVisionClient`):** la clave viaja en la cabecera
`x-goog-api-key`, nunca en la URL. Ante `429` (cuota) o `503` (sobrecarga) conmuta
al siguiente modelo de `kardo.gemini.fallback-models`; un `4xx` de configuración
no se reintenta. Sin cadena disponible devuelve `503`; si la respuesta no es el
JSON pactado, `502`.

### El detalle que decide si el índice GIN se usa

El operador debe llevar la columna indexada a la izquierda. Medido en la base real:

```
name %> 'CERV PILSEN 650'   ->  Bitmap Index Scan on idx_products_name_trgm
'CERV PILSEN 650' %> name   ->  Seq Scan  (ni siquiera con enable_seqscan=off)
```

**Y un segundo detalle, menos evidente:** `%>` no compara contra el umbral de la
aplicación sino contra el GUC `pg_trgm.word_similarity_threshold`, que vale **0.6**
por defecto. El caso insignia del spec, `"CERV PILSEN 650"` frente a
`"Cerveza Pilsen Callao Botella 650ml"`, tiene similitud **0.550**: con el umbral
de fábrica la línea se descartaba en el `WHERE` y llegaba como producto nuevo
aunque el catálogo sí lo tuviera. `SkuMatchingRepository` alinea ese GUC con
`kardo.matching.min-score` mediante `set_config(..., is_local => true)` dentro de
la transacción, de modo que no contamina otras sesiones del pool.

Verificado contra la base real (tienda 999):

| Caso | Resultado |
|---|---|
| `CERV PILSEN 650` | `product_id` 4 — score **0.550** |
| `LECHE GLORIA 400` | `product_id` 5 — score 0.765 |
| `TORNILLO INOXIDABLE M8` | `product_id` **null** — lo asigna el operario |
| `confirm` con 3 líneas (24/24, 40/48, 0/10) | `200` — 3 líneas, 2 lotes, discrepancias `-8` y `-10` |
| `confirm` del mismo documento | `409` por `uq_grn_store` |
| `scan` con un archivo que no es imagen | `400` |
| `scan` sin `GEMINI_API_KEY` válida | `502` (Google responde 403, no se reintenta) |
| `scan` sin token | `401` |

El matching se hace con `word_similarity`, que es sensible al orden de los
argumentos: `word_similarity(aguja, pajar)`. La aguja es el texto del documento y
el pajar el nombre del catálogo.

### Prueba con Gemini real (ciclo completo)

Con una API key real y un documento de prueba, el flujo completo
foto → extracción → matching → confirmación → inventario quedó verificado:

```json
{"supplierTaxId":"20548796321","supplierName":"DISTRIBUIDORA ANDINA S.A.C.",
 "documentNumber":"T003-0004871","issueDate":"2026-10-12","modelUsed":"gemini-3.8-flash",
 "unmatchedLines":0,"lines":[
  {"descriptionRaw":"CERV PILSEN 650","productId":4,"matchScore":0.55,"batchCode":"L-2611A","expirationDate":"2027-08-15"},
  {"descriptionRaw":"LECHE GLORIA 400G","productId":5,"matchScore":0.72},
  {"descriptionRaw":"YOGURT FRESA 1L","productId":2,"matchScore":0.81},
  {"descriptionRaw":"GALLETA RELL CHOC X6","productId":3,"matchScore":0.67},
  {"descriptionRaw":"ATUN FLORIDA LATA","productId":9,"matchScore":1.0}]}
```

`confirm` sobre esa conciliación (una línea recibida incompleta, 40 de 48) devolvió
`5 líneas, 5 lotes, discrepancia en la línea 2`, y los lotes entraron en el orden
FEFO correcto por fecha de vencimiento.

Con una foto que **no** es un documento (una imagen de productos), el modelo
devolvió todos los campos nulos y cero ítems en lugar de inventarlos: es el
comportamiento que exige el prompt y protege el inventario de datos alucinados.

> ⚠️ **Modelos de Gemini:** `gemini-2.0-flash` fue retirado por Google y responde
> `404 "This model is no longer available"`. La configuración apunta ahora a
> `gemini-3.8-flash` con respaldos `gemini-3.5-flash` y `gemini-2.5-flash`.
> Por eso el cliente trata el `404` como motivo para pasar al siguiente modelo:
> un modelo retirado dejaría la recepción caída hasta un redespliegue.
> Conviene actualizar también `docs/ARCHITECTURE_SPEC.md`, que aún nombra 2.0.

---

## 🖥️ Interfaz web y modo demo (Sprint 4)

La SPA se sirve desde el propio contenedor en `/` (`src/main/resources/static/`),
con Tailwind por CDN y JavaScript sin framework. Arranca la app y abre
`http://localhost:8080/`.

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/demo/reset` | **Público.** Ejecuta `CALL sp_reset_demo_environment()` y devuelve el token JWT de la tienda 999 más el tablero |
| `GET` | `/api/v1/demo/dashboard` | Métricas del semáforo FEFO |
| `GET` | `/api/v1/demo/fefo-queue?limit=n` | Lotes activos en el orden exacto en que FEFO los entregará |
| `GET` | `/api/v1/products?store_id={id}` | Catálogo para el selector de SKU |

`/demo/reset` es el único endpoint público que escribe: es la puerta de entrada
del evaluador, que todavía no tiene credenciales, y solo puede tocar la tienda 999.

> ⚠️ `sp_reset_demo_environment()` **borra** los lotes, guías y ventas de la
> tienda 999 antes de resembrar. Es su función, pero conviene saberlo antes de
> pulsar el botón sobre datos que quieras conservar.

### Decisiones de interfaz
- **El héroe es la cola FEFO**, no una fila de tarjetas KPI: lo primero que ve el
  operario es qué vence antes y cuánto dinero hay en juego.
- **El color solo aparece donde codifica riesgo.** El filete izquierdo de cada
  lote es rojo (≤48 h), ámbar (≤5 días) o verde; el resto de la pantalla es tinta
  fría sobre papel. Nada decorativo compite con la alerta.
- **La cuarentena resalta la celda culpable**, no la fila entera, para que el
  operario sepa exactamente qué corregir.
- **El botón de confirmar permanece deshabilitado** mientras alguna línea de la
  guía no tenga SKU asignado: impide imputar mercadería al producto equivocado.

### Verificado en el navegador contra Supabase y Gemini reales
| Paso | Resultado |
|---|---|
| Restaurar tienda demo | 3 lotes sembrados: 2 d (rojo), 5 d (ámbar), 30 d · S/ 143.50 en riesgo |
| Cuarentena: editar costo y guardar | 1 fila guardada, 1 revalidada y rechazada en servidor |
| Foto de guía → extracción | RUC, documento, fecha y 3 líneas mapeadas (scores 0.67–0.81) |
| Ajustar recibido (36→30) y confirmar | 3 lotes creados, tablero actualizado solo, descuadre registrado |
| Archivo que no es Excel | HTTP 422 con mensaje legible en pantalla |
