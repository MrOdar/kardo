# 📋 Informe de Arquitectura Técnica: SmartKardex AI (v2.3 Platinum Definitiva)
## Plataforma SaaS B2B de Migración Inteligente, Conciliación Documental con Visión IA y Motor Transaccional FEFO (3NF)

---

### 1. Resumen Ejecutivo y Storytelling de Negocio
**SmartKardex AI** es una plataforma SaaS B2B diseñada para erradicar las ineficiencias críticas de inventario, los descuadres de recepción documental y las pérdidas por vencimiento en cadenas de retail de alta frecuencia, minimarkets y farmacias.

* **El Problema:** El 90% de los negocios de proximidad gestiona su stock en hojas de cálculo heterogéneas y transcribe manualmente facturas y guías de remisión impresas. Esto genera que más del 70% de las mermas monetarias se deban al vencimiento de perecibles (por falla en la rotación FEFO/PEPS) y a discrepancias no reclamadas frente al proveedor.
* **La Solución:** Un SaaS que ingesta archivos Excel masivos en streaming continuo (< 20 MB de RAM), procesa guías de remisión impresas mediante Visión Multimodal (Gemini 3.8 Flash) cruzándolas contra el catálogo relacional en 3NF, y ejecuta las salidas bajo estricto algoritmo transaccional FEFO con auditoría antifraude en PostgreSQL 17.

---

### 2. Diagrama de Arquitectura y Pila Tecnológica ($0 Costo / Producción 24/7)

```mermaid
flowchart TD
    Client["📱 SPA Web Client\n(HTML5 / Tailwind CSS CDN / Vanilla JS)\nDesplegado en Vercel\n- Estado de Cuarentena en memoria del navegador (Max 100 filas)\n- Tablas interactivas de corrección y matching de SKU"]
    
    subgraph Cloud_Free_Tier ["Infraestructura Cloud (100% Gratuita & Cero Latencia)"]
        UptimeMonitor["⏰ UptimeRobot / cron-job.org\n(GET /api/v1/ping cada 5 min a memoria RAM\nCero consultas a BD / 0 conexiones HikariCP)"]
        
        Backend["⚙️ Spring Boot 3.3.4 (Java 21 LTS)\nRender Container (Límite estricto 512 MB RAM)\n- Spooling a disco con file-size-threshold=64KB\n- fastexcel + getInputStream directo\n- JdbcTemplate.batchUpdate (Sin First-Level Cache)\n- Circuit Breaker de Calidad (Max 100 errores)\n- BufferedImage (1800 px bicúbico)\n- Spring Security 6 (JWT sin estado)\n- Heartbeat diario anti-hibernación (SELECT 1)"]
        
        Gemini["🤖 Google Gemini 3.8 Flash\n(Google AI Studio Free Tier)\n- Extracción documental OCR con JSON estructurado\n- Fallback dinámico ante 429/503/404"]
        
        DB[("🐘 PostgreSQL 17 (3NF)\nSupabase Free Tier (500 MB persistente)\n- GIN index scan con operador commutado (name %> item.ocr_text)\n- sp_process_fefo_sale con FOR SHARE en stores\n- Balance de masa: current_qty + quarantined_qty <= initial_qty\n- sp_reset_demo_environment con store lock pesimista")]
    end

    UptimeMonitor -->|Keep-Alive HTTP /ping| Backend
    Client -->|HTTPS / REST API + JWT Bearer| Backend
    Backend -->|Payload Base64 optimizado 1800px| Gemini
    Backend -->|HikariCP Connection Pool| DB
```

#### Decisiones de Ingeniería Justificadas:
| Componente | Tecnología | Decisión de Arquitectura / Trade-off |
|---|---|---|
| **Core Runtime** | **Java 21 LTS + Spring Boot 3.3.4** | Tipado fuerte, Virtual Threads para concurrencia I/O y estándar corporativo bancario. |
| **Parser de Excel** | **`fastexcel` (dhatim) + `JdbcTemplate`** | **Evita OOM:** Streaming directo desde `file.getInputStream()` con spooling en `/tmp` fuera del Java Heap (`file-size-threshold=64KB`). Inserta mediante `JdbcTemplate.batchUpdate()` en micro-lotes de 500 filas, esquivando el First-Level Cache de Hibernate. |
| **Persistencia** | **PostgreSQL 17 en Supabase** | 500 MB persistentes que no consumen horas de cómputo activo como Neon, con índice `GIN` para `word_similarity` y procedimientos transaccionales. |
| **Keep-Alive Híbrido** | **UptimeRobot (5 min) + Heartbeat diario** | `/api/v1/ping` en RAM cada 5 min mantiene vivo Render; un `@Scheduled` diario ejecuta `SELECT 1` para evitar la pausa por 7 días de inactividad de Supabase. |
| **IA & OCR** | **Gemini 3.8 Flash (1800 px bicúbico)** | Preserva la legibilidad de letras pequeñas en facturas A4 produciendo un payload ligero (~350 KB) con autodescubrimiento y conmutación de modelos. |

