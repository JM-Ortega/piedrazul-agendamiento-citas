#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# 01-init-databases.sh
#
# Este script se ejecuta únicamente en la primera inicialización del contenedor
# PostgreSQL, cuando el directorio PGDATA está vacío.
#
# Responsabilidad:
#   - Crear roles de base de datos.
#   - Crear schemas base para Piedrazul, extensiones y Keycloak.
#   - Configurar permisos mínimos.
#   - Configurar search_path por rol.
#   - Preparar PostgreSQL para Spring Boot, Flyway y Keycloak.
#
# Este script NO crea tablas, índices, funciones, vistas, extensiones concretas
# ni datos iniciales de Piedrazul. Todo eso pertenece a las migraciones Flyway.
#
# Contraseñas:
#   Puede recibirlas como variable normal:
#       APP_DB_PASSWORD=...
#
#   O como Docker secret:
#       APP_DB_PASSWORD_FILE=/run/secrets/app_db_password
#
#   Si se definen ambas para una misma clave, el script falla.
# -----------------------------------------------------------------------------

read_secret() {
    local var_name="$1"
    local file_var_name="${var_name}_FILE"

    local value="${!var_name:-}"
    local file_path="${!file_var_name:-}"

    if [[ -n "$value" && -n "$file_path" ]]; then
        echo "ERROR: define solo ${var_name} o ${file_var_name}, no ambos." >&2
        exit 1
    fi

    if [[ -n "$file_path" ]]; then
        if [[ ! -r "$file_path" ]]; then
            echo "ERROR: no se puede leer ${file_var_name}: ${file_path}" >&2
            exit 1
        fi

        value="$(< "$file_path")"
    fi

    if [[ -z "$value" ]]; then
        echo "ERROR: ${var_name} o ${file_var_name} es requerido." >&2
        exit 1
    fi

    printf '%s' "$value"
}

: "${POSTGRES_USER:?POSTGRES_USER es requerido}"
: "${POSTGRES_DB:?POSTGRES_DB es requerido}"

: "${APP_DB_USERNAME:?APP_DB_USERNAME es requerido}"
: "${MIGRATION_DB_USERNAME:?MIGRATION_DB_USERNAME es requerido}"
: "${KC_DB_USERNAME:?KC_DB_USERNAME es requerido}"

APP_DB_PASSWORD_VALUE="$(read_secret APP_DB_PASSWORD)"
MIGRATION_DB_PASSWORD_VALUE="$(read_secret MIGRATION_DB_PASSWORD)"
KC_DB_PASSWORD_VALUE="$(read_secret KC_DB_PASSWORD)"

echo "==> Inicializando infraestructura de PostgreSQL..."
echo "    Base de datos      : ${POSTGRES_DB}"
echo "    Rol bootstrap      : ${POSTGRES_USER}"
echo "    Rol aplicación     : ${APP_DB_USERNAME}"
echo "    Rol migraciones    : ${MIGRATION_DB_USERNAME}"
echo "    Rol Keycloak       : ${KC_DB_USERNAME}"

psql \
  -v ON_ERROR_STOP=1 \
  -v app_db="${POSTGRES_DB}" \
  -v app_role="${APP_DB_USERNAME}" \
  -v app_password="${APP_DB_PASSWORD_VALUE}" \
  -v migration_role="${MIGRATION_DB_USERNAME}" \
  -v migration_password="${MIGRATION_DB_PASSWORD_VALUE}" \
  -v kc_role="${KC_DB_USERNAME}" \
  -v kc_password="${KC_DB_PASSWORD_VALUE}" \
  --username "${POSTGRES_USER}" \
  --dbname "${POSTGRES_DB}" <<'EOSQL'

-- =====================================================
-- 1. Roles
-- =====================================================
-- app_role:
--   Usuario usado por Spring Boot en ejecución normal.
--   No debe crear ni modificar la estructura de la base.
--
-- migration_role:
--   Usuario usado por Flyway.
--   Puede crear/modificar objetos del schema de Piedrazul.
--
-- kc_role:
--   Usuario usado exclusivamente por Keycloak.
--   Trabaja dentro del schema keycloak.

SELECT NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_roles
    WHERE rolname = :'app_role'
) AS create_app_role
\gset

\if :create_app_role
    CREATE ROLE :"app_role" LOGIN PASSWORD :'app_password';
\endif

ALTER ROLE :"app_role"
    WITH LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    PASSWORD :'app_password';


SELECT NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_roles
    WHERE rolname = :'migration_role'
) AS create_migration_role
\gset

\if :create_migration_role
    CREATE ROLE :"migration_role" LOGIN PASSWORD :'migration_password';
\endif

ALTER ROLE :"migration_role"
    WITH LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    PASSWORD :'migration_password';


