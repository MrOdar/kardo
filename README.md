# 📦 Kardo

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange?style=flat&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.4-6DB33F?style=flat&logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL 17](https://img.shields.io/badge/Database-PostgreSQL_17_(3NF)-336791?style=flat&logo=postgresql)](https://www.postgresql.org/)
[![Gemini Flash](https://img.shields.io/badge/AI-Gemini_Flash_Vision-4285F4?style=flat&logo=google)](https://ai.google.dev/)

**Kardo evita que la mercadería se venza en el anaquel.**

Un minimarket pierde dinero por una razón aburrida: nadie sabe qué lote vence
primero. Kardo lleva ese control, vende siempre lo más próximo a caducar y
avisa —con un precio concreto— cuando todavía se puede recuperar la plata.

🔗 **Demo:** [kardo.onrender.com](https://kardo.onrender.com) · pulsa
**Restaurar tienda demo** y entras sin credenciales.

---

## Qué hace

| | |
|---|---|
| 📥 **Carga el inventario desde Excel** | Lee el archivo en streaming, valida fila a fila y devuelve las malas a una cuarentena editable, sin rechazar el archivo entero. Subir dos veces lo mismo no duplica nada. |
| 📸 **Recibe mercadería con una foto** | Una foto de la guía de remisión se lee con visión multimodal y cada línea se cruza contra el catálogo; el operario confirma lo que de verdad llegó. |
| 🔴 **Ordena la salida por vencimiento (FEFO)** | Cada venta descuenta del lote que caduca primero, con el costo real de ese lote. El margen sale de la base, no de una estimación. |
| 🏷️ **Dice qué rebajar hoy** | Traduce los días que le quedan a un lote en un precio sugerido, que **nunca baja del costo**: por debajo de ahí, rebajar sale peor que la merma. |
| 🗑️ **Controla las mermas** | El operario declara la baja y las unidades quedan inmovilizadas; un gerente —distinto del que la declaró— decide si la pérdida se consolida. |
| 🏷️ **Imprime el cartel de la oferta** | Una hoja de etiquetas con el precio rebajado, el lote y la fecha. Sale de la misma columna que cobra la caja, así que el cartel y el ticket no pueden discrepar. |
| 📈 **Explica por qué pasó** | La serie diaria del valor en riesgo, los días de cobertura de cada producto —si no da tiempo a venderlo, eso se decidió al comprar— y de qué proveedor viene lo que acaba en riesgo. |
| 📊 **Exporta valorizado** | Excel para trabajar los datos, PDF firmable para el contador. |

---

## Cómo funciona

**La tienda sale del token, nunca de la petición.** Cada usuario pertenece a una
tienda y el backend ignora cualquier `storeId` que llegue en el cuerpo: es lo que
impide que un token de una tienda escriba en otra.

**Lo que no puede cuadrar mal, lo impide la base.** El invariante
`current_qty + quarantined_qty <= initial_qty` es un `CHECK` del esquema, y la
venta FEFO es un procedimiento PL/pgSQL con bloqueo pesimista sobre los lotes.
Dos cajas vendiendo a la vez no pueden pasarse del stock aunque la aplicación
tuviera un error.

**Todo está pensado para 512 MB de RAM.** El Excel se procesa fila a fila
(`fastexcel`, nunca el DOM de POI), el multipart se vuelca a disco a partir de
64 KB, se usa `JdbcTemplate` en vez de JPA para no arrastrar un caché de primer
nivel, y el pool de conexiones vive en 5.

**Quien hace algo queda firmado.** Cargas, correcciones, mermas, rebajas y
recepciones registran autor y momento. La huella SHA-256 del contenido es lo que
hace idempotente una carga reintentada tras un corte de red.

> El porqué de cada decisión, los bugs que aparecieron al probar contra
> infraestructura real y cómo se corrigieron están en
> **[`docs/ARCHITECTURE_SPEC.md`](docs/ARCHITECTURE_SPEC.md)**.

---

## Con qué está hecho

| Capa | Tecnología | Por qué |
|---|---|---|
| Backend | **Java 21** · Spring Boot 3.3.4 | `JdbcTemplate` y no JPA: el control de memoria es explícito |
| Base de datos | **PostgreSQL 17** (Supabase) | Esquema 3NF, procedimientos PL/pgSQL, índice GIN `pg_trgm` para el matching de SKUs |
| Excel | **fastexcel** | Streaming de lectura y escritura, memoria constante |
| Visión | **Gemini Flash** | Extracción de guías con cadena de modelos de respaldo ante 429/503 |
| PDF | **PDFBox** | Apache-2.0: sin la pregunta de licencia que trae LGPL |
| Seguridad | **Spring Security 6** · JWT HS256 · BCrypt | Sin estado en servidor: el contenedor puede reiniciarse sin perder sesiones |
| Interfaz | **HTML + Tailwind (CDN) + JS sin framework** | Se sirve desde el mismo jar; no hay build de front |
| Despliegue | **Render** (backend) · **Supabase** (datos) | Capa gratuita, con heartbeat para que ninguno hiberne |

La aplicación abre en **lo primero de hoy**: las prioridades de la tienda
ordenadas por lo que cuesta dejarlas para mañana, cada una con el botón que
lleva adonde se resuelve. Detrás, siete secciones: semáforo FEFO, qué rebajar
hoy, inventario y lotes, Excel y versionado, recepción con IA, análisis y
diagnóstico de rendimiento. `Ctrl+K` abre el buscador global.

---

## Puesta en marcha

Requisitos: **JDK 21** y **Maven 3.9+**.

```bash
cp config/application-local.properties.example config/application-local.properties
# rellena la cadena de Supabase, el JWT_SECRET y la API key de Gemini
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Arranca en `http://localhost:8080`. Las credenciales viven en `config/`, que
Spring Boot lee en ejecución y **nunca entra en el jar**.

En producción se configuran por variables de entorno: `DATABASE_URL`,
`DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET`, `GEMINI_API_KEY` y
`CORS_ALLOWED_ORIGINS`.

### Base de datos

El esquema se aplica a mano (la aplicación no ejecuta migraciones). En orden:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- luego sql/01 … sql/12, en orden numérico
```

`sql/01` empieza con `DROP TABLE … CASCADE`: **es destructivo**. Del `03` en
adelante son aditivos e idempotentes, así que se pueden reaplicar sin miedo.

### Pruebas

```bash
mvn verify    # 183 unitarias + 91 de integración
```

Las de integración levantan un **PostgreSQL 17 efímero** con Testcontainers y le
aplican los mismos scripts de `sql/` que se usan en Supabase: no hay un esquema
paralelo de pruebas. Sin Docker se saltan y el build sigue verde, así que
`mvn verify` es seguro en cualquier máquina; GitHub Actions las ejecuta de
verdad sobre Linux.

> **Windows + Docker Desktop reciente:** pueden saltarse en local aunque Docker
> esté instalado. Docker Desktop 29 responde al `/info` de clientes que no son su
> CLI con un `400` vacío y Testcontainers lo interpreta como "no hay Docker".
> WSL2 o un runner Linux son el camino corto.

---

## Mantenerlo en pie

| Qué | Cómo |
|---|---|
| Render despierto | `GET /api/v1/ping` desde UptimeRobot cada 5 min, sin tocar la base |
| Serie del riesgo | `@Scheduled` a las 23:50 de Lima: una foto diaria por tienda en `risk_snapshots` |
| Supabase activo | `@Scheduled` interno con un `SELECT 1` diario |
| ¿Quién va lento? | `GET /api/v1/ops/diagnostics` responde si es Gemini, Supabase o el pool |

El health check de Render debe apuntar a `/api/v1/ping`, no a
`/actuator/health`: ese último consulta la base y la despertaría en cada sonda.
