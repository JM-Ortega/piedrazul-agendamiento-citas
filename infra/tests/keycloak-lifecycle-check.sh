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
#   1. El realm del repositorio no trae ningún secreto de cliente ni material de claves; el
#      backend es confidencial con service account y roles exactos; el frontend solo admite
#      Authorization Code con PKCE S256 desde producción y desarrollo local; guarda los eventos
#      de la auditoría de inicios de sesión. (En 5: nace con la política de contraseñas aprobada,
#      alineada con los límites del backend, y su admin-cli sin password grant.)
#   2. .env producido: sin credenciales de administración de Keycloak, de automatización ni la
#      contraseña inicial del administrador humano;
#      KC_BACKEND_CLIENT_SECRET llega literal al backend aunque tenga ' " $ \ # o espacios
#      (sin lista de caracteres prohibidos; solo el trim de extremos ya existente).
#   3. Compose de producción: solo el backend recibe KC_BACKEND_CLIENT_SECRET; Keycloak no
#      recibe KC_BOOTSTRAP_ADMIN_*, KC_ADMIN_* ni secretos de clientes; nadie recibe
#      KC_AUTOMATION_CLIENT_SECRET ni KC_PLATFORM_ADMIN_*; Keycloak arranca sin --import-realm.
#   4. Workflow: sin las credenciales retiradas ni el marker; recuperación explícita, solo
#      por dispatch y con configuración del servidor, pasada a converge; mismo environment y
#      concurrencia; cada secreto de la app que referencia tiene un consumidor en Ansible; la
#      contraseña inicial del administrador humano solo llega a converge y a su validación
#      temprana antes del Apply (kc_admin_preflight), y no es obligatoria fuera de ellas.
#   5. Ansible: el ciclo corre después de setup y antes de refresh_backend; setup no arranca
#      Keycloak; ninguna decisión depende del marker; las tareas con secretos o contraseñas
#      llevan no_log; el administrador humano solo se crea en el evento P2, con su contraseña
#      validada antes del evento, y la lectura de su estado no expone nombres ni credenciales.
#   6. Desarrollo: docker-compose.yml raíz con .env.example sigue siendo válido y aplica el
#      secreto local con keycloak-dev-secret.
#   8. Correo nativo: el realm nace sin SMTP ni recuperación; autoridad exacta, sin master; STARTTLS
#      obligatorio; Keycloak no recibe claves de correo; la contraseña SMTP solo viaja por stdin
#      con no_log y escritura parcial; solo corre desde el final del ciclo; deploy no lo toca.
#   9. Alcance de roles explícito: backend y frontend con Full Scope Allowed deshabilitado y
#      exactamente sus roles (backend: sus roles asignados; frontend: los roles de negocio, los
#      mismos que convierte Spring); clientes integrados sin cambios y sin política global; el
#      alcance solo se escribe en el evento con autoridad temporal, en orden seguro. Con las
#      tareas reales de Ansible y lecturas sintéticas de la base: el orden no es divergencia;
#      Full Scope habilitado, roles que faltan o sobran → P3 recuperable con motivo; frontend
#      ausente → bloqueante; un rol de negocio nuevo sin alcance falla antes de tocar Keycloak.
#   7. Frontend del tema de login: el realm mantiene deshabilitado el registro nativo; solo
#      keycloak recibe FRONTEND_PUBLIC_URL (https://<frontend_public_domain> en producción,
#      el valor de .env.example en desarrollo); sin ella Compose falla, y setup.yml valida
#      el dominio antes de copiar Compose o escribir .env.
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
for key in KC_BACKEND_CLIENT_SECRET KC_AUTOMATION_CLIENT_SECRET KC_PLATFORM_ADMIN_INITIAL_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD KC_ADMIN_PASSWORD; do
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
FRONTEND_CLIENT='.clients[] | select(.clientId == "piedrazul-frontend")'
check "realm: redirecciones del frontend, solo producción y desarrollo local" \
  jq -e "${FRONTEND_CLIENT} | (.redirectUris | sort) == [\"http://localhost:4200/*\", \"https://piedrazul.org/*\"]" "${REALM}"
check "realm: orígenes del frontend, solo producción y desarrollo local" \
  jq -e "${FRONTEND_CLIENT} | (.webOrigins | sort) == [\"http://localhost:4200\", \"https://piedrazul.org\"]" "${REALM}"
check "realm: redirecciones de cierre de sesión, solo producción y desarrollo local" \
  jq -e "${FRONTEND_CLIENT} | .attributes[\"post.logout.redirect.uris\"] == \"http://localhost:4200##https://piedrazul.org\"" "${REALM}"
check "realm: el frontend es público, solo Authorization Code con PKCE S256" \
  jq -e "${FRONTEND_CLIENT} | .publicClient and .standardFlowEnabled and (.implicitFlowEnabled | not) and (.directAccessGrantsEnabled | not) and (.serviceAccountsEnabled | not) and .attributes[\"pkce.code.challenge.method\"] == \"S256\"" "${REALM}"
check "realm: ningún dominio retirado" bash -c "! grep -q 'narvaezlab' '${REALM}'"
check "realm: roles técnicos exactos de la cuenta de servicio del backend" \
  jq -e '.users[] | select(.serviceAccountClientId == "piedrazul-backend") | (.clientRoles | keys) == ["realm-management"] and (.clientRoles["realm-management"] | sort) == ["manage-users", "query-users", "view-events", "view-realm", "view-users"]' "${REALM}"
check "realm: guarda LOGIN y LOGIN_ERROR para la auditoría de inicios de sesión" \
  jq -e '.eventsEnabled and (.enabledEventTypes | sort) == ["LOGIN", "LOGIN_ERROR"]' "${REALM}"
