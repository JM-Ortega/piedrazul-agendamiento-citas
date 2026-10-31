#!/usr/bin/env bash
# Contrato de configuración de notificaciones en producción: workflow → validación
# (setup.yml) → productor (env.j2) → consumidor (Compose → backend). No arranca contenedores
# ni usa red o credenciales reales: los valores son sintéticos y nunca se imprimen.
# El arranque del backend con cada configuración lo cubre SendGridConfigTest (backend).
#
# Uso: infra/tests/notifications-contract-check.sh
# Requiere: docker compose, ansible-playbook, jq, yq (mikefarah v4).
#
# Verifica:
#   1. group_vars de producción deshabilitan SendGrid.
#   2. .env producido (deshabilitado): NOTIFICATIONS_SENDGRID_ENABLED=false, SENDGRID_API_KEY
#      vacía aunque el runner tenga PIEDRAZUL_BACKEND_MAIL_SEND, sin NOTIFICATION_ENCRYPTION_KEY;
#      Compose entrega eso al backend y nada más.
#   3. .env producido (habilitado): SENDGRID_API_KEY es PIEDRAZUL_BACKEND_MAIL_SEND.
#   4. setup.yml: la validación de la clave corre antes de generar .env; deshabilitado pasa sin
#      clave; habilitado sin clave (o en blanco) falla sin mostrarla; habilitado con clave pasa.
#   5. Workflow: ningún paso referencia PIEDRAZUL_BACKEND_MAIL_SEND ni
#      NOTIFICATION_ENCRYPTION_KEY; la verificación de credenciales pasa sin ellas; el deploy no
#      recibe secretos.
#   6. Ningún componente del backend, Compose, Ansible ni .env.example declara
#      NOTIFICATION_ENCRYPTION_KEY.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
TASKS="${ANSIBLE_DIR}/roles/app/tasks"
GROUP_VARS="${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"

for bin in docker ansible-playbook jq yq; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
for key in SENDGRID_API_KEY NOTIFICATIONS_SENDGRID_ENABLED NOTIFICATION_ENCRYPTION_KEY PIEDRAZUL_BACKEND_MAIL_SEND; do
  [ -z "${!key+x}" ] || { echo "${key} está definida en el entorno: Compose o Ansible la usarían; ejecuta sin ella" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
fails() { ! "$@"; }
synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }

MAIL="$(synthetic mail)"
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"
playbook() { # <playbook> [args...] → ansible-playbook con el inventario de producción, local
  local pb="$1"; shift
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${pb}" "$@" </dev/null
}

# ── 1. group_vars ────────────────────────────────────────────────────────────
check "group_vars: SendGrid deshabilitado en producción" \
  test "$(yq -r '.notifications_sendgrid_enabled' "${GROUP_VARS}")" = "false"

# ── 2-3. .env producido y Compose ────────────────────────────────────────────
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
        app_db_secrets: {POSTGRES_PASSWORD: s, APP_DB_PASSWORD: s, MIGRATION_DB_PASSWORD: s, KC_DB_PASSWORD: s}
        app_kc_backend_secret: s
EOF
render() { # <salida> [args...] — el runner tiene ambas variables en todos los casos
  local out="$1"; shift
  ( export PIEDRAZUL_BACKEND_MAIL_SEND="${MAIL}" NOTIFICATION_ENCRYPTION_KEY="$(synthetic enc)"
    playbook "${TMP}/render.yml" -e "env_template=${ANSIBLE_DIR}/roles/app/templates/env.j2" \
      -e "env_out=${out}" "$@" ) > "${TMP}/render.log" 2>&1
}
if render "${TMP}/.env"; then
  ok "env.j2 renderiza con group_vars de producción"
else
  ko "env.j2 renderiza con group_vars de producción"
  grep -E 'ERROR|fatal' "${TMP}/render.log" | sed -E 's/synthetic-[a-z]+-[0-9a-f]+/<sintético>/g' || true
  exit 1
fi
check ".env: NOTIFICATIONS_SENDGRID_ENABLED=false" grep -qx 'NOTIFICATIONS_SENDGRID_ENABLED=false' "${TMP}/.env"
check ".env: SENDGRID_API_KEY vacía aunque el runner tenga PIEDRAZUL_BACKEND_MAIL_SEND" \
  bash -c "grep -qx 'SENDGRID_API_KEY=' '${TMP}/.env' && ! grep -q 'synthetic-mail-' '${TMP}/.env'"
