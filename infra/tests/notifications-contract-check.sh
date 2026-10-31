#!/usr/bin/env bash
# Contrato de configuración del correo por SES en producción: GitHub Environment → workflow
# (ses-config.sh) → validación (ses_inputs.yml) → productor (env.j2) → consumidor (Compose →
# backend) → verificaciones (ses_delivery.yml, ses_host_gate.yml, ses_runtime.yml) → retiro.
# No usa red, Amazon SES ni credenciales reales: los valores son sintéticos y nunca se imprimen.
# El contenedor backend se simula (colección community.docker falsa). La puerta de imagen sí
# corre contenedores reales, sin red, sobre imágenes de prueba locales. Es evidencia de
# implementación: no prueba variables reales de GitHub, un host real ni la entrega de un correo.
# El enlace con la aplicación real lo cubre SesEnvironmentBindingTest (backend).
#
# Uso: infra/tests/notifications-contract-check.sh
#   SES_REAL_CAPABLE_IMAGE, SES_REAL_INCAPABLE_IMAGE  imágenes del backend construidas de verdad,
#                                                     además de las de prueba (opcionales)
# Requiere: docker (daemon para la puerta de imagen), ansible-playbook, jq, yq (mikefarah v4),
#           python3.
#
# Verifica:
#   1. Sin rastro operativo de SendGrid; expresión del endpoint igual en workflow y Ansible.
#   2. Deshabilitado: sin ninguna entrada (ni de relleno) pasa en cada etapa; .env y Compose no
#      activan SES aunque el runner tenga valores; solo el backend recibe las claves.
#   3. Habilitado: decisión, endpoint y credenciales llegan intactos con caracteres sensibles a
#      shell, YAML y Jinja; una entrega alterada se detecta.
#   4. Entradas inválidas fallan sin escribir .env y sin mostrar valores; el .env anterior queda.
#   5. Preflight (ses-config.sh): decisión estricta, copias fuera del environment, secreto solo
#      con converge, advertencia del deploy sin secretos.
#   6. Workflow: alcance de los secretos, orden antes del Apply y de SSH, deploy sin secretos.
#   7. Deriva antes de mutar (ses_host_gate.yml) y ciclo de vida: habilitar, deshabilitar,
#      cambiar endpoint, rotar y recuperarse; converge y deploy siguen siendo distintos.
#   8. En ejecución (ses_runtime.yml, refresh_backend.yml): el contenedor refleja Compose.
#   9. Imagen: capaz aceptada, incapaz rechazada, sin red ni credenciales; las tareas reales de
#      setup.yml y deploy.yml inspeccionan exactamente backend_image_ref, antes de .env e image.env,
#      y solo con SES habilitado (ahí docker y la descarga son simulados).
#  10. .env root:root 0600 sin romper consumidores; orden y no_log en Ansible.
#  11. Verificación de credenciales del workflow sin secretos de correo.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
TASKS="${ANSIBLE_DIR}/roles/app/tasks"
GROUP_VARS="${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
SES_CONFIG="${ROOT}/.github/scripts/ses-config.sh"
HOST=email-smtp.us-east-1.amazonaws.com

for bin in docker ansible-playbook jq yq python3; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
for key in $(compgen -e | grep -E '^(SES_SMTP_|NOTIFICATIONS_SES_|NOTIFICATION_ENCRYPTION_KEY$)' || true); do
  echo "${key} está definida en el entorno: Compose o Ansible la usarían; ejecuta sin ella" >&2; exit 2
done

TMP="$(mktemp -d)"
IMAGE_PREFIX="piedrazul-ses-check-$$/backend"
cleanup() {
  docker image ls --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -F "${IMAGE_PREFIX}:" | xargs -r docker image rm -f >/dev/null 2>&1 || true
  rm -rf "${TMP}"
}
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
fails() { ! "$@"; }
synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
task_index() { yq -r '.[].name' "$1" | grep -nxF -- "$2" | head -1 | cut -d: -f1 || true; }
before() { [ -n "$1" ] && [ -n "$2" ] && [ "$1" -lt "$2" ]; }

USERNAME="$(synthetic user)"
# Todo lo que YAML, .env, Compose o Jinja podrían alterar, dentro de lo admitido
PASSWORD='synthetic-pw$HOME${PATH}\n"q"'"'"'s#x`y`{{z}}{%w%}\\$$+/='
ROTATED="$(synthetic rotated)"
ENABLED_ENV=(NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "SES_SMTP_USERNAME=${USERNAME}" "SES_SMTP_PASSWORD=${PASSWORD}")

