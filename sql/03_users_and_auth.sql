-- ==============================================================================
-- Kardo — 03_users_and_auth.sql
-- Identidad de operarios y gerentes.
--
-- ADITIVO: no borra ni modifica nada de 01/02. Se puede ejecutar varias veces.
--
-- Por que existe: hasta ahora la tienda sobre la que se escribia llegaba como
-- parametro del cliente, de modo que cualquier token podia apuntar a cualquier
-- tienda. A partir de aqui la tienda es un atributo del usuario, el token la
-- transporta y el backend la toma de ahi.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS users (
    user_id       SERIAL PRIMARY KEY,
    company_id    INT NOT NULL REFERENCES companies(company_id) ON DELETE RESTRICT,

    -- Tienda a la que pertenece el usuario. Es el limite de lo que puede tocar:
    -- el backend ignora cualquier store_id que llegue en la peticion.
    store_id      INT NOT NULL REFERENCES stores(store_id) ON DELETE RESTRICT,

    email         VARCHAR(120) NOT NULL UNIQUE,
    full_name     VARCHAR(120) NOT NULL,

    -- BCrypt, nunca la contraseña en claro.
    password_hash VARCHAR(100) NOT NULL,

    role          VARCHAR(20) NOT NULL CHECK (role IN ('OPERATOR', 'MANAGER')),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- El login busca por correo en minusculas; el indice unico ya cubre la busqueda.
CREATE INDEX IF NOT EXISTS idx_users_store ON users (store_id) WHERE is_active;

-- ------------------------------------------------------------------------------
-- Usuarios de la tienda demo (store_id 999).
--
-- Son credenciales PUBLICAS y deliberadas: el evaluador entra con ellas a un
-- escenario que se borra y resiembra con un boton. Nunca uses este patron para
-- una tienda real.
--   demo@kardo.pe      / kardo-demo-2026   (MANAGER)
--   operario@kardo.pe  / kardo-demo-2026   (OPERATOR)
-- Hash BCrypt (coste 10) de 'kardo-demo-2026'.
-- ------------------------------------------------------------------------------
INSERT INTO users (company_id, store_id, email, full_name, password_hash, role)
VALUES
    (999, 999, 'demo@kardo.pe', 'Gerente Demo',
     '$2a$10$0VtKSaAKlGHg9L5XHg7nXe.LE37wFq3kCn16si7egncA9WUcjj1rC', 'MANAGER'),
    (999, 999, 'operario@kardo.pe', 'Operario Demo',
     '$2a$10$0VtKSaAKlGHg9L5XHg7nXe.LE37wFq3kCn16si7egncA9WUcjj1rC', 'OPERATOR')
ON CONFLICT (email) DO UPDATE
    SET full_name     = EXCLUDED.full_name,
        password_hash = EXCLUDED.password_hash,
        role          = EXCLUDED.role,
        is_active     = TRUE;