SELECT NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_roles
    WHERE rolname = :'kc_role'
) AS create_kc_role
\gset

\if :create_kc_role
    CREATE ROLE :"kc_role" LOGIN PASSWORD :'kc_password';
\endif

ALTER ROLE :"kc_role"
    WITH LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    PASSWORD :'kc_password';


-- =====================================================
-- 2. Schemas
-- =====================================================
-- piedrazul:
--   Schema principal de la aplicación.
--   Sus tablas, índices, funciones, constraints y la tabla
--   flyway_schema_history serán creados por Flyway.
--
-- extensions:
--   Schema destinado a extensiones PostgreSQL activadas por Flyway.
--
-- keycloak:
--   Schema exclusivo para Keycloak.

CREATE SCHEMA IF NOT EXISTS piedrazul AUTHORIZATION :"migration_role";
CREATE SCHEMA IF NOT EXISTS extensions AUTHORIZATION :"migration_role";
CREATE SCHEMA IF NOT EXISTS keycloak AUTHORIZATION :"kc_role";

ALTER SCHEMA piedrazul OWNER TO :"migration_role";
ALTER SCHEMA extensions OWNER TO :"migration_role";
ALTER SCHEMA keycloak OWNER TO :"kc_role";


-- =====================================================
-- 3. Permisos sobre la base de datos
-- =====================================================
-- Se revocan permisos generales y se conceden solo a los roles necesarios.
-- El rol de migración recibe CREATE sobre la base para que Flyway pueda
-- habilitar extensiones desde migraciones versionadas.

REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;

GRANT CONNECT ON DATABASE :"app_db" TO :"migration_role";
GRANT CONNECT ON DATABASE :"app_db" TO :"app_role";
GRANT CONNECT ON DATABASE :"app_db" TO :"kc_role";

GRANT CREATE ON DATABASE :"app_db" TO :"migration_role";


-- =====================================================
-- 4. Permisos sobre schemas
-- =====================================================

-- Flyway puede crear y modificar objetos en el schema de Piedrazul.
GRANT USAGE, CREATE ON SCHEMA piedrazul TO :"migration_role";

-- Flyway puede instalar extensiones en este schema.
GRANT USAGE, CREATE ON SCHEMA extensions TO :"migration_role";

-- La aplicación solo puede usar objetos existentes.
-- No puede crear tablas, índices ni funciones.
GRANT USAGE ON SCHEMA piedrazul TO :"app_role";
GRANT USAGE ON SCHEMA extensions TO :"app_role";

-- Keycloak administra su propio schema.
GRANT USAGE, CREATE ON SCHEMA keycloak TO :"kc_role";


-- =====================================================
-- 5. Search path
-- =====================================================
-- Define el orden de búsqueda de objetos cuando no se escribe el schema.
--
-- Spring/Flyway:
--   patients -> piedrazul.patients
--   unaccent -> extensions.unaccent
--
-- Keycloak:
--   Sus tablas se crean/buscan dentro de keycloak.

ALTER ROLE :"migration_role" IN DATABASE :"app_db"
    SET search_path = piedrazul, extensions, pg_catalog;

ALTER ROLE :"app_role" IN DATABASE :"app_db"
    SET search_path = piedrazul, extensions, pg_catalog;

ALTER ROLE :"kc_role" IN DATABASE :"app_db"
    SET search_path = keycloak, pg_catalog;


-- =====================================================
-- 6. Permisos por defecto para objetos creados por Flyway
-- =====================================================
-- Todo objeto nuevo creado por migration_role dentro de piedrazul queda
-- automáticamente disponible para app_role según su tipo.
--
-- Esto evita tener que hacer GRANT manual después de cada migración.

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA piedrazul
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA piedrazul
    GRANT USAGE, SELECT ON SEQUENCES TO :"app_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA piedrazul
    GRANT EXECUTE ON FUNCTIONS TO :"app_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA piedrazul
    GRANT USAGE ON TYPES TO :"app_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA extensions
    GRANT EXECUTE ON FUNCTIONS TO :"app_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA extensions
    GRANT USAGE ON TYPES TO :"app_role";


-- =====================================================
-- 7. Bloqueo del schema public
-- =====================================================
-- Evita que objetos de la aplicación, Keycloak o Flyway terminen
-- accidentalmente en public.

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM :"migration_role";
REVOKE ALL ON SCHEMA public FROM :"app_role";
REVOKE ALL ON SCHEMA public FROM :"kc_role";


-- =====================================================
-- 8. Finalización
-- =====================================================

\echo '==> Infraestructura PostgreSQL lista'
\echo '==> Schema piedrazul listo para Flyway'
\echo '==> Schema extensions listo para extensiones versionadas'
\echo '==> Schema keycloak listo para Keycloak'

EOSQL

echo "==> Inicialización de PostgreSQL completada correctamente."