check ".env: sin NOTIFICATION_ENCRYPTION_KEY (aunque el runner la tenga)" \
  bash -c "! grep -Eq '^NOTIFICATION_ENCRYPTION_KEY=' '${TMP}/.env' && ! grep -q 'synthetic-enc-' '${TMP}/.env'"

printf 'BACKEND_IMAGE=ghcr.io/example/backend@sha256:%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\nKEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:%s\nTRAEFIK_IMAGE=docker.io/library/traefik@sha256:%s\n' \
  "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})" "$(printf 'c%.0s' {1..64})" "$(printf 'd%.0s' {1..64})" > "${TMP}/image.env"
if docker compose --project-name piedrazul-notif-contract --file "${COMPOSE_DIR}/prod.yml" \
     --env-file "${TMP}/.env" --env-file "${TMP}/image.env" --profile migrate config --format json \
     > "${TMP}/config.json" 2> "${TMP}/compose.log"; then
  ok "Compose de producción renderiza con el .env producido"
else
  ko "Compose de producción renderiza con el .env producido"; exit 1
fi
check "Compose: el backend recibe NOTIFICATIONS_SENDGRID_ENABLED=false y SENDGRID_API_KEY vacía" \
  jq -e '.services.backend.environment | .NOTIFICATIONS_SENDGRID_ENABLED == "false" and .SENDGRID_API_KEY == ""' "${TMP}/config.json"
check "Compose: ningún servicio recibe NOTIFICATION_ENCRYPTION_KEY" \
  bash -c "! jq -r '.services[].environment // {} | keys[]' '${TMP}/config.json' | grep -qx NOTIFICATION_ENCRYPTION_KEY"
check "Compose: solo el backend recibe SENDGRID_API_KEY" \
  test "$(jq -r '.services | to_entries[] | select(.value.environment // {} | has("SENDGRID_API_KEY")) | .key' "${TMP}/config.json" | tr '\n' ' ')" = "backend "

if render "${TMP}/enabled.env" -e notifications_sendgrid_enabled=true; then
  check ".env habilitado: NOTIFICATIONS_SENDGRID_ENABLED=true" grep -qx 'NOTIFICATIONS_SENDGRID_ENABLED=true' "${TMP}/enabled.env"
  check ".env habilitado: SENDGRID_API_KEY es PIEDRAZUL_BACKEND_MAIL_SEND" grep -qxF "SENDGRID_API_KEY=${MAIL}" "${TMP}/enabled.env"
else
  ko "env.j2 renderiza con SendGrid habilitado"
fi

# ── 4. setup.yml: validación de la clave ─────────────────────────────────────
GUARD='Validar la clave de SendGrid si el proveedor está habilitado'
task_index() { yq -r '.[].name' "${TASKS}/setup.yml" | grep -nxF -- "$1" | head -1 | cut -d: -f1; }
check "setup.yml: valida la clave de SendGrid antes de generar .env" \
  bash -c '[ -n "$1" ] && [ "$1" -lt "$2" ]' _ "$(task_index "${GUARD}")" "$(task_index 'Generar .env desde template')"
check "setup.yml: la validación lleva no_log" \
  test "$(yq -r ".[] | select(.name == \"${GUARD}\") | .no_log" "${TASKS}/setup.yml")" = "true"
# La tarea real, aislada del resto de setup.yml
yq ".[] | select(.name == \"${GUARD}\") | [.]" "${TASKS}/setup.yml" > "${TMP}/guard.yml"
cat > "${TMP}/guard-play.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_tasks: ${TMP}/guard.yml
EOF
guard() { # <clave o "-" si falta> [args de ansible...]
  local mail="$1"; shift
  ( if [ "${mail}" = "-" ]; then unset PIEDRAZUL_BACKEND_MAIL_SEND; else export PIEDRAZUL_BACKEND_MAIL_SEND="${mail}"; fi
    playbook "${TMP}/guard-play.yml" "$@" ) > "${TMP}/guard.log" 2>&1
}
check "setup.yml: deshabilitado (group_vars) pasa sin PIEDRAZUL_BACKEND_MAIL_SEND" guard -
check "setup.yml: habilitado sin PIEDRAZUL_BACKEND_MAIL_SEND falla" fails guard - -e notifications_sendgrid_enabled=true
check "setup.yml: habilitado con PIEDRAZUL_BACKEND_MAIL_SEND en blanco falla" \
  fails guard "   " -e notifications_sendgrid_enabled=true
check "setup.yml: habilitado con PIEDRAZUL_BACKEND_MAIL_SEND pasa" \
  guard "${MAIL}" -e notifications_sendgrid_enabled=true
check "setup.yml: el log de la validación no muestra la clave" bash -c "! grep -q 'synthetic-mail-' '${TMP}/guard.log'"

