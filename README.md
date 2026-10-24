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

---

## 🔐 Identidad y aislamiento entre tiendas

Antes de esto, la tienda sobre la que se escribía llegaba como parámetro del
cliente (`?store_id=999`) y nadie la contrastaba con el token: un operario
autenticado podía cargar inventario en una tienda ajena cambiando un número en
la URL. Ahora **la tienda es un atributo del usuario**, viaja firmada dentro del
JWT y el backend la toma de ahí.

Aplica `sql/03_users_and_auth.sql` (aditivo, idempotente) para crear la tabla
`users` y los dos usuarios de la tienda demo.

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/auth/login` | **Público.** Correo y contraseña → sesión con token, rol y tienda |
| `GET` | `/api/v1/auth/me` | Quién es el portador del token actual |

**Credenciales de la demo** (públicas y deliberadas; el escenario se borra con un
botón): `demo@kardo.pe` / `kardo-demo-2026` (gerente) y `operario@kardo.pe` con
la misma contraseña (operario).

### Cómo se aplica
- `KardoPrincipal` lleva correo, tienda y roles; `StoreScope.current()` es el
  único origen del `store_id` en los controladores.
- Cuando el cuerpo de la petición trae `storeId` (contrato de la SPA), se
  compara con el del token y se rechaza con **403** si no coinciden: callarlo
  convertiría un intento de salto entre tiendas en una escritura silenciosa.
- `@EnableMethodSecurity` + `@PreAuthorize` en las escrituras: los roles del
  token dejan de ser decorativos.
- Un token sin `storeId` no autentica: nadie opera sin tienda asignada.
- El login responde lo mismo ante contraseña incorrecta y correo inexistente,
  para no permitir enumerar los correos dados de alta.

### Un efecto secundario que apareció al probarlo
Con la cadena de seguridad endurecida, **cualquier error dentro de un endpoint
protegido se respondía como 401** en lugar de su código real: el reenvío interno
a `/error` volvía a pasar por la cadena y, al exigir autenticación, tapaba el
error original. Se permite explícitamente el despacho `ERROR`, y una carga sin
archivo vuelve a responder `415` en vez de un `401` desconcertante.

| Comprobación contra Supabase | Resultado |
|---|---|
| Login correcto | `200` con rol MANAGER y tienda 999 |
| Contraseña incorrecta / correo inexistente | `401`, mensaje idéntico |
| `bulk-fix` con `storeId` ajeno | `403` y el intento queda en el log con el correo |
| `confirm` con `storeId` ajeno | `403` |
| `?store_id=1234` en el catálogo | Ignorado: devuelve el catálogo de la tienda del token |
| Token firmado con otra clave | `401` |
| Sin token | `401` |
| Operario cargando Excel | `200`, tienda tomada del token |

---

## 🧾 Salida FEFO (venta real)

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/v1/inventory/sales` | `{"sku":"...","quantity":25}` — tienda y operario salen del token |

Registra **ventas reales**, no simulaciones: descuenta stock e inserta en
`daily_sales`. La respuesta detalla de qué lotes salió cada unidad, que es lo que
hace visible la rotación:

```json
{"sku":"SKU-LECHE-UHT-1L","quantity":25,"revenue":130.00,"cogs":95.00,"margin":35.00,
 "consumedBatches":[
   {"batchCode":"LOT-LCH-EXP-48H","daysToExpiry":2,"quantity":20,"lotUnitCogs":3.80},
   {"batchCode":"LOT-LCH-FRESCO-30D","daysToExpiry":30,"quantity":5,"lotUnitCogs":3.80}]}
```

La SPA lo expone como **Registrar salida** bajo el semáforo; tras la venta la
alerta roja baja a 0 y el valor en riesgo se recalcula solo.

### Tres bugs corregidos en `sp_process_fefo_sale` (`sql/04_fefo_sale_hardening.sql`)

**1. Aislamiento entre compañías.** El procedimiento resolvía el producto así:

```sql
SELECT product_id, retail_price INTO ... FROM products WHERE sku = p_sku;
```

El DDL permite el mismo SKU en compañías distintas (`UNIQUE (company_id, sku)`),
así que con dos clientes en la misma base ese `SELECT INTO` toma una fila
arbitraria: descuenta stock del producto equivocado. Verificado creando una
segunda compañía con el SKU `SKU-LECHE-UHT-1L`: la consulta devolvía **2 filas**.
Ahora el producto se busca dentro de la compañía dueña de la tienda, y la venta
de 25 unidades en la tienda 999 dejó intacto el lote de la otra compañía.

**2. Tienda inexistente.** El `PERFORM ... FOR SHARE` no encontraba nada y la
ejecución seguía hasta reventar con una violación de clave foránea al insertar
la venta. Ahora se detecta al inicio.

**3. Cantidad no positiva.** Vender 0 o −5 unidades no entraba en el bucle y el
procedimiento terminaba **sin error y sin registrar nada**: una venta fantasma
silenciosa. Ahora se rechaza.