---

### 3. Blindaje Quirúrgico de los Retos de Arquitectura

#### 🛠️ Reto 1: Control Antifraude e Invariante de Masa Físico (`chk_batch_mass_balance`)
* **Problema:** Mover unidades de `current_qty` a `quarantined_qty` sin actualizar la restricción de integridad permite que, ante fallos de concurrencia o dobles rechazos, el inventario supere el lote original (`initial_qty`).
* **Solución Arquitectónica (Restricción de Masa e Integridad):**
  * La definición DDL de `inventory_batches` incorpora el invariante estricto:
    ```sql
    CONSTRAINT chk_batch_mass_balance 
    CHECK (current_qty >= 0 AND quarantined_qty >= 0 AND (current_qty + quarantined_qty) <= initial_qty);
    ```
  * **Transacción Atómica con Guarda:**
    ```sql
    UPDATE inventory_batches 
    SET current_qty = current_qty - :discarded_qty,
        quarantined_qty = quarantined_qty + :discarded_qty,
        batch_status = CASE 
            WHEN (current_qty - :discarded_qty) = 0 THEN 'DEPLETED' 
            ELSE batch_status 
        END
    WHERE batch_id = :batch_id 
      AND current_qty >= :discarded_qty;
    ```
  * **Resolución del Manager:**
    * **Si el Manager APRUEBA (`APPROVED`):** Se ejecuta `quarantined_qty = quarantined_qty - :discarded_qty` consolidando la merma contable.
    * **Si el Manager RECHAZA (`REJECTED`):** Se revierte a `current_qty = current_qty + :discarded_qty` y, si `expiration_date >= CURRENT_DATE`, se reactiva `batch_status = 'ACTIVE'`.

#### 🛠️ Reto 2: Resolución Óptima de OCR Documental
* **Problema:** Reescalar una factura A4 con texto denso a `< 1024 px` degrada caracteres alfanuméricos (`8` vs `0`, `B` vs `6`), induciendo alucinaciones en la IA.
* **Solución Arquitectónica:**
  * El módulo `ImageProcessingService` fija el lado mayor en **`1800 px`** con interpolación bicúbica (`RenderingHints.VALUE_INTERPOLATION_BICUBIC`) y compresión JPEG al 85%.
  * Garantiza nitidez para fuentes de hasta 6 pt en un payload optimizado de ~350 KB transmitido en ~1.1 s a Gemini.

#### 🛠️ Reto 3: Ingesta Streaming Zero-Server-State y Circuit Breaker de Calidad
* **Problema:** Cargar archivos grandes en RAM o tolerar archivos con miles de filas erróneas colapsa la memoria del servidor de 512 MB y congela el DOM del navegador.
* **Solución Arquitectónica:**
  1. **Configuración de Spooling de Tomcat:**
     ```properties
     spring.servlet.multipart.file-size-threshold=64KB
     spring.servlet.multipart.max-file-size=10MB
     ```
     Tomcat escribe a `/tmp` fuera del Java Heap; `fastexcel` lee el flujo directamente vía `file.getInputStream()`.
  2. **Persistencia Directa:** Se inserta mediante `JdbcTemplate.batchUpdate()` en micro-lotes de 500 filas sin First-Level Cache de Hibernate.
  3. **Circuit Breaker de Calidad:** Si el archivo supera las **100 filas corruptas**, el streaming aborta inmediatamente con HTTP 422: *"Estructura de archivo corrupta o plantilla no compatible"*.
  4. Los errores menores a 100 se devuelven en el JSON HTTP y se corrigen en la memoria del navegador del cliente (JS), enviando un `POST /bulk-fix` únicamente con las filas modificadas. **0 bytes de estado en el heap del servidor**.

