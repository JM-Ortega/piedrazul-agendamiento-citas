#!/usr/bin/env bash
# Contrato del bootstrap del primer administrador de negocio (IDENTITY_SEED_*): entrada
# (workflow) → validación (identity_seed_inputs.yml) → productor (env.j2) → consumidor
# (Compose → backend) → verificación en ejecución (identity_seed_runtime.yml) → retiro.
# No arranca contenedores ni usa red o credenciales reales: los valores son sintéticos y
# nunca se imprimen. El contenedor y sus logs se simulan (módulo docker_container_info y
# CLI docker falsos) para ejecutar las tareas reales sin daemon.
#
# Uso: infra/tests/identity-seed-contract-check.sh
# Requiere: docker compose (solo el CLI, sin daemon), ansible-playbook, jq, yq (mikefarah v4).
#
# Verifica:
#   1. Habilitado y válido: el .env lleva todas las entradas (también IDENTIFICATION_TYPE y
#      PHONE) y Compose entrega al backend exactamente la contraseña, con caracteres
#      especiales ($ ${...} \ " ' # espacios UTF-8); identity_seed_delivery.yml lo confirma
#      y detecta una entrega alterada.
#   2. Entradas ausentes o inválidas (y una decisión no booleana) fallan antes de escribir
#      el .env, sin mostrar valores.
#   3. Deshabilitado (aunque el runner tenga la contraseña): .env y Compose sin ningún valor
#      del bootstrap; habilitado → deshabilitado sobre el mismo .env lo retira.
#   4. En ejecución (identity_seed_runtime.yml y refresh_backend.yml con contenedor, logs y
#      Compose simulados): el contenedor está en vigor solo si sus IDENTITY_SEED_* son los que
#      Compose entregaría desde .env; un cambio pendiente de recreación falla (o se informa si
#      sigue un deploy); habilitado, solo CREATED o ALREADY_LINKED_READY pasan
#      (ALREADY_LINKED_NOT_READY, observación fallida y un CREATED de un contenedor anterior no);
#      un backend que no arranca (fail-closed) informa el resultado; el environment y el .env
#      del host deben coincidir.
#   5. Workflow: la contraseña solo se lee con el bootstrap habilitado; la verificación de
#      credenciales no la exige deshabilitado y sí habilitado; el deploy solo recibe la
#      decisión (no entradas ni secreto); converge declara si sigue un deploy. Orden en Ansible: validar antes de .env, entrega después, verificación
#      en ejecución en refresh y deploy.
#   7. Imagen: con el bootstrap habilitado, setup.yml (antes de escribir .env) y deploy.yml
#      (antes de migrar o arrancar) exigen que la imagen del backend declare el contrato
#      create-only (etiqueta OCI del Dockerfile); una imagen anterior nunca lo recibe.
#   6. Backend: las propiedades leen IDENTITY_SEED_ADMIN_* (con tipo y celular), la demo es
#      opt-in, producción corre con perfil prod y Compose no pasa APP_DEMO_SEED_ENABLED.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
TASKS="${ANSIBLE_DIR}/roles/app/tasks"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"

for bin in docker ansible-playbook jq yq; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
for key in $(compgen -e | grep -E '^IDENTITY_SEED_' || true); do
  echo "${key} está definida en el entorno; ejecuta sin ella" >&2; exit 2
done

TMP="$(mktemp -d)"
[ -n "${KEEP_TMP:-}" ] && echo "TMP=${TMP}" || trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { # check <descripción> <comando...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi
}

synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }

