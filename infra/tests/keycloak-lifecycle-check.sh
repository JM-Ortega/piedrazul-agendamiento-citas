#!/usr/bin/env bash
# Contrato estático del ciclo técnico de Keycloak: realm del repositorio → .env (env.j2) →
# servicios de Compose → workflow → tareas de Ansible. No arranca contenedores ni usa
# credenciales reales: los secretos son sintéticos y nunca se imprimen.
# La prueba ejecutable de P1/P2/P3/recuperación es keycloak-lifecycle-harness.sh.
#
# Uso: infra/tests/keycloak-lifecycle-check.sh
# Requiere: docker compose, ansible-playbook, jq, yq (mikefarah v4).
#
# Verifica:
#   1. El realm del repositorio no trae ningún secreto de cliente; el backend es
#      confidencial con service account.
#   2. .env producido: sin credenciales de administración de Keycloak ni de automatización;
#      KC_BACKEND_CLIENT_SECRET llega literal al backend aunque tenga ' " $ \ # o espacios
#      (sin lista de caracteres prohibidos; solo el trim de extremos ya existente).
#   3. Compose de producción: solo el backend recibe KC_BACKEND_CLIENT_SECRET; Keycloak no
#      recibe KC_BOOTSTRAP_ADMIN_*, KC_ADMIN_* ni secretos de clientes; nadie recibe
#      KC_AUTOMATION_CLIENT_SECRET; Keycloak arranca sin --import-realm.
#   4. Workflow: sin las credenciales retiradas ni el marker; recuperación explícita, solo
#      por dispatch y con configuración del servidor, pasada a converge; mismo environment y
#      concurrencia; cada secreto de la app que referencia tiene un consumidor en Ansible.
#   5. Ansible: el ciclo corre después de setup y antes de refresh_backend; setup no arranca
#      Keycloak; ninguna decisión depende del marker; las tareas con secretos llevan no_log.
#   6. Desarrollo: docker-compose.yml raíz con .env.example sigue siendo válido y aplica el
#      secreto local con keycloak-dev-secret.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
TASKS="${ANSIBLE_DIR}/roles/app/tasks"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
REALM="${ROOT}/infra/keycloak/realm/piedrazul-realm.json"

for bin in docker ansible-playbook jq yq; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
for key in KC_BACKEND_CLIENT_SECRET KC_AUTOMATION_CLIENT_SECRET KC_BOOTSTRAP_ADMIN_PASSWORD KC_ADMIN_PASSWORD; do
  [ -z "${!key+x}" ] || { echo "${key} está definida en el entorno: Compose la usaría en vez del .env; ejecuta sin ella" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }

# ── 1. Realm ─────────────────────────────────────────────────────────────────
check "realm: ningún cliente trae secret" jq -e '[.clients[] | select(has("secret"))] | length == 0' "${REALM}"
check "realm: piedrazul-backend confidencial, con service account, sin flujos interactivos" \
  jq -e '.clients[] | select(.clientId == "piedrazul-backend") | .publicClient == false and .serviceAccountsEnabled == true and .standardFlowEnabled == false and .directAccessGrantsEnabled == false' "${REALM}"
check "realm: el service account del backend declara sus roles técnicos" \
  jq -e '.users[] | select(.serviceAccountClientId == "piedrazul-backend") | (.clientRoles["realm-management"] | length) > 0' "${REALM}"
check "realm: sin usuarios humanos (solo service accounts)" jq -e '[.users[] | select(has("serviceAccountClientId") | not)] | length == 0' "${REALM}"

# ── 2. .env producido ────────────────────────────────────────────────────────
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
          POSTGRES_PASSWORD: "{{ lookup('env', 'S_PG') }}"
          APP_DB_PASSWORD: "{{ lookup('env', 'S_APP') }}"
          MIGRATION_DB_PASSWORD: "{{ lookup('env', 'S_MIG') }}"
          KC_DB_PASSWORD: "{{ lookup('env', 'S_KCDB') }}"
        app_kc_backend_secret: "{{ lookup('env', 'S_BACKEND') }}"