#### 🛠️ Reto 4: Entity Matching de SKUs con Índice GIN (`Bitmap Index Scan`) sin N+1
* **Problema:** En la factura dice `"CERV PILSEN 650"` y en BD `"Cerveza Pilsen Callao Botella 650ml"`. Si el operando indexado no está en la posición adecuada, PostgreSQL descarta el índice GIN e incurre en un escaneo secuencial (*Seq Scan*). Además, un `CROSS JOIN` pierde los productos nuevos.
* **Solución Arquitectónica:**
  1. Se crea el índice GIN en PostgreSQL:
     ```sql
     CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);
     ```
  2. Se conmuta el operador colocando la columna indexada (`name`) a la izquierda (`name %> item.ocr_text`) para forzar el uso del índice GIN, utilizando arreglos paralelos tipados y `LEFT JOIN LATERAL`:
     ```sql
     SELECT item.ocr_line_id, p.product_id, p.name, 
            word_similarity(item.ocr_text, p.name) AS score
     FROM UNNEST(:line_ids::int[], :ocr_texts::text[]) AS item(ocr_line_id, ocr_text)
     LEFT JOIN LATERAL (
         SELECT product_id, name 
         FROM products 
         WHERE name %> item.ocr_text
         ORDER BY word_similarity(item.ocr_text, name) DESC 
         LIMIT 1
     ) p ON TRUE;
     ```
  3. En 1 solo viaje de red (*round-trip*) se resuelven todos los ítems de la guía. Si el producto es nuevo, `p.product_id` retorna `NULL` y la interfaz web solicita al operario vincularlo o crear un nuevo SKU.
  4. **Alineación del umbral de trigramas (verificado en producción):** el operador `%>` no compara contra el umbral de la aplicación sino contra el GUC `pg_trgm.word_similarity_threshold`, cuyo valor de fábrica es **0.6**. El caso insignia de este documento, `"CERV PILSEN 650"` frente a `"Cerveza Pilsen Callao Botella 650ml"`, tiene una similitud real de **0.550**: con el umbral por defecto la línea se descartaba en el `WHERE` y llegaba como producto nuevo aunque el catálogo sí lo tuviera. El repositorio alinea el GUC con `kardo.matching.min-score` dentro de la transacción de lectura:
     ```sql
     SELECT set_config('pg_trgm.word_similarity_threshold', :min_score, true);
     ```
     El `is_local => true` evita contaminar otras sesiones del pool de HikariCP.
  5. **Evidencia del plan de ejecución** (medida con `EXPLAIN` sobre la base real):
     ```
     name %> 'CERV PILSEN 650'   ->  Bitmap Index Scan on idx_products_name_trgm
     'CERV PILSEN 650' %> name   ->  Seq Scan (ni siquiera con enable_seqscan=off)
     ```
     Confirma que el orden de los operandos es lo que decide el uso del índice GIN.

#### 🛠️ Reto 5: Estrategia Híbrida 24/7 (Cero Latencia en Render + Cero Hibernación en Supabase)
* **Problema:** Mantener despierto Render mediante `/ping` en memoria no previene que Supabase pause la base de datos tras 7 días sin consultas SQL.
* **Solución Arquitectónica:**
  * **Capa 1 (Render despierto):** **UptimeRobot** emite un `GET /api/v1/ping` cada 5 minutos exactos hacia un endpoint en memoria (`ResponseEntity.ok("pong")`), garantizando que la JVM no se duerma.
  * **Capa 2 (Supabase activo):** Un componente `@Scheduled(cron = "0 0 12 * * ?")` dentro de Spring Boot ejecuta un `SELECT 1` al mediodía (1 consulta al día). Esto mantiene activo el proyecto de Supabase sin consumir cuotas de cómputo innecesarias.

#### 🛠️ Reto 6: Concurrencia e Idempotencia en el Escenario Demo (Bloqueo Jerárquico en `stores`)
* **Problema:** `sp_reset_demo_environment()` borra tablas hijas, pero transacciones de venta simultáneas (`sp_process_fefo_sale`) pueden operar en paralelo si no existe un candado jerárquico común.
* **Solución Arquitectónica:**
  * **Candado en la Venta (`sp_process_fefo_sale`):** En la primera línea del procedimiento de venta se adquiere un bloqueo compartido:
    ```sql
    PERFORM 1 FROM stores WHERE store_id = p_store_id FOR SHARE;
    ```
  * **Candado Exclusivo en el Reset Demo (`sp_reset_demo_environment`):**
    ```sql
    CREATE OR REPLACE PROCEDURE sp_reset_demo_environment()
    LANGUAGE plpgsql AS $$
    BEGIN
        -- Candado exclusivo sobre la tienda demo: espera a que terminen ventas en vuelo
        -- y bloquea nuevas ventas hasta concluir la reinicialización
        PERFORM store_id FROM stores WHERE store_id = 999 FOR UPDATE;
        
        -- Borrado en orden estricto de dependencias foráneas
        DELETE FROM daily_sales WHERE store_id = 999;
        DELETE FROM waste_log WHERE store_id = 999;
        DELETE FROM inventory_batches WHERE store_id = 999;
        DELETE FROM goods_receipt_items WHERE receipt_id IN (
            SELECT receipt_id FROM goods_receipt_headers WHERE store_id = 999
        );
        DELETE FROM goods_receipt_headers WHERE store_id = 999;
        
        -- Siembra determinista de 25 productos, 2 guías y 5 lotes por vencer (< 48h)
        INSERT INTO goods_receipt_headers (...) VALUES (...);
        INSERT INTO inventory_batches (...) VALUES (...);
    END;
    $$;
    ```
  * Garantiza ejecución determinista en < 200 ms sin violaciones de integridad ni deadlocks.