# ── Host simulado: app_dir con compose/, .env, image.env ──────────────────────
APP="${TMP}/app"
mkdir -p "${APP}/compose"
cp "${ROOT}"/infra/compose/*.yml "${APP}/compose/"
printf 'BACKEND_IMAGE=ghcr.io/example/backend@sha256:%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\nKEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:%s\nTRAEFIK_IMAGE=docker.io/library/traefik@sha256:%s\n' \
  "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})" "$(printf 'c%.0s' {1..64})" "$(printf 'd%.0s' {1..64})" > "${APP}/image.env"
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"

# Ansible corre los comandos del host por SSH: allí no existe el entorno del runner. Con
# conexión local, Compose preferiría IDENTITY_SEED_* del proceso al .env y ocultaría lo que
# se prueba; este docker los quita antes de llamar al real.
REAL_DOCKER="$(command -v docker)"
mkdir -p "${TMP}/remote-bin"
printf '%s\n' '#!/usr/bin/env bash' \
  'for v in $(compgen -e | grep "^IDENTITY_SEED_"); do unset "$v"; done' \
  "exec \"${REAL_DOCKER}\" \"\$@\"" > "${TMP}/remote-bin/docker"
chmod +x "${TMP}/remote-bin/docker"

# Colección community.docker falsa (sin daemon): contenedor, Compose de recreación e
# imagen simulados; el docker falso sirve los logs desde un archivo y delega lo demás
# (compose config) al docker "remoto".
FAKE="${TMP}/fake"
MODS="${FAKE}/collections/ansible_collections/community/docker/plugins/modules"
mkdir -p "${FAKE}/bin" "${MODS}" "${FAKE}/collections/ansible_collections/community/docker/meta"
printf 'namespace: community\nname: docker\nversion: 0.0.0\n' > "${FAKE}/collections/ansible_collections/community/docker/galaxy.yml"
printf 'requires_ansible: ">=2.9"\n' > "${FAKE}/collections/ansible_collections/community/docker/meta/runtime.yml"
printf '%s\n' '#!/usr/bin/python' 'import json, os' 'from ansible.module_utils.basic import AnsibleModule' \
  'module = AnsibleModule(argument_spec=dict(name=dict(type="str", required=True)))' \
  'module.exit_json(changed=False, **json.load(open(os.environ["FAKE_CONTAINER_JSON"])))' > "${MODS}/docker_container_info.py"
printf '%s\n' '#!/usr/bin/python' 'import os' 'from ansible.module_utils.basic import AnsibleModule' \
  'spec = {k: dict(type="raw") for k in ("project_src", "project_name", "files", "services", "dependencies",' \
  '        "state", "pull", "wait", "wait_timeout", "env_files")}' \
  'module = AnsibleModule(argument_spec=spec)' \
  'if os.environ.get("FAKE_COMPOSE_FAIL") == "1":' \
  '    module.fail_json(msg="Error while waiting: container backend exited (1)")' \
  'module.exit_json(changed=True)' > "${MODS}/docker_compose_v2.py"
printf '%s\n' '#!/usr/bin/env bash' \
  '[ "$1" = logs ] && { cat "${FAKE_DOCKER_LOG}"; exit 0; }' \
  "exec \"${TMP}/remote-bin/docker\" \"\$@\"" > "${FAKE}/bin/docker"
chmod +x "${FAKE}/bin/docker"
printf '%s\n' '#!/usr/bin/python' 'from ansible.module_utils.basic import AnsibleModule' \
  'spec = {k: dict(type="raw") for k in ("name", "platform", "pull")}' \
  'AnsibleModule(argument_spec=spec).exit_json(changed=False)' > "${MODS}/docker_image_pull.py"
printf '%s\n' '#!/usr/bin/python' 'import json, os' 'from ansible.module_utils.basic import AnsibleModule' \
  'module = AnsibleModule(argument_spec=dict(name=dict(type="raw")))' \
  'module.exit_json(changed=False, images=json.load(open(os.environ["FAKE_IMAGE_JSON"])))' > "${MODS}/docker_image_info.py"
CONTRACT_LABEL="$(yq -r '.identity_bootstrap_image_label' "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")"
CONTRACT_VALUE="$(yq -r '.identity_bootstrap_image_contract' "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")"
image_fixture() { # <archivo> <valor de la etiqueta o vacío para no tenerla>
  if [ -n "$2" ]; then jq -n --arg k "${CONTRACT_LABEL}" --arg v "$2" '[{Config: {Labels: {($k): $v}}}]' > "$1"
  else echo '[{"Config": {"Labels": null}}]' > "$1"; fi
}
image_fixture "${TMP}/image-ok.json" "${CONTRACT_VALUE}"
IMAGE_SELECTED="ghcr.io/example/backend@sha256:$(printf 'a%.0s' {1..64})"

# Mismo orden que setup.yml: credenciales técnicas y de la base → validación → .env → entrega
cat > "${TMP}/setup.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.set_fact:
        app_kc_backend_secret: "{{ lookup('env', 'KC_BACKEND_CLIENT_SECRET') }}"
        app_kc_automation_secret: "{{ lookup('env', 'KC_AUTOMATION_CLIENT_SECRET') }}"
        app_kc_platform_admin_password: ""
        app_db_secrets:
          POSTGRES_PASSWORD: "{{ lookup('env', 'POSTGRES_PASSWORD') }}"
          APP_DB_PASSWORD: "{{ lookup('env', 'APP_DB_PASSWORD') }}"
          MIGRATION_DB_PASSWORD: "{{ lookup('env', 'MIGRATION_DB_PASSWORD') }}"
          KC_DB_PASSWORD: "{{ lookup('env', 'KC_DB_PASSWORD') }}"
      no_log: true
    - ansible.builtin.include_tasks: ${TASKS}/identity_seed_inputs.yml
    - ansible.builtin.include_tasks: ${TASKS}/identity_seed_image_gate.yml
      when: app_identity_seed_enabled | bool
    - ansible.builtin.template:
        src: ${ANSIBLE_DIR}/roles/app/templates/env.j2
        dest: "{{ app_dir }}/.env"
        mode: "0600"
      no_log: true
    - ansible.builtin.include_tasks: ${TASKS}/identity_seed_delivery.yml
EOF

cat > "${TMP}/delivery.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_tasks: ${TASKS}/identity_seed_inputs.yml
    - ansible.builtin.include_tasks: ${TASKS}/identity_seed_delivery.yml
EOF

KCB="$(synthetic kcb)"
KCA="$(synthetic kca)"
PGP="$(synthetic pg)"
APPP="$(synthetic app)"
MIGP="$(synthetic mig)"
KCDB="$(synthetic kcdb)"

# Corre un playbook con el entorno de bootstrap dado (pares CLAVE=valor) y los demás
# secretos sintéticos. Salida en ${TMP}/run.log.
run_playbook() { # <playbook> [CLAVE=valor ...]
  local playbook="$1"; shift
  env -i PATH="${HOST_BIN:-${TMP}/remote-bin}:${PATH}" HOME="${HOME}" LANG=C.UTF-8 \
    ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ANSIBLE_COLLECTIONS_PATH="${ANSIBLE_COLLECTIONS_PATH:-${FAKE}/collections}" \
    FAKE_IMAGE_JSON="${FAKE_IMAGE_JSON:-${TMP}/image-ok.json}" \
    FAKE_CONTAINER_JSON="${TMP}/container.json" FAKE_DOCKER_LOG="${TMP}/docker.log" FAKE_COMPOSE_FAIL="${FAKE_COMPOSE_FAIL:-}" \
    KC_BACKEND_CLIENT_SECRET="${KCB}" KC_AUTOMATION_CLIENT_SECRET="${KCA}" \
    POSTGRES_PASSWORD="${PGP}" APP_DB_PASSWORD="${APPP}" MIGRATION_DB_PASSWORD="${MIGP}" KC_DB_PASSWORD="${KCDB}" \
    "$@" \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "${playbook}" \
      -e "app_dir=${APP}" -e "repo_root=${ROOT}" -e "backend_image_ref=${IMAGE_SELECTED}" ${EXTRA_VARS:-} \
    </dev/null > "${TMP}/run.log" 2>&1
  local rc=$?
  cp "${TMP}/run.log" "${TMP}/run.$((++RUNS)).log"
  return ${rc}
}
RUNS=0

# Contraseña con todo lo que .env/Compose podrían alterar
PASSWORD='Tmp $HOME ${PATH} \n "q" '"'"'s'"'"' #x `y` ñ€ \\ $$ fin'
VALID=(IDENTITY_SEED_ENABLED=true IDENTITY_SEED_ADMIN_USERNAME=1061234567
  IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE=CEDULA "IDENTITY_SEED_ADMIN_FIRST_NAME=Ana María"
  "IDENTITY_SEED_ADMIN_LAST_NAME=Prueba \$Ruiz" IDENTITY_SEED_ADMIN_EMAIL=ana.prueba@example.test
  IDENTITY_SEED_ADMIN_PHONE=3000000000 "IDENTITY_SEED_ADMIN_PASSWORD=${PASSWORD}")

with() { # with CLAVE=valor... → VALID con esos reemplazos
  local -A over=() ; local kv
  for kv in "$@"; do over["${kv%%=*}"]="${kv#*=}"; done
  for kv in "${VALID[@]}"; do
    local k="${kv%%=*}"
    if [ -n "${over[$k]+x}" ]; then printf '%s\0' "${k}=${over[$k]}"; else printf '%s\0' "${kv}"; fi
  done
}

compose_backend_env() { # nunca aborta: sin render, un objeto vacío hace fallar las comprobaciones
  (cd "${APP}/compose" && docker compose --project-name piedrazul-contract --file prod.yml \
    --env-file ../.env --env-file ../image.env config --format json 2>/dev/null) \
    | jq '.services.backend.environment' 2>/dev/null || echo '{}'
}

no_leak() { ! grep -qF -- "$1" "${TMP}/run.log"; }
log_has() { tr -s ' \n' '  ' < "${TMP}/run.log" | grep -qF -- "$1"; }
fails() { ! "$@"; }

# ── 1. Habilitado y válido ─────────────────────────────────────────────────────
if run_playbook "${TMP}/setup.yml" "${VALID[@]}"; then
  ok "habilitado: validación, .env y entrega pasan con entradas válidas"
else
  ko "habilitado: validación, .env y entrega pasan con entradas válidas"
  grep -vF -- "${PASSWORD}" "${TMP}/run.log" | grep -A8 -E "FAILED|fatal" | head -30 || true
fi
check "habilitado: la salida de Ansible no contiene la contraseña" no_leak "${PASSWORD}"

compose_backend_env > "${TMP}/backend-env.json"
delivered() { jq -r --arg k "$1" '.[$k]' "${TMP}/backend-env.json" | sed 's/\$\$/$/g'; }
check "habilitado: el backend recibe la contraseña exacta (caracteres especiales)" \
  test "$(delivered IDENTITY_SEED_ADMIN_PASSWORD)" = "${PASSWORD}"
check "habilitado: el backend recibe IDENTITY_SEED_ENABLED=true" test "$(delivered IDENTITY_SEED_ENABLED)" = true
check "habilitado: el backend recibe IDENTIFICATION_TYPE" test "$(delivered IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE)" = CEDULA
check "habilitado: el backend recibe PHONE" test "$(delivered IDENTITY_SEED_ADMIN_PHONE)" = 3000000000
check "habilitado: el backend recibe nombres con espacios, tildes y \$" \
  test "$(delivered IDENTITY_SEED_ADMIN_FIRST_NAME)|$(delivered IDENTITY_SEED_ADMIN_LAST_NAME)" = 'Ana María|Prueba $Ruiz'

# Entrega alterada (sin escape en .env): identity_seed_delivery.yml debe detectarla
cp "${APP}/.env" "${TMP}/env.good"
sed -i 's/^IDENTITY_SEED_ADMIN_PASSWORD=.*/IDENTITY_SEED_ADMIN_PASSWORD=Tmp $HOME/' "${APP}/.env"
if run_playbook "${TMP}/delivery.yml" "${VALID[@]}"; then
  ko "una entrega alterada de la contraseña se detecta"
