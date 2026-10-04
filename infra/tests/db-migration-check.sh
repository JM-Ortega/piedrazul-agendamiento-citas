#!/usr/bin/env bash
# Convergencia de la base de producción sobre un PostgreSQL desechable, con el Compose de
# producción (infra/compose/prod.yml) y credenciales sintéticas que nunca se imprimen.
# No depende del contenido ni del número de migraciones de la imagen.
#
# Uso: infra/tests/db-migration-check.sh
# Entorno opcional:
#   BACKEND_IMAGE   imagen del backend a probar (por defecto: construye backend/)
#   POSTGRES_IMAGE  imagen de postgres a probar (por defecto: construye infra/postgres/)
#   KEEP=1          no borra el proyecto al terminar (inspección)
# Requiere: docker (compose v2), flock.
#
# Usa los nombres fijos de producción (contenedor postgres, red piedrazul_net) en un
# proyecto propio (piedrazul-dbcheck) y se niega a correr si ya existen fuera de él.
#
# Verifica:
#   1. PGDATA vacío → postgres inicializa → reconciliación → migración de un solo uso OK,
#      sin arrancar la aplicación (ni web, ni seeders).
#   2. Repetir la migración sobre un esquema al día es seguro (sin cambios en el historial).
#   3. app_role no tiene autoridad DDL; migration_role es dueño del esquema; kc_role no
#      accede al esquema de la aplicación.
#   3b. Historial de Flyway: app_role no puede INSERT/UPDATE/DELETE/TRUNCATE/DROP (intentos
#      reales) y conserva SELECT y DML sobre las tablas de la aplicación, tras: migración desde
#      cero, reconciliación repetida, migración repetida, rotación, y reconciliación sin
#      migración sobre un historial que había quedado con escritura.
#   4. Rotación: nuevas credenciales declaradas + reconciliación cambian las credenciales
#      reales sin recrear PGDATA; las anteriores dejan de autenticar; la migración funciona
#      con las nuevas.
#   5. Falla cerrado: credencial de migración incorrecta → exit != 0; lock tomado → la
#      migración no corre.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/compose/prod.yml"
PROJECT=piedrazul-dbcheck

for bin in docker flock; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

# ── Seguridad: nunca tocar un stack real ─────────────────────────────────────
owner_of() { docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$1" 2>/dev/null || true; }
if docker container inspect postgres >/dev/null 2>&1 && [ "$(owner_of postgres)" != "${PROJECT}" ]; then
  echo "Existe un contenedor 'postgres' fuera de ${PROJECT}; no se corre la prueba." >&2
  exit 2
fi
if docker network inspect piedrazul_net >/dev/null 2>&1 &&
   [ "$(docker network inspect --format '{{ index .Labels "com.docker.compose.project" }}' piedrazul_net)" != "${PROJECT}" ]; then
  echo "Existe la red 'piedrazul_net' fuera de ${PROJECT}; no se corre la prueba." >&2
  exit 2
fi

TMP="$(mktemp -d)"
LOCK="${TMP}/db.lock"
LOGDIR="${LOGDIR:-${TMP}/logs}"
mkdir -p "${LOGDIR}"

compose() {
  docker compose --project-name "${PROJECT}" --file "${COMPOSE_FILE}" \
    --env-file "${TMP}/.env" --env-file "${TMP}/image.env" "$@" 2> >(grep -v 'variable is not set' >&2)
}
cleanup() {
  if [ "${KEEP:-0}" != 1 ]; then
    compose --profile migrate down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "${TMP}"
  else
    echo "KEEP=1: proyecto ${PROJECT} y ${TMP} conservados"
  fi
}
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
refute() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ko "${desc}"; else ok "${desc}"; fi; }
must() { local desc="$1"; shift; if "$@" >/dev/null 2>"${TMP}/must.err"; then ok "${desc}"; else ko "${desc}"; mask < "${TMP}/must.err" | tail -20; exit 1; fi; }

synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
mask() { sed -E 's/synthetic-[a-z]+-[0-9a-f]+/<sintético>/g'; }

# ── Imágenes ─────────────────────────────────────────────────────────────────
if [ -z "${POSTGRES_IMAGE:-}" ]; then
  POSTGRES_IMAGE=piedrazul-check/postgres:local
  docker build -q -t "${POSTGRES_IMAGE}" "${ROOT}/infra/postgres" >/dev/null
fi
if [ -z "${BACKEND_IMAGE:-}" ]; then
  BACKEND_IMAGE=piedrazul-check/backend:local
  docker build -q -t "${BACKEND_IMAGE}" "${ROOT}/backend" >/dev/null