# ── Host simulado ───────────────────────────────────────────────────────────
APP="${TMP}/app"
mkdir -p "${APP}/compose"
cp "${COMPOSE_DIR}"/*.yml "${APP}/compose/"
IMAGE_SELECTED="ghcr.io/example/backend@sha256:$(printf 'a%.0s' {1..64})"
printf 'BACKEND_IMAGE=%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\nKEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:%s\nTRAEFIK_IMAGE=docker.io/library/traefik@sha256:%s\n' \
  "${IMAGE_SELECTED}" "$(printf 'b%.0s' {1..64})" "$(printf 'c%.0s' {1..64})" "$(printf 'd%.0s' {1..64})" > "${APP}/image.env"
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"

# Ansible corre los comandos del host por SSH: allí no existe el entorno del runner. Con
# conexión local, Compose preferiría esas variables al .env y ocultaría lo que se prueba.
REAL_DOCKER="$(command -v docker)"
mkdir -p "${TMP}/remote-bin"
printf '%s\n' '#!/usr/bin/env bash' \
  'for v in $(compgen -e | grep -E "^(SES_SMTP_|NOTIFICATIONS_SES_)"); do unset "$v"; done' \
  "exec \"${REAL_DOCKER}\" \"\$@\"" > "${TMP}/remote-bin/docker"
chmod +x "${TMP}/remote-bin/docker"

# Colección community.docker falsa: contenedor y Compose de recreación simulados
FAKE="${TMP}/collections/ansible_collections/community/docker"
mkdir -p "${FAKE}/plugins/modules" "${FAKE}/meta"
printf 'namespace: community\nname: docker\nversion: 0.0.0\n' > "${FAKE}/galaxy.yml"
printf 'requires_ansible: ">=2.9"\n' > "${FAKE}/meta/runtime.yml"
printf '%s\n' '#!/usr/bin/python' 'import json, os' 'from ansible.module_utils.basic import AnsibleModule' \
  'module = AnsibleModule(argument_spec=dict(name=dict(type="str", required=True)))' \
  'module.exit_json(changed=False, **json.load(open(os.environ["FAKE_CONTAINER_JSON"])))' > "${FAKE}/plugins/modules/docker_container_info.py"
printf '%s\n' '#!/usr/bin/python' 'from ansible.module_utils.basic import AnsibleModule' \
  'spec = {k: dict(type="raw") for k in ("project_src", "project_name", "files", "services", "dependencies",' \
  '        "state", "pull", "wait", "wait_timeout", "env_files")}' \
  'AnsibleModule(argument_spec=spec).exit_json(changed=True)' > "${FAKE}/plugins/modules/docker_compose_v2.py"

RUNS=0
run_playbook() { # <playbook> [CLAVE=valor ...] → salida en ${TMP}/run.log; variables extra en EXTRA
  local playbook="$1" rc=0; shift
  env -i PATH="${HOST_BIN:-${TMP}/remote-bin}:${PATH}" HOME="${HOME}" LANG=C.UTF-8 \
    ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ANSIBLE_COLLECTIONS_PATH="${TMP}/collections" FAKE_CONTAINER_JSON="${TMP}/container.json" "$@" \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${playbook}" \
      -e "app_dir=${APP}" -e "backend_image_ref=${IMAGE_SELECTED}" ${EXTRA:-} \
    </dev/null > "${TMP}/run.log" 2>&1 || rc=$?
  RUNS=$((RUNS + 1)); cp "${TMP}/run.log" "${TMP}/run.${RUNS}.log"
  return "${rc}"
}
log_has() { tr -s ' \n' '  ' < "${TMP}/run.log" | grep -qF -- "$1"; }
play_header() { printf -- '- hosts: vps\n  gather_facts: false\n  connection: local\n  tasks:\n'; }

# Mismo orden que setup.yml: validación → .env → entrega
{ play_header; cat <<PLAY
    - ansible.builtin.set_fact:
        app_db_secrets: {POSTGRES_PASSWORD: s, APP_DB_PASSWORD: s, MIGRATION_DB_PASSWORD: s, KC_DB_PASSWORD: s}
        app_kc_backend_secret: s
      no_log: true
    - ansible.builtin.include_tasks: ${TASKS}/ses_inputs.yml
    - ansible.builtin.template:
        src: ${ANSIBLE_DIR}/roles/app/templates/env.j2
        dest: "{{ app_dir }}/.env"
        mode: "0600"
      no_log: true
    - ansible.builtin.include_tasks: ${TASKS}/ses_delivery.yml
PLAY
} > "${TMP}/setup.yml"
{ play_header; cat <<PLAY
    - ansible.builtin.include_tasks: ${TASKS}/ses_inputs.yml
    - ansible.builtin.include_tasks: ${TASKS}/ses_delivery.yml
PLAY
} > "${TMP}/delivery.yml"
# La puerta de deploy.yml sobre el .env real del host simulado
{ play_header; cat <<PLAY
    - ansible.builtin.slurp:
        src: "{{ app_dir }}/.env"
      register: app_env_file
      no_log: true
    - ansible.builtin.include_tasks: ${TASKS}/ses_host_gate.yml
PLAY
} > "${TMP}/gate.yml"
for t in ses_runtime refresh_backend; do
  { play_header; printf '    - ansible.builtin.include_tasks: %s\n' "${TASKS}/${t}.yml"; } > "${TMP}/${t}.yml"
done

setup() { run_playbook "${TMP}/setup.yml" "$@"; }
gate() { # <decisión del environment> <endpoint del environment>
  EXTRA="-e app_ses_intended=$1 -e app_ses_intended_host=$2" run_playbook "${TMP}/gate.yml"
}
backend_env() { # lo que Compose entregaría al backend, con cada $$ como $
  (cd "${APP}/compose" && "${TMP}/remote-bin/docker" compose --project-name piedrazul-notif-contract --file prod.yml \
    --env-file ../.env --env-file ../image.env config --format json 2>/dev/null) | jq '.services.backend.environment' 2>/dev/null || echo '{}'
}
delivered() { backend_env | jq -r --arg k "$1" '.[$k]' | sed 's/\$\$/$/g'; }
# Contenedor como el que Compose crearía hoy desde .env (el filtro jq permite alterarlo)
container_from_env() { # [filtro jq sobre el objeto de entorno]
  backend_env | jq --arg image "${IMAGE_SELECTED}" "walk(if type == \"string\" then gsub(\"\\\\\$\\\\\$\"; \"\$\") else . end) | ${1:-.}
    | {exists: true, container: {Id: \"abc\", Config: {Image: \$image, Env: (to_entries | map(\"\(.key)=\(.value)\"))},
       State: {Running: true, StartedAt: \"2026-01-01T00:00:00Z\"}}}" > "${TMP}/container.json"
}
no_container() { echo '{"exists": false}' > "${TMP}/container.json"; }
no_container

# ── 1. SendGrid retirado; expresión única ───────────────────────────────────
check "ningún componente operativo conserva SendGrid" \
  bash -c "! grep -rqiE 'sendgrid|PIEDRAZUL_BACKEND_MAIL_SEND' '${COMPOSE_DIR}' '${ANSIBLE_DIR}/roles' '${ANSIBLE_DIR}/inventories' '${WORKFLOW}'"
check "workflow: ningún paso lee NOTIFICATION_ENCRYPTION_KEY" bash -c "! grep -qw NOTIFICATION_ENCRYPTION_KEY '${WORKFLOW}'"
check "ningún componente declara NOTIFICATION_ENCRYPTION_KEY (backend, Compose, Ansible, .env.example)" \
  bash -c "! grep -rqE 'NOTIFICATION_ENCRYPTION_KEY|notifications\.encryption' '${ROOT}/backend/src/main' '${COMPOSE_DIR}' '${ANSIBLE_DIR}/roles' '${ANSIBLE_DIR}/inventories' '${ROOT}/.env.example' '${ROOT}/docker-compose.yml'"
ANSIBLE_HOST_RE="$(yq -r '.ses_smtp_host_pattern' "${GROUP_VARS}")"
BASH_HOST_RE="$(sed -n "s/^HOST_RE='\(.*\)'$/\1/p" "${SES_CONFIG}")"
check "endpoint: workflow y Ansible usan la misma expresión" test "${ANSIBLE_HOST_RE%\\Z}" = "${BASH_HOST_RE%\$}"
for h in email-smtp.us-east-1.amazonaws.com email-smtp-fips.us-east-1.amazonaws.com email-smtp.eu-central-1.api.aws \
  email-smtp-fips.us-gov-west-1.amazonaws.com email-smtp.ap-southeast-2.amazonaws.com email-smtp.il-central-1.api.aws; do
  check "endpoint válido: ${h}" bash -c "'${SES_CONFIG}' check-host '$h' && python3 -c 'import re,sys; sys.exit(0 if re.match(sys.argv[1], sys.argv[2]) else 1)' '${ANSIBLE_HOST_RE}' '$h'"
done
for h in smtp.example.org email-smtp.amazonaws.com email-smtp.us-east-1.amazonaws.com.evil.example EMAIL-SMTP.US-EAST-1.AMAZONAWS.COM \
  'email-smtp.us-east-1.amazonaws.com:587' 'x.email-smtp.us-east-1.amazonaws.com' 'email-smtp.us-east-1.amazonaws.com ' \
  'email-smtp.us_east_1.amazonaws.com' 'email-smtp.us-east-1.api.aws.example'; do
  check "endpoint rechazado: '${h}'" bash -c "! '${SES_CONFIG}' check-host '$h' && ! python3 -c 'import re,sys; sys.exit(0 if re.match(sys.argv[1], sys.argv[2]) else 1)' '${ANSIBLE_HOST_RE}' '$h'"
done
check "endpoint rechazado con salto de línea final" bash -c "! '${SES_CONFIG}' check-host \$'${HOST}\n' && ! python3 -c 'import re,sys; sys.exit(0 if re.match(sys.argv[1], sys.argv[2]) else 1)' '${ANSIBLE_HOST_RE}' \$'${HOST}\n'"

# ── 2. Deshabilitado: el camino de la primera instalación ───────────────────
if setup; then ok "deshabilitado sin ninguna entrada: validación, .env y entrega pasan"
else ko "deshabilitado sin ninguna entrada: validación, .env y entrega pasan"; fi
check ".env: NOTIFICATIONS_SES_ENABLED=false" grep -qx 'NOTIFICATIONS_SES_ENABLED=false' "${APP}/.env"
check ".env: endpoint y credenciales vacíos" \
  bash -c "grep -qx 'SES_SMTP_HOST=' '${APP}/.env' && grep -qx 'SES_SMTP_USERNAME=' '${APP}/.env' && grep -qx 'SES_SMTP_PASSWORD=' '${APP}/.env'"
check ".env: sin puerto, plazos, circuit breaker ni consola" bash -c "! grep -Eq '^(SES_SMTP_PORT|SES_SMTP_.*TIMEOUT|SES_BREAKER_|NOTIFICATIONS_CONSOLE_)' '${APP}/.env'"
check "deshabilitado=false explícito pasa" setup NOTIFICATIONS_SES_ENABLED=false
setup NOTIFICATIONS_SES_ENABLED=false "SES_SMTP_HOST=${HOST}" "SES_SMTP_USERNAME=${USERNAME}" "SES_SMTP_PASSWORD=${PASSWORD}" || true
check "deshabilitado: los valores que tenga el runner no llegan a .env" \
  bash -c "! grep -qF '${USERNAME}' '${APP}/.env' && grep -qx 'SES_SMTP_HOST=' '${APP}/.env'"
(cd "${APP}/compose" && "${TMP}/remote-bin/docker" compose --project-name piedrazul-notif-contract --file prod.yml \
  --env-file ../.env --env-file ../image.env --profile migrate config --format json > "${TMP}/config.json" 2>/dev/null) &&
  ok "Compose de producción renderiza con el .env deshabilitado" || { ko "Compose de producción renderiza con el .env deshabilitado"; exit 1; }
check "Compose: el backend recibe SES apagado y vacío" \
  jq -e '.services.backend.environment | .NOTIFICATIONS_SES_ENABLED == "false" and .SES_SMTP_HOST == "" and .SES_SMTP_USERNAME == "" and .SES_SMTP_PASSWORD == ""' "${TMP}/config.json"
check "Compose: no entrega puerto, plazos, circuit breaker ni consola" \
  bash -c "! jq -r '.services.backend.environment | keys[]' '${TMP}/config.json' | grep -Eq '^(SES_SMTP_PORT|SES_SMTP_.*TIMEOUT|SES_SMTP_TOTAL_DEADLINE|SES_BREAKER_|NOTIFICATIONS_CONSOLE_)'"
for key in NOTIFICATIONS_SES_ENABLED SES_SMTP_HOST SES_SMTP_USERNAME SES_SMTP_PASSWORD; do
  check "Compose: solo el backend recibe ${key} (migrate incluido en el render)" \
    test "$(jq -r --arg k "${key}" '.services | to_entries[] | select(.value.environment // {} | has($k)) | .key' "${TMP}/config.json" | tr '\n' ' ')" = "backend "
done
check "Compose: el render incluye el servicio migrate" jq -e '.services.migrate' "${TMP}/config.json"
check "Compose: ningún servicio toma .env entero (env_file)" bash -c "! jq -e '[.services[] | select(has(\"env_file\"))] | length > 0' '${TMP}/config.json'"
check "Compose: ningún servicio recibe NOTIFICATION_ENCRYPTION_KEY" \
  bash -c "! jq -r '.services[].environment // {} | keys[]' '${TMP}/config.json' | grep -qx NOTIFICATION_ENCRYPTION_KEY"
# Un .env anterior, sin estas claves: Compose no falla y no activa nada
sed -E '/^(NOTIFICATIONS_SES_ENABLED|SES_SMTP_)/d' "${APP}/.env" > "${APP}/.env.old"
cp "${APP}/.env" "${TMP}/env.current"; mv "${APP}/.env.old" "${APP}/.env"
check "Compose: un .env anterior a estas claves deja SES apagado" test "$(delivered NOTIFICATIONS_SES_ENABLED)" = false
check "deploy: un .env anterior a estas claves pasa con SES deshabilitado" gate false ''
cp "${TMP}/env.current" "${APP}/.env"
check "deploy: .env deshabilitado pasa con la decisión deshabilitada" gate false ''

# ── 3. Habilitado ───────────────────────────────────────────────────────────
if setup "${ENABLED_ENV[@]}"; then ok "habilitado: validación, .env y entrega pasan"
else ko "habilitado: validación, .env y entrega pasan"; grep -E 'FAILED|fatal' "${TMP}/run.log" | cut -c1-200 | sed 's/synthetic-[^ ]*/<sintético>/g' || true; fi
check "habilitado: el backend recibe true" test "$(delivered NOTIFICATIONS_SES_ENABLED)" = true
check "habilitado: el backend recibe el endpoint" test "$(delivered SES_SMTP_HOST)" = "${HOST}"
check "habilitado: el backend recibe el usuario" test "$(delivered SES_SMTP_USERNAME)" = "${USERNAME}"
check "habilitado: el backend recibe la contraseña exacta (caracteres sensibles)" test "$(delivered SES_SMTP_PASSWORD)" = "${PASSWORD}"
check "habilitado: el remitente sigue viniendo de group_vars" test "$(delivered NOTIFICATIONS_EMAIL_FROM)" = "$(yq -r '.notifications_email_from' "${GROUP_VARS}")"
check "habilitado: el nombre del remitente llega con espacios" test "$(delivered NOTIFICATIONS_EMAIL_FROM_NAME)" = "$(yq -r '.notifications_email_from_name' "${GROUP_VARS}")"
cp "${APP}/.env" "${TMP}/env.enabled"
check "deploy: .env habilitado y al día pasa" gate true "${HOST}"
sed -i 's/^SES_SMTP_PASSWORD=.*/SES_SMTP_PASSWORD=$HOME/' "${APP}/.env"
if run_playbook "${TMP}/delivery.yml" "${ENABLED_ENV[@]}"; then ko "una entrega alterada de la contraseña se detecta"
else check "una entrega alterada de la contraseña se detecta" log_has 'configuración de SES: SES_SMTP_PASSWORD (valores no mostrados)'; fi
cp "${TMP}/env.enabled" "${APP}/.env"

