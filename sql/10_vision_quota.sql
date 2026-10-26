-- ==============================================================================
-- Kardo — 10_vision_quota.sql
-- Consumo diario de la vision por computadora.
--
-- ADITIVO: no borra ni modifica nada de 01–09. Se puede ejecutar varias veces.
--
-- Por que existe: el escaneo de guias es lo unico del sistema que cuesta dinero
-- fuera de nuestra infraestructura. Cualquiera con un token valido podia llamar
-- a /receipts/scan en bucle y agotar la cuota gratuita de la API key, que es
-- una sola para todo el despliegue: una tienda distraida -o un script- dejaba
-- sin recepcion documental a todas las demas.
--
-- El contador vive en la base y no en memoria a proposito: el contenedor de
-- Render se reinicia y hiberna, y un tope que se olvida con cada reinicio no es
-- un tope. Ademas deja el consumo a la vista, que es lo que permite decidir si
-- hay que pagar el tier siguiente.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS vision_usage (
    -- El dia es el de Lima, no el del servidor. Supabase corre en UTC: con
    -- CURRENT_DATE el cupo se renovaria a las 7 de la tarde, en plena tarde de
    -- trabajo, y el operario no entenderia por que a veces puede y a veces no.
    usage_date   DATE NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Lima')::date,
    store_id     INT  NOT NULL REFERENCES stores(store_id) ON DELETE CASCADE,

    -- Intentos, no exitos: una llamada que Gemini rechaza ya consumio cuota
    -- suya, de modo que descontarla solo al salir bien seria contar de menos.
    scans        INT NOT NULL DEFAULT 0 CHECK (scans >= 0),

    last_scan_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_scan_by VARCHAR(120),

    PRIMARY KEY (usage_date, store_id)
);

-- El tope global del dia suma todas las tiendas; la clave primaria ya ordena
-- por fecha, pero este indice evita leer tiendas de otros dias al sumar.
CREATE INDEX IF NOT EXISTS idx_vision_usage_fecha ON vision_usage (usage_date);