fi
printf 'BACKEND_IMAGE=%s\nPOSTGRES_IMAGE=%s\n' "${BACKEND_IMAGE}" "${POSTGRES_IMAGE}" > "${TMP}/image.env"
echo "backend=${BACKEND_IMAGE} postgres=${POSTGRES_IMAGE}"

# ── Entorno sintético (mismos nombres que group_vars de producción) ──────────
declare -A PW
write_env() {
  cat > "${TMP}/.env" <<EOF
DB_HOST=postgres
DB_PORT=5432
DB_NAME=piedrazul_db
DB_SCHEMA=piedrazul
POSTGRES_USER=postgres
POSTGRES_PASSWORD='${PW[postgres]}'
APP_DB_USERNAME=piedrazul_app
APP_DB_PASSWORD='${PW[app]}'
MIGRATION_DB_USERNAME=piedrazul_migration
MIGRATION_DB_PASSWORD='${PW[migration]}'
KC_DB_USERNAME=keycloak_user
KC_DB_PASSWORD='${PW[kc]}'
KC_DB_SCHEMA=keycloak
EOF
}
new_passwords() { for r in postgres app migration kc; do PW[$r]="$(synthetic "$r")"; done; }

# ── Operaciones (mismas que app/tasks/db_converge.yml y migrate.yml) ─────────
converge_db() { compose up --detach --wait --wait-timeout 120 postgres; }
reconcile() { flock --exclusive --timeout 30 "${LOCK}" docker exec --user postgres postgres /docker-entrypoint-initdb.d/01-init-databases.sh; }
migrate() { # <log>
  flock --exclusive --timeout "${LOCK_TIMEOUT:-30}" "${LOCK}" \
    docker compose --project-name "${PROJECT}" --file "${COMPOSE_FILE}" \
      --env-file "${TMP}/.env" --env-file "${TMP}/image.env" ${EXTRA_ENV_FILE:+--env-file "${EXTRA_ENV_FILE}"} \
      --profile migrate run --rm --no-deps migrate > "$1" 2>&1
}
env_migrate() { EXTRA_ENV_FILE="$1" migrate "$2"; }
sql() { docker exec --user postgres postgres psql -X -q -t -A -v ON_ERROR_STOP=1 -d piedrazul_db -c "$1"; }
# Autenticación real como la de los consumidores: otro contenedor por piedrazul_net
# (scram-sha-256). Dentro del contenedor postgres, socket y loopback son trust.
client() { # <rol> <contraseña> <sql>
  docker run --rm --network piedrazul_net -e PGPASSWORD="$2" --entrypoint psql "${POSTGRES_IMAGE}" \
    -X -q -t -A -v ON_ERROR_STOP=1 -h postgres -U "$1" -d piedrazul_db -c "$3"
}
login() { client "$1" "$2" 'select 1'; }
as_app() { client piedrazul_app "${PW[app]}" "$1"; }
history_count() { sql 'select count(*) from piedrazul.flyway_schema_history'; }