else
  check "una entrega alterada de la contraseña se detecta" log_has 'bootstrap: IDENTITY_SEED_ADMIN_PASSWORD (valores no mostrados)'
fi
cp "${TMP}/env.good" "${APP}/.env"

# ── 1b. Imagen del backend: solo una que implementa el bootstrap lo recibe ─────
gate_case() { # <descripción> <fixture de imagen> <decisión> <pass|fail>
  local desc="$1" fixture="$2" decision="$3" expected="$4" result=fail
  local -a envs=(); mapfile -d '' envs < <(with IDENTITY_SEED_ENABLED="${decision}")
  rm -f "${APP}/.env"
  FAKE_IMAGE_JSON="${fixture}" run_playbook "${TMP}/setup.yml" "${envs[@]}" && result=pass
  if [ "${result}" = "${expected}" ] && no_leak "${PASSWORD}" \
     && { [ "${expected}" = pass ] || { [ ! -e "${APP}/.env" ] && log_has "no declara ${CONTRACT_LABEL}=${CONTRACT_VALUE}"; }; }; then
    ok "${desc}"
  else
    ko "${desc}"
  fi
}
image_fixture "${TMP}/image-legacy.json" ""
image_fixture "${TMP}/image-other.json" "otro-contrato"
gate_case "imagen anterior sin etiqueta + bootstrap habilitado: falla antes de escribir .env" "${TMP}/image-legacy.json" true fail
gate_case "imagen con otro contrato + bootstrap habilitado: falla antes de escribir .env" "${TMP}/image-other.json" true fail
gate_case "imagen anterior + bootstrap deshabilitado: no se exige la etiqueta" "${TMP}/image-legacy.json" false pass
gate_case "imagen compatible + bootstrap habilitado: pasa" "${TMP}/image-ok.json" true pass
cp "${TMP}/env.good" "${APP}/.env"

