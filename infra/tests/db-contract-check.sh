#!/usr/bin/env bash
# Contrato de configuración de la base de producción: productor (env.j2) → consumidores
# (Compose) → orden de la cadena (Ansible, workflow). No arranca contenedores, no usa red ni
# credenciales reales: las contraseñas son sintéticas y nunca se imprimen.
#
# Uso: infra/tests/db-contract-check.sh
# Requiere: docker compose, ansible-playbook, jq, yq (mikefarah v4).
#
# Verifica:
#   1. env.j2 renderizado con los group_vars reales produce todas las variables que Compose
#      exige (${VAR:?}) y ninguna del contrato anterior (DB_USER, DB_PASSWORD, JPA_DDL_AUTO).
#   2. Compose de producción renderiza; cada servicio recibe solo su autoridad de la base:
#        postgres → todas (inicialización + reconciliación)
#        backend  → APP_DB_*, Flyway deshabilitado, JPA validate; sin MIGRATION_DB_*
#        migrate  → MIGRATION_DB_* + APP_DB_USERNAME (placeholder); sin APP_DB_PASSWORD
#        keycloak → KC_DB_*
#   3. migrate y backend resuelven a la misma imagen (BACKEND_IMAGE); migrate no arranca
#      con `up` (perfil) ni expone puertos o rutas.
#   4. Cada variable requerida de la base ausente o vacía hace fallar el render.
#   5. Orden: deploy.yml reconcilia → migra → arranca backend; setup.yml reconcilia antes de
#      Keycloak; el workflow migra solo un backend construido y no pasa credenciales de la
#      base al paso de deploy.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"

for bin in docker ansible-playbook jq yq; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

for key in POSTGRES_USER POSTGRES_PASSWORD APP_DB_USERNAME APP_DB_PASSWORD MIGRATION_DB_USERNAME \
  MIGRATION_DB_PASSWORD KC_DB_USERNAME KC_DB_PASSWORD DB_HOST DB_PORT DB_NAME DB_SCHEMA KC_DB_SCHEMA; do
  [ -z "${!key+x}" ] || { echo "${key} está definida en el entorno: Compose la usaría en vez del .env; ejecuta sin ella" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { # check <descripción> <comando...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi
}

synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }

DB_REQUIRED=(DB_HOST DB_PORT DB_NAME DB_SCHEMA POSTGRES_USER POSTGRES_PASSWORD
  APP_DB_USERNAME APP_DB_PASSWORD MIGRATION_DB_USERNAME MIGRATION_DB_PASSWORD
  KC_DB_USERNAME KC_DB_PASSWORD KC_DB_SCHEMA)

# ── 1. Productor: env.j2 con los group_vars reales ──────────────────────────
cat > "${TMP}/render.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.template:
        src: "{{ env_template }}"
        dest: "{{ env_out }}"
        mode: "0600"
      vars:
        app_db_secrets:
          POSTGRES_PASSWORD: "{{ lookup('env', 'POSTGRES_PASSWORD') }}"
          APP_DB_PASSWORD: "{{ lookup('env', 'APP_DB_PASSWORD') }}"
          MIGRATION_DB_PASSWORD: "{{ lookup('env', 'MIGRATION_DB_PASSWORD') }}"
          KC_DB_PASSWORD: "{{ lookup('env', 'KC_DB_PASSWORD') }}"
        app_kc_backend_secret: "{{ lookup('env', 'KC_BACKEND_CLIENT_SECRET') }}"
EOF
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"

# Sin export: Compose interpola primero desde el entorno del proceso y eso ocultaría
# variables ausentes del .env. Solo el render de env.j2 las recibe.
POSTGRES_PASSWORD="$(synthetic pg)"
APP_DB_PASSWORD="$(synthetic app)"
MIGRATION_DB_PASSWORD="$(synthetic mig)"
KC_DB_PASSWORD="$(synthetic kc)"
KC_BACKEND_CLIENT_SECRET="$(synthetic kcb)"