Cada error lleva su `ERRCODE` y `FefoSaleErrorTranslator` lo convierte en el HTTP
correcto en lugar de un 500 opaco:

| Caso | ERRCODE | HTTP | Verificado |
|---|---|---|---|
| Stock insuficiente | `P0002` | `409` | "Faltaron 9954 unidades", stock intacto |
| SKU fuera del catálogo | `P0001` | `404` | ✓ |
| Tienda inexistente | `P0003` | `404` | ✓ |
| Cantidad no positiva | `P0004` | `400` | Bean Validation lo corta antes |
| Sin token | — | `401` | ✓ |

> La llamada va con casts explícitos (`?::int`): en Java la tienda es `long`, el
> driver la envía como `BIGINT` y PostgreSQL no encuentra el procedimiento, que
> declara sus parámetros `INT`.

---

## 🗑️ Mermas con cuarentena y aprobación (Reto 1 del spec)

El esquema ya traía las piezas —`quarantined_qty`, `chk_batch_mass_balance` y
`waste_log.approval_status`— pero ningún endpoint las usaba. `sql/05_waste_workflow.sql`
las cierra con tres procedimientos.

| Método | Endpoint | Rol |
|---|---|---|
| `POST` | `/api/v1/inventory/waste` | Operario o gerente: declara la baja |
| `GET` | `/api/v1/inventory/waste?status=PENDING_APPROVAL` | Ambos: consulta |
| `POST` | `/api/v1/inventory/waste/{id}/approve` | **Solo gerente** |
| `POST` | `/api/v1/inventory/waste/{id}/reject` | **Solo gerente** |

### Por qué una cuarentena y no un descuento directo
Entre que el operario declara una merma y el gerente la resuelve, esas unidades
no están vendibles (ya se tiraron o están apartadas) pero tampoco son una
pérdida contable confirmada. Si se descontaran de `current_qty` sin más, un
rechazo posterior tendría que *inventar* stock; si no se descontaran, el motor
FEFO seguiría vendiendo mercadería que ya no está en la estantería.
`quarantined_qty` las mantiene inmovilizadas y el invariante
`current_qty + quarantined_qty <= initial_qty` impide que el lote crezca.

**Aprobar** saca las unidades de la cuarentena sin devolverlas: la pérdida se
consolida. **Rechazar** las devuelve a `current_qty` y reactiva el lote, salvo
que haya vencido entretanto, en cuyo caso vuelve como `EXPIRED`: el stock existe
pero el índice parcial de FEFO no lo tomará.

### Verificado contra Supabase
| Caso | Resultado |
|---|---|
| Operario declara 6 u de un lote de 20 | `current_qty` 14 + `quarantined_qty` 6 = masa 20 ≤ 48 · pérdida S/ 22.80 |
| El motor FEFO intenta vender 16 | Vende 15 y pasa al siguiente lote: **las 6 en cuarentena no se tocan** |
| **El mismo operario intenta aprobar** | **`403`** — quien declara no puede confirmar |
| El gerente aprueba | Cuarentena a 0, `APPROVED`, `authorized_by` registrado |
| Segundo clic del gerente | `409` "ya fue resuelta" (resolución idempotente) |
| El gerente rechaza otra merma | La mercadería vuelve a `current_qty` y el lote sigue `ACTIVE` |
| Declarar más unidades de las disponibles | `409`, sin tocar el lote |
| Lote de otra tienda o inexistente | `404` |
| Invariante de masa tras todas las pruebas | 0 violaciones, 0 cantidades negativas |

> **Límite conocido:** el control que se aplica es por rol, no por persona. Un
> gerente puede declarar y aprobar su propia merma. Separar también por usuario
> (que nadie resuelva lo que él mismo declaró) es el siguiente paso natural si se
> quiere segregación de funciones estricta.

---

## 📋 Auditoría de cargas e ingesta idempotente

`sql/06_upload_audit.sql` añade `inventory_uploads` y dos columnas a
`goods_receipt_headers`. Una misma tabla resuelve dos problemas distintos.

**Auditoría.** Hasta ahora una carga de Excel no dejaba rastro: el inventario
cambiaba y no había a quién preguntar. Cada carga registra operario, archivo,
tamaño, métricas, IP y momento exacto. Las recepciones guardan además
`confirmed_by`: la conciliación es una decisión humana sobre qué entró de
verdad al almacén, no un dato del documento.

| Método | Endpoint | Descripción |
|---|---|---|
| `GET` | `/api/v1/inventory/ingest/history` | Quién subió qué archivo, cuándo y con qué resultado |

**Idempotencia.** Subir dos veces el mismo archivo duplicaba los lotes en
silencio — lo verificamos en el Sprint 2: un SKU repetido generó dos lotes. Con
conexiones inestables, reintentar una carga que sí llegó es el error más común
del operario. La restricción `UNIQUE (store_id, file_hash)` lo impide, y como el
SHA-256 se calcula sobre los bytes, **renombrar el archivo no lo esquiva**.