check "realm: claves generadas por Keycloak, sin material en el repositorio" \
  jq -e '[.components["org.keycloak.keys.KeyProvider"][].config | keys[] | select(. != "priority" and . != "algorithm")] | length == 0' "${REALM}"

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
     KC_PLATFORM_ADMIN_INITIAL_PASSWORD="$(synthetic human)" \
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
check ".env: sin KC_BOOTSTRAP_ADMIN_*, KC_ADMIN_*, KC_AUTOMATION_* ni KC_PLATFORM_ADMIN_* (aunque el runner los tenga)" \
  bash -c "! grep -Eq '^(KC_BOOTSTRAP_ADMIN_|KC_ADMIN_|KC_AUTOMATION_|KC_PLATFORM_ADMIN_)' '${TMP}/.env' && ! grep -q 'synthetic-human-' '${TMP}/.env'"
check ".env: KC_BACKEND_CLIENT_SECRET entre comillas dobles con escape" \
  grep -qE '^KC_BACKEND_CLIENT_SECRET="' "${TMP}/.env"

# ── 3. Servicios de Compose ──────────────────────────────────────────────────
printf 'BACKEND_IMAGE=ghcr.io/example/backend@sha256:%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\nKEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:%s\nTRAEFIK_IMAGE=docker.io/library/traefik@sha256:%s\n' \
  "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})" "$(printf 'c%.0s' {1..64})" "$(printf 'd%.0s' {1..64})" > "${TMP}/image.env"
docker compose --project-name piedrazul-kc-contract --file "${COMPOSE_DIR}/prod.yml" \
  --env-file "${TMP}/.env" --env-file "${TMP}/image.env" --profile migrate config --format json > "${TMP}/config.json"
holders() { jq -r --arg k "$1" '.services | to_entries[] | select(.value.environment // {} | has($k)) | .key' "${TMP}/config.json" | sort | tr '\n' ' '; }
check "Compose: solo backend recibe KC_BACKEND_CLIENT_SECRET" test "$(holders KC_BACKEND_CLIENT_SECRET)" = "backend "
check "Compose: el backend recibe el secreto declarado, literal (con ' \" \$ \\\\ # y espacio)" \
  jq -e --arg s "${S_BACKEND}" '(.services.backend.environment.KC_BACKEND_CLIENT_SECRET | gsub("[$][$]"; "$")) == $s' "${TMP}/config.json"
check "Compose: ningún servicio recibe KC_AUTOMATION_*, KC_ADMIN_*, KC_BOOTSTRAP_ADMIN_* ni KC_PLATFORM_ADMIN_*" \
  bash -c "! jq -r '.services[].environment // {} | keys[]' '${TMP}/config.json' | grep -Eq '^(KC_AUTOMATION_|KC_ADMIN_|KC_BOOTSTRAP_ADMIN_|KC_PLATFORM_ADMIN_)'"
check "Compose: keycloak solo recibe credenciales de su base (KC_DB_PASSWORD)" \
  test "$(jq -r '.services.keycloak.environment | keys[] | select(test("PASSWORD|SECRET|TOKEN|KEY"))' "${TMP}/config.json" | tr '\n' ' ')" = "KC_DB_PASSWORD "
check "Compose: keycloak de producción arranca con start, sin --import-realm (ni start-dev)" \
  jq -e '.services.keycloak.command == ["start"]' "${TMP}/config.json"

# ── 7a. Frontend del tema de login (producción) ──────────────────────────────
# frontend_public_domain (host_vars) es "{{ base_domain }}": el valor esperado sale de group_vars
FRONTEND_EXPECTED="https://$(yq -r '.base_domain' "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")"
check "realm: registro nativo de Keycloak deshabilitado (registrationAllowed=false)" \
  jq -e '.registrationAllowed == false' "${REALM}"
check "Compose: solo keycloak recibe FRONTEND_PUBLIC_URL" test "$(holders FRONTEND_PUBLIC_URL)" = "keycloak "
check "Compose: keycloak recibe FRONTEND_PUBLIC_URL=https://<frontend_public_domain> de env.j2 (${FRONTEND_EXPECTED})" \
  bash -c "grep -qxF 'FRONTEND_PUBLIC_URL=${FRONTEND_EXPECTED}' '${TMP}/.env' && jq -e --arg v '${FRONTEND_EXPECTED}' '.services.keycloak.environment.FRONTEND_PUBLIC_URL == \$v' '${TMP}/config.json'"
grep -v '^FRONTEND_PUBLIC_URL=' "${TMP}/.env" > "${TMP}/no-frontend.env"
check "Compose: sin FRONTEND_PUBLIC_URL el render de producción falla" \
  bash -c "! docker compose --project-name piedrazul-kc-contract --file '${COMPOSE_DIR}/prod.yml' --env-file '${TMP}/no-frontend.env' --env-file '${TMP}/image.env' --profile migrate config -q"
sed -E 's/^FRONTEND_PUBLIC_URL=.*/FRONTEND_PUBLIC_URL=/' "${TMP}/.env" > "${TMP}/empty-frontend.env"
check "Compose: FRONTEND_PUBLIC_URL vacía hace fallar el render de producción" \
  bash -c "! docker compose --project-name piedrazul-kc-contract --file '${COMPOSE_DIR}/prod.yml' --env-file '${TMP}/empty-frontend.env' --env-file '${TMP}/image.env' --profile migrate config -q"
