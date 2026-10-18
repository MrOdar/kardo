# 📦 SmartKardex AI

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