EOF
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"
S_BACKEND="$(synthetic kcb)"$'\'"$\\#& z'
# El runner sí tiene estas variables: deben quedar fuera del .env aunque estén presentes
if env S_PG="$(synthetic pg)" S_APP="$(synthetic app)" S_MIG="$(synthetic mig)" S_KCDB="$(synthetic kc)" \
     S_BACKEND="${S_BACKEND}" KC_AUTOMATION_CLIENT_SECRET="$(synthetic auto)" \
     KC_BOOTSTRAP_ADMIN_PASSWORD="$(synthetic boot)" KC_ADMIN_PASSWORD="$(synthetic adm)" \
     ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false \
   ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${TMP}/render.yml" \
     -e "env_template=${ANSIBLE_DIR}/roles/app/templates/env.j2" -e "env_out=${TMP}/.env" \
     </dev/null > "${TMP}/render.log" 2>&1; then
  ok "env.j2 renderiza con group_vars de producción"
else
  ko "env.j2 renderiza con group_vars de producción"
  grep -E 'ERROR|fatal' "${TMP}/render.log" | sed -E 's/synthetic-[a-z]+-[0-9a-f]+/<sintético>/g' || true
  exit 1
fi
check ".env: sin KC_BOOTSTRAP_ADMIN_*, KC_ADMIN_* ni KC_AUTOMATION_* (aunque el runner los tenga)" \
  bash -c "! grep -Eq '^(KC_BOOTSTRAP_ADMIN_|KC_ADMIN_|KC_AUTOMATION_)' '${TMP}/.env'"
check ".env: KC_BACKEND_CLIENT_SECRET entre comillas dobles con escape" \
  grep -qE '^KC_BACKEND_CLIENT_SECRET="' "${TMP}/.env"

# ── 3. Servicios de Compose ──────────────────────────────────────────────────
printf 'BACKEND_IMAGE=ghcr.io/example/backend@sha256:%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\n' \
  "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})" > "${TMP}/image.env"
docker compose --project-name piedrazul-kc-contract --file "${COMPOSE_DIR}/prod.yml" \
  --env-file "${TMP}/.env" --env-file "${TMP}/image.env" --profile migrate config --format json > "${TMP}/config.json"
holders() { jq -r --arg k "$1" '.services | to_entries[] | select(.value.environment // {} | has($k)) | .key' "${TMP}/config.json" | sort | tr '\n' ' '; }
check "Compose: solo backend recibe KC_BACKEND_CLIENT_SECRET" test "$(holders KC_BACKEND_CLIENT_SECRET)" = "backend "
check "Compose: el backend recibe el secreto declarado, literal (con ' \" \$ \\\\ # y espacio)" \
  jq -e --arg s "${S_BACKEND}" '(.services.backend.environment.KC_BACKEND_CLIENT_SECRET | gsub("[$][$]"; "$")) == $s' "${TMP}/config.json"
check "Compose: ningún servicio recibe KC_AUTOMATION_*, KC_ADMIN_* ni KC_BOOTSTRAP_ADMIN_*" \
  bash -c "! jq -r '.services[].environment // {} | keys[]' '${TMP}/config.json' | grep -Eq '^(KC_AUTOMATION_|KC_ADMIN_|KC_BOOTSTRAP_ADMIN_)'"
check "Compose: keycloak solo recibe credenciales de su base (KC_DB_PASSWORD)" \
  test "$(jq -r '.services.keycloak.environment | keys[] | select(test("PASSWORD|SECRET|TOKEN|KEY"))' "${TMP}/config.json" | tr '\n' ' ')" = "KC_DB_PASSWORD "
check "Compose: keycloak de producción arranca con start, sin --import-realm (ni start-dev)" \
  jq -e '.services.keycloak.command == ["start"]' "${TMP}/config.json"