# Escrituras sin efecto (WHERE false) sobre una tabla: igual exigen el privilegio, así que
# prueban la autoridad del rol sin depender del contenido. Columna: la primera que admite
# INSERT explícito (sin identidad ALWAYS ni generada).
noop_dml() { # <tabla regclass> → INSERT/UPDATE/DELETE sin filas
  sql "select format('INSERT INTO %1\$s (%2\$I) SELECT %2\$I FROM %1\$s WHERE false; UPDATE %1\$s SET %2\$I = %2\$I WHERE false; DELETE FROM %1\$s WHERE false;', '$1'::regclass, attname)
       from pg_attribute where attrelid = '$1'::regclass and attnum > 0 and not attisdropped
       and attidentity <> 'a' and attgenerated = '' order by attnum limit 1"
}
HISTORY=piedrazul.flyway_schema_history
# Tablas de la aplicación (todas menos el historial) en las que app_role tiene DML completo
app_dml_tables() {
  sql "select c.oid::regclass from pg_class c join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'piedrazul' and c.relkind in ('r','p') and c.relname <> 'flyway_schema_history'
       and has_table_privilege('piedrazul_app', c.oid, 'INSERT') and has_table_privilege('piedrazul_app', c.oid, 'UPDATE')
       and has_table_privilege('piedrazul_app', c.oid, 'DELETE') order by 1"
}
# Propiedad: historial solo para migration_role; tablas de la aplicación con su DML.
history_protected() { # <etapa>
  local stage="$1" t stmts=""
  check "${stage}: app_role sin ningún privilegio sobre el historial (catálogo)" \
    test "$(sql "select has_table_privilege('piedrazul_app', '${HISTORY}', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')")" = f
  check "${stage}: migration_role dueño del historial" \
    test "$(sql "select pg_get_userbyid(relowner) from pg_class where oid = '${HISTORY}'::regclass")" = piedrazul_migration
  refute "${stage}: app_role no puede INSERT en el historial" as_app "BEGIN; INSERT INTO ${HISTORY} SELECT * FROM ${HISTORY} WHERE false; ROLLBACK"
  refute "${stage}: app_role no puede UPDATE el historial" as_app "BEGIN; UPDATE ${HISTORY} SET description = description WHERE false; ROLLBACK"
  refute "${stage}: app_role no puede DELETE del historial" as_app "BEGIN; DELETE FROM ${HISTORY} WHERE false; ROLLBACK"
  refute "${stage}: app_role no puede TRUNCATE el historial" as_app "BEGIN; TRUNCATE ${HISTORY}; ROLLBACK"
  refute "${stage}: app_role no puede DROP el historial" as_app "BEGIN; DROP TABLE ${HISTORY}; ROLLBACK"
  check "${stage}: app_role lee todas las tablas de la aplicación" \
    test "$(sql "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'piedrazul' and c.relkind in ('r','p') and c.relname <> 'flyway_schema_history' and not has_table_privilege('piedrazul_app', c.oid, 'SELECT')")" = 0
  local tables
  tables="$(app_dml_tables)"
  check "${stage}: app_role conserva DML completo en tablas de la aplicación ($(wc -w <<< "${tables}"))" test -n "${tables}"
  for t in ${tables}; do stmts+="$(noop_dml "$t") "; done
  check "${stage}: app_role ejecuta INSERT/UPDATE/DELETE reales sobre esas tablas" as_app "BEGIN; ${stmts} ROLLBACK"
}

# ── 1. Desde cero ────────────────────────────────────────────────────────────
compose --profile migrate down --volumes --remove-orphans >/dev/null 2>&1 || true
new_passwords
write_env

must "PGDATA vacío: postgres inicializa y queda healthy" converge_db
must "reconciliación sobre la base recién inicializada" reconcile
SYSID="$(sql 'select system_identifier from pg_control_system()')"

if migrate "${LOGDIR}/migrate-1.log"; then ok "migración de un solo uso desde cero (exit 0)"; else
  ko "migración de un solo uso desde cero (exit 0)"; mask < "${LOGDIR}/migrate-1.log" | tail -40; exit 1; fi
APPLIED="$(history_count)"
check "historial de Flyway con migraciones aplicadas (${APPLIED}) y todas exitosas" \
  test "${APPLIED}" -gt 0 -a "$(sql 'select count(*) from piedrazul.flyway_schema_history where not success')" = 0
check "la migración corre SchemaMigrationApplication" grep -q 'Started SchemaMigrationApplication' "${LOGDIR}/migrate-1.log"
refute "la migración no arranca la aplicación (sin web, BackendApplication ni seeders)" \
  grep -Eq 'Tomcat|BackendApplication|DataInitializer|piedrazul\.backend\.' "${LOGDIR}/migrate-1.log"
check "no queda contenedor de migración (--rm)" \
  test -z "$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" --filter 'label=com.docker.compose.service=migrate')"
history_protected "desde cero"
must "reconciliación repetida tras migrar (db_converge)" reconcile
history_protected "reconciliación repetida"

# ── 2. Repetir sobre un esquema al día ───────────────────────────────────────
if migrate "${LOGDIR}/migrate-2.log"; then ok "repetir la migración con el esquema al día (exit 0)"; else
  ko "repetir la migración con el esquema al día (exit 0)"; mask < "${LOGDIR}/migrate-2.log" | tail -40; fi
check "repetir no cambia el historial" test "$(history_count)" = "${APPLIED}"
history_protected "repetir migración"

# ── 3. Autoridades ───────────────────────────────────────────────────────────
check "app_role sin CREATE en piedrazul/extensions ni en la base" test "$(sql "select has_schema_privilege('piedrazul_app','piedrazul','CREATE') or has_schema_privilege('piedrazul_app','extensions','CREATE') or has_database_privilege('piedrazul_app','piedrazul_db','CREATE')")" = f
check "app_role no es dueño de ningún objeto ni tiene atributos privilegiados" test "$(sql "select (select count(*) from pg_class where relowner = 'piedrazul_app'::regrole) + (select count(*) from pg_namespace where nspowner = 'piedrazul_app'::regrole) + (select count(*) from pg_roles where rolname = 'piedrazul_app' and (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))")" = 0
refute "app_role no puede crear tablas (DDL rechazado)" as_app 'create table piedrazul.p1_ddl_probe (x int)'
refute "app_role no puede alterar el historial de Flyway (DDL rechazado)" as_app 'alter table piedrazul.flyway_schema_history add column p1_probe int'
check "migration_role es dueño de los schemas piedrazul y extensions" test "$(sql "select string_agg(pg_get_userbyid(nspowner), ',' order by nspname) from pg_namespace where nspname in ('piedrazul','extensions')")" = piedrazul_migration,piedrazul_migration
check "kc_role sin acceso al schema de la aplicación" test "$(sql "select has_schema_privilege('keycloak_user','piedrazul','USAGE')")" = f
check "kc_role dueño del schema keycloak" test "$(sql "select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'keycloak'")" = keycloak_user