# ── 5. Workflow ──────────────────────────────────────────────────────────────
WF_ENV_KEYS="$(yq -r '.. | select(tag == "!!map" and has("env")) | .env | keys | .[]' "${WORKFLOW}")"
WF_RUNS="$(yq -r '.. | select(tag == "!!map" and has("run")) | .run' "${WORKFLOW}")"
check "workflow: se leen las claves env y los scripts de los pasos" test -n "${WF_ENV_KEYS}" -a -n "${WF_RUNS}"
check "workflow: ningún paso lee ni recibe PIEDRAZUL_BACKEND_MAIL_SEND ni NOTIFICATION_ENCRYPTION_KEY" \
  bash -c "! grep -Eq 'secrets\.(PIEDRAZUL_BACKEND_MAIL_SEND|NOTIFICATION_ENCRYPTION_KEY)' '${WORKFLOW}' &&
    ! grep -Eqw 'PIEDRAZUL_BACKEND_MAIL_SEND|NOTIFICATION_ENCRYPTION_KEY' <<< \"\$1\$2\"" _ "${WF_ENV_KEYS}" "${WF_RUNS}"
DEPLOY_ENV="$(yq -r '.jobs.production.steps[] | select(.id == "deploy") | .env[]' "${WORKFLOW}")"
check "workflow: el deploy no recibe secretos" bash -c '[ -n "$1" ] && ! grep -q "secrets\." <<< "$1"' _ "${DEPLOY_ENV}"
yq -r '.jobs.production.steps[] | select(.id == "credentials") | .run' "${WORKFLOW}" > "${TMP}/credentials.sh"
credentials() { # [CLAVE=valor ...] → código de salida de la verificación de credenciales
  env -i PATH="${PATH}" HCP_WORKSPACE_ID=ws-abc DEPLOY_REQUIRED=true RUN_HOST_CONFIG=true RUN_APP_DEPLOY=true \
    TF_API_TOKEN=x ANSIBLE_SSH_KEY=x POSTGRES_PASSWORD=x APP_DB_PASSWORD=x MIGRATION_DB_PASSWORD=x KC_DB_PASSWORD=x \
    CLOUDFLARE_DNS_API_TOKEN=x KC_BACKEND_CLIENT_SECRET=x KC_AUTOMATION_CLIENT_SECRET=x "$@" \
    bash "${TMP}/credentials.sh" > "${TMP}/credentials.log" 2>&1
}
check "credenciales: pasan sin PIEDRAZUL_BACKEND_MAIL_SEND ni NOTIFICATION_ENCRYPTION_KEY" credentials
for key in TF_API_TOKEN ANSIBLE_SSH_KEY POSTGRES_PASSWORD APP_DB_PASSWORD MIGRATION_DB_PASSWORD KC_DB_PASSWORD \
  CLOUDFLARE_DNS_API_TOKEN KC_BACKEND_CLIENT_SECRET KC_AUTOMATION_CLIENT_SECRET; do
  check "credenciales: sin ${key} falla y lo nombra" \
    bash -c "! env -i PATH='${PATH}' HCP_WORKSPACE_ID=ws-abc DEPLOY_REQUIRED=true RUN_HOST_CONFIG=true RUN_APP_DEPLOY=true \
      TF_API_TOKEN=x ANSIBLE_SSH_KEY=x POSTGRES_PASSWORD=x APP_DB_PASSWORD=x MIGRATION_DB_PASSWORD=x KC_DB_PASSWORD=x \
      CLOUDFLARE_DNS_API_TOKEN=x KC_BACKEND_CLIENT_SECRET=x KC_AUTOMATION_CLIENT_SECRET=x ${key}= \
      bash '${TMP}/credentials.sh' > '${TMP}/c.log' 2>&1 && grep -q 'Faltan credenciales o variables: ${key}\$' '${TMP}/c.log'"
done

# ── 6. Sin consumidor de NOTIFICATION_ENCRYPTION_KEY ────────────────────────
check "ningún componente declara NOTIFICATION_ENCRYPTION_KEY (backend, Compose, Ansible, .env.example)" \
  bash -c "! grep -rqE 'NOTIFICATION_ENCRYPTION_KEY|notifications\.encryption' '${ROOT}/backend/src/main' '${COMPOSE_DIR}' '${ANSIBLE_DIR}/roles' '${ANSIBLE_DIR}/inventories' '${ROOT}/.env.example' '${ROOT}/docker-compose.yml'"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "notifications-contract-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "notifications-contract-check: todo PASS"