# ── 4. Entradas inválidas: fallan antes de escribir .env ─────────────────────
invalid_case() { # <descripción> <texto esperado> [CLAVE=valor ...]
  local desc="$1" expected="$2"; shift 2
  cp "${TMP}/env.current" "${APP}/.env"
  if setup "$@"; then ko "${desc}"
  elif cmp -s "${TMP}/env.current" "${APP}/.env" && log_has "${expected}"; then ok "${desc}"
  else ko "${desc} (mensaje o .env)"; fi
}
SYN_USER=SES_SMTP_USERNAME=${USERNAME}; SYN_PASS=SES_SMTP_PASSWORD=${PASSWORD}
for v in TRUE True yes 1 ' true' 'true '; do
  invalid_case "decisión '${v}' falla" 'debe ser exactamente true o false' "NOTIFICATIONS_SES_ENABLED=${v}" "SES_SMTP_HOST=${HOST}" "${SYN_USER}" "${SYN_PASS}"
done
invalid_case "habilitado sin endpoint falla" 'SES_SMTP_HOST no es un endpoint' NOTIFICATIONS_SES_ENABLED=true "${SYN_USER}" "${SYN_PASS}"
invalid_case "habilitado con otro servidor falla" 'SES_SMTP_HOST no es un endpoint' NOTIFICATIONS_SES_ENABLED=true SES_SMTP_HOST=smtp.example.org "${SYN_USER}" "${SYN_PASS}"
invalid_case "habilitado sin usuario falla" 'SES_SMTP_USERNAME vacío' NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "${SYN_PASS}"
invalid_case "habilitado sin contraseña falla" 'SES_SMTP_PASSWORD vacío' NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "${SYN_USER}"
invalid_case "contraseña en blanco falla" 'SES_SMTP_PASSWORD vacío' NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "${SYN_USER}" 'SES_SMTP_PASSWORD=   '
invalid_case "contraseña con espacio falla" 'SES_SMTP_PASSWORD vacío' NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "${SYN_USER}" 'SES_SMTP_PASSWORD=synthetic pw'
invalid_case "contraseña con salto de línea falla" 'SES_SMTP_PASSWORD vacío' NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "${SYN_USER}" $'SES_SMTP_PASSWORD=synthetic\npw'
invalid_case "usuario no ASCII falla" 'SES_SMTP_USERNAME vacío' NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" 'SES_SMTP_USERNAME=usuarioñ' "${SYN_PASS}"
EXTRA='-e notifications_email_from=sin-dominio' invalid_case "remitente inválido falla" 'notifications_email_from no es un remitente válido' "${ENABLED_ENV[@]}"
EXTRA='-e notifications_email_from_name=""' invalid_case "nombre de remitente vacío falla" 'notifications_email_from_name vacío' "${ENABLED_ENV[@]}"
cp "${TMP}/env.current" "${APP}/.env"

# ── 5. Preflight ────────────────────────────────────────────────────────────
OUTPUT_LOG="${TMP}/preflight.log"
preflight() { # [CLAVE=valor ...]
  env -i PATH="${PATH}" SES_GROUP_VARS="${GROUP_VARS}" SES_VARS_OUTSIDE_ENVIRONMENT=false "$@" "${SES_CONFIG}" preflight > "${OUTPUT_LOG}" 2>&1
}
preflight_logged() { preflight "$@"; local rc=$?; cat "${OUTPUT_LOG}" >> "${TMP}/run.preflight.log"; return "${rc}"; }
out_has() { grep -qF -- "$1" "${OUTPUT_LOG}"; }
ENABLED_PREFLIGHT=(NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}")
HOSTCONF_PREFLIGHT=("${ENABLED_PREFLIGHT[@]}" RUN_HOST_CONFIG=true "SES_SMTP_USERNAME=${USERNAME}" "SES_SMTP_PASSWORD=${PASSWORD}")
for run in "Run P (intención con converge):true" "Run I:true" "convergencia sin deploy:true" "deploy ordinario:false"; do
  check "deshabilitado, ${run%:*}: pasa sin ninguna entrada" preflight_logged "RUN_HOST_CONFIG=${run##*:}"
  check "deshabilitado, ${run%:*}: informa ses_enabled=false, sin credenciales" bash -c "grep -qx ses_enabled=false '${OUTPUT_LOG}' && grep -qx ses_credentials=none '${OUTPUT_LOG}'"