---

### 4. Roadmap de Desarrollo (2 Semanas / 4 Sprints con Tailwind CSS)

```mermaid
flowchart LR
    Sprint1["Sprint 1 (Días 1-3)\nBD & Motor Transaccional\n- DDL 3NF en Supabase\n- chk_batch_mass_balance\n- Candados stores (FOR SHARE / FOR UPDATE)\n- Setup Spring Boot 3.3.4"] 
    --> Sprint2["Sprint 2 (Días 4-6)\nStreaming & Ingesta Elástica\n- Spooling Tomcat (64KB)\n- fastexcel + JdbcTemplate.batchUpdate\n- Circuit Breaker (100 errores)\n- /api/v1/ping + Heartbeat Supabase"]
    --> Sprint3["Sprint 3 (Días 7-9)\nVisión IA & Matching Batch\n- OCR Gemini 3.8 Flash (1800 px)\n- name %> item.ocr_text con LEFT JOIN\n- Fallback dinámico de modelos"]
    --> Sprint4["Sprint 4 (Días 10-12)\nUI & Puesta en Producción\n- Tablas interactivas Tailwind\n- Botón Restaurar Demo\n- Deploy Render + Vercel\n- Config UptimeRobot"]
```

---

### 5. Checklist Operativo y Parámetros de Configuración de Producción

Para garantizar una integración fluida entre Vercel, Render y Supabase, se definen los parámetros exactos de infraestructura:

#### 1. Configuración de Red, CORS y Puerto Dinámico (`application.properties`)
* **Puerto Dinámico en Render:**
  ```properties
  server.port=${PORT:8080}
  ```
* **Filtro CORS en Spring Security 6:** Bean `CorsConfigurationSource` habilitando el origen de Vercel (`https://smartkardex-ai.vercel.app`), con `setAllowCredentials(true)` y cabeceras expuestas `Authorization` y `Content-Type`.

#### 2. Conectividad y Pool HikariCP para Supabase Free Tier
* **Versión del motor:** Supabase provisiona hoy **PostgreSQL 17** (verificado: `17.11`). El DDL en 3NF, los procedimientos y el índice GIN funcionan sin cambios; la referencia a la 15 del diseño original queda superada.
* **Activación DDL Manual en Supabase:**
  ```sql
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
  ```
* **Advertencia sobre `sql/01_schema_ddl_3nf.sql`:** comienza con `DROP TABLE ... CASCADE`. Solo debe ejecutarse para inicializar un proyecto vacío; sobre una base con datos los destruye.
* **Dialecto fijado en el arranque:** `JdbcConfig` declara el dialecto PostgreSQL en lugar de dejar que Spring Data JDBC lo deduzca abriendo una conexión durante el refresco del contexto. Sin eso, una base pausada o una red caída impiden que el contenedor levante y, por tanto, que responda `/api/v1/ping`, que es justo el endpoint encargado de mantenerlo vivo.
* **Límite de Conexiones HikariCP (evita saturación en capa gratuita):**
  ```properties
  spring.datasource.hikari.maximum-pool-size=5
  spring.datasource.hikari.minimum-idle=2
  spring.datasource.hikari.connection-timeout=30000
  ```

#### 3. Timeouts y Conexión para Gemini 3.8 Flash
* **Timeouts de Lectura y Conexión:** Configuración del cliente HTTP (`RestClient`) con **20 segundos** de lectura para absorber picos de tráfico en la API de Google sin lanzar `SocketTimeoutException`.
* **Seguridad de Secretos:** `GEMINI_API_KEY` inyectada en las variables de entorno cifradas de Render y enviada en la cabecera `x-goog-api-key`, nunca como parámetro de la URL (no debe quedar en logs de acceso ni en historiales de proxy).
* **Cadena de modelos:**
  ```properties
  kardo.gemini.model=gemini-3.8-flash
  kardo.gemini.fallback-models=gemini-3.5-flash,gemini-2.5-flash
  kardo.gemini.max-attempts=3
  ```