check "setup.yml: valida frontend_public_domain antes de copiar Compose y de escribir .env" \
  bash -c "names=\$(yq -r '.[].name' '${TASKS}/setup.yml'); v=\$(echo \"\$names\" | grep -nx 'Validar el dominio público del frontend' | cut -d: -f1); c=\$(echo \"\$names\" | grep -nx 'Copiar archivos compose al servidor' | cut -d: -f1); e=\$(echo \"\$names\" | grep -nx 'Generar .env desde template' | cut -d: -f1); [ -n \"\$v\" ] && [ \"\$v\" -lt \"\$c\" ] && [ \"\$v\" -lt \"\$e\" ]"

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
check "workflow: KC_PLATFORM_ADMIN_INITIAL_PASSWORD solo llega a converge y a su validación temprana antes del Apply (kc_admin_preflight)" \
  test "$(yq -r '[.jobs[].steps[]? | select((.env // {}) | has("KC_PLATFORM_ADMIN_INITIAL_PASSWORD")) | .id] | join(" ")' "${WORKFLOW}")" = "kc_admin_preflight converge"
check "workflow: la validación temprana solo corre si hay que aplicar y no escribe la contraseña en salidas ni en el resumen" \
  bash -c "[ \"\$(yq -r '.jobs.production.steps[] | select(.id == \"kc_admin_preflight\") | .if' '${WORKFLOW}')\" = \"env.TF_APPLY_REQUIRED == 'true'\" ] && ! yq -r '.jobs.production.steps[] | select(.id == \"kc_admin_preflight\") | .run' '${WORKFLOW}' | grep -Eq 'GITHUB_OUTPUT|GITHUB_STEP_SUMMARY|GITHUB_ENV|KC_PLATFORM_ADMIN_INITIAL_PASSWORD}'"
check "workflow: KC_PLATFORM_ADMIN_INITIAL_PASSWORD no es obligatoria fuera de P2 (no se exige al verificar credenciales)" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.id == \"credentials\") | .run' '${WORKFLOW}' | grep -q KC_PLATFORM_ADMIN"
check "workflow: el alcance de KC_PLATFORM_ADMIN_INITIAL_PASSWORD está declarado (production-hetzner)" \
  grep -q '\[KC_PLATFORM_ADMIN_INITIAL_PASSWORD\]="production-hetzner"' "${ROOT}/.github/scripts/audit-github-trust.sh"
check "workflow: deploy no recibe credenciales técnicas de Keycloak" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.id == \"deploy\") | .env | keys[]' '${WORKFLOW}' | grep -Eq '^KC_'"
# Todo secreto de la app que el job production referencia tiene un consumidor en Ansible
MISSING_CONSUMER=""
for s in $(grep -oE 'secrets\.[A-Z_]+' "${WORKFLOW}" | sed 's/secrets\.//' | sort -u); do
  # Credenciales de la cadena (no de la app): HCP, SSH y publicación de Pages (wrangler)
  case "$s" in GITHUB_TOKEN|TF_API_TOKEN|ANSIBLE_SSH_KEY|CLOUDFLARE_PAGES_API_TOKEN) continue ;; esac
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
  yq -r '.. | select(type == "!!map" and has("name")) | select((.["community.docker.docker_container_exec"].env // {} | keys | map(select(test("SECRET|PASSWORD"))) | length > 0) or (.environment // {} | keys | map(select(test("SECRET|PASSWORD"))) | length > 0)) | select(.no_log != true) | .name' "$f"
done)"
check "toda tarea que pasa secretos o contraseñas por entorno lleva no_log (${UNLOGGED:-ninguna sin no_log})" test -z "${UNLOGGED}"
check "las tareas que leen o evalúan KC_PLATFORM_ADMIN_INITIAL_PASSWORD llevan no_log" \
  test "$(yq -r '[.[] | select(.name | test("contraseña inicial del administrador humano desde|Evaluar la contraseña inicial")) | .no_log] | join(" ")' "${TASKS}/keycloak_credentials.yml")" = "true true"
check "el tipo de evento (fresh/recovery) se fija como hecho antes del evento, no se reevalúa dentro" \
  test "$(yq -r '.[] | select(.name == "Evento con autoridad temporal") | .vars.app_kc_event' "${TASKS}/keycloak_lifecycle.yml")" = "{{ app_kc_event_kind }}"
check "administrador humano: platform-admin solo corre en el evento P2" \
  test "$(yq -r '.. | select(type == "!!map" and has("name")) | select(.name == "Crear el administrador humano permanente (solo P2)") | .when' "${TASKS}/keycloak_bootstrap.yml")" = "app_kc_event == 'fresh'"
check "administrador humano: ningún otro task file invoca platform-admin" \
  test "$(grep -rl '"platform-admin"' "${TASKS}" | xargs -r -n1 basename | tr '\n' ' ')" = "keycloak_bootstrap.yml "
check "administrador humano: la contraseña inicial se valida (solo P2) antes del evento" \
  bash -c "test \$(yq -r '[.[].name] | index(\"Verificar la contraseña inicial del administrador humano (solo P2)\")' '${TASKS}/keycloak_lifecycle.yml') -lt \$(yq -r '[.[].name] | index(\"Evento con autoridad temporal\")' '${TASKS}/keycloak_lifecycle.yml') && test \"\$(yq -r '.[] | select(.name == \"Verificar la contraseña inicial del administrador humano (solo P2)\") | .when' '${TASKS}/keycloak_lifecycle.yml')\" = \"app_kc_state == 'P2'\""
check "administrador humano: nace deshabilitado y se habilita solo después de verificarlo" \
  bash -c "a=\$(grep -n '\"enabled\":false' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | cut -d: -f1); v=\$(grep -n 'platform_admin.verified=yes' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | cut -d: -f1); e=\$(grep -n 'enabled=true || die platform_admin_enable' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | cut -d: -f1); test -n \"\$a\" && test \"\$a\" -lt \"\$v\" && test \"\$v\" -lt \"\$e\""
check "administrador humano: una colisión de nombre falla sin adoptar (antes de crear)" \
  bash -c "c=\$(grep -n 'die platform_admin_collision' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | cut -d: -f1); n=\$(grep -n 'create users -r master' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | cut -d: -f1); test -n \"\$c\" && test \"\$c\" -lt \"\$n\""