done
check "false explícito pasa" preflight_logged NOTIFICATIONS_SES_ENABLED=false
check "deshabilitado con un SES_SMTP_HOST inválido guardado: no se valida" preflight_logged NOTIFICATIONS_SES_ENABLED=false SES_SMTP_HOST=x
for v in TRUE True yes 1 ' true' 'true ' 'false '; do
  check "preflight: decisión '${v}' falla" fails preflight_logged "NOTIFICATIONS_SES_ENABLED=${v}" "SES_SMTP_HOST=${HOST}"
done
preflight_logged NOTIFICATIONS_SES_ENABLED=synthetic-flag || true
check "preflight: la decisión inválida se nombra sin mostrarla" bash -c "grep -q 'valor no mostrado' '${OUTPUT_LOG}' && ! grep -q synthetic-flag '${OUTPUT_LOG}'"
check "preflight: habilitado con converge y entradas válidas pasa" preflight_logged "${HOSTCONF_PREFLIGHT[@]}"
check "preflight: con converge informa ses_credentials=converge" out_has ses_credentials=converge
check "preflight: deploy sin secretos pasa" preflight_logged "${ENABLED_PREFLIGHT[@]}" RUN_HOST_CONFIG=false
check "preflight: deploy sin secretos informa ses_credentials=host" out_has ses_credentials=host
check "preflight: deploy sin secretos advierte que no renueva credenciales" out_has 'Este run no las renueva'
check "preflight: habilitado sin endpoint falla" fails preflight_logged NOTIFICATIONS_SES_ENABLED=true RUN_HOST_CONFIG=false
check "preflight: lo nombra (endpoint)" out_has 'falta la variable SES_SMTP_HOST'
check "preflight: endpoint que no es SES falla" fails preflight_logged NOTIFICATIONS_SES_ENABLED=true SES_SMTP_HOST=smtp.example.org
check "preflight: con converge sin usuario falla" fails preflight_logged "${ENABLED_PREFLIGHT[@]}" RUN_HOST_CONFIG=true "SES_SMTP_PASSWORD=${PASSWORD}"
check "preflight: lo nombra" out_has 'falta el secreto SES_SMTP_USERNAME'
check "preflight: con converge sin contraseña falla" fails preflight_logged "${ENABLED_PREFLIGHT[@]}" RUN_HOST_CONFIG=true "SES_SMTP_USERNAME=${USERNAME}"
check "preflight: credencial con espacios falla" fails preflight_logged "${ENABLED_PREFLIGHT[@]}" RUN_HOST_CONFIG=true "SES_SMTP_USERNAME=${USERNAME}" 'SES_SMTP_PASSWORD=synthetic pw'
check "preflight: credencial no ASCII falla" fails preflight_logged "${ENABLED_PREFLIGHT[@]}" RUN_HOST_CONFIG=true "SES_SMTP_USERNAME=${USERNAME}" 'SES_SMTP_PASSWORD=contraseñasintética'
check "preflight: remitente inválido falla" fails env -i PATH="${PATH}" SES_GROUP_VARS=/nonexistent SES_VARS_OUTSIDE_ENVIRONMENT=false "${ENABLED_PREFLIGHT[@]}" "${SES_CONFIG}" preflight
for v in true '' ausente; do
  args=(); [ "$v" = ausente ] || args=("SES_VARS_OUTSIDE_ENVIRONMENT=$v")
  check "preflight: copia fuera del environment o sin comprobar ('${v}') falla, también deshabilitado" \
    fails env -i PATH="${PATH}" SES_GROUP_VARS="${GROUP_VARS}" "${args[@]+"${args[@]}"}" "${SES_CONFIG}" preflight
done

# ── 6. Workflow ─────────────────────────────────────────────────────────────
step() { yq -r ".jobs.production.steps[] | select(.id == \"$1\") | $2" "${WORKFLOW}"; }
step_index() { yq -r '.jobs.production.steps | to_entries | .[] | select(.value.id == "'"$1"'") | .key' "${WORKFLOW}"; }
SES_SECRET_STEPS="$(yq -r '.jobs[] | .steps[]? | select(((.env // {}) | to_entries | map(.value | tostring) | join(" ")) | test("secrets\.SES_SMTP_")) | .id' "${WORKFLOW}" | sort | tr '\n' ' ')"
check "workflow: solo preflight y converge reciben secretos de SES" test "${SES_SECRET_STEPS}" = "converge ses_preflight "
check "workflow: ninguna otra parte referencia esos secretos" \
  test "$(grep -c 'secrets\.SES_SMTP_' "${WORKFLOW}")" = 4
check "workflow: cada lectura de un secreto de SES exige la decisión habilitada" \
  bash -c "! grep 'secrets\.SES_SMTP_' '${WORKFLOW}' | grep -qv \"vars.NOTIFICATIONS_SES_ENABLED == 'true' && \""
check "workflow: el preflight solo lee los secretos con converge" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; step ses_preflight .env.SES_SMTP_PASSWORD | grep -q \"needs.classify.outputs.run_host_config == 'true'\""
check "workflow: el deploy no recibe secretos" bash -c '[ -n "$1" ] && ! grep -q "secrets\." <<< "$1"' _ "$(step deploy '.env[]')"
check "workflow: el deploy recibe la decisión y el endpoint" \
  test "$(step deploy '.env | keys | map(select(test("SES"))) | join(" ")')" = "NOTIFICATIONS_SES_ENABLED SES_SMTP_HOST"
check "workflow: converge y deploy declaran la decisión y el endpoint" \
  bash -c "for s in converge deploy; do yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .run\" '${WORKFLOW}' | grep -q 'app_ses_intended_host=\${SES_SMTP_HOST}' || exit 1; done"
check "workflow: el endpoint deshabilitado no llega a converge ni deploy" \
  bash -c "for s in converge deploy; do yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .env.SES_SMTP_HOST\" '${WORKFLOW}' | grep -q \"vars.NOTIFICATIONS_SES_ENABLED == 'true' && vars.SES_SMTP_HOST\" || exit 1; done"
check "workflow: el entorno de los jobs no lleva valores de SES" test -z "$(yq -r '.jobs[] | (.env // {}) | keys | .[] | select(test("^(NOTIFICATIONS_SES|SES_SMTP)"))' "${WORKFLOW}")"
check "workflow: el preflight corre por intención, también en Run P" \
  bash -c "[ \"\$1\" = \"env.SES_PREFLIGHT_REQUIRED == 'true'\" ] && [ \"\$2\" = '\${{ needs.classify.outputs.deploy_required }}' ]" _ \
  "$(step ses_preflight .if)" "$(yq -r '.jobs.production.env.SES_PREFLIGHT_REQUIRED' "${WORKFLOW}")"
check "workflow: el preflight usa la intención de converge, no la del job" test "$(step ses_preflight .env.RUN_HOST_CONFIG)" = '${{ needs.classify.outputs.run_host_config }}'
check "workflow: un fallo del preflight detiene el job" test "$(step ses_preflight '."continue-on-error" // false')" = false
for later in kc_admin_preflight fresh_apply hcp_apply host_trust access host_state converge deploy; do
  check "workflow: el preflight corre antes de ${later}" before "$(step_index ses_preflight)" "$(step_index "${later}")"
done
check "classify: no tiene environment y por eso ve solo copias externas" test "$(yq -r '.jobs.classify.environment // "ninguno"' "${WORKFLOW}")" = ninguno
# La expresión REAL, evaluada con la semántica documentada de GitHub: vars.X sin definir vale '',
# contains() no distingue mayúsculas y toJSON(vars) es el mapa de nombres a valores. Es una
# simulación: que GitHub incluya en vars una variable con valor vacío no se prueba aquí.
yq -r '.jobs.classify.steps[] | select(.id == "intent") | .env.SES_VARS_OUTSIDE_ENVIRONMENT' "${WORKFLOW}" > "${TMP}/outside.expr"
cat > "${TMP}/outside.py" <<'PY'
import json, re, sys
variables = json.loads(sys.argv[2])
expr = re.fullmatch(r"\$\{\{(.*)\}\}", open(sys.argv[1]).read().strip()).group(1)
upper = {k.upper(): v for k, v in variables.items()}
dumped = json.dumps(variables, indent=2).lower()
terms = [t.strip() for t in expr.split("||")]
def term(t):
    m = re.fullmatch(r"vars\.([A-Z_]+) != ''", t)
    if m:
        return upper.get(m.group(1), "") != ""
    m = re.fullmatch(r"contains\(toJSON\(vars\), '(.*)'\)", t)
    if m:
        return m.group(1).lower() in dumped
    sys.exit(f"término no reconocido: {t}")
