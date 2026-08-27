#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# 01-init-db.sh
#
# Inicializa PostgreSQL en el primer arranque del contenedor
# (solo cuando PGDATA está vacío).
#
# Crea:
#   - rol de conexión para Keycloak
#   - schema "piedrazul" para la app
#   - schema "keycloak" para Keycloak
#
# Requiere:
#   - POSTGRES_USER
#   - POSTGRES_DB
#   - KC_DB_USERNAME
#   - KC_DB_PASSWORD
# -----------------------------------------------------------------------------

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${KC_DB_USERNAME:?KC_DB_USERNAME is required}"
: "${KC_DB_PASSWORD:?KC_DB_PASSWORD is required}"

echo "==> Initializing PostgreSQL schemas and roles..."
echo "    App role      : ${POSTGRES_USER}"
echo "    App database  : ${POSTGRES_DB}"
echo "    Keycloak role : ${KC_DB_USERNAME}"

psql \
  -v ON_ERROR_STOP=1 \
  -v app_role="${POSTGRES_USER}" \
  -v app_db="${POSTGRES_DB}" \
  -v kc_role="${KC_DB_USERNAME}" \
  -v kc_password="${KC_DB_PASSWORD}" \
  --username "${POSTGRES_USER}" \
  --dbname "${POSTGRES_DB}" <<'EOSQL'

-- 1. Crear rol de Keycloak si no existe
SELECT NOT EXISTS (
  SELECT 1
  FROM pg_catalog.pg_roles
  WHERE rolname = :'kc_role'
) AS should_create
\gset

\if :should_create
  CREATE ROLE :"kc_role" LOGIN PASSWORD :'kc_password';
\endif

-- 2. Crear schemas con owner correcto
CREATE SCHEMA IF NOT EXISTS piedrazul AUTHORIZATION :"app_role";
CREATE SCHEMA IF NOT EXISTS keycloak AUTHORIZATION :"kc_role";

-- 3. Reafirmar ownership por si ya existían
ALTER SCHEMA piedrazul OWNER TO :"app_role";
ALTER SCHEMA keycloak OWNER TO :"kc_role";

-- 4. Search path por rol, limitado a esta base
ALTER ROLE :"app_role" IN DATABASE :"app_db"
  SET search_path = piedrazul, pg_catalog;

ALTER ROLE :"kc_role" IN DATABASE :"app_db"
  SET search_path = keycloak, pg_catalog;

-- 5. Permisos mínimos sobre sus propios schemas
GRANT USAGE, CREATE ON SCHEMA piedrazul TO :"app_role";
GRANT USAGE, CREATE ON SCHEMA keycloak TO :"kc_role";

-- 6. Bloquear uso accidental del schema public
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM :"app_role";
REVOKE ALL ON SCHEMA public FROM :"kc_role";

-- 7. Log
\echo '==> Schema piedrazul listo'
\echo '==> Schema keycloak listo'

EOSQL

echo "==> PostgreSQL initialization completed successfully."