check "política de master: solo mínimo de 16, sin categorías, rotación ni hashAlgorithm" \
  test "$(yq -r '.kc_master_password_policy' "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")" = "length(16)"
check "política de master: se fija solo si master no tiene política y falla ante otra (antes de crear la cuenta)" \
  bash -c "f='${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh'; sed -n '/^platform_admin()/,/^}/p' \"\$f\" | grep -q 'die master_password_policy_unexpected' && test \$(grep -n 'passwordPolicy=\${KCL_MASTER_PASSWORD_POLICY}' \"\$f\" | cut -d: -f1) -lt \$(grep -n 'create users -r master' \"\$f\" | cut -d: -f1) && test \$(grep -c 'passwordPolicy=' \"\$f\") = 1"
check "política de master: ningún task file ni otra acción la escribe (solo platform-admin, en P2)" \
  bash -c "! grep -rq 'passwordPolicy' '${TASKS}' && ! sed -n '/^reconcile()/,/^}/p;/^establish()/,/^}/p;/^cleanup()/,/^}/p' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | grep -q passwordPolicy"
check "realm de la aplicación: nace con la política de contraseñas aprobada" \
  test "$(jq -r '.passwordPolicy' "${REALM}")" = 'length(6) and maxLength(100)'
check "realm de la aplicación: la política coincide con los límites de creación de cuentas del backend" \
  bash -c "grep -Fxq '        @Size(min = 6, max = 100)' '${ROOT}/backend/src/main/java/co/edu/unicauca/piedrazul/backend/user/api/dto/input/CreateSystemUserRequest.java' && grep -Fxq '    @Size(min = 6, max = 100)' '${ROOT}/backend/src/main/java/co/edu/unicauca/piedrazul/backend/patients/api/dto/input/CreatePatientWithUserRequest.java'"
check "realm de la aplicación: admin-cli sin password grant" \
  jq -e '[.clients[] | select(.clientId == "admin-cli") | .directAccessGrantsEnabled] == [false]' "${REALM}"
check "realm de la aplicación: ninguna sesión de kcadm entra por piedrazul con usuario y contraseña" \
  bash -c "! grep -rhE -- '--realm' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' '${ROOT}/docker-compose.yml' | grep -- '--user' | grep -v -- '--realm master'"
check "administrador humano: reconcile y establish no tocan usuarios humanos de master" \
  bash -c "! sed -n '/^reconcile()/,/^}/p;/^establish()/,/^}/p' '${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh' | grep -Eq 'users/|reset-password|PLATFORM_ADMIN'"
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
# El nombre del administrador humano solo puede aparecer como filtro (variable psql), nunca
# como columna seleccionada
check "salida: la lectura de la base no selecciona usernames (conteos, ids internos y clientIds técnicos)" \
  bash -c "! grep -v '^ *--' '${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql' | grep -i 'username' | grep -vxF \"  LEFT JOIN user_entity u ON u.realm_id = r.id AND u.username = :'platform_admin_username'\" | grep -q ."
# credential solo como conteo por tipo: nunca secret_data, credential_data ni client.secret
check "keycloak-state.sql: no selecciona columnas de secretos" \
  bash -c "! grep -Eiq '\\bsecret\\b|secret_data|credential_data|\\bsalt\\b' <(grep -v '^ *--' '${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql') && test \"\$(grep -v '^ *--' '${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql' | grep -ic credential)\" = \"\$(grep -v '^ *--' '${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql' | grep -Ec \"count\\(\\*\\) FROM credential c WHERE c.user_id = u.id AND c.type = '(password|otp)'\")\""

# ── 8. Correo nativo ─────────────────────────────────────────────────────────
GROUP_VARS="${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml"
SMTP_TASKS="${TASKS}/keycloak_smtp.yml"
ADMIN_SH="${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh"
STATE_SQL="${ANSIBLE_DIR}/roles/app/files/keycloak-state.sql"
check "correo: el realm del repositorio nace sin SMTP y sin recuperación" \
  jq -e '.resetPasswordAllowed == false and .smtpServer == {}' "${REALM}"
check "correo: la automatización tiene exactamente sus tres roles del realm de la aplicación, nada en master" \
  test "$(yq -r '.kc_automation_roles | sort | join(" ")' "${GROUP_VARS}")" = '{{ kc_realm }}-realm:manage-clients {{ kc_realm }}-realm:manage-realm {{ kc_realm }}-realm:view-clients'
check "correo: producción usa el puerto 587 con STARTTLS y autenticación, sin SSL implícito" \
  test "$(yq -r '.kc_smtp_connection | [.port, .starttls, .ssl, .auth] | join("/")' "${GROUP_VARS}")" = "587/true/false/true"
check "Compose: keycloak no recibe ninguna clave de correo" \
  bash -c "! jq -r '.services.keycloak.environment | keys[]' '${TMP}/config.json' | grep -Eq '^(SES_|NOTIFICATIONS_|SMTP)'"
check "correo: el SMTP se reconcilia solo al final del ciclo" \
  test "$(yq -r '.[-1]."ansible.builtin.include_tasks"' "${TASKS}/keycloak_lifecycle.yml")" = "keycloak_smtp.yml"
check "correo: ningún otro task file lo incluye; deploy no lo toca" \
  test "$(grep -rlE 'keycloak_smtp.yml$|"smtp"\]' "${TASKS}" | xargs -r -n1 basename | sort | tr '\n' ' ')" = "keycloak_lifecycle.yml keycloak_smtp.yml "