print("true" if any(term(t) for t in terms) else "false")
PY
outside() { python3 "${TMP}/outside.py" "${TMP}/outside.expr" "$1"; }
for c in '{"NOTIFICATIONS_SES_ENABLED":""}' '{"NOTIFICATIONS_SES_ENABLED":"false"}' '{"NOTIFICATIONS_SES_ENABLED":"true"}' \
  '{"SES_SMTP_HOST":""}' '{"SES_SMTP_HOST":"x"}' '{"notifications_ses_enabled":""}' '{"ses_smtp_host":"x"}' \
  '{"PRODUCTION_PUSH_DEPLOY":"deferred","SES_SMTP_HOST":""}'; do
  check "classify: copia ${c} se detecta" test "$(outside "$c")" = true
done
for c in '{}' '{"PRODUCTION_PUSH_DEPLOY":"deferred"}' '{"OTRA":"mención \"SES_SMTP_HOST\" en un valor"}' '{"SES_SMTP_HOST_OLD":"x"}'; do
  check "classify: ${c} no es una copia" test "$(outside "$c")" = false
done
check "classify: los dos nombres se buscan por valor y por nombre" \
  bash -c "grep -q 'vars.NOTIFICATIONS_SES_ENABLED != ' '${TMP}/outside.expr' && grep -q 'vars.SES_SMTP_HOST != ' '${TMP}/outside.expr'"
check "classify: no pide permisos nuevos" test "$(yq -r '.jobs.classify.permissions | to_entries | map(.key + "=" + .value) | join(",")' "${WORKFLOW}")" = "contents=read,actions=read"
check "workflow: nadie vuelca los secretos para buscar nombres" bash -c "! grep -qE 'toJSON\\(\\s*secrets' '${WORKFLOW}'"
check "classify: publica el resultado" test "$(yq -r '.jobs.classify.outputs.ses_vars_outside_environment' "${WORKFLOW}")" = '${{ steps.intent.outputs.ses_vars_outside_environment }}'
check "classify: el resultado se escribe en todas las ramas" bash -c "yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .run' '${WORKFLOW}' | grep -q '^echo \"ses_vars_outside_environment='"
check "workflow: el preflight toma el resultado de classify" \
  test "$(step ses_preflight .env.SES_VARS_OUTSIDE_ENVIRONMENT)" = '${{ needs.classify.outputs.ses_vars_outside_environment }}'
step ses_preflight .run > "${TMP}/preflight-step.sh"
run_step() { # [CLAVE=valor ...] → el paso real, desde la raíz del repositorio
  ( cd "${ROOT}" && env -i PATH="${PATH}" RUNNER_TEMP="${TMP}" GITHUB_OUTPUT="${TMP}/gh-output" SES_VARS_OUTSIDE_ENVIRONMENT=false "$@" \
      bash "${TMP}/preflight-step.sh" ) > "${TMP}/step.log" 2>&1
}
: > "${TMP}/gh-output"
check "paso real: deshabilitado pasa y escribe sus salidas" bash -c "$(declare -f run_step); TMP='${TMP}'; ROOT='${ROOT}'; run_step RUN_HOST_CONFIG=true && grep -qx ses_enabled=false '${TMP}/gh-output' && ! grep -q '::' '${TMP}/gh-output'"
check "paso real: un endpoint inválido falla (pipefail) sin salidas nuevas" bash -c "$(declare -f run_step); TMP='${TMP}'; ROOT='${ROOT}'; ! run_step NOTIFICATIONS_SES_ENABLED=true SES_SMTP_HOST=smtp.example.org && grep -q '^::error' '${TMP}/step.log'"
check "resumen: un deploy sin secretos dice que no renovó credenciales" \
  bash -c "yq -r '.jobs.production.steps[] | select(.name == \"Resumen de producción\") | .run' '${WORKFLOW}' | grep -q 'credenciales no renovadas ni comparadas'"
check "resumen: separa configuración de entrega" \
  bash -c "yq -r '.jobs.production.steps[] | select(.name == \"Resumen de producción\") | .run' '${WORKFLOW}' | grep -q 'Entrega de correo \*\*no verificada\*\*'"

# ── 7. Deriva antes de mutar y ciclo de vida ────────────────────────────────
OTHER=email-smtp.eu-west-1.amazonaws.com
cp "${TMP}/env.enabled" "${APP}/.env"
gate_fails_with() { # <descripción> <texto> <decisión> <endpoint>
  if gate "$3" "$4"; then ko "$1"; elif log_has "$2" && log_has 'corre'; then ok "$1"; else ko "$1 (mensaje)"; fi
}
if gate false ''; then ko "deploy: habilitado en el host y deshabilitado en el environment falla"
else check "deploy: habilitado en el host y deshabilitado en el environment falla" log_has 'sigue habilitado en el host'; fi
if gate true "${OTHER}"; then ko "deploy: endpoint cambiado sin converge falla"
else check "deploy: endpoint cambiado sin converge falla" log_has 'SES_SMTP_HOST del host difiere'; fi
check "deploy: las credenciales no se comparan (limitación declarada): un .env con otras credenciales pasa" gate true "${HOST}"
cp "${TMP}/env.current" "${APP}/.env"
if gate true "${HOST}"; then ko "deploy: habilitado en el environment sin converge falla"
else check "deploy: habilitado en el environment sin converge falla" log_has 'habilitado en el environment y no en el host'; fi
incomplete_case() { # <descripción> <sed> <texto> <decisión> <endpoint>
  cp "${TMP}/env.enabled" "${APP}/.env"; sed -i -E "$2" "${APP}/.env"
  if gate "$4" "$5"; then ko "$1"; else check "$1" log_has "$3"; fi
}
incomplete_case "deploy: .env habilitado sin usuario falla" 's/^SES_SMTP_USERNAME=.*/SES_SMTP_USERNAME=/' 'no tiene SES_SMTP_USERNAME' true "${HOST}"
incomplete_case "deploy: .env habilitado con contraseña vacía entre comillas falla" 's/^SES_SMTP_PASSWORD=.*/SES_SMTP_PASSWORD=""/' 'no tiene SES_SMTP_PASSWORD' true "${HOST}"
incomplete_case "deploy: .env deshabilitado que conserva credenciales falla" 's/^NOTIFICATIONS_SES_ENABLED=.*/NOTIFICATIONS_SES_ENABLED=false/' 'conserva endpoint o credenciales' false ''
incomplete_case "deploy: decisión malformada en .env falla" 's/^NOTIFICATIONS_SES_ENABLED=.*/NOTIFICATIONS_SES_ENABLED=TRUE/' 'no es true ni false' false ''

cp "${TMP}/env.current" "${APP}/.env"
check "ciclo: habilitar sobre un .env deshabilitado" setup "${ENABLED_ENV[@]}"
check "ciclo: habilitado, deploy pasa" gate true "${HOST}"
check "ciclo: cambiar el endpoint por converge" setup NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${OTHER}" "${SYN_USER}" "${SYN_PASS}"
check "ciclo: deploy pasa con el endpoint nuevo" gate true "${OTHER}"
check "ciclo: rotar por converge" setup NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${OTHER}" "${SYN_USER}" "SES_SMTP_PASSWORD=${ROTATED}"
check "ciclo: Compose entrega la credencial rotada" test "$(delivered SES_SMTP_PASSWORD)" = "${ROTATED}"
cp "${APP}/.env" "${TMP}/env.rotated"
if setup NOTIFICATIONS_SES_ENABLED=true SES_SMTP_HOST=smtp.example.org "${SYN_USER}" "${SYN_PASS}"; then ko "ciclo: un converge inválido falla"
else check "ciclo: un converge inválido deja el .env anterior" cmp -s "${TMP}/env.rotated" "${APP}/.env"; fi
check "ciclo: corregir la entrada recupera el converge" setup NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}" "${SYN_USER}" "${SYN_PASS}"
check "ciclo: deshabilitar por converge" setup NOTIFICATIONS_SES_ENABLED=false
check "ciclo: deshabilitar quita los valores efectivos" \
  bash -c "! grep -qF '${USERNAME}' '${APP}/.env' && grep -qx 'SES_SMTP_USERNAME=' '${APP}/.env' && grep -qx 'SES_SMTP_PASSWORD=' '${APP}/.env'"
