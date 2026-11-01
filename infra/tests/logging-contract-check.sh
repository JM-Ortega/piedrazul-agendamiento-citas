#!/usr/bin/env bash
# Contrato de LOG_LEVEL_MODULITH: group_vars → .env (env.j2) → Compose → backend. Renderiza el
# env.j2 real y evalúa la configuración real de Compose. No arranca contenedores ni usa red o
# credenciales reales. Lo que Spring hace con cada valor lo prueba
# ModulithLogLevelConfigurationTest; esto no prueba un backend desplegado.
#
# Uso: infra/tests/logging-contract-check.sh
# Requiere: docker compose (solo el CLI, sin daemon), ansible-playbook, jq, yq (mikefarah v4).
#
# Verifica:
#   1. El .env producido define LOG_LEVEL_MODULITH con el valor de group_vars.
#   2. Compose entrega al backend un valor explícito tal cual y WARN con la variable ausente o
#      vacía, sin avisos de variable no definida y sin afectar a los demás servicios.
#   3. application.yaml lee esa variable con WARN por defecto.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
GROUP_VARS="${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml"

for bin in docker ansible-playbook jq yq; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
[ -z "${LOG_LEVEL_MODULITH+x}" ] || { echo "LOG_LEVEL_MODULITH está definida en el entorno: Compose la usaría en vez del .env; ejecuta sin ella" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

# ── 1. Productor: env.j2 con los group_vars reales ──────────────────────────
mkdir -p "${TMP}/app/compose"
cp "${ROOT}"/infra/compose/*.yml "${TMP}/app/compose/"
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
        app_db_secrets: {POSTGRES_PASSWORD: sintetico, APP_DB_PASSWORD: sintetico, MIGRATION_DB_PASSWORD: sintetico, KC_DB_PASSWORD: sintetico}
        app_kc_backend_secret: sintetico
EOF
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"
printf 'BACKEND_IMAGE=ghcr.io/example/backend@sha256:%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\nKEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:%s\nTRAEFIK_IMAGE=docker.io/library/traefik@sha256:%s\n' \
  "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})" "$(printf 'c%.0s' {1..64})" "$(printf 'd%.0s' {1..64})" > "${TMP}/app/image.env"

if ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false \
   ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${TMP}/render.yml" \
     -e "env_template=${ANSIBLE_DIR}/roles/app/templates/env.j2" -e "env_out=${TMP}/env.rendered" \
     </dev/null > "${TMP}/render.log" 2>&1; then
  ok "env.j2 renderiza con group_vars de producción"
else
  ko "env.j2 renderiza con group_vars de producción"
  exit 1
fi
PRODUCED="$(yq -r '.log_level_modulith' "${GROUP_VARS}")"
check "group_vars declara log_level_modulith=WARN" test "${PRODUCED}" = WARN
check ".env producido define LOG_LEVEL_MODULITH" grep -qx "LOG_LEVEL_MODULITH=${PRODUCED}" "${TMP}/env.rendered"

# ── 2. Consumidor: Compose de producción ────────────────────────────────────
delivered() { # <archivo .env> → valor que recibiría el backend; deja los avisos en compose.err
  cp "$1" "${TMP}/app/.env"
  (cd "${TMP}/app/compose" && docker compose --project-name piedrazul-logging --file prod.yml \
    --env-file ../.env --env-file ../image.env config --format json 2>"${TMP}/compose.err") \
    | jq -r '.services.backend.environment.LOG_LEVEL_MODULITH'
}
no_unset_warning() { ! grep -q 'LOG_LEVEL_MODULITH' "${TMP}/compose.err"; }

sed 's/^LOG_LEVEL_MODULITH=.*/LOG_LEVEL_MODULITH=ERROR/' "${TMP}/env.rendered" > "${TMP}/env.explicit"
sed '/^LOG_LEVEL_MODULITH=/d' "${TMP}/env.rendered" > "${TMP}/env.absent"
sed 's/^LOG_LEVEL_MODULITH=.*/LOG_LEVEL_MODULITH=/' "${TMP}/env.rendered" > "${TMP}/env.empty"

check "Compose: el .env producido entrega WARN" test "$(delivered "${TMP}/env.rendered")" = WARN
check "Compose: un valor explícito se entrega tal cual" test "$(delivered "${TMP}/env.explicit")" = ERROR
check "Compose: variable ausente (.env anterior) entrega WARN" test "$(delivered "${TMP}/env.absent")" = WARN
check "Compose: variable ausente no produce aviso" no_unset_warning
check "Compose: variable vacía entrega WARN, no vacío" test "$(delivered "${TMP}/env.empty")" = WARN
check "Compose: solo el backend recibe LOG_LEVEL_MODULITH" \
  test "$(cd "${TMP}/app/compose" && docker compose --project-name piedrazul-logging --file prod.yml \
    --env-file ../.env --env-file ../image.env config --format json 2>/dev/null \
    | jq -r '[.services | to_entries[] | select(.value.environment.LOG_LEVEL_MODULITH != null) | .key] | join(",")')" = backend

# ── 3. Backend ──────────────────────────────────────────────────────────────
check "application.yaml: org.springframework.modulith ← LOG_LEVEL_MODULITH con WARN por defecto" \
  test "$(yq -r '.logging.level."org.springframework.modulith"' "${ROOT}/backend/src/main/resources/application.yaml")" = '${LOG_LEVEL_MODULITH:WARN}'

echo
if [ "${FAILS}" -eq 0 ]; then
  echo "Contrato de LOG_LEVEL_MODULITH: OK"
else
  echo "Contrato de LOG_LEVEL_MODULITH: ${FAILS} fallo(s)"
  exit 1
fi