APPLY='.[] | select(.name == "Aplicar SMTP y recuperación al realm")'
check "correo: la escritura lleva no_log y la contraseña va por stdin, fuera de argv y del entorno" \
  bash -c "test \"\$(yq -r '${APPLY} | .no_log' '${SMTP_TASKS}')\" = true && yq -r '${APPLY} | .\"community.docker.docker_container_exec\".stdin' '${SMTP_TASKS}' | grep -q _realm_update && ! yq -r '${APPLY} | .\"community.docker.docker_container_exec\" | (.env, .argv)' '${SMTP_TASKS}' | grep -Eq 'app_ses_smtp|_realm_update'"
check "correo: sin decisión de SES no se toca el realm (sin valor por defecto)" \
  bash -c "yq -r '.[0].\"ansible.builtin.assert\".that[]' '${SMTP_TASKS}' | grep -qx 'app_ses_enabled is defined' && ! grep -q 'app_ses_enabled | default' '${SMTP_TASKS}'"
check "correo: keycloak-admin.sh smtp escribe parcial (sin --merge) y lee stdin" \
  bash -c "sed -n '/^smtp()/,/^}/p' '${ADMIN_SH}' | grep -q 'base64 -d | as automation update \"realms/\${KCL_REALM}\" -f - ||' && ! sed -n '/^smtp()/,/^}/p' '${ADMIN_SH}' | grep -q -- '--merge'"
check "correo: keycloak-state.sql nunca selecciona los valores de user ni password" \
  bash -c "! grep -v '^ *--' '${STATE_SQL}' | grep -E \"c.name IN\" | grep -Eq \"'(user|password)'\" && test \"\$(grep -v '^ *--' '${STATE_SQL}' | grep -c 'c.value')\" = 2"
check "correo: la clasificación P1/P2/P3 no depende del SMTP" \
  bash -c "! sed -n '/Calcular divergencias bloqueantes/,\$p' '${TASKS}/keycloak_state.yml' | grep -Eq 'smtp|reset_password'"
check "workflow: el resumen avisa que habilitar SES enciende la recuperación de Keycloak" \
  bash -c "yq -r '.jobs.production.steps[] | select(.name == \"Resumen de producción\") | .run' '${WORKFLOW}' | grep -q 'habilita su recuperación de contraseña'"

# ── 9. Alcance de roles explícito ────────────────────────────────────────────
ROLE_ENUM="$(sed -n '/enum Role/,/}/p' "${ROOT}/backend/src/main/java/co/edu/unicauca/piedrazul/backend/shared/enums/Role.java" | grep -oE '^\s+[A-Z_]+' | tr -d ' ' | sort | paste -sd, -)"
check "alcance: backend y frontend con Full Scope Allowed deshabilitado" \
  jq -e '[.clients[] | select(.clientId == "piedrazul-backend" or .clientId == "piedrazul-frontend") | .fullScopeAllowed] == [false, false]' "${REALM}"
check "alcance: el del backend es exactamente sus roles asignados (realm-management), sin roles de realm" \
  jq -e '([.clientScopeMappings[][] | select(.client == "piedrazul-backend")] | length == 1) and (.clientScopeMappings["realm-management"][] | select(.client == "piedrazul-backend") | .roles | sort) == (.users[] | select(.serviceAccountClientId == "piedrazul-backend") | .clientRoles["realm-management"] | sort) and ([.scopeMappings[] | select(.client == "piedrazul-backend")] | length == 0)' "${REALM}"
check "alcance: el del frontend es exactamente los roles de negocio del realm, sin roles de clientes" \
  jq -e '([.scopeMappings[] | select(.client == "piedrazul-frontend") | .roles[]] | sort) == ([.roles.realm[].name | select(. != "offline_access" and . != "uma_authorization" and (startswith("default-roles-") | not))] | sort) and ([.clientScopeMappings[][] | select(.client == "piedrazul-frontend")] | length == 0)' "${REALM}"
check "alcance: los roles de negocio del frontend son los del enum Role que convierte Spring (${ROLE_ENUM})" \
  test "$(jq -r '[.scopeMappings[] | select(.client == "piedrazul-frontend") | .roles[]] | sort | join(",")' "${REALM}")" = "${ROLE_ENUM}"
check "alcance: Spring convierte solo realm_access.roles filtrados por Role (contrato sin cambios)" \
  bash -c "f='${ROOT}/backend/src/main/java/co/edu/unicauca/piedrazul/backend/config/security/JwtAuthConverter.java'; grep -q 'jwt.getClaim(\"realm_access\")' \"\$f\" && grep -q 'realmAccess.get(\"roles\")' \"\$f\" && grep -q 'filter(BUSINESS_ROLES::contains)' \"\$f\""
check "alcance: clientes integrados sin cambios (admin-cli y security-admin-console con Full Scope)" \
  jq -e '[.clients[] | select(.clientId == "admin-cli" or .clientId == "security-admin-console") | .fullScopeAllowed] == [true, true]' "${REALM}"
check "alcance: sin política global full-scope-disabled" bash -c "! grep -rq 'full-scope-disabled' '${REALM}' '${ANSIBLE_DIR}'"
check "alcance: fullScopeAllowed nunca va en la forma de un cliente (se escribiría antes que sus roles)" \
  bash -c "! yq -r '.[] | select(.name == \"Calcular la forma técnica esperada\") | .\"ansible.builtin.set_fact\".app_kc_expected.backend_shape_json' '${TASKS}/keycloak_lifecycle.yml' | grep -q fullScopeAllowed && ! sed -n '/^establish()/,/^}/p' '${ADMIN_SH}' | grep -q fullScopeAllowed"
check "alcance: orden seguro en scopes: agregar → deshabilitar Full Scope → quitar → releer" \
  bash -c "s=\$(sed -n '/^scopes()/,/^}/p' '${ADMIN_SH}'); a=\$(grep -n 'scope_role add' <<< \"\$s\" | cut -d: -f1); f=\$(grep -n 'fullScopeAllowed=false ||' <<< \"\$s\" | cut -d: -f1); r=\$(grep -n 'scope_role remove' <<< \"\$s\" | cut -d: -f1); b=\$(grep -n 'die \"scope_readback:' <<< \"\$s\" | cut -d: -f1); test -n \"\$a\" && test \"\$a\" -lt \"\$f\" && test \"\$f\" -lt \"\$r\" && test \"\$r\" -lt \"\$b\""