check "ciclo: Compose ya no entrega credenciales" test "$(delivered SES_SMTP_PASSWORD)" = ""
check "ciclo: deshabilitado, deploy pasa" gate false ''

# ── 8. En ejecución ─────────────────────────────────────────────────────────
runtime() { # <decisión> <endpoint> → ses_runtime.yml con modo enforce
  EXTRA="-e app_ses_intended=$1 -e app_ses_intended_host=$2 ${RUNTIME_EXTRA:-}" run_playbook "${TMP}/ses_runtime.yml"
}
refresh() { # <decisión> <endpoint> <sigue un deploy>
  EXTRA="-e app_ses_intended=$1 -e app_ses_intended_host=$2 -e app_deploy_follows=$3" run_playbook "${TMP}/refresh_backend.yml"
}
no_container
check "runtime: deshabilitado y sin backend, nada obsoleto" runtime false ''
container_from_env
check "runtime: deshabilitado, contenedor al día" runtime false ''
container_from_env 'del(.NOTIFICATIONS_SES_ENABLED, .SES_SMTP_HOST, .SES_SMTP_USERNAME, .SES_SMTP_PASSWORD)'
check "runtime: contenedor anterior a estas claves equivale a deshabilitado" runtime false ''
check "refresh: converge sin deploy con el backend al día pasa" refresh false '' false
cp "${TMP}/env.enabled" "${APP}/.env"
no_container
if runtime true "${HOST}"; then ko "runtime: habilitado sin backend falla"
else check "runtime: habilitado sin backend falla" log_has 'no hay contenedor backend'; fi
check "runtime: habilitado sin backend, con deploy en el mismo run, queda para el deploy" refresh true "${HOST}" true
check "runtime: el modo diferido lo informa" log_has 'Lo verifica el deploy de este run'
container_from_env
check "runtime: habilitado, contenedor al día" runtime true "${HOST}"
if runtime false ''; then ko "runtime: el environment y Compose discrepan: falla"
else check "runtime: el environment y Compose discrepan: falla" log_has 'no coinciden con lo que Compose'; fi
if runtime true "${OTHER}"; then ko "runtime: endpoint del environment distinto de Compose falla"
else check "runtime: endpoint del environment distinto de Compose falla" log_has 'no coinciden con lo que Compose'; fi
stale_case() { # <descripción> <filtro> <clave>
  container_from_env "$2"
  if runtime true "${HOST}"; then ko "$1"; else check "$1" log_has "$3"; fi
}
stale_case "runtime: decisión obsoleta en el contenedor falla" '.NOTIFICATIONS_SES_ENABLED = "false"' 'NOTIFICATIONS_SES_ENABLED'
stale_case "runtime: endpoint obsoleto en el contenedor falla" ".SES_SMTP_HOST = \"${OTHER}\"" 'SES_SMTP_HOST'
stale_case "runtime: credencial anterior a la rotación falla" ".SES_SMTP_PASSWORD = \"${ROTATED}\"" 'SES_SMTP_PASSWORD; valores no mostrados'
container_from_env ".SES_SMTP_PASSWORD = \"${ROTATED}\""
RUNTIME_EXTRA='-e app_ses_runtime_mode=deferred'
check "runtime: modo diferido no exige" runtime true "${HOST}"
RUNTIME_EXTRA=''
if refresh true "${HOST}" false; then ko "refresh: un backend recreado que no refleja Compose falla"
else check "refresh: un backend recreado que no refleja Compose falla" log_has 'SES_SMTP_PASSWORD; valores no mostrados'; fi
jq --arg image "ghcr.io/example/backend@sha256:$(printf 'e%.0s' {1..64})" '.container.Config.Image = $image' "${TMP}/container.json" > "${TMP}/container.other"
mv "${TMP}/container.other" "${TMP}/container.json"
if refresh true "${HOST}" false; then ko "refresh: converge sin deploy no oculta un backend obsoleto no recreable"
else check "refresh: converge sin deploy no oculta un backend obsoleto no recreable" log_has 'pendiente de recrearlo'; fi
check "refresh: con deploy en el mismo run lo verifica el deploy" refresh true "${HOST}" true

# ── 9. Imagen: contenedores reales, sin red ─────────────────────────────────
GATE_FILE="${TASKS}/ses_image_gate.yml"
LIST_TASK='Listar el JAR de la imagen del backend seleccionada'
VERIFY_TASK='Verificar que la imagen del backend contiene el proveedor SES'
check "imagen: la inspección corre sin red, sin escritura y sin capacidades" \
  bash -c "yq -r '.[] | select(.name == \"${LIST_TASK}\") | .\"ansible.builtin.command\".argv[]' '${GATE_FILE}' | tr '\n' ' ' | grep -q -- '--rm --pull=never --network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges --entrypoint=unzip'"
check "imagen: la inspección no recibe entorno ni montajes" \
  bash -c "! yq -r '.[] | select(.name == \"${LIST_TASK}\") | .\"ansible.builtin.command\".argv[]' '${GATE_FILE}' | grep -Eq -- '^(-e|--env|--env-file|-v|--volume|--mount)'"
check "imagen: un fallo de la inspección no aborta antes de la verificación" \
  test "$(yq -r ".[] | select(.name == \"${LIST_TASK}\") | .failed_when" "${GATE_FILE}")" = false
yq "[.[] | select(.name == \"${LIST_TASK}\" or .name == \"${VERIFY_TASK}\")]" "${GATE_FILE}" > "${TMP}/probe.yml"
{ play_header; printf '    - ansible.builtin.include_tasks: %s\n' "${TMP}/probe.yml"; } > "${TMP}/probe-play.yml"
yq '[.[] | select(.name == "Validar la identidad de la imagen del backend (SES)")]' "${GATE_FILE}" > "${TMP}/identity.yml"
{ play_header; printf '    - ansible.builtin.include_tasks: %s\n' "${TMP}/identity.yml"; } > "${TMP}/identity-play.yml"
probe() { EXTRA="-e backend_image_ref=$1" run_playbook "${TMP}/probe-play.yml"; }
identity_ok() { EXTRA="-e backend_image_ref=$1 ${EXTRA_ID}" run_playbook "${TMP}/identity-play.yml"; }
EXACT_REPO=ghcr.io/example/backend
EXTRA_ID="-e backend_image=${EXACT_REPO}"
check "imagen: acepta la referencia exacta del repositorio" identity_ok "${IMAGE_SELECTED}"
check "imagen: rechaza un tag" fails identity_ok "${EXACT_REPO}:latest"
check "imagen: rechaza otro repositorio" fails identity_ok "ghcr.io/example/otro@sha256:$(printf 'a%.0s' {1..64})"