# ── 2. Entradas ausentes o inválidas: fallan antes de escribir .env ──────────
EXAMPLE_SECRET="$(sed -n 's/^KC_BACKEND_CLIENT_SECRET=//p' "${ROOT}/.env.example")"
invalid_case() { # <descripción> <regla esperada en el mensaje> CLAVE=valor...
  local desc="$1" rule="$2"; shift 2
  local -a envs=()
  mapfile -d '' envs < <(with "$@")
  rm -f "${APP}/.env"
  if run_playbook "${TMP}/setup.yml" "${envs[@]}"; then
    ko "inválido (${desc}): debía fallar"
    return
  fi
  if log_has "${rule}" && [ ! -e "${APP}/.env" ]; then
    ok "inválido (${desc}): falla antes de escribir .env"
  else
    ko "inválido (${desc}): falla antes de escribir .env con la regla '${rule}'"
  fi
  local kv
  for kv in "$@"; do
    local v="${kv#*=}"
    [ ${#v} -ge 6 ] || continue
    check "inválido (${desc}): no muestra el valor" no_leak "${v}"
  done
}

invalid_case "sin PHONE" "IDENTITY_SEED_ADMIN_PHONE vacío" IDENTITY_SEED_ADMIN_PHONE=
invalid_case "sin IDENTIFICATION_TYPE" "IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE vacío" IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE=
invalid_case "sin contraseña" "IDENTITY_SEED_ADMIN_PASSWORD vacío" IDENTITY_SEED_ADMIN_PASSWORD=
invalid_case "tipo desconocido" "no es un tipo de documento conocido" IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE=DNI
invalid_case "celular inválido" "no es un celular de 10 dígitos" IDENTITY_SEED_ADMIN_PHONE=300123
invalid_case "correo inválido" "no es un correo" IDENTITY_SEED_ADMIN_EMAIL=no-es-correo
invalid_case "contraseña corta" "con menos de 16 caracteres" "IDENTITY_SEED_ADMIN_PASSWORD=Corta-Clave-15!"
invalid_case "contraseña con espacio final" "en los extremos" "IDENTITY_SEED_ADMIN_PASSWORD=Temp-Bootstrap-Value-26 "
invalid_case "contraseña con salto de línea" "caracteres de control" "IDENTITY_SEED_ADMIN_PASSWORD=Temp-Bootstrap"$'\n'"-Value-2026"
invalid_case "contraseña con el documento" "contiene el documento" "IDENTITY_SEED_ADMIN_PASSWORD=Clave-1061234567-Inicial"
invalid_case "contraseña con el correo" "contiene el correo" "IDENTITY_SEED_ADMIN_PASSWORD=X-ANA.PRUEBA-Temporal-2026"
invalid_case "contraseña = credencial técnica" "credencial técnica" "IDENTITY_SEED_ADMIN_PASSWORD=${KCB}"
invalid_case "contraseña = contraseña de la base" "credencial técnica" "IDENTITY_SEED_ADMIN_PASSWORD=${APPP}"
invalid_case "contraseña = valor de .env.example" "valor de referencia de .env.example" "IDENTITY_SEED_ADMIN_PASSWORD=${EXAMPLE_SECRET}"
invalid_case "decisión no booleana" "IDENTITY_SEED_ENABLED debe ser true, false" IDENTITY_SEED_ENABLED=yes

# ── 3. Deshabilitado y retiro ──────────────────────────────────────────────────
cp "${TMP}/env.good" "${APP}/.env"
disabled_env=()
mapfile -d '' disabled_env < <(with IDENTITY_SEED_ENABLED=false)
if run_playbook "${TMP}/setup.yml" "${disabled_env[@]}"; then
  ok "habilitado → deshabilitado: validación, .env y entrega pasan (contraseña aún en el runner)"
else
  ko "habilitado → deshabilitado: validación, .env y entrega pasan (contraseña aún en el runner)"
fi
check "deshabilitado: .env sin la contraseña" bash -c "! grep -qF -- \"\$1\" '${APP}/.env'" _ "${PASSWORD}"
check "deshabilitado: .env con IDENTITY_SEED_ENABLED=false" grep -qx 'IDENTITY_SEED_ENABLED=false' "${APP}/.env"
check "deshabilitado: .env con todas las entradas vacías" \
  bash -c "! grep -Eq '^IDENTITY_SEED_ADMIN_[A-Z_]+=.+' '${APP}/.env'"
compose_backend_env > "${TMP}/backend-env.json"
check "deshabilitado: Compose entrega la contraseña vacía" test "$(delivered IDENTITY_SEED_ADMIN_PASSWORD)" = ""
check "deshabilitado: Compose entrega IDENTITY_SEED_ENABLED=false" test "$(delivered IDENTITY_SEED_ENABLED)" = false
check "deshabilitado: la configuración efectiva no contiene la contraseña" \
  bash -c "! grep -qF -- \"\$1\" '${TMP}/backend-env.json'" _ "Tmp \$\$HOME"
if run_playbook "${TMP}/setup.yml"; then
  ok "sin decisión ni entradas (variables no definidas): deshabilitado, sin exigir nada"
else
  ko "sin decisión ni entradas (variables no definidas): deshabilitado, sin exigir nada"
fi

# ── 4. En ejecución: contenedor, logs y Compose de recreación simulados ───────
# docker_container_info y docker_compose_v2 falsos; el docker falso sirve los logs desde un
# archivo y delega lo demás (compose config) al docker "remoto" (sin entorno del runner).

for t in runtime refresh_backend; do
  cat > "${TMP}/${t}.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_tasks: ${TASKS}/$( [ "$t" = runtime ] && echo identity_seed_runtime || echo refresh_backend ).yml
EOF
done

IMAGE_OTHER="ghcr.io/example/backend@sha256:$(printf 'c%.0s' {1..64})"
ENV_ENABLED="${TMP}/env.good"
ENV_DISABLED="${TMP}/env.disabled"
cp "${APP}/.env" "${ENV_DISABLED}"

# container <estado> [imagen]: en-vigor-habilitado | en-vigor-deshabilitado | anterior-habilitado
#   (habilitado con otra contraseña) | conserva (deshabilitado salvo la contraseña) | ausente
container() {
  local state="$1" image="${2:-${IMAGE_SELECTED}}" src
  if [ "${state}" = ausente ]; then echo '{"exists": false}' > "${TMP}/container.json"; return; fi
  case "${state}" in en-vigor-habilitado|anterior-habilitado) src="${ENV_ENABLED}" ;; *) src="${ENV_DISABLED}" ;; esac
  (cd "${APP}/compose" && "${TMP}/remote-bin/docker" compose --project-name piedrazul-contract --file prod.yml \
    --env-file "${src}" --env-file ../image.env config --format json) \
    | jq --arg state "${state}" --arg image "${image}" '
      .services.backend.environment | with_entries(select(.key | startswith("IDENTITY_SEED_")))
      | map_values(gsub("\\$\\$"; "$"))
      | if $state == "anterior-habilitado" then .IDENTITY_SEED_ADMIN_PASSWORD = "Retenida-Temporal-2026" else . end
      | if $state == "conserva" then .IDENTITY_SEED_ADMIN_PASSWORD = "Retenida-Temporal-2026" else . end
      | {exists: true, container: {Config: {Image: $image,
          Env: (["APP_DB_PASSWORD=secreto-ajeno"] + (to_entries | map(.key + "=" + .value)))},
        State: {StartedAt: "2026-10-10T00:00:00.000000000Z"}}}' > "${TMP}/container.json"
}
logline() { printf '2026-10-10 INFO --- identity-bootstrap result=%s attempt=a stage=x%s\n' "$1" "${2:+ reasons=$2}" > "${TMP}/docker.log"; }