# ── 4. Workflow ──────────────────────────────────────────────────────────────
check "workflow: sin KC_BOOTSTRAP_ADMIN_PASSWORD ni KC_ADMIN_PASSWORD" \
  bash -c "! grep -Eq 'KC_BOOTSTRAP_ADMIN_PASSWORD|KC_ADMIN_PASSWORD' '${WORKFLOW}'"
check "workflow: sin marker ni force_init_keycloak" \
  bash -c "! grep -Eqi 'keycloak_initialized|force_init_keycloak|kc_marker' '${WORKFLOW}'"
check "workflow: input keycloak_recovery booleano, false por defecto" \
  test "$(yq -r '.on.workflow_dispatch.inputs.keycloak_recovery | .type + "/" + (.default | tostring)' "${WORKFLOW}")" = "boolean/false"
INTENT="$(yq -r '.jobs.classify.steps[] | select(.id == "intent") | .run' "${WORKFLOW}")"
check "workflow: push nunca autoriza recuperación" grep -q 'echo "keycloak_recovery=false"' <<< "${INTENT}"
check "workflow: recuperación exige run_host_config=true" \
  grep -q 'if \[ "${IN_KEYCLOAK_RECOVERY}" = "true" \] && \[ "${IN_HOST_CONFIG}" != "true" \]' <<< "${INTENT}"
check "workflow: converge recibe keycloak_recovery" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"converge\") | .run' '${WORKFLOW}' | grep -q 'keycloak_recovery=\${KEYCLOAK_RECOVERY}'"
check "workflow: production sigue en environment production-hetzner con su concurrencia" \
  test "$(yq -r '.jobs.production | .environment + "/" + .concurrency.group + "/" + (.concurrency."cancel-in-progress" | tostring)' "${WORKFLOW}")" = "production-hetzner/piedrazul-production-hetzner/false"
check "workflow: converge recibe KC_BACKEND_CLIENT_SECRET y KC_AUTOMATION_CLIENT_SECRET" \
  test "$(yq -r '.jobs.production.steps[] | select(.id == "converge") | .env | keys | map(select(test("^KC_(BACKEND|AUTOMATION)_CLIENT_SECRET$"))) | sort | join(" ")' "${WORKFLOW}")" = "KC_AUTOMATION_CLIENT_SECRET KC_BACKEND_CLIENT_SECRET"
check "workflow: deploy no recibe credenciales técnicas de Keycloak" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.id == \"deploy\") | .env | keys[]' '${WORKFLOW}' | grep -Eq '^KC_'"
# Todo secreto de la app que el job production referencia tiene un consumidor en Ansible
MISSING_CONSUMER=""
for s in $(grep -oE 'secrets\.[A-Z_]+' "${WORKFLOW}" | sed 's/secrets\.//' | sort -u); do
  case "$s" in GITHUB_TOKEN|TF_API_TOKEN|ANSIBLE_SSH_KEY) continue ;; esac
  grep -rqE "lookup\('env', '${s}'\)" "${ANSIBLE_DIR}/roles" || MISSING_CONSUMER+="${s} "
done
check "workflow: cada secreto de la app referenciado tiene consumidor en Ansible (${MISSING_CONSUMER:-todos})" test -z "${MISSING_CONSUMER}"

# ── 5. Ansible ───────────────────────────────────────────────────────────────
CONVERGE_TASKS="$(yq -r '.[0].tasks[] | .["ansible.builtin.include_role"].tasks_from' "${ANSIBLE_DIR}/playbooks/converge.yml" | tr '\n' ' ')"
check "converge.yml: setup → keycloak_lifecycle → refresh_backend" \
  test "${CONVERGE_TASKS}" = "setup.yml keycloak_lifecycle.yml refresh_backend.yml "
check "setup.yml: no arranca Keycloak" \
  bash -c "! yq -r '.[] | select(.\"community.docker.docker_compose_v2\") | .\"community.docker.docker_compose_v2\".services[]' '${TASKS}/setup.yml' | grep -qx keycloak"
check "ningún task file decide por el marker (solo se retira)" \
  test "$(grep -rl 'keycloak_initialized' "${TASKS}" | xargs -r -n1 basename | tr '\n' ' ')" = "keycloak_lifecycle.yml "