BASE_IMAGE="$(awk '$1 == "FROM" {image = $2} END {print image}' "${ROOT}/backend/Dockerfile")"
fixture_image() { # <nombre> [entrada del JAR...]; sin entradas: imagen sin JAR
  local name="$1" dir="${TMP}/image-${1}"; shift
  mkdir -p "${dir}"
  printf 'FROM %s\n' "${BASE_IMAGE}" > "${dir}/Dockerfile"
  if [ "$#" -gt 0 ]; then
    python3 - "${dir}/app.jar" "$@" <<'PY2'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as jar:
    for entry in sys.argv[2:]:
        jar.writestr(entry, b"synthetic")
PY2
    printf 'COPY app.jar /app/app.jar\n' >> "${dir}/Dockerfile"
  fi
  docker build --pull=false -q -t "${IMAGE_PREFIX}:${name}" "${dir}" >/dev/null 2>"${TMP}/image-build.log"
}
PROVIDER_CLASS="$(yq -r '.ses_image_provider_class' "${GROUP_VARS}")"
if fixture_image capable "${PROVIDER_CLASS}" BOOT-INF/classes/application.yaml \
   && fixture_image incapable BOOT-INF/classes/application.yaml BOOT-INF/classes/other/Provider.class \
   && fixture_image misplaced BOOT-INF/classes/other/ses/SesEmailProvider.class \
   && fixture_image prefixed "not-${PROVIDER_CLASS}" \
   && fixture_image nojar; then
  ok "imagen: imágenes de prueba construidas desde la base de backend/Dockerfile"
  check "imagen de prueba con el proveedor: aceptada" probe "${IMAGE_PREFIX}:capable"
  check "imagen de prueba sin el proveedor: rechazada" fails probe "${IMAGE_PREFIX}:incapable"
  check "imagen rechazada: el mensaje la explica" log_has 'no se pudo establecer que la imagen'
  check "imagen de prueba con la clase en otra ruta: rechazada" fails probe "${IMAGE_PREFIX}:misplaced"
  check "imagen de prueba con la ruta como sufijo de otra: rechazada" fails probe "${IMAGE_PREFIX}:prefixed"
  check "imagen de prueba sin JAR: rechazada (no se pudo establecer)" fails probe "${IMAGE_PREFIX}:nojar"
  check "imagen ausente del host: rechazada, sin descargar" fails probe "${IMAGE_PREFIX}:absent"
else
  ko "imagen: imágenes de prueba construidas desde la base de backend/Dockerfile (propiedad sin verificar: capacidad de imagen)"
  sed 's/^/      | /' "${TMP}/image-build.log" | tail -5 || true
fi
if [ -n "${SES_REAL_CAPABLE_IMAGE:-}" ]; then check "imagen real con SES: aceptada" probe "${SES_REAL_CAPABLE_IMAGE}"; fi
if [ -n "${SES_REAL_INCAPABLE_IMAGE:-}" ]; then check "imagen real anterior a SES: rechazada" fails probe "${SES_REAL_INCAPABLE_IMAGE}"; fi

# ── 9b. La puerta en sus dos caminos: tareas reales de setup.yml y deploy.yml ─
# Docker y la descarga simulados: lo que importa aquí es qué identidad reciben, y en qué orden.
gate_task() { # <archivo real> → su inclusión de la puerta, con la ruta absoluta
  yq "[.[] | select(.name == \"Verificar que la imagen del backend trae el proveedor SES\")] | .[0].\"ansible.builtin.include_tasks\" = \"${TASKS}/ses_image_gate.yml\"" "$1"
}
gate_task "${TASKS}/setup.yml" > "${TMP}/gate-setup.yml"
gate_task "${TASKS}/deploy.yml" > "${TMP}/gate-deploy.yml"
{ play_header; cat <<PLAY
    - ansible.builtin.include_tasks: ${TASKS}/ses_inputs.yml
    - ansible.builtin.include_tasks: ${TMP}/gate-setup.yml
    - ansible.builtin.file:
        path: "{{ app_dir }}/env-written"
        state: touch
PLAY
} > "${TMP}/path-setup.yml"
{ play_header; cat <<PLAY
    - ansible.builtin.slurp:
        src: "{{ app_dir }}/.env"
      register: app_env_file
      no_log: true
    - ansible.builtin.include_tasks: ${TASKS}/ses_host_gate.yml
    - ansible.builtin.include_tasks: ${TMP}/gate-deploy.yml
    - ansible.builtin.file:
        path: "{{ app_dir }}/image-env-written"
        state: touch
PLAY
} > "${TMP}/path-deploy.yml"
mkdir -p "${TMP}/gate-bin"
cat > "${TMP}/gate-bin/docker" <<GATE
#!/usr/bin/env bash
if [ "\$1" = run ]; then
  printf '%s\n' "\$*" >> "\${FAKE_RUN_LOG}"
  cat "\${FAKE_JAR_LISTING}"
  exit "\${FAKE_RUN_RC:-0}"
fi
exec "${TMP}/remote-bin/docker" "\$@"
GATE
chmod +x "${TMP}/gate-bin/docker"
printf '%s\n' '#!/usr/bin/python' 'import os' 'from ansible.module_utils.basic import AnsibleModule' \
  'spec = {k: dict(type="raw") for k in ("name", "platform", "pull")}' \
  'module = AnsibleModule(argument_spec=spec)' \
  'open(os.environ["FAKE_PULL_LOG"], "a").write(module.params["name"] + "\n")' \
  'module.exit_json(changed=False)' > "${FAKE}/plugins/modules/docker_image_pull.py"
printf '  1234  2026-01-01 00:00   %s\n' "${PROVIDER_CLASS}" > "${TMP}/jar-capable"
printf '  1234  2026-01-01 00:00   BOOT-INF/classes/application.yaml\n' > "${TMP}/jar-incapable"
REPO_IMAGE="$(yq -r '.ghcr_registry + "/" + .ghcr_namespace + "/backend"' "${GROUP_VARS}")"
BUILD_REF="${REPO_IMAGE}@sha256:$(printf '1%.0s' {1..64})"
ROLLBACK_REF="${REPO_IMAGE}@sha256:$(printf '2%.0s' {1..64})"
path_run() { # <camino> <imagen> <listado del JAR> [CLAVE=valor ...]
  local path="$1" image="$2" listing="$3"; shift 3
  rm -f "${TMP}/pull.log" "${TMP}/run-args.log" "${APP}/env-written" "${APP}/image-env-written"
  touch "${TMP}/pull.log" "${TMP}/run-args.log"
  HOST_BIN="${TMP}/gate-bin" EXTRA="-e backend_image_ref=${image} ${PATH_EXTRA:-}" run_playbook "${TMP}/path-${path}.yml" \
    FAKE_RUN_LOG="${TMP}/run-args.log" FAKE_PULL_LOG="${TMP}/pull.log" FAKE_JAR_LISTING="${TMP}/${listing}" "$@"
}
inspected_exactly() { # <imagen>: descargó e inspeccionó esa identidad, una vez y nada más
  [ "$(cat "${TMP}/pull.log")" = "$1" ] && [ "$(wc -l < "${TMP}/run-args.log")" = 1 ] &&
    grep -qF -- " --entrypoint=unzip $1 -l /app/app.jar" "${TMP}/run-args.log"
}
not_inspected() { [ ! -s "${TMP}/pull.log" ] && [ ! -s "${TMP}/run-args.log" ]; }
check "camino setup: habilitado, imagen capaz: sigue" path_run setup "${BUILD_REF}" jar-capable "${ENABLED_ENV[@]}"
check "camino setup: inspecciona exactamente backend_image_ref" inspected_exactly "${BUILD_REF}"
check "camino setup: entonces escribe .env" test -e "${APP}/env-written"
check "camino setup: habilitado, imagen incapaz: falla" fails path_run setup "${BUILD_REF}" jar-incapable "${ENABLED_ENV[@]}"
check "camino setup: .env sin escribir" test ! -e "${APP}/env-written"
check "camino setup: inspección fallida: falla aunque el listado nombre la clase" fails path_run setup "${BUILD_REF}" jar-capable "${ENABLED_ENV[@]}" FAKE_RUN_RC=1
check "camino setup: referencia por tag: falla antes de descargar o ejecutar" fails path_run setup "${REPO_IMAGE}:latest" jar-capable "${ENABLED_ENV[@]}"
check "camino setup: el tag no se inspecciona" not_inspected
check "camino setup: deshabilitado no toca la imagen" path_run setup "${BUILD_REF}" jar-incapable
check "camino setup: deshabilitado, sin descarga ni ejecución" not_inspected
check "camino setup: deshabilitado sigue" test -e "${APP}/env-written"