if env POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" APP_DB_PASSWORD="${APP_DB_PASSWORD}" \
     MIGRATION_DB_PASSWORD="${MIGRATION_DB_PASSWORD}" KC_DB_PASSWORD="${KC_DB_PASSWORD}" \
     KC_BACKEND_CLIENT_SECRET="${KC_BACKEND_CLIENT_SECRET}" \
     ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false \
   ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${TMP}/render.yml" \
     -e "env_template=${ANSIBLE_DIR}/roles/app/templates/env.j2" -e "env_out=${TMP}/.env" \
     </dev/null > "${TMP}/render.log" 2>&1; then
  ok "env.j2 renderiza con group_vars de producción"
else
  ko "env.j2 renderiza con group_vars de producción (ver salida sin secretos abajo)"
  grep -E 'ERROR|fatal' "${TMP}/render.log" | sed -E 's/synthetic-[a-z]+-[0-9a-f]+/<sintético>/g' || true
  exit 1
fi

env_has() { grep -Eq "^$1=.+" "${TMP}/.env"; }
for key in "${DB_REQUIRED[@]}"; do
  check ".env producido define ${key}" env_has "${key}"
done
for key in DB_USER DB_PASSWORD JPA_DDL_AUTO; do
  check ".env producido no define ${key} (contrato anterior)" bash -c "! grep -Eq '^${key}=' '${TMP}/.env'"
done

# Toda variable que Compose exige (${VAR:?}) la produce env.j2 o image.env
REQUIRED_BY_COMPOSE="$(grep -ohE '\$\{[A-Z_]+:\?' "${COMPOSE_DIR}"/*.yml | sed -E 's/^\$\{//; s/:\?$//' | sort -u)"
for key in ${REQUIRED_BY_COMPOSE}; do
  case "${key}" in
    BACKEND_IMAGE|POSTGRES_IMAGE) continue ;;  # image.env (release_images.yml)
  esac
  check "Compose exige ${key} y env.j2 lo produce" env_has "${key}"
done

# ── 2-3. Consumidores: Compose de producción ─────────────────────────────────
BACKEND_IMAGE="ghcr.io/example/backend@sha256:$(printf 'a%.0s' {1..64})"
POSTGRES_IMAGE="ghcr.io/example/postgres@sha256:$(printf 'b%.0s' {1..64})"
printf 'BACKEND_IMAGE=%s\nPOSTGRES_IMAGE=%s\n' "${BACKEND_IMAGE}" "${POSTGRES_IMAGE}" > "${TMP}/image.env"

compose_config() { # <env-file> [perfil]
  local profile=()
  [ -n "${2:-}" ] && profile=(--profile "$2")
  docker compose --project-name piedrazul-contract --file "${COMPOSE_DIR}/prod.yml" \
    --env-file "$1" --env-file "${TMP}/image.env" "${profile[@]}" config --format json
}

if compose_config "${TMP}/.env" migrate > "${TMP}/config.json" 2> "${TMP}/config.err"; then
  ok "Compose de producción renderiza sin variables de la base faltantes"
else
  ko "Compose de producción renderiza sin variables de la base faltantes"
  sed -E 's/synthetic-[a-z]+-[0-9a-f]+/<sintético>/g' "${TMP}/config.err"
  exit 1
fi

cfg() { jq -e "$1" "${TMP}/config.json"; }
envkeys() { jq -r --arg s "$1" '.services[$s].environment | keys[]' "${TMP}/config.json"; }
db_keys_of() { envkeys "$1" | grep -E '^(POSTGRES_(USER|PASSWORD)|APP_DB_|MIGRATION_DB_|KC_DB_(USERNAME|PASSWORD))' | sort | tr '\n' ' '; }

check "postgres recibe todas las autoridades de la base" \
  test "$(db_keys_of postgres)" = "APP_DB_PASSWORD APP_DB_USERNAME KC_DB_PASSWORD KC_DB_USERNAME MIGRATION_DB_PASSWORD MIGRATION_DB_USERNAME POSTGRES_PASSWORD POSTGRES_USER "