check "el marker solo aparece con state: absent" \
  test "$(yq -r '.[] | select(.["ansible.builtin.file"].path // "" | test("keycloak_initialized")) | .["ansible.builtin.file"].state' "${TASKS}/keycloak_lifecycle.yml")" = "absent"
check "init_keycloak.yml retirado" test ! -e "${TASKS}/init_keycloak.yml"
UNLOGGED="$(for f in "${TASKS}"/keycloak_*.yml; do
  yq -r '.. | select(type == "!!map" and has("name")) | select((.["community.docker.docker_container_exec"].env // {} | keys | map(select(test("SECRET"))) | length > 0) or (.environment // {} | keys | map(select(test("SECRET"))) | length > 0)) | select(.no_log != true) | .name' "$f"
done)"
check "toda tarea que pasa secretos por entorno lleva no_log (${UNLOGGED:-ninguna sin no_log})" test -z "${UNLOGGED}"
check "keycloak-admin.sh: kcadm nunca recibe --secret/--password en argv" \
  bash -c "! grep -Eq -- '--(secret|password)( |=)' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh'"
check "keycloak-admin.sh: ningún secreto se interpola en JSON desde bash" \
  bash -c "! grep -Eq '\"secret\": ?\"%s' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh'"
check "limpieza: solo autoridad temporal propia (nunca la ajena)" \
  bash -c "grep -q 'KCL_TEMPORARY_PRINCIPALS: \"{{ app_kc_observed.temporary_owned' '${TASKS}/keycloak_bootstrap.yml' && ! grep -q 'temporary_foreign | join' '${TASKS}/keycloak_bootstrap.yml'"
check "importación del realm solo dentro del evento P2" \
  test "$(yq -r '.. | select(type == "!!map" and has("name")) | select(.name == "Importar el realm de la aplicación (solo P2)") | .when' "${TASKS}/keycloak_bootstrap.yml")" = "app_kc_event == 'fresh'"
check "divergencias bloqueantes fallan antes de arrancar Keycloak" \
  bash -c "test \$(yq -r '[.[].name] | index(\"Fallar cerrado ante divergencias bloqueantes (P3, escalar)\")' '${TASKS}/keycloak_lifecycle.yml') -lt \$(yq -r '[.[].name] | index(\"Levantar Keycloak sobre la base ya inicializada\")' '${TASKS}/keycloak_lifecycle.yml')"
check "salida: la lectura de la base no selecciona usernames (conteos, ids internos y clientIds técnicos)" \
  bash -c "! grep -v '^ *--' '${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql' | grep -qi 'username'"
check "keycloak-state.sql: no selecciona columnas de secretos" \
  bash -c "! grep -Eiq '\\bsecret\\b|credential' <(grep -v '^--' '${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql')"

# ── 6. Desarrollo ────────────────────────────────────────────────────────────
if docker compose --project-name piedrazul-kc-dev-contract --file "${ROOT}/docker-compose.yml" \
     --env-file "${ROOT}/.env.example" config --format json > "${TMP}/dev.json" 2> "${TMP}/dev.err"; then
  ok "desarrollo: docker-compose.yml raíz renderiza con .env.example"
else
  ko "desarrollo: docker-compose.yml raíz renderiza con .env.example"
fi
check "desarrollo: keycloak con start-dev y admin de bootstrap de desarrollo" \
  jq -e '.services.keycloak.command == ["start-dev", "--import-realm"] and (.services.keycloak.environment | has("KC_BOOTSTRAP_ADMIN_USERNAME"))' "${TMP}/dev.json"
check "desarrollo: keycloak-dev-secret depende de keycloak healthy y usa la misma imagen" \
  jq -e '.services["keycloak-dev-secret"].depends_on.keycloak.condition == "service_healthy" and .services["keycloak-dev-secret"].image == .services.keycloak.image' "${TMP}/dev.json"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "keycloak-lifecycle-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "keycloak-lifecycle-check: todo PASS"