check "alcance: solo establish lo escribe, después de los roles asignados; probe, reconcile, smtp y cleanup no" \
  bash -c "test \"\$(sed -n '/^establish()/,/^}/p' '${ADMIN_SH}' | grep -E '^  scopes\$|add-roles \"\\\$\\{KCL_REALM\\}\"' | sed -E 's/.*add-roles.*/roles/; s/^  scopes\$/scopes/' | paste -sd' ')\" = 'roles scopes' && ! sed -n '/^probe()/,/^}/p;/^reconcile()/,/^}/p;/^smtp()/,/^}/p;/^cleanup()/,/^}/p' '${ADMIN_SH}' | grep -Eq 'scope|fullScopeAllowed'"
check "alcance: KCL_SCOPES solo llega al evento con autoridad temporal (nunca a P1)" \
  test "$(grep -rl 'KCL_SCOPES' "${TASKS}" | xargs -r -n1 basename | tr '\n' ' ')" = "keycloak_bootstrap.yml "
check "alcance: keycloak-state.sql lo lee sin escribir (solo SELECT dentro de la transacción de lectura)" \
  bash -c "grep -q 'BEGIN TRANSACTION READ ONLY' '${STATE_SQL}' && ! grep -v '^ *--' '${STATE_SQL}' | grep -Eiq '\\b(insert|update|delete)\\b'"

# Tareas reales de Ansible (expectativa del realm, lectura e interpretación de la base y
# divergencias) con lecturas sintéticas de la base: sin contenedores
yq '[.[] | select(.name == "Calcular la forma técnica esperada" or .name == "Verificar el alcance de roles explícito declarado en el realm del repositorio")]' \
  "${TASKS}/keycloak_lifecycle.yml" > "${TMP}/scope-expected.yml"
yq '[.[] | select(.name == "Interpretar el estado de la base" or .name == "Decidir si se exige el alcance de la API de administración" or .name == "Calcular divergencias bloqueantes" or .name == "Calcular divergencias recuperables")]' \
  "${TASKS}/keycloak_state.yml" > "${TMP}/scope-classify.yml"
yq '(.. | select(type == "!!map" and has("name")) | select(.name == "Establecer la automatización y la forma del backend") | ."community.docker.docker_container_exec".env) as $e | [{"name": "KCL_SCOPES y KCL_ADMIN_API_* del evento", "ansible.builtin.set_fact": {"kcl_scopes": $e.KCL_SCOPES, "kcl_admin_api_scope": $e.KCL_ADMIN_API_SCOPE, "kcl_admin_api_grant": $e.KCL_ADMIN_API_GRANT, "kcl_admin_api_deny": $e.KCL_ADMIN_API_DENY}}]' \
  "${TASKS}/keycloak_bootstrap.yml" > "${TMP}/scope-event.yml"
cat > "${TMP}/scope.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_tasks: "{{ tmp }}/scope-expected.yml"
    - ansible.builtin.set_fact:
        app_kc_db_probe:
          stdout_lines: >-
            {{ ['schema=initialized', 'realm=master', 'realm=' ~ kc_realm, 'migration_version=' ~ (case_version | default('26.8.1')),
                'client.automation=' ~ app_kc_expected.automation_shape, 'client.backend=' ~ app_kc_expected.backend_shape,
                'roles.automation=' ~ (app_kc_expected.automation_roles | join(',')),
                'roles.backend=' ~ (app_kc_expected.backend_roles | join(','))] + case_scope_lines
               + (case_admin_api_lines | default([])) }}
    - ansible.builtin.include_tasks: "{{ tmp }}/scope-classify.yml"
    # Después de clasificar: KCL_ADMIN_API_* dependen de la versión leída de la base
    - ansible.builtin.include_tasks: "{{ tmp }}/scope-event.yml"
    - ansible.builtin.copy:
        dest: "{{ out_file }}"
        content: >-
          {{ {'blocking': app_kc_blocking, 'reasons': app_kc_reasons, 'kcl_scopes': kcl_scopes,
              'admin_api_required': app_kc_admin_api_required | bool, 'admin_api': app_kc_observed.admin_api,
              'kcl_admin_api_scope': kcl_admin_api_scope, 'kcl_admin_api_grant': kcl_admin_api_grant | trim,
              'kcl_admin_api_deny': kcl_admin_api_deny | trim} | to_json }}
