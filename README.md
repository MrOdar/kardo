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