# ── 4. Rotación sin recrear PGDATA ───────────────────────────────────────────
declare -A OLD
for r in "${!PW[@]}"; do OLD[$r]="${PW[$r]}"; done
new_passwords
write_env
must "postgres se recrea con las credenciales nuevas declaradas (PGDATA conservado)" converge_db
check "mismo cluster tras recrear el contenedor (system_identifier)" test "$(sql 'select system_identifier from pg_control_system()')" = "${SYSID}"
check "datos conservados (historial de Flyway intacto)" test "$(history_count)" = "${APPLIED}"
refute "antes de reconciliar, la credencial nueva de app_role todavía no autentica" login piedrazul_app "${PW[app]}"
must "reconciliación sin conocer las contraseñas anteriores" reconcile
declare -A ROLE=([postgres]=postgres [app]=piedrazul_app [migration]=piedrazul_migration [kc]=keycloak_user)
for r in postgres app migration kc; do
  check "${ROLE[$r]}: la credencial nueva autentica" login "${ROLE[$r]}" "${PW[$r]}"
  refute "${ROLE[$r]}: la credencial anterior ya no autentica" login "${ROLE[$r]}" "${OLD[$r]}"
done
history_protected "rotación + reconciliación"
check "tras reconciliar, app_role sigue sin autoridad DDL" test "$(sql "select has_schema_privilege('piedrazul_app','piedrazul','CREATE') or has_database_privilege('piedrazul_app','piedrazul_db','CREATE')")" = f
if migrate "${LOGDIR}/migrate-3.log"; then ok "migración con las credenciales rotadas (exit 0)"; else
  ko "migración con las credenciales rotadas (exit 0)"; mask < "${LOGDIR}/migrate-3.log" | tail -40; fi
check "el historial sigue igual tras rotar" test "$(history_count)" = "${APPLIED}"
history_protected "migración tras rotar"

# Camino sin migración: una base cuyo historial quedó con escritura para app_role (p. ej.
# migrada antes de esta protección). Solo la reconciliación, sin migrar, la retira.
sql "grant all on ${HISTORY} to piedrazul_app" >/dev/null
check "simulación: app_role con escritura sobre el historial" \
  test "$(sql "select has_table_privilege('piedrazul_app', '${HISTORY}', 'INSERT')")" = t
must "reconciliación sin migración" reconcile
history_protected "sin migración (solo reconciliación)"

# ── 5. Falla cerrado ─────────────────────────────────────────────────────────
printf "MIGRATION_DB_PASSWORD='%s'\n" "$(synthetic wrong)" > "${TMP}/wrong.env"
refute "credencial de migración incorrecta → la migración falla (exit != 0)" \
  env_migrate "${TMP}/wrong.env" "${LOGDIR}/migrate-wrong.log"
check "la falla queda visible en la salida (MIGRACIÓN: falló)" grep -q 'MIGRACIÓN: falló' "${LOGDIR}/migrate-wrong.log"
check "una migración fallida no cambia el historial" test "$(history_count)" = "${APPLIED}"
check "una migración fallida no deja contenedor" \
  test -z "$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" --filter 'label=com.docker.compose.service=migrate')"

# El lock lo retiene el proceso hijo (sleep): se espera a que termine, no se mata
flock --exclusive "${LOCK}" sleep 8 &
HOLDER=$!
sleep 1
if LOCK_TIMEOUT=2 migrate "${LOGDIR}/migrate-locked.log"; then
  ko "con el lock de base tomado, la migración no corre"
else
  refute "con el lock de base tomado, la migración no corre" grep -q 'SchemaMigrationApplication' "${LOGDIR}/migrate-locked.log"
fi
wait "${HOLDER}" || true
if migrate "${LOGDIR}/migrate-after-lock.log"; then ok "liberado el lock, la migración corre (exit 0)"; else
  ko "liberado el lock, la migración corre (exit 0)"; mask < "${LOGDIR}/migrate-after-lock.log" | tail -20; fi

if [ -n "${KEEP_LOGS:-}" ]; then
  mkdir -p "${KEEP_LOGS}"
  for f in "${LOGDIR}"/*.log; do mask < "$f" | grep -v 'variable is not set' > "${KEEP_LOGS}/$(basename "$f")" || true; done
fi

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "db-migration-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "db-migration-check: todo PASS (migraciones aplicadas por la imagen: ${APPLIED})"