play() { # <playbook> <descripción> <pass|fail> [mensaje esperado] [-- extra vars...]
  local pb="$1" desc="$2" expected="$3" msg="${4:-}"
  if [ $# -ge 4 ]; then shift 4; else shift $#; fi
  local result=fail
  HOST_BIN="${FAKE}/bin" ANSIBLE_COLLECTIONS_PATH="${FAKE}/collections" \
    EXTRA_VARS="-e app_identity_seed_result_retries=1 -e app_identity_seed_result_delay=0 -e backend_image_ref=${IMAGE_SELECTED} $*" \
    run_playbook "${TMP}/${pb}.yml" && result=pass
  if [ "${result}" = "${expected}" ] && no_leak "Retenida-Temporal-2026" && no_leak "${PASSWORD}" \
     && { [ -z "${msg}" ] || log_has "${msg}"; }; then
    ok "${desc}"
  else
    ko "${desc}${msg:+ (esperado: ${msg})}"
  fi
}
runtime() { play runtime "$@"; }
refresh() { play refresh_backend "$@"; }

# ── 4a. identity_seed_runtime.yml (modo enforce, como al final de deploy.yml)
cp "${ENV_DISABLED}" "${APP}/.env"
container en-vigor-deshabilitado; : > "${TMP}/docker.log"
runtime "runtime, deshabilitado: contenedor en vigor pasa" pass
container conserva
runtime "runtime, deshabilitado: contenedor que conserva la contraseña falla" fail "conserva la contraseña inicial"
container en-vigor-habilitado
runtime "runtime, deshabilitado: contenedor aún habilitado (pendiente) falla" fail "El retiro está pendiente de recrear el backend"
container ausente
runtime "runtime, deshabilitado: sin contenedor pasa (nada que retirar)" pass
container en-vigor-deshabilitado
runtime "runtime, environment habilitado y .env deshabilitado: falla" fail "el .env del host no tiene el bootstrap" -e app_identity_seed_intended=true
runtime "runtime, environment deshabilitado y .env deshabilitado: pasa" pass "" -e app_identity_seed_intended=false

cp "${ENV_ENABLED}" "${APP}/.env"
container en-vigor-habilitado
logline CREATED;              runtime "runtime, habilitado: CREATED pasa" pass
logline ALREADY_LINKED_READY; runtime "runtime, habilitado: ALREADY_LINKED_READY pasa" pass
logline ALREADY_LINKED_NOT_READY admin_role_missing
runtime "runtime, habilitado: ALREADY_LINKED_NOT_READY (ADMIN retirado) falla sin reparar" fail "reasons=admin_role_missing"
logline ALREADY_LINKED_NOT_READY account_disabled
runtime "runtime, habilitado: ALREADY_LINKED_NOT_READY (desactivada) falla" fail "no restituye roles ni reactiva cuentas"
logline ALREADY_LINKED;       runtime "runtime, habilitado: un ALREADY_LINKED sin confirmar no pasa" fail "no terminó en CREATED ni ALREADY_LINKED_READY"
logline FAILED_NO_CHANGES linked_observation_failed
runtime "runtime, habilitado: observación fallida de la vinculada falla" fail "result=FAILED_NO_CHANGES"
for outcome in CONFLICT INVALID_INPUT BUSY FAILED_COMPENSATED FAILED_NEEDS_INSPECTION; do
  logline "${outcome}" r; runtime "runtime, habilitado: ${outcome} falla" fail "result=${outcome}"
done
: > "${TMP}/docker.log"
runtime "runtime, habilitado: sin línea de resultado falla" fail "sin resultado"
logline CREATED
container anterior-habilitado
runtime "runtime, habilitado: contenedor con la contraseña anterior (pendiente) no toma su CREATED" fail "pendiente de recrearlo"
container en-vigor-deshabilitado
runtime "runtime, habilitado: contenedor sin el bootstrap (pendiente) no toma su CREATED" fail "pendiente de recrearlo"
container ausente
runtime "runtime, habilitado: sin contenedor falla" fail "no hay contenedor backend"
container en-vigor-habilitado
runtime "runtime, environment deshabilitado y .env habilitado (retiro pendiente): falla" fail "el retiro está pendiente" -e app_identity_seed_intended=false
runtime "runtime, modo deferred con cambio pendiente: solo informa" pass "Lo verifica el deploy" -e app_identity_seed_runtime_mode=deferred -e app_identity_seed_intended=false

# ── 4b. refresh_backend.yml (final de converge)
cp "${ENV_ENABLED}" "${APP}/.env"
container en-vigor-habilitado "${IMAGE_SELECTED}"; logline CONFLICT existing_admin_authority
FAKE_COMPOSE_FAIL=1 refresh "refresh: backend que no arranca (fail-closed) informa el resultado del bootstrap" fail "result=CONFLICT"
logline ALREADY_LINKED_READY
refresh "refresh: backend recreado con ALREADY_LINKED_READY pasa" pass
logline ALREADY_LINKED_NOT_READY admin_role_missing
refresh "refresh: backend recreado con ALREADY_LINKED_NOT_READY falla" fail "reasons=admin_role_missing"
container en-vigor-deshabilitado "${IMAGE_OTHER}"; logline CREATED
refresh "refresh sin recrear, sigue un deploy: el bootstrap pendiente solo se informa" pass "Lo verifica el deploy" -e app_deploy_follows=true
refresh "refresh sin recrear, solo configuración: bootstrap pendiente falla" fail "pendiente de recrearlo" -e app_deploy_follows=false
container ausente
refresh "refresh sin backend, solo configuración: bootstrap habilitado falla" fail "no hay contenedor backend" -e app_deploy_follows=false
cp "${ENV_DISABLED}" "${APP}/.env"
container conserva "${IMAGE_OTHER}"
refresh "refresh sin recrear, solo configuración: retiro pendiente falla" fail "conserva la contraseña inicial" -e app_deploy_follows=false
refresh "refresh sin recrear, sigue un deploy: retiro pendiente solo se informa" pass "Lo verifica el deploy" -e app_deploy_follows=true
container en-vigor-deshabilitado "${IMAGE_OTHER}"
refresh "refresh sin recrear, solo configuración: retiro ya en vigor pasa" pass "" -e app_deploy_follows=false
container ausente
refresh "refresh sin backend, solo configuración: deshabilitado pasa" pass "" -e app_deploy_follows=false
container en-vigor-deshabilitado "${IMAGE_SELECTED}"
FAKE_COMPOSE_FAIL=1 refresh "refresh: backend que no arranca con el bootstrap deshabilitado falla igual" fail "no quedó en ejecución"

# ── 5. Workflow y orden en Ansible ────────────────────────────────────────────
step_env() { yq -r ".jobs.production.steps[] | select(.name == \"$1\") | .env.$2 // \"\"" "${WORKFLOW}"; }
check "workflow: converge solo lee la contraseña con el bootstrap habilitado" \
  test "$(step_env 'Correr converge' IDENTITY_SEED_ADMIN_PASSWORD)" = \
       "\${{ vars.IDENTITY_SEED_ENABLED == 'true' && secrets.IDENTITY_SEED_ADMIN_PASSWORD || '' }}"
check "workflow: converge entrega IDENTIFICATION_TYPE y PHONE" \
  bash -c '[ -n "$1" ] && [ -n "$2" ]' _ "$(step_env 'Correr converge' IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE)" \
  "$(step_env 'Correr converge' IDENTITY_SEED_ADMIN_PHONE)"
check "workflow: deploy no recibe entradas ni secreto del bootstrap (solo la decisión)" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.name == \"Correr deploy\") | .env | keys | .[]' '${WORKFLOW}' | grep -q '^IDENTITY_SEED_ADMIN_'"
step_run() { yq -r ".jobs.production.steps[] | select(.name == \"$1\") | .run" "${WORKFLOW}"; }
check "workflow: converge declara si sigue un deploy" grep -qF -- '-e "app_deploy_follows=${RUN_APP_DEPLOY}"' <(step_run 'Correr converge')
check "workflow: converge pasa la decisión del environment" grep -qF -- '-e "app_identity_seed_intended=${IDENTITY_SEED_ENABLED:-false}"' <(step_run 'Correr converge')
check "workflow: deploy pasa la decisión del environment" grep -qF -- '-e "app_identity_seed_intended=${IDENTITY_SEED_ENABLED:-false}"' <(step_run 'Correr deploy')
check "workflow: deploy recibe IDENTITY_SEED_ENABLED del environment (variable, no secreto)" \
  test "$(step_env 'Correr deploy' IDENTITY_SEED_ENABLED)" = '${{ vars.IDENTITY_SEED_ENABLED }}'
check "workflow: ningún paso lee el secreto sin condicionarlo al bootstrap" \
  bash -c "! grep -E 'secrets\.IDENTITY_SEED_ADMIN_PASSWORD' '${WORKFLOW}' | grep -vqF \"vars.IDENTITY_SEED_ENABLED == 'true' && secrets.IDENTITY_SEED_ADMIN_PASSWORD || ''\""

# La verificación de credenciales, ejecutada con su propio script
yq -r '.jobs.production.steps[] | select(.name == "Verificar credenciales de producción") | .run' "${WORKFLOW}" > "${TMP}/credentials.sh"
credentials() { # [CLAVE=valor ...] → código de salida del script
  env -i PATH="${PATH}" HCP_WORKSPACE_ID=ws-abc DEPLOY_REQUIRED=true RUN_HOST_CONFIG=true RUN_APP_DEPLOY=true \
    TF_API_TOKEN=x ANSIBLE_SSH_KEY=x POSTGRES_PASSWORD=x APP_DB_PASSWORD=x MIGRATION_DB_PASSWORD=x KC_DB_PASSWORD=x \
    CLOUDFLARE_DNS_API_TOKEN=x \
    KC_BACKEND_CLIENT_SECRET=x KC_AUTOMATION_CLIENT_SECRET=x "$@" bash "${TMP}/credentials.sh" > "${TMP}/credentials.log" 2>&1
}
check "credenciales: deshabilitado no exige la contraseña inicial" credentials IDENTITY_SEED_ENABLED=false
check "credenciales: sin decisión no exige la contraseña inicial" credentials
check "credenciales: habilitado sin contraseña falla" fails credentials IDENTITY_SEED_ENABLED=true \
  IDENTITY_SEED_ADMIN_USERNAME=1 IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE=CEDULA IDENTITY_SEED_ADMIN_FIRST_NAME=a \
  IDENTITY_SEED_ADMIN_LAST_NAME=b IDENTITY_SEED_ADMIN_EMAIL=c IDENTITY_SEED_ADMIN_PHONE=d
check "credenciales: el fallo nombra IDENTITY_SEED_ADMIN_PASSWORD (sin valores)" \
  grep -q 'Faltan credenciales o variables: IDENTITY_SEED_ADMIN_PASSWORD$' "${TMP}/credentials.log"
check "credenciales: habilitado sin PHONE ni IDENTIFICATION_TYPE falla" fails credentials IDENTITY_SEED_ENABLED=true \
  IDENTITY_SEED_ADMIN_USERNAME=1 IDENTITY_SEED_ADMIN_FIRST_NAME=a IDENTITY_SEED_ADMIN_LAST_NAME=b \
  IDENTITY_SEED_ADMIN_EMAIL=c IDENTITY_SEED_ADMIN_PASSWORD=e
check "credenciales: habilitado completo pasa" credentials IDENTITY_SEED_ENABLED=true \
  IDENTITY_SEED_ADMIN_USERNAME=1 IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE=CEDULA IDENTITY_SEED_ADMIN_FIRST_NAME=a \
  IDENTITY_SEED_ADMIN_LAST_NAME=b IDENTITY_SEED_ADMIN_EMAIL=c IDENTITY_SEED_ADMIN_PHONE=d IDENTITY_SEED_ADMIN_PASSWORD=e
check "credenciales: decisión no booleana falla" fails credentials IDENTITY_SEED_ENABLED=yes

task_index() { # <archivo> <fragmento de nombre> → posición de la primera tarea que lo contiene
  yq -r '.[].name' "$1" | grep -nF -- "$2" | head -1 | cut -d: -f1
}
SETUP="${TASKS}/setup.yml"
check "setup.yml: valida el bootstrap antes de generar .env" \
  test "$(task_index "${SETUP}" 'bootstrap del primer administrador')" -lt "$(task_index "${SETUP}" 'Generar .env')"
check "setup.yml: verifica la entrega después de generar .env e image.env" \
  test "$(task_index "${SETUP}" 'entrega del bootstrap')" -gt "$(task_index "${SETUP}" 'Generar image.env')"
check "refresh_backend.yml: verifica el backend recreado" \
  test "$(task_index "${TASKS}/refresh_backend.yml" 'Verificar el bootstrap del primer administrador en el backend recreado')" -gt "$(task_index "${TASKS}/refresh_backend.yml" 'Recrear el backend')"
check "deploy.yml: verifica el backend después de desplegarlo" \
  test "$(task_index "${TASKS}/deploy.yml" 'Verificar el bootstrap del primer administrador en el backend')" -gt "$(task_index "${TASKS}/deploy.yml" 'Desplegar postgres y backend')"

check "setup.yml: la puerta de imagen corre antes de generar .env" \
  test "$(task_index "${SETUP}" 'admite el bootstrap')" -lt "$(task_index "${SETUP}" 'Generar .env')"
for t in 'Converger postgres' 'Migrar esquema' 'Desplegar postgres y backend'; do
  check "deploy.yml: la puerta de imagen corre antes de '${t}'" \
    test "$(task_index "${TASKS}/deploy.yml" 'admite el bootstrap')" -lt "$(task_index "${TASKS}/deploy.yml" "${t}")"
done
check "refresh_backend.yml: solo recrea la imagen seleccionada (la que verificó setup)" \
  grep -qF "app_backend_container.container.Config.Image == backend_image_ref" "${TASKS}/refresh_backend.yml"
check "Dockerfile: la etapa final declara ${CONTRACT_LABEL}=${CONTRACT_VALUE}" \
  bash -c "awk '/^FROM /{stage++} stage==2' '${ROOT}/backend/Dockerfile' | grep -qx 'LABEL ${CONTRACT_LABEL}=\"${CONTRACT_VALUE}\"'"
check "ningún otro paso recrea el backend (solo refresh_backend.yml y deploy.yml lo nombran en Compose)" \
  test "$(grep -l -- '- backend$' "${TASKS}"/*.yml | xargs -n1 basename | sort | tr '\n' ' ')" = "deploy.yml refresh_backend.yml "
check "el backend es una hoja de Compose (ningún servicio depende de él)" \
  bash -c "! yq -e '.services[] | select(.depends_on.backend)' '${ROOT}'/infra/compose/*.yml >/dev/null 2>&1"

# ── 6. Backend: propiedades, demo y perfil de producción ──────────────────────
APP_YAML="${ROOT}/backend/src/main/resources/application.yaml"
for field in username:USERNAME identification-type:IDENTIFICATION_TYPE first-name:FIRST_NAME last-name:LAST_NAME \
  email:EMAIL phone:PHONE password:PASSWORD; do
  check "application.yaml: ${field%%:*} ← IDENTITY_SEED_ADMIN_${field##*:} sin valor por defecto" \
    test "$(yq -r ".app.seed.identity.admin.\"${field%%:*}\"" "${APP_YAML}")" = "\${IDENTITY_SEED_ADMIN_${field##*:}:}"
done
check "application.yaml: bootstrap deshabilitado por defecto" \
  test "$(yq -r '.app.seed.identity.enabled' "${APP_YAML}")" = '${IDENTITY_SEED_ENABLED:false}'
check "application.yaml: demo deshabilitada por defecto" \
  test "$(yq -r '.app.seed.demo.enabled' "${APP_YAML}")" = '${APP_DEMO_SEED_ENABLED:false}'
check "producción: perfil prod" \
  test "$(yq -r '.spring_profiles_active' "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")" = prod
check "producción: Compose no pasa APP_DEMO_SEED_ENABLED al backend" \
  bash -c "! grep -Eq '^[[:space:]]*APP_DEMO_SEED_ENABLED[[:space:]]*[:=]|[$][{]APP_DEMO_SEED_ENABLED' '${ROOT}/infra/compose/backend.yml' '${ANSIBLE_DIR}/roles/app/templates/env.j2'"

echo
if [ "${FAILS}" -eq 0 ]; then
  echo "Contrato del bootstrap del primer administrador: OK"
else
  echo "Contrato del bootstrap del primer administrador: ${FAILS} fallo(s)"
  exit 1
fi