cp "${TMP}/env.enabled" "${APP}/.env"
PATH_EXTRA="-e app_ses_intended=true -e app_ses_intended_host=${HOST}"
check "camino deploy: habilitado, rollback capaz: sigue" path_run deploy "${ROLLBACK_REF}" jar-capable
check "camino deploy: inspecciona la identidad del deploy, no otra" inspected_exactly "${ROLLBACK_REF}"
check "camino deploy: entonces escribe image.env" test -e "${APP}/image-env-written"
check "camino deploy: habilitado, rollback incapaz: falla" fails path_run deploy "${ROLLBACK_REF}" jar-incapable
check "camino deploy: image.env sin escribir" test ! -e "${APP}/image-env-written"
PATH_EXTRA="-e app_ses_intended=false -e app_ses_intended_host="
cp "${TMP}/env.current" "${APP}/.env"
check "camino deploy: deshabilitado no toca la imagen" path_run deploy "${ROLLBACK_REF}" jar-incapable
check "camino deploy: deshabilitado, sin descarga ni ejecución" not_inspected
check "camino deploy: deshabilitado sigue" test -e "${APP}/image-env-written"
PATH_EXTRA=''
for s in converge deploy; do
  check "workflow: ${s} recibe la identidad seleccionada" \
    bash -c "[ \"\$(yq -r '.jobs.production.steps[] | select(.id == \"$s\") | .env.BACKEND_IMAGE_REF' '${WORKFLOW}')\" = '\${{ steps.select.outputs.backend_image_ref }}' ] && yq -r '.jobs.production.steps[] | select(.id == \"$s\") | .run' '${WORKFLOW}' | grep -q 'backend_image_ref=\${BACKEND_IMAGE_REF}'"
done
check "refresh_backend.yml: solo recrea la identidad que setup.yml verificó" \
  bash -c "yq -r '.[] | select(.name == \"Decidir si el backend se recrea en este converge\") | .\"ansible.builtin.set_fact\".app_backend_refreshable' '${TASKS}/refresh_backend.yml' | grep -q 'Config.Image == backend_image_ref'"

# ── 10. Permisos de .env y orden en Ansible ─────────────────────────────────
SETUP="${TASKS}/setup.yml"
DEPLOY="${TASKS}/deploy.yml"
REFRESH="${TASKS}/refresh_backend.yml"
ENV_TASK='Generar .env desde template'
check ".env: root:root 0600" \
  test "$(yq -r ".[] | select(.name == \"${ENV_TASK}\") | .\"ansible.builtin.template\" | .owner + \":\" + .group + \" \" + .mode" "${SETUP}")" = "root:root 0600"
check ".env: la tarea lleva no_log" test "$(yq -r ".[] | select(.name == \"${ENV_TASK}\") | .no_log" "${SETUP}")" = true
check ".env: Ansible corre con become por defecto" grep -qE '^become +\= +True' "${ANSIBLE_DIR}/ansible.cfg"
check ".env: ninguna tarea del rol ni playbook que lo lea renuncia a become" \
  bash -c "! grep -rlE '^ *become: *(false|no)' '${ANSIBLE_DIR}/roles' '${ANSIBLE_DIR}/playbooks' | grep -v keycloak-admin-preflight.yml | grep -q ."
check ".env: keycloak-admin-preflight, sin become, no lo toca" bash -c "! grep -q '\.env' '${ANSIBLE_DIR}/playbooks/keycloak-admin-preflight.yml'"
check "setup.yml: entradas → imagen → copia de Compose" \
  bash -c "[ '$(task_index "${SETUP}" 'Tomar y validar la configuración del correo SES')' -lt '$(task_index "${SETUP}" 'Verificar que la imagen del backend trae el proveedor SES')' ] && [ '$(task_index "${SETUP}" 'Verificar que la imagen del backend trae el proveedor SES')' -lt '$(task_index "${SETUP}" 'Copiar archivos compose al servidor')' ]"
check "setup.yml: la entrega se verifica después de generar .env" before "$(task_index "${SETUP}" "${ENV_TASK}")" "$(task_index "${SETUP}" 'Verificar la entrega de la configuración de SES al backend')"
check "setup.yml: la puerta de imagen solo corre habilitado" \
  test "$(yq -r '.[] | select(.name == "Verificar que la imagen del backend trae el proveedor SES") | .when' "${SETUP}")" = "app_ses_enabled | bool"
HOST_GATE="$(task_index "${DEPLOY}" 'Verificar que el .env del host corresponde a la configuración de SES')"
IMAGE_GATE="$(task_index "${DEPLOY}" 'Verificar que la imagen del backend trae el proveedor SES')"
check "deploy.yml: incluye la puerta del host y la de imagen" test -n "${HOST_GATE}" -a -n "${IMAGE_GATE}"
check "deploy.yml: la puerta del host corre antes de descargar imágenes" before "${HOST_GATE}" "$(task_index "${DEPLOY}" 'Validar y descargar imágenes propias')"
for t in 'Actualizar image.env' 'Converger postgres y reconciliar autoridades de la base' 'Migrar esquema con el backend seleccionado' 'Desplegar postgres y backend con identidades exactas'; do
  check "deploy.yml: las puertas de SES corren antes de '${t}'" \
    bash -c "[ '${HOST_GATE}' -lt '$(task_index "${DEPLOY}" "${t}")' ] && [ '${IMAGE_GATE}' -lt '$(task_index "${DEPLOY}" "${t}")' ]"
done
check "deploy.yml: la puerta de imagen solo corre habilitado" \
  test "$(yq -r '.[] | select(.name == "Verificar que la imagen del backend trae el proveedor SES") | .when' "${DEPLOY}")" = "app_ses_env_enabled | bool"
check "deploy.yml: verifica el backend después de desplegarlo" before "$(task_index "${DEPLOY}" 'Desplegar postgres y backend con identidades exactas')" "$(task_index "${DEPLOY}" 'Verificar la configuración de SES en el backend')"
check "refresh_backend.yml: verifica después de recrear" before "$(task_index "${REFRESH}" 'Recrear el backend con la configuración vigente')" "$(task_index "${REFRESH}" 'Verificar la configuración de SES en el backend recreado')"
# Toda tarea que lee o evalúa valores de .env, Compose o el contenedor oculta su salida
for f in ses_inputs ses_delivery ses_host_gate ses_runtime; do
  check "${f}.yml: las tareas con valores sensibles llevan no_log" \
    test -z "$(yq -r '.[] | select((."ansible.builtin.command" or ."community.docker.docker_container_info" or (."ansible.builtin.set_fact" | tostring | test("smtp|compose|app_env_file|container|_lines|app_ses_env_"))) and .no_log != true and (.name | test("modo|decisión de correo") | not)) | .name' "${TASKS}/${f}.yml")"
done

# ── 11. Verificación de credenciales del workflow ───────────────────────────
step credentials .run > "${TMP}/credentials.sh"
CREDENTIALS_ENV=(HCP_WORKSPACE_ID=ws-abc DEPLOY_REQUIRED=true RUN_HOST_CONFIG=true RUN_APP_DEPLOY=true
  TF_API_TOKEN=x ANSIBLE_SSH_KEY=x POSTGRES_PASSWORD=x APP_DB_PASSWORD=x MIGRATION_DB_PASSWORD=x KC_DB_PASSWORD=x
  CLOUDFLARE_DNS_API_TOKEN=x KC_BACKEND_CLIENT_SECRET=x KC_AUTOMATION_CLIENT_SECRET=x)
credentials() { env -i PATH="${PATH}" "${CREDENTIALS_ENV[@]}" "$@" bash "${TMP}/credentials.sh" > "${TMP}/credentials.log" 2>&1; }
check "credenciales: pasan sin ningún secreto de correo" credentials
for key in TF_API_TOKEN ANSIBLE_SSH_KEY POSTGRES_PASSWORD APP_DB_PASSWORD MIGRATION_DB_PASSWORD KC_DB_PASSWORD \
  CLOUDFLARE_DNS_API_TOKEN KC_BACKEND_CLIENT_SECRET KC_AUTOMATION_CLIENT_SECRET; do
  check "credenciales: sin ${key} falla y lo nombra" \
    bash -c "$(declare -f credentials); TMP='${TMP}'; $(declare -p CREDENTIALS_ENV); ! credentials ${key}= && grep -q 'Faltan credenciales o variables: ${key}\$' '${TMP}/credentials.log'"
done

# ── Ningún valor sintético en la salida ─────────────────────────────────────
for label in "usuario:${USERNAME}" "contraseña:${PASSWORD}" "credencial rotada:${ROTATED}"; do
  check "ninguna salida de Ansible, preflight ni del paso muestra ${label%%:*}" \
    bash -c 'value="$1"; shift; ! grep -qF -- "$value" "$@"' _ "${label#*:}" "${TMP}"/run.*.log "${TMP}/step.log"
done

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "notifications-contract-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "notifications-contract-check: todo PASS"
