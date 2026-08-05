-- =============================================================================
-- IOL Lakehouse — Initialisation PostgreSQL
-- Exécuté automatiquement au premier démarrage du conteneur PostgreSQL
-- =============================================================================

-- Schémas Médaillon
CREATE SCHEMA IF NOT EXISTS bronze;  -- données brutes immuables (Hop écrit ici)
CREATE SCHEMA IF NOT EXISTS silver;  -- données nettoyées et normalisées (Hop écrit ici)
CREATE SCHEMA IF NOT EXISTS gold;    -- données agrégées pour le reporting (Hop écrit ici)

-- Auth applicative Spring/JPA.
-- Les métadonnées ETL restent dans MongoDB, mais les utilisateurs sont stockés
-- dans PostgreSQL afin de partager la même base que l'API.
CREATE TABLE IF NOT EXISTS public.users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_active
    ON public.refresh_tokens(user_id, revoked);

CREATE TABLE IF NOT EXISTS public.password_reset_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_password_reset_user_active
    ON public.password_reset_codes(user_id, consumed, created_at DESC);

-- Commentaires pour documentation
COMMENT ON SCHEMA bronze IS 'Zone Bronze : données brutes extraites par Hop, immuables';
COMMENT ON SCHEMA silver IS 'Zone Silver : données nettoyées et normalisées par Hop';
COMMENT ON SCHEMA gold   IS 'Zone Gold : agrégats décisionnels pour Apache Superset';
COMMENT ON TABLE public.users IS 'Utilisateurs applicatifs IOL ETL Platform';