check "backend recibe solo APP_DB_* (sin MIGRATION_DB_*, KC_DB_*, POSTGRES_*)" \
  test "$(db_keys_of backend)" = "APP_DB_PASSWORD APP_DB_USERNAME "
check "migrate recibe MIGRATION_DB_* y solo el nombre de APP_DB_USERNAME" \
  test "$(db_keys_of migrate)" = "APP_DB_USERNAME MIGRATION_DB_PASSWORD MIGRATION_DB_USERNAME "
check "keycloak recibe solo KC_DB_*" \
  test "$(db_keys_of keycloak)" = "KC_DB_PASSWORD KC_DB_USERNAME "

check "backend: SPRING_FLYWAY_ENABLED=false" cfg '.services.backend.environment.SPRING_FLYWAY_ENABLED == "false"'
check "backend: JPA_DDL_AUTO=validate" cfg '.services.backend.environment.JPA_DDL_AUTO == "validate"'
check "backend: APP_DB_PASSWORD es la declarada" \
  cfg ".services.backend.environment.APP_DB_PASSWORD == \"${APP_DB_PASSWORD}\""
check "migrate: MIGRATION_DB_PASSWORD es la declarada" \
  cfg ".services.migrate.environment.MIGRATION_DB_PASSWORD == \"${MIGRATION_DB_PASSWORD}\""

check "migrate y backend = BACKEND_IMAGE (misma identidad exacta)" \
  cfg ".services.migrate.image == \"${BACKEND_IMAGE}\" and .services.backend.image == \"${BACKEND_IMAGE}\""
check "migrate: punto de entrada de solo migración" \
  cfg '.services.migrate.entrypoint | index("-Dloader.main=co.edu.unicauca.piedrazul.migration.SchemaMigrationApplication") != null'
check "migrate: perfil migrate, sin restart, puertos ni labels" \
  cfg '.services.migrate.profiles == ["migrate"] and .services.migrate.restart == "no" and (.services.migrate.ports // [] | length == 0) and (.services.migrate.expose // [] | length == 0) and (.services.migrate.labels // {} | length == 0)'

compose_config "${TMP}/.env" > "${TMP}/config-default.json" 2>/dev/null
check "sin el perfil migrate, el servicio migrate no existe para up" \
  jq -e '.services | has("migrate") | not' "${TMP}/config-default.json"

# ── 4. Requeridas: ausente o vacía → falla el render ─────────────────────────
for key in "${DB_REQUIRED[@]}"; do
  grep -v "^${key}=" "${TMP}/.env" > "${TMP}/missing.env"
  check "sin ${key}: Compose falla" bash -c "! docker compose --project-name piedrazul-contract --file '${COMPOSE_DIR}/prod.yml' --env-file '${TMP}/missing.env' --env-file '${TMP}/image.env' --profile migrate config -q"
  sed -E "s/^${key}=.*/${key}=/" "${TMP}/.env" > "${TMP}/empty.env"
  check "${key} vacía: Compose falla" bash -c "! docker compose --project-name piedrazul-contract --file '${COMPOSE_DIR}/prod.yml' --env-file '${TMP}/empty.env' --env-file '${TMP}/image.env' --profile migrate config -q"
done

# ── 5. Orden de la cadena ────────────────────────────────────────────────────
TASKS="${ANSIBLE_DIR}/roles/app/tasks"
task_index() { # <archivo> <nombre exacto de la tarea>
  yq -r "[.[].name] | to_entries[] | select(.value == \"$2\") | .key" "$1"
}
D_CONVERGE="$(task_index "${TASKS}/deploy.yml" 'Converger postgres y reconciliar autoridades de la base')"
D_MIGRATE="$(task_index "${TASKS}/deploy.yml" 'Migrar esquema con el backend seleccionado')"
D_BACKEND="$(task_index "${TASKS}/deploy.yml" 'Desplegar postgres y backend con identidades exactas')"
check "deploy.yml: reconciliación → migración → backend" \
  test -n "${D_CONVERGE}" -a -n "${D_MIGRATE}" -a -n "${D_BACKEND}" -a "${D_CONVERGE:-99}" -lt "${D_MIGRATE:-0}" -a "${D_MIGRATE:-99}" -lt "${D_BACKEND:-0}"