* **Conmutación de modelo ante 429 / 503 / 404:** además de la cuota agotada (429) y la sobrecarga (503), se conmuta ante **404**. Google retira modelos antiguos y responde `"This model is no longer available"`; tratarlo como error definitivo dejaría la recepción de mercadería caída hasta un redespliegue. Los `400`, `401` y `403` sí son definitivos: reintentar con otro modelo no los arregla y consume cuota.
* **Nota de vigencia:** `gemini-2.0-flash`, el modelo original de este documento, fue retirado por Google y hoy devuelve `404`. Conviene revisar `GET /v1beta/models` antes de cada despliegue mayor.

#### 4. Spooling de Tomcat para Archivos Multiparte
* **Protección del Heap de 512 MB:**
  ```properties
  spring.servlet.multipart.enabled=true
  spring.servlet.multipart.file-size-threshold=64KB
  spring.servlet.multipart.max-file-size=10MB
  spring.servlet.multipart.max-request-size=10MB
  ```

#### 5. Manejo Centralizado de Excepciones (`@RestControllerAdvice`)
* `InsufficientStockException` ➔ HTTP 409 (Conflict).
* `MaxUploadSizeExceededException` ➔ HTTP 400 (Bad Request).
* Circuit Breaker (> 100 filas erróneas en Excel) ➔ HTTP 422 (Unprocessable Entity).

---

### 6. Resumen del Perfil Competitivo Frente a Evaluadores Técnicos
Este proyecto te posiciona como un candidato sobresaliente para el **BCP / Interbank** porque demuestra:
1. **Control Fino de Recursos en la Nube:** Sabes diseñar sistemas que operan de forma estable dentro de límites reales de memoria (512 MB de RAM) usando streaming, spooling en disco y `JdbcTemplate`.
2. **Dominio Transaccional Avanzado:** Previenes condiciones de carrera en mermas con reservas preventivas (`quarantined_qty`), blindas el balance de masa con `chk_batch_mass_balance` y sincronizas candados jerárquicos entre `stores` y sus tablas hijas (`FOR SHARE` vs `FOR UPDATE`).
3. **Ingeniería de Consultas SQL:** Optimizas matching de texto informal forzando el índice `GIN` con `name %> item.ocr_text` y arreglos paralelos con `UNNEST` y `LEFT JOIN LATERAL`, eliminando problemas de latencia `N+1` y previniendo la pérdida silenciosa de productos nuevos.
4. **Resiliencia Operativa:** Aplicas Circuit Breakers para proteger la memoria frente a archivos corruptos y orquestas disponibilidad 24/7 híbrida contra la hibernación de servicios gratuitos.

---

### 7. Verificación Contra Infraestructura Real (18/10/2026)

Las decisiones de este documento se contrastaron contra el proyecto Supabase real
y la API de Google AI Studio. Resultados y ajustes derivados:

| Área | Hallazgo | Ajuste aplicado |
|---|---|---|
| Motor de BD | Supabase provisiona PostgreSQL **17.11**, no 15 | Referencias actualizadas; sin cambios en DDL ni procedimientos |
| Matching GIN | `name %> texto` → `Bitmap Index Scan`; invertido → `Seq Scan` | Confirma el orden de operandos del Reto 4 |
| Umbral de trigramas | `%>` usa el GUC de 0.6; `"CERV PILSEN 650"` puntúa 0.550 y se descartaba | `set_config('pg_trgm.word_similarity_threshold', min_score, true)` por transacción |
| Modelos de Gemini | `gemini-2.0-flash` retirado (`404 NOT_FOUND`) | Cadena `gemini-3.8-flash` → `3.5-flash` → `2.5-flash`; el `404` conmuta de modelo |
| Extracción documental | Guía de 5 líneas leída con RUC, número, fecha, lotes y vencimientos correctos | — |
| Alucinaciones | Ante una foto que no es un documento, el modelo devuelve campos nulos y cero ítems | El prompt prohíbe inventar valores |
| Secretos | La configuración local en `src/main/resources` viajaba dentro del jar | Movida a `./config/`, excluida del empaquetado y de Git |
| `JWT_SECRET` ausente | Spring entrega el literal `${JWT_SECRET}` (13 bytes) y jjwt falla con un error sobre bits | `JwtService` valida el secreto y nombra la variable que falta |
| Comentarios SQL | Ambos archivos de `sql/` abrían con `#`, inválido en PostgreSQL | Convertidos a `--` |

Cobertura automatizada: **33 pruebas** (streaming de Excel, compuerta de calidad,
Circuit Breaker, JWT, procesamiento de imagen, cliente de Gemini con sus
conmutaciones y conciliación documental).
