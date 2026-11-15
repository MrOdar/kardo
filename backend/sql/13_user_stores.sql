-- ============================================================================
-- 13. Una persona, varias tiendas
--
-- Por que existe: hasta aqui `users.store_id` era una sola clave foranea, de
-- modo que una persona pertenecia a UNA tienda. En cuanto una empresa pudo
-- abrir la segunda (sql/12 y el alta de empresas), el gerente que acababa de
-- crearla no podia verla: la unica salida era tener dos cuentas con dos
-- correos, porque el correo es unico en toda la base.
--
-- A partir de aqui la pertenencia es una tabla propia, con el **rol por
-- tienda**: se puede ser gerente en una y operario en otra, que es como
-- funciona de verdad una cadena pequeña.
--
-- ADITIVO. No borra ni modifica nada de 03: `users.store_id` y `users.role`
-- siguen ahi y se siguen escribiendo, pero pasan a ser la **tienda de origen**
-- -con que tienda se abre sesion por defecto- y dejan de mandar. Quien manda
-- es `user_stores`. No se eliminan a proposito: un DROP COLUMN sobre una base
-- en produccion no tiene vuelta atras, y el precio de dejarlos es una columna
-- redundante bien documentada.
-- ============================================================================

-- Indices unicos que hacen posible la clave foranea compuesta de abajo. Sobre
-- columnas que ya son unicas por separado, asi que no cambian nada salvo
-- permitir que PostgreSQL los use como destino de una FK.
CREATE UNIQUE INDEX IF NOT EXISTS uq_stores_id_company ON stores (store_id, company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_id_company  ON users  (user_id, company_id);

/*
 * La pertenencia no puede cruzar empresas, y no porque el servicio se acuerde
 * de comprobarlo: las dos claves foraneas compuestas llevan la misma columna
 * `company_id`, de modo que una fila que apunte a un usuario de una empresa y
 * a una tienda de otra **no se puede insertar**. Es el mismo criterio que el
 * invariante de masa de los lotes: lo que no puede cuadrar mal, lo impide la
 * base.
 */
CREATE TABLE IF NOT EXISTS user_stores (
    user_id    INT NOT NULL,
    store_id   INT NOT NULL,
    company_id INT NOT NULL,

    -- Rol en ESTA tienda. Se puede ser gerente en una y operario en otra.
    role       VARCHAR(20) NOT NULL CHECK (role IN ('OPERATOR', 'MANAGER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_stores PRIMARY KEY (user_id, store_id),
    CONSTRAINT fk_user_stores_user
        FOREIGN KEY (user_id, company_id) REFERENCES users (user_id, company_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_user_stores_store
        FOREIGN KEY (store_id, company_id) REFERENCES stores (store_id, company_id)
        ON DELETE CASCADE
);

COMMENT ON TABLE user_stores IS
    'Donde trabaja cada persona y con que rol en cada sitio. Manda sobre users.store_id y users.role.';

-- Para "quien trabaja en esta tienda", que es como lo consulta la lista de la
-- organizacion.
CREATE INDEX IF NOT EXISTS idx_user_stores_store ON user_stores (store_id);

-- ----------------------------------------------------------------------------
-- Respaldo del modelo anterior.
--
-- Cada usuario existente pasa a ser miembro de su tienda con el rol que ya
-- tenia: nadie pierde ni gana permisos al aplicar esto. Idempotente, de modo
-- que reaplicar el script no duplica ni pisa un rol cambiado despues.
-- ----------------------------------------------------------------------------
INSERT INTO user_stores (user_id, store_id, company_id, role)
SELECT u.user_id, u.store_id, u.company_id, u.role
  FROM users u
    ON CONFLICT (user_id, store_id) DO NOTHING;