check "deploy.yml: migración condicionada a app_migrate" \
  test "$(yq -r '.[] | select(.name == "Migrar esquema con el backend seleccionado") | .when' "${TASKS}/deploy.yml")" = "app_migrate | bool"
S_CONVERGE="$(task_index "${TASKS}/setup.yml" 'Converger postgres y reconciliar autoridades de la base')"
S_INFRA="$(task_index "${TASKS}/setup.yml" 'Levantar servicios de infraestructura')"
check "setup.yml: reconciliación antes de Keycloak/Traefik" \
  test -n "${S_CONVERGE}" -a -n "${S_INFRA}" -a "${S_CONVERGE:-99}" -lt "${S_INFRA:-0}"
check "setup.yml: no levanta backend ni migrate" \
  bash -c "! yq -r '.[] | select(.\"community.docker.docker_compose_v2\") | .\"community.docker.docker_compose_v2\".services[]' '${TASKS}/setup.yml' | grep -Eq '^(backend|migrate)$'"
check "migrate.yml: la migración corre bajo flock de db_lock_file" \
  grep -q "\['flock', '--exclusive', '--timeout', db_lock_timeout | string, db_lock_file\]" "${TASKS}/migrate.yml"
check "db_converge.yml: la reconciliación corre bajo flock de db_lock_file" \
  test "$(yq -r '.[] | select(.name == "Reconciliar roles, credenciales y permisos de la base") | .["ansible.builtin.command"].argv[0:5] | join(" ")' "${TASKS}/db_converge.yml")" = 'flock --exclusive --timeout {{ db_lock_timeout }} {{ db_lock_file }}'

step() { yq -r ".jobs.production.steps[] | select(.id == \"$1\") | $2" "${WORKFLOW}"; }
check "workflow: app_migrate solo para un backend construido en el run" \
  test "$(step deploy '.env.APP_MIGRATE')" = "\${{ steps.select.outputs.backend_source == 'build' }}"
check "workflow: deploy pasa app_migrate a deploy.yml" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"deploy\") | .run' '${WORKFLOW}' | grep -q 'app_migrate=\${APP_MIGRATE}'"
check "workflow: el paso de deploy no recibe credenciales de la base" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.id == \"deploy\") | .env | keys[]' '${WORKFLOW}' | grep -Eq '^(POSTGRES_PASSWORD|APP_DB_PASSWORD|MIGRATION_DB_PASSWORD|KC_DB_PASSWORD|DB_PASSWORD)$'"
check "workflow: converge recibe las cuatro contraseñas de la base" \
  test "$(step converge '.env | keys | map(select(test("^(POSTGRES|APP_DB|MIGRATION_DB|KC_DB)_PASSWORD$"))) | sort | join(" ")')" = "APP_DB_PASSWORD KC_DB_PASSWORD MIGRATION_DB_PASSWORD POSTGRES_PASSWORD"
check "workflow: ningún paso usa secrets.DB_PASSWORD" \
  bash -c "! grep -q 'secrets.DB_PASSWORD' '${WORKFLOW}'"
STEP_IDS="$(yq -r '.jobs.production.steps[].id // ""' "${WORKFLOW}" | grep -n . )"
pos() { grep -E ":$1\$" <<< "${STEP_IDS}" | cut -d: -f1; }
check "workflow: deploy (con migración) antes de verificar y registrar el último éxito" \
  test "$(pos deploy)" -lt "$(pos running_identity)" -a "$(pos running_identity)" -lt "$(pos record)"
check "workflow: el registro del último éxito no corre tras un fallo" \
  test "$(step record '.if')" = "env.RUN_APP_DEPLOY == 'true'"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "db-contract-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "db-contract-check: todo PASS"