El archivo se recorre dos veces: la primera para la huella, la segunda para la
ingesta. Es barato porque Tomcat ya lo dejó en disco, y permite rechazar una
recarga en milisegundos en lugar de tras procesar 10 MB. Todo ocurre en una
transacción, asiento de auditoría incluido: si dos peticiones idénticas entran a
la vez, la restricción hace fallar a la segunda y sus lotes desaparecen con el
rollback.

```
POST /inventory/ingest/excel  (inventario-demo.xlsx)        → 200, uploadId 2, 4 lotes
POST /inventory/ingest/excel  (el mismo archivo)            → 409 "Este archivo ya se
   cargó el 18/10/2026 a las 23:40 por operario@kardo.pe (4 lotes creados)"
POST /inventory/ingest/excel  (copia-renombrada.xlsx)       → 409 (misma huella)
POST /demo/reset  +  la misma carga                          → 200, uploadId 3
```

Esa última línea era un detalle real: el reset borra lotes y guías pero no el
rastro de cargas, así que tras restaurar la demo el evaluador no podía volver a
subir el mismo Excel. `DemoRepository` limpia ahora las huellas de la tienda 999
junto con el resto del escenario.

> **IP y privacidad:** se registra para poder rastrear un acceso indebido, no
> para perfilar al operario. Es dato personal bajo la Ley 29733 y conviene fijar
> una política de retención antes de explotarlo. Detrás del proxy de Render la
> dirección real llega en `X-Forwarded-For`, de ahí
> `server.forward-headers-strategy=framework`; sin eso se registraría siempre la
> IP del proxy.
>
> **Fuera del alcance por ahora:** el reenvío de filas corregidas (`/bulk-fix`)
> no genera asiento de auditoría propio, solo registro en el log.

---

## 📤 Exportación valorizada y reversión de cargas

| Método | Endpoint | Rol |
|---|---|---|
| `GET` | `/api/v1/inventory/export` | Operario o gerente: descarga `.xlsx` del stock valorizado |
| `POST` | `/api/v1/inventory/ingest/{id}/rollback` | **Solo gerente**: deshace una carga |

### Exportación
`StreamingResponseBody` + el escritor de `fastexcel` vuelcan el archivo
directamente sobre la respuesta: no se construye en memoria ni en disco, la misma
disciplina que la ingesta y por el mismo motivo. El cursor se lee de a 500 filas
dentro de una transacción de solo lectura, para que una venta a mitad de la
exportación no produzca un archivo que no cuadre consigo mismo.

Columnas: SKU, producto, categoría, unidad, lote, fabricación, vencimiento, días
restantes, estado, cantidad, en cuarentena, costo unitario y valor, ordenadas por
vencimiento (FEFO) y con fila de totales. Verificado: 85 unidades y **S/ 333.50**,
el mismo valor que muestra el tablero.

### Reversión: deltas, no snapshots
No se guarda una foto del inventario por cada carga —multiplicaría los datos y se
comería los 500 MB del plan gratuito. En su lugar, cada lote lleva el `upload_id`
que lo creó: el "snapshot" es una consulta, no una copia.

**Revertir no es borrar lo que la carga escribió, es borrarlo solo si nadie lo ha
usado.** Un lote del que ya se vendió, del que se declaró una merma o que quedó
parcialmente consumido es historia real del almacén; eliminarlo dejaría ventas
apuntando a un lote inexistente. Por eso la reversión es **todo o nada**: si un
solo lote fue tocado se rechaza entera y se nombra cuál.

```json
{"status":409,"message":"No se puede revertir la carga 5: su mercadería ya se movió.
  Registra los ajustes a mano.",
 "details":["L-2026-A (Cerveza Pilsen Callao Botella 650ml): ya tiene ventas o mermas registradas"]}
```

El catálogo **no** se toca: un SKU creado por la carga puede tener ya otros lotes
de una recepción posterior, y borrarlo se llevaría por delante stock legítimo.

| Comprobación contra Supabase | Resultado |
|---|---|
| Operario intenta revertir | `403` |
| Gerente revierte una carga intacta | `200` — 4 lotes eliminados, 6 productos intactos, `rolled_back_by` registrado |
| Revertir dos veces | `409` "ya fue revertida" |
| Venta sobre un lote de la carga y luego revertir | `409` nombrando el lote bloqueante |
| Subir de nuevo el archivo **tras revertir** | `200` — la carga revertida ya no bloquea |

Ese último caso obligó a cambiar la unicidad por una **parcial**
(`WHERE rolled_back_at IS NULL`): el flujo natural tras un error es revertir y
volver a subir, y con una unicidad total el segundo intento se rechazaba como
duplicado dejando al gerente atascado.