EOF
AUTO_T='piedrazul-realm:manage-clients,piedrazul-realm:manage-realm,piedrazul-realm:view-clients'
BACK_T='realm-management:manage-users,realm-management:query-users,realm-management:view-events,realm-management:view-realm,realm-management:view-users'
FRONT_T='realm:ADMIN,realm:AUDITOR,realm:DOCTOR,realm:PATIENT,realm:SCHEDULER'
scope_case() { # <nombre> <lista JSON de líneas scope.*> [extra args]: deja ${TMP}/scope-<nombre>.json
  local name="$1" lines="$2"; shift 2
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${TMP}/scope.yml" \
      -e "repo_root=${ROOT}" -e "tmp=${TMP}" -e "out_file=${TMP}/scope-${name}.json" \
      -e "{\"case_scope_lines\": ${lines}}" "$@" </dev/null > "${TMP}/scope-${name}.log" 2>&1
}
# Un caso que no llega a escribir su resultado falla en sus checks, no aborta el resto
scope_has() { jq -e --arg s "$2" "$3" "${TMP}/scope-$1.json" >/dev/null; }
scope_case target "[\"scope.frontend=full:false;realm:PATIENT,realm:ADMIN,realm:SCHEDULER,realm:AUDITOR,realm:DOCTOR\", \"scope.automation=full:false;${AUTO_T}\", \"scope.backend=full:false;$(tr ',' '\n' <<< "${BACK_T}" | sort -r | paste -sd, -)\"]" || true
check "alcance (Ansible): el estado esperado, en cualquier orden, no es divergencia" \
  scope_has target '' '.blocking == [] and .reasons == []'
check "alcance (Ansible): el evento recibe exactamente el alcance aprobado de los tres clientes" \
  scope_has target "master|piedrazul-keycloak-automation|${AUTO_T};piedrazul|piedrazul-backend|${BACK_T};piedrazul|piedrazul-frontend|${FRONT_T}" '.kcl_scopes == $s'
scope_case full-on '["scope.automation=full:true;", "scope.backend=full:true;", "scope.frontend=full:true;"]' || true
check "alcance (Ansible): Full Scope habilitado y sin asignaciones en los tres clientes → P3 recuperable para cada uno" \
  scope_has full-on '' '.blocking == [] and (.reasons | length == 3) and all(.reasons[]; test("^alcance de roles de piedrazul-(keycloak-automation|backend|frontend) divergente: Full Scope Allowed habilitado; faltan "))'
check "alcance (Ansible): el motivo nombra los roles que faltan (sin secretos)" \
  scope_has full-on "alcance de roles de piedrazul-frontend divergente: Full Scope Allowed habilitado; faltan ${FRONT_T}" '.reasons | index($s) != null'
scope_case drift "[\"scope.automation=full:false;piedrazul-realm:manage-clients,piedrazul-realm:view-clients\", \"scope.backend=full:false;${BACK_T},realm-management:manage-realm\", \"scope.frontend=full:true;${FRONT_T}\"]" || true
check "alcance (Ansible): rol faltante (automatización), rol sobrante (backend) y Full Scope habilitado tras un evento interrumpido (frontend)" \
  scope_has drift '' '.blocking == [] and .reasons == ["alcance de roles de piedrazul-keycloak-automation divergente: faltan piedrazul-realm:manage-realm", "alcance de roles de piedrazul-backend divergente: sobran realm-management:manage-realm", "alcance de roles de piedrazul-frontend divergente: Full Scope Allowed habilitado"]'
scope_case no-frontend "[\"scope.automation=full:false;${AUTO_T}\", \"scope.backend=full:false;${BACK_T}\"]" || true
check "alcance (Ansible): sin el cliente frontend → P3 bloqueante (no se recrea)" \
  scope_has no-frontend '' '.blocking == ["falta el cliente piedrazul-frontend en piedrazul"]'
jq '.roles.realm += [{"name": "RECEPTIONIST", "composite": false, "clientRole": false}]' "${REALM}" > "${TMP}/realm-new-role.json"
scope_case new-role '[]' -e "kc_realm_file=${TMP}/realm-new-role.json" && RC=0 || RC=$?
check "alcance (Ansible): un rol de negocio nuevo sin su alcance en el frontend falla antes de tocar Keycloak (rc=${RC})" \
  bash -c "test ${RC} -ne 0 && tr -s ' \n' '  ' < '${TMP}/scope-new-role.log' | grep -q 'exactamente los roles de negocio del realm (realm:ADMIN,realm:AUDITOR,realm:DOCTOR,realm:PATIENT,realm:RECEPTIONIST,realm:SCHEDULER'"
jq '(.clients[] | select(.clientId == "piedrazul-backend") | .fullScopeAllowed) = true' "${REALM}" > "${TMP}/realm-full.json"
scope_case realm-full '[]' -e "kc_realm_file=${TMP}/realm-full.json" && RC=0 || RC=$?
check "alcance (Ansible): un realm del repositorio con Full Scope habilitado en el backend falla (rc=${RC})" \
  bash -c "test ${RC} -ne 0 && grep -q 'deben tener fullScopeAllowed=false' '${TMP}/scope-realm-full.log'"

# ── 9b. Alcance de la API de administración (kc-admin-api, Keycloak >= 26.8.2) ──
check "admin API: group_vars fija kc-admin-api desde 26.8.2" \
  test "$(yq -r '[.kc_admin_api_scope, .kc_admin_api_scope_since] | join(" ")' "${GROUP_VARS}")" = "kc-admin-api 26.8.2"
check "admin API: nunca se deshabilita la verificación de audiencia de Keycloak" \
  bash -c "! grep -rEiq 'audience[-_]check|AUDIENCE_CHECK' '${ROOT}/infra' '${ROOT}/docker-compose.yml' '${ROOT}/.github' --exclude=keycloak-lifecycle-check.sh"
check "admin API: el realm del repositorio no define ni referencia kc-admin-api (Keycloak lo crea después de importar los clientes e ignora la referencia)" \
  bash -c "! grep -q 'kc-admin-api' '${REALM}'"
check "admin API: solo establish lo asigna, después del alcance de roles y antes de verificar la automatización con una operación real" \
  bash -c "s=\$(sed -n '/^establish()/,/^}/p' '${ADMIN_SH}'); a=\$(grep -n '^  scopes\$' <<< \"\$s\" | cut -d: -f1); b=\$(grep -n '^  admin_api\$' <<< \"\$s\" | cut -d: -f1); c=\$(grep -n 'echo \"automation.admin_api=ok\"' <<< \"\$s\" | cut -d: -f1); test -n \"\$a\" && test \"\$a\" -lt \"\$b\" && test \"\$b\" -lt \"\$c\" && ! sed -n '/^probe()/,/^}/p;/^reconcile()/,/^}/p;/^smtp()/,/^}/p;/^cleanup()/,/^}/p;/^platform_admin()/,/^}/p' '${ADMIN_SH}' | grep -Eq '^ +admin_api\$|admin_api_(list|has|scope_id)|KCL_ADMIN_API'"
check "admin API: solo toca la asignación de ese scope (por defecto / opcional), nunca otros alcances ni roles" \
  bash -c "s=\$(sed -n '/^admin_api_list()/,/^establish()/p' '${ADMIN_SH}'); ! grep -Eq 'add-roles|remove-roles|scope-mappings|fullScopeAllowed|client-scopes -r .* create' <<< \"\$s\" && test \"\$(grep -c -- '-client-scopes/\\\${sid}' <<< \"\$s\")\" = 3"
check "admin API: KCL_ADMIN_API_* solo llegan al evento con autoridad temporal (nunca a P1)" \
  test "$(grep -rl 'KCL_ADMIN_API_' "${TASKS}" | xargs -r -n1 basename | tr '\n' ' ')" = "keycloak_bootstrap.yml "
check "admin API: el evento exige la operación real de la automatización" \
  bash -c "grep -q \"'automation.admin_api=ok' in app_kc_establish.stdout_lines\" '${TASKS}/keycloak_bootstrap.yml'"
check "admin API: keycloak-state.sql lee la asignación de kc-admin-api de los tres clientes propios" \
  bash -c "grep -q \"'admin_api.' || t.label\" '${STATE_SQL}' && grep -q \"s.name = :'admin_api_scope'\" '${STATE_SQL}'"
check "desarrollo: keycloak-dev-secret asigna kc-admin-api por defecto al backend" \
  bash -c "yq -r '.services[\"keycloak-dev-secret\"].command[0]' '${ROOT}/docker-compose.yml' | grep -q 'default-client-scopes/\\\$\\\$SID'"

TARGET_SCOPE_LINES="[\"scope.frontend=full:false;${FRONT_T}\", \"scope.automation=full:false;${AUTO_T}\", \"scope.backend=full:false;${BACK_T}\"]"
scope_case aa-target "${TARGET_SCOPE_LINES}" -e case_version=26.8.2 \
  -e '{"case_admin_api_lines": ["admin_api.automation=default", "admin_api.backend=default", "admin_api.frontend=none"]}' || true
check "admin API (Ansible): 26.8.2 con kc-admin-api por defecto en automatización y backend y sin él en el frontend → sin divergencia" \
  scope_has aa-target '' '.admin_api_required and .blocking == [] and .reasons == []'
check "admin API (Ansible): el evento lo asigna a automatización (master) y backend y lo niega al frontend" \
  scope_has aa-target '' '.kcl_admin_api_scope == "kc-admin-api" and .kcl_admin_api_grant == "master|piedrazul-keycloak-automation;piedrazul|piedrazul-backend" and .kcl_admin_api_deny == "piedrazul|piedrazul-frontend"'
scope_case aa-drift "${TARGET_SCOPE_LINES}" -e case_version=26.8.2 \
  -e '{"case_admin_api_lines": ["admin_api.automation=none", "admin_api.backend=optional", "admin_api.frontend=default+optional"]}' || true
check "admin API (Ansible): ausente (automatización), solo opcional (backend) o presente en el frontend → P3 recuperable para cada uno, sin bloquear" \
  scope_has aa-drift '' '.blocking == [] and .reasons == ["alcance kc-admin-api de piedrazul-keycloak-automation divergente: none (esperado: default)", "alcance kc-admin-api de piedrazul-backend divergente: optional (esperado: default)", "alcance kc-admin-api de piedrazul-frontend divergente: default+optional (esperado: none)"]'
scope_case aa-upgraded "${TARGET_SCOPE_LINES}" -e case_version=26.8.2 \
  -e '{"case_admin_api_lines": ["admin_api.automation=none", "admin_api.backend=none", "admin_api.frontend=none"]}' || true
check "admin API (Ansible): base recién migrada a 26.8.2 (Keycloak solo lo asigna a sus clientes) → P3 recuperable de automatización y backend" \
  scope_has aa-upgraded '' '.blocking == [] and (.reasons | length == 2) and all(.reasons[]; test("^alcance kc-admin-api de piedrazul-(keycloak-automation|backend) divergente: none "))'
scope_case aa-before "${TARGET_SCOPE_LINES}" -e case_version=26.8.1 \
  -e '{"case_admin_api_lines": ["admin_api.automation=none", "admin_api.backend=none", "admin_api.frontend=none"]}' || true
check "admin API (Ansible): base anterior a 26.8.2 → no se exige ni se asigna (el scope no existe)" \
  scope_has aa-before '' '(.admin_api_required | not) and .reasons == [] and .kcl_admin_api_grant == "" and .kcl_admin_api_deny == ""'
scope_case aa-newer "${TARGET_SCOPE_LINES}" -e case_version=26.10.0 \
  -e '{"case_admin_api_lines": ["admin_api.automation=default", "admin_api.backend=none", "admin_api.frontend=none"]}' || true
check "admin API (Ansible): versiones posteriores también lo exigen (comparación de versión, no de texto)" \
  scope_has aa-newer '' '.admin_api_required and .reasons == ["alcance kc-admin-api de piedrazul-backend divergente: none (esperado: default)"]'

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
# ── 7b. Frontend del tema de login (desarrollo) ──────────────────────────────
check "desarrollo: keycloak recibe FRONTEND_PUBLIC_URL de .env.example" \
  jq -e --arg v "$(grep -E '^FRONTEND_PUBLIC_URL=' "${ROOT}/.env.example" | cut -d= -f2-)" '.services.keycloak.environment.FRONTEND_PUBLIC_URL == $v and $v == "http://localhost:4200"' "${TMP}/dev.json"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "keycloak-lifecycle-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "keycloak-lifecycle-check: todo PASS"
