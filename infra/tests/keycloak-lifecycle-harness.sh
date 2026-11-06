#!/usr/bin/env bash
# Prueba ejecutable del ciclo técnico de Keycloak (P1/P2/P3 y recuperación) con los task
# files reales del rol app (keycloak_credentials.yml → db_converge.yml →
# keycloak_lifecycle.yml) contra PostgreSQL (imagen de infra/postgres) y Keycloak
# desechables, con conexión local. No toca producción, GitHub ni credenciales reales: todo
# secreto es sintético, nunca se imprime, y al final se verifica que no aparece en ningún log.
#
# Uso: infra/tests/keycloak-lifecycle-harness.sh [directorio de evidencia]
#   HARNESS_TARGET=foreign-user → solo P2, el escenario de usuario temporal ajeno y fugas
#   HARNESS_TARGET=migration-version → P2, IDENT y versión registrada de la base desconocida
#     (MV) y fugas
#   HARNESS_TARGET=upgrade      → P2 con la versión anterior de Keycloak
#     (HARNESS_KC_FROM_REF, referencia fijada <tag>@sha256:<índice>), primer login del
#     administrador humano en ella, actualización por convergencia normal a la versión que
#     declara infra/compose/keycloak.yml (migración real de la base), intento de volver
#     atrás, y después todos los escenarios P1/P3/REC/INT/ADMIN-H sobre la base migrada.
#     Si HARNESS_KC_FROM_REF es la misma versión con otro digest: sin migración, la vuelta a
#     ese digest es compatible (converge la admite) y se restaura la identidad declarada
#   HARNESS_TARGET=smtp → P2 con SES habilitado contra un SMTP sintético (smtp-sink.py) y el
#     escenario SMTP y fugas, también codificadas
# Keycloak corre siempre por su identidad exacta: la referencia fijada se resuelve (como en
# el workflow) a su manifiesto linux/amd64 y converge la recibe como keycloak_image_ref.
# Requiere: docker (compose v2, buildx), jq, yq, ansible-playbook con community.docker 5.x
#   (ANSIBLE_COLLECTIONS_PATH) y python con requests para el intérprete local
#   (HARNESS_PYTHON, por defecto python3); la imagen HARNESS_PYTHON_IMAGE (por defecto
#   python:3.12-slim) corre keycloak-browser-login.py dentro de la red de Keycloak.
#
# Usa los nombres fijos de producción (contenedores postgres/keycloak, red piedrazul_net):
# se niega a correr si ya existen. Proyecto Compose propio y volumen propio; limpia al salir.
#
# Escenarios:
#   IDENT   identidad de Keycloak: declaración por tag, índice distinto del declarado,
#           índice en vez de manifiesto, image.env distinta de la recibida → falla antes de
#           tocar Keycloak; identidad en ejecución distinta → la verificación falla
#   MV      (solo migration-version) base inicializada sin versión registrada conocida
#           (tabla migration_model ausente, vacía, ambigua o con otra forma) → falla cerrado
#           antes de tocar Keycloak, también con recuperación; restaurada → P1
#   UPG     (solo upgrade) migración hacia adelante con el administrador humano, OTP,
#           acciones requeridas, clientes, roles y secretos preservados; volver atrás falla
#           cerrado sin tocar Keycloak (misma versión, otro digest: sin migración y la
#           vuelta es compatible; rechazar ese digest es de la política de vulnerabilidades)
#   P2      base nueva → evento → P1; ninguna credencial del repositorio válida en ningún
#           momento (sondeo continuo durante el evento); sin autoridad temporal al final
#   P1      dos convergencias más: sin evento, idempotentes, sin recrear el realm ni escribir
#           el alcance de roles
#   SCOPE   alcance de roles explícito de automatización, backend y frontend (Full Scope Allowed
#           deshabilitado) tras P2; tokens reales: frontend con PKCE por rol de negocio, varios
#           roles, sin roles técnicos, renovación al otorgar y quitar un rol, cierre de sesión;
#           backend y automatización con exactamente su autoridad (API permitida y denegada);
#           sin aviso de Full Scope de Keycloak para esos clientes. Divergencia del alcance en
#           los tres clientes → P3 recuperable sin mutar → recuperación existente → P1
#   ROT-B   rotación de KC_BACKEND_CLIENT_SECRET por convergencia normal
#   P3-*    estados divergentes: la convergencia normal falla cerrada, sin mutar
#   REC     recuperación autorizada (incluida la rotación de KC_AUTOMATION_CLIENT_SECRET)
#   INT     recuperación interrumpida (autoridad temporal sin limpiar → P3 → recuperación) y
#           recuperación que falla después de reconciliar parcialmente
#   ADMIN-H administrador humano permanente: contraseña inicial exigida solo en P2 y antes del
#           evento; creado en P2 con rol, acciones requeridas y la política de contraseñas de
#           master (mínimo 16); primer login de navegador (enrolamiento OTP + cambio de
#           contraseña: una corta se rechaza, una de 16+ solo minúsculas se acepta) y logins
#           siguientes con OTP; P1, rotaciones y recuperaciones no lo modifican ni reescriben la
#           política; colisión de nombre y política preexistente distinta sin adopción ni
#           sobrescritura; OTP retirado (comportamiento nativo aceptado: acceso solo con
#           contraseña); borrado intencional sin recreación; creación interrumpida en una P2
#           nueva (deshabilitada, visible, sin reparar)
#   SMTP    (solo smtp) correo nativo con el interruptor de SES: autenticación y envío sintéticos;
#           repetición, rotación, cambio de remitente y endpoint sin reiniciar Keycloak;
#           deshabilitar; falla al escribir y al releer, y reintento; SMTP ajeno sin sobrescribir;
#           realm anterior a manage-realm (P3 → recuperación); secretos fuera de argv, entorno,
#           archivos y logs. Toda corrida sin este target comprueba P2 con SES ausente.
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
PYTHON="${HARNESS_PYTHON:-python3}"
PROJECT=piedrazul-kchl
TARGET="${HARNESS_TARGET:-}"
# Keycloak: la referencia fijada que declara el repositorio y, para upgrade, la anterior
KC_TO_DECLARED="$(yq -r '.services.keycloak.image' "${ROOT}/infra/compose/keycloak.yml")"
KC_FROM_DECLARED="${HARNESS_KC_FROM_REF:-quay.io/keycloak/keycloak:26.5.6@sha256:8d44614c74798322c4e07fbe0ecb15cfbb5879d69b484628555f58ade06f0d8c}"

for bin in docker ansible-playbook jq yq; do
  command -v "${bin}" >/dev/null || { echo "falta ${bin}" >&2; exit 2; }
done
for name in postgres keycloak; do
  ! docker container inspect "${name}" >/dev/null 2>&1 || { echo "ya existe un contenedor ${name}; no se corre" >&2; exit 2; }
done
! docker network inspect piedrazul_net >/dev/null 2>&1 || { echo "ya existe la red piedrazul_net; no se corre" >&2; exit 2; }

W="$(mktemp -d)"
EVIDENCE="${1:-${W}/evidence}"
mkdir -p "${EVIDENCE}"
LOGS="${EVIDENCE}/logs"
mkdir -p "${LOGS}"
RESULTS="${EVIDENCE}/results.txt"
: > "${RESULTS}"

cleanup() {
  docker compose --project-name "${PROJECT}" --file "${W}/compose/prod.yml" \
    --env-file "${W}/.env" --env-file "${W}/image.env" down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network rm piedrazul_net >/dev/null 2>&1 || true
  rm -rf "${W}"
}
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1" | tee -a "${RESULTS}"; }
ko() { printf 'FAIL  %s\n' "$1" | tee -a "${RESULTS}"; FAILS=$((FAILS + 1)); }
check() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${d}"; else ko "${d}"; fi; }
section() { printf '\n== %s\n' "$1" | tee -a "${RESULTS}"; }
synthetic() { printf 'synthetic%s%s' "$1" "$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
# Sufijo con caracteres que .env, Compose o JSON podrían alterar: sin restricción de formato
TRICKY=$'\'"$\\#& z'

# ── Espacio de trabajo: equivalente a /opt/piedrazul ─────────────────────────
cp -r "${ROOT}/infra/compose" "${W}/compose"
cp -r "${ROOT}/infra/keycloak" "${W}/keycloak"
cp -r "${ROOT}/infra/traefik" "${W}/traefik"
docker build -q -t "${PROJECT}-postgres:local" "${ROOT}/infra/postgres" >/dev/null
# use_keycloak <referencia fijada>: como el workflow (image_targets) la resuelve una vez a su
# manifiesto linux/amd64; como setup.yml, escribe esa identidad en image.env. La declaración
# que lee keycloak_image.yml es ${W}/declared/keycloak.yml (el compose/keycloak.yml del commit).
use_keycloak() {
  local identity
  identity="$("${ROOT}/.github/scripts/resolve-amd64-image.sh" "$1")" || { echo "no se pudo resolver $1" >&2; exit 2; }
  KC_DECLARED="$1"
  KC_INDEX="$(sed -n 's/^image_index_digest=//p' <<< "${identity}")"
  KC_REF="$(sed -n 's/^image_ref=//p' <<< "${identity}")"
  mkdir -p "${W}/declared"
  printf 'services:\n  keycloak:\n    image: %s\n' "${KC_DECLARED}" > "${W}/declared/keycloak.yml"
  write_image_env "${KC_REF}"
}
write_image_env() { # <KEYCLOAK_IMAGE>
  printf 'BACKEND_IMAGE=%s\nPOSTGRES_IMAGE=%s\nKEYCLOAK_IMAGE=%s\nTRAEFIK_IMAGE=%s\n' "busybox:harness-never-started" "${PROJECT}-postgres:local" "$1" "busybox:harness-never-started" > "${W}/image.env"
}
if [ "${TARGET}" = upgrade ]; then use_keycloak "${KC_FROM_DECLARED}"; else use_keycloak "${KC_TO_DECLARED}"; fi
printf 'all:\n  hosts:\n    vps:\n      ansible_connection: local\n      ansible_python_interpreter: %s\n' \
  "$(command -v "${PYTHON}")" > "${W}/hosts.yml"

POSTGRES_PASSWORD="$(synthetic pg)"
APP_DB_PASSWORD="$(synthetic app)"
MIGRATION_DB_PASSWORD="$(synthetic mig)"
KC_DB_PASSWORD="$(synthetic kcdb)"
BACKEND_SECRET="$(synthetic backend)"
AUTOMATION_SECRET="$(synthetic automation)"
# Administrador humano: contraseña inicial con caracteres que shell, .env, JSON o Jinja
# podrían alterar; la nueva la "elige" el operador en el primer login
PLATFORM_USER="$(sed -n 's/^kc_platform_admin_username: *//p' "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")"
PLATFORM_PW="$(synthetic human)${TRICKY}{{ lookup('env', 'HOME') }}{% raw %}%"
# La contraseña que elige el operador: 16+ caracteres, solo minúsculas (sin categorías)
PLATFORM_NEW_PW="synthetic$(LC_ALL=C tr -dc 'a-z' < /dev/urandom | head -c 20)"
PLATFORM_SHORT_PW="syntheticshort"   # 14: por debajo de la política de master
PLATFORM_ENV_PW="${PLATFORM_PW}"   # lo que recibe converge como KC_PLATFORM_ADMIN_INITIAL_PASSWORD
PY_IMAGE="${HARNESS_PYTHON_IMAGE:-python:3.12-slim}"
mkdir -p "${W}/otp"
SECRETS_SEEN=("${POSTGRES_PASSWORD}" "${APP_DB_PASSWORD}" "${MIGRATION_DB_PASSWORD}" "${KC_DB_PASSWORD}"
  "${BACKEND_SECRET}" "${AUTOMATION_SECRET}" "${PLATFORM_PW}" "${PLATFORM_NEW_PW}" "${PLATFORM_SHORT_PW}")
# SES: ausente salvo en el target smtp. Host fuera del patrón real para que nunca salga de la red
# de Docker; credenciales con lo que shell, JSON o Jinja podrían alterar, dentro del contrato SES.
SES_ENABLED="" SES_HOST="" SES_USER="" SES_PASSWORD="" SES_ARGS=()
if [ "${TARGET}" = smtp ]; then
  SES_ENABLED=true SES_HOST=smtp-sink SES_USER="$(synthetic smtpuser)"
  SES_PASSWORD="$(synthetic smtppw)'\"\$\\#&{{lookup('env','HOME')}}{%raw%}%"
  SES_ARGS=(-e '{"ses_smtp_host_pattern": "^smtp-sink(-2)?\\Z", "kc_smtp_connection": {"port": "2525", "starttls": "false", "ssl": "false", "auth": "true"}}')
  SECRETS_SEEN+=("${SES_USER}" "${SES_PASSWORD}")
fi
REPO_BACKEND_SECRET="$(sed -n 's/^KC_BACKEND_CLIENT_SECRET=//p' "${ROOT}/.env.example")"
REPO_ADMIN_USER="$(sed -n 's/^KC_BOOTSTRAP_ADMIN_USERNAME=//p' "${ROOT}/.env.example")"
REPO_ADMIN_PASSWORD="$(sed -n 's/^KC_BOOTSTRAP_ADMIN_PASSWORD=//p' "${ROOT}/.env.example")"

cat > "${W}/render.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  tasks:
    - ansible.builtin.template:
        src: "{{ repo_root }}/infra/ansible/roles/app/templates/env.j2"
        dest: "{{ app_dir }}/.env"
        mode: "0600"
      vars:
        app_db_secrets:
          POSTGRES_PASSWORD: "{{ lookup('env', 'POSTGRES_PASSWORD') }}"
          APP_DB_PASSWORD: "{{ lookup('env', 'APP_DB_PASSWORD') }}"
          MIGRATION_DB_PASSWORD: "{{ lookup('env', 'MIGRATION_DB_PASSWORD') }}"
          KC_DB_PASSWORD: "{{ lookup('env', 'KC_DB_PASSWORD') }}"
        app_kc_backend_secret: "{{ lookup('env', 'KC_BACKEND_CLIENT_SECRET') }}"
      no_log: true
EOF
# Subconjunto de converge.yml que gobierna Keycloak, con los task files reales
cat > "${W}/converge.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  tasks:
    - ansible.builtin.include_role: {name: app, tasks_from: keycloak_credentials.yml}
    - ansible.builtin.include_role: {name: app, tasks_from: keycloak_backend_delivery.yml}
    - ansible.builtin.include_role: {name: app, tasks_from: db_converge.yml}
    - ansible.builtin.include_role: {name: app, tasks_from: keycloak_image.yml}
    - ansible.builtin.include_role: {name: app, tasks_from: ses_inputs.yml}
    - ansible.builtin.include_role: {name: app, tasks_from: keycloak_lifecycle.yml}
EOF
# Verificación de la identidad en ejecución (la misma tarea que verify-release.yml)
cat > "${W}/verify.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  tasks:
    - ansible.builtin.include_role: {name: app, tasks_from: verify_running_images.yml}
      vars: {app_release_services: [keycloak]}
EOF

ansible_run() { # <log> <playbook> [extra args...]
  local log="$1" pb="$2"; shift 2
  env POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" APP_DB_PASSWORD="${APP_DB_PASSWORD}" \
      MIGRATION_DB_PASSWORD="${MIGRATION_DB_PASSWORD}" KC_DB_PASSWORD="${KC_DB_PASSWORD}" \
      KC_BACKEND_CLIENT_SECRET="${BACKEND_SECRET}" KC_AUTOMATION_CLIENT_SECRET="${AUTOMATION_SECRET}" \
      KC_PLATFORM_ADMIN_INITIAL_PASSWORD="${PLATFORM_ENV_PW}" \
      NOTIFICATIONS_SES_ENABLED="${SES_ENABLED}" SES_SMTP_HOST="${SES_HOST}" \
      SES_SMTP_USERNAME="${SES_USER}" SES_SMTP_PASSWORD="${SES_PASSWORD}" \
      ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" \
      ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${W}/hosts.yml" "${W}/${pb}" \
      -e "app_dir=${W}" -e "compose_project_name=${PROJECT}" -e "db_lock_file=${W}/db.lock" \
      -e "repo_root=${ROOT}" -e "keycloak_compose_file=${W}/declared/keycloak.yml" \
      -e "keycloak_image_ref=${KC_REF}" -e "keycloak_image_index_digest=${KC_INDEX}" \
      "${SES_ARGS[@]}" "$@" </dev/null > "${LOGS}/${log}.log" 2>&1
}
render_env() { ansible_run "render-$1" render.yml; }
converge() { # <etiqueta> [true|false (recuperación)] [extra args...]
  local label="$1" recovery="${2:-false}"; shift 2 2>/dev/null || shift $#
  ansible_run "${label}" converge.yml -e "keycloak_recovery=${recovery}" "$@"
}
# El callback YAML parte los mensajes largos en varias líneas: se buscan con el espacio normalizado
log_has() { tr -s ' \n' '  ' < "${LOGS}/$1.log" | grep -qF -- "$2"; }

compose() { docker compose --project-name "${PROJECT}" --file "${W}/compose/prod.yml" --env-file "${W}/.env" --env-file "${W}/image.env" "$@"; }
sql() { docker exec postgres psql -U postgres -d piedrazul_db -tAc "$1"; }
temp_count() { sql "select count(*) from keycloak.client_attributes where name='is_temporary_admin' and value='true'" 2>/dev/null; }
realm_id() { sql "select id from keycloak.realm where name='piedrazul'"; }
realms() { sql "select string_agg(name, ',' order by name) from keycloak.realm where name in ('master','piedrazul')" 2>/dev/null; }
sa_roles() { sql "select string_agg(coalesce(rc.client_id,'realm')||':'||k.name, ',' order by k.name) from keycloak.user_entity u join keycloak.client c on c.id=u.service_account_client_link join keycloak.user_role_mapping m on m.user_id=u.id join keycloak.keycloak_role k on k.id=m.role_id left join keycloak.client rc on rc.id=k.client where c.client_id='$1'"; }
# task_ran <log> <prefijo del nombre>: la tarea corrió (no "skipping") en ese log
task_ran() { grep -A1 -F "TASK [app : $2" "${LOGS}/$1.log" | grep -qE '^(changed|ok): '; }
users_in_realm() { sql "select string_agg(username, ',' order by username) from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id where r.name='piedrazul'"; }
# Versión de Keycloak que migró la base por última vez / versión declarada por una imagen
kc_db_version() { sql "select version from keycloak.migration_model order by update_time desc, id desc limit 1"; }
kc_image_version() { docker image inspect "$1" --format '{{index .Config.Labels "org.opencontainers.image.version"}}'; }
kc_container() { docker inspect keycloak --format '{{.Id}}|{{.State.StartedAt}}|{{.State.Running}}|{{.Config.Image}}' 2>/dev/null; }
# Clientes técnicos: secreto (solo su hash) y roles, para comparar antes y después
technical_row() {
  sql "select string_agg(c.client_id||':'||md5(coalesce(c.secret,''))||':'||c.enabled, ',' order by c.client_id) from keycloak.client c where c.client_id in ('piedrazul-backend','piedrazul-keycloak-automation')"
  sa_roles piedrazul-backend; sa_roles piedrazul-keycloak-automation
}

# kc_client_auth <realm> <clientId> <secret> → ok|rejected|unavailable (secreto por entorno del exec)
kc_client_auth() {
  docker exec -e "S=$3" keycloak bash -c '
    c="$(mktemp -u /dev/shm/h.XXXXXX)"; trap "rm -f $c" EXIT
    if out="$(KC_CLI_CLIENT_SECRET="$S" /opt/keycloak/bin/kcadm.sh config credentials --config "$c" \
        --server http://localhost:8180 --realm "$0" --client "$1" 2>&1)"; then echo ok
    elif grep -qiE "invalid client|unauthorized_client|401" <<<"$out"; then echo rejected
    else echo unavailable; fi' "$1" "$2" 2>/dev/null || echo unavailable
}
# kc_user_auth <realm> <user> <password>: password grant de admin-cli, solo para master (el
# admin-cli de piedrazul no lo tiene: ahí se usa realm_login, por el flujo browser)
kc_user_auth() {
  docker exec -e "S=$3" keycloak bash -c '
    c="$(mktemp -u /dev/shm/h.XXXXXX)"; trap "rm -f $c" EXIT
    if out="$(KC_CLI_PASSWORD="$S" /opt/keycloak/bin/kcadm.sh config credentials --config "$c" \
        --server http://localhost:8180 --realm "$0" --user "$1" 2>&1)"; then echo ok
    elif grep -qiE "invalid user|invalid_grant|401" <<<"$out"; then echo rejected
    else echo unavailable; fi' "$1" "$2" 2>/dev/null || echo unavailable
}
# kc_as <clientId> <secret> <realm de autenticación> <args kcadm...>: mutaciones de prueba
kc_as() {
  local client="$1" secret="$2" realm="$3"; shift 3
  docker exec -i -e "S=${secret}" keycloak bash -c '
    c="$(mktemp -u /dev/shm/h.XXXXXX)"; trap "rm -f $c" EXIT
    KC_CLI_CLIENT_SECRET="$S" /opt/keycloak/bin/kcadm.sh config credentials --config "$c" \
      --server http://localhost:8180 --realm "$0" --client "$1" >/dev/null 2>&1 || exit 3
    shift; /opt/keycloak/bin/kcadm.sh "$@" --config "$c"' "${realm}" "${client}" "$@"
}
kc_env_keys() { docker inspect keycloak --format '{{range .Config.Env}}{{println .}}{{end}}' | sed 's/=.*//' | sort; }
# Administrador humano: huella completa (id, estado, acciones, roles, atributos y credenciales
# con un hash de su contenido) para demostrar que nada lo modifica. Solo se compara, nunca se
# imprime.
platform_row() {
  sql "select u.id||'|'||u.enabled||'|'||coalesce(u.created_timestamp,0)||'|'||coalesce(u.federation_link,'')
         ||'|'||coalesce((select string_agg(required_action,'+' order by required_action) from keycloak.user_required_action where user_id=u.id),'')
         ||'|'||coalesce((select string_agg(k.name,',' order by k.name) from keycloak.user_role_mapping m join keycloak.keycloak_role k on k.id=m.role_id where m.user_id=u.id),'')
         ||'|'||coalesce((select string_agg(a.name||'='||a.value,',' order by a.name) from keycloak.user_attribute a where a.user_id=u.id),'')
         ||'|'||coalesce((select string_agg(c.id||':'||c.type||':'||c.created_date||':'||md5(coalesce(c.secret_data,'')||coalesce(c.credential_data,'')),',' order by c.id) from keycloak.credential c where c.user_id=u.id),'')
       from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='master' where u.username='${PLATFORM_USER}'"
}
# platform_facts → habilitado|acciones|roles de realm|password:n,otp:n|temporal (sin secretos)
platform_facts() {
  sql "select u.enabled||'|'||coalesce((select string_agg(required_action,'+' order by required_action) from keycloak.user_required_action where user_id=u.id),'')
         ||'|'||coalesce((select string_agg(k.name,',' order by k.name) from keycloak.user_role_mapping m join keycloak.keycloak_role k on k.id=m.role_id where m.user_id=u.id and not k.client_role),'')
         ||'|password:'||(select count(*) from keycloak.credential c where c.user_id=u.id and c.type='password')
         ||',otp:'||(select count(*) from keycloak.credential c where c.user_id=u.id and c.type='otp')
         ||'|'||exists(select 1 from keycloak.user_attribute a where a.user_id=u.id and a.name='is_temporary_admin')
       from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='master' where u.username='${PLATFORM_USER}'"
}
platform_count() { sql "select count(*) from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='master' where u.username='${PLATFORM_USER}'"; }
platform_status() { log_has "$1" "ADMINISTRACIÓN HUMANA DE KEYCLOAK (aparte del estado técnico): $2"; }
# browser <modo> <salida> [NOMBRE...]: keycloak-browser-login.py en la red de Keycloak. Las
# contraseñas pasan como variables de entorno del docker CLI (-e NOMBRE: el valor no va en argv).
browser() {
  local mode="$1" outf="$2" n; shift 2
  local args=()
  for n in "$@"; do args+=(-e "${n}"); done
  docker run --rm --network piedrazul_net --user "$(id -u):$(id -g)" \
    -v "${ROOT}/infra/tests/keycloak-browser-login.py:/kbl.py:ro" -v "${W}/otp:/otp" \
    -e KBL_BASE=http://keycloak:8180 -e KBL_REALM=master -e KBL_CLIENT=security-admin-console \
    -e "KBL_USER=${PLATFORM_USER}" -e KBL_OTP_FILE=/otp/seed "${args[@]}" \
    "${PY_IMAGE}" python -I /kbl.py "${mode}" > "${LOGS}/browser-${outf}.txt" 2>&1
}
browser_has() { grep -qxF -- "$2" "${LOGS}/browser-$1.txt"; }
browser_all() { local f="$1" l; shift; for l in "$@"; do browser_has "${f}" "${l}" || return 1; done; }
# realm_login <salida> <usuario> <contraseña> [cliente]: un intento independiente (proceso
# nuevo, sin cookies ni sesión previas) en el realm de la aplicación por el flujo browser del
# frontend: su admin-cli no tiene password grant, así que kcadm no puede probar contraseñas ahí
realm_login() {
  KBL_REALM=piedrazul KBL_CLIENT="${4:-piedrazul-frontend}" KBL_REDIRECT=http://localhost:4200/ \
    KBL_USER="$2" KBL_PASSWORD="$3" browser realm-login "$1" KBL_REALM KBL_CLIENT KBL_REDIRECT KBL_USER KBL_PASSWORD
}
# login_error_counts <id> → "<invalid_user_credentials> <otros motivos>" en el registro de
# eventos del realm (el backend tiene view-events); vacío si no se pudo leer
login_error_counts() {
  kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get events -r piedrazul -q "user=$1" -q type=LOGIN_ERROR -q max=1000 --fields error 2>/dev/null \
    | jq -r '"\([.[] | select(.error == "invalid_user_credentials")] | length) \([.[] | select(.error != "invalid_user_credentials")] | length)"'
}
# realm_rejected <salida> <usuario> <id> <contraseña>: realm_login y anota en su salida el
# motivo que registró Keycloak. La página de un rechazo por contraseña y la de una cuenta
# bloqueada son iguales a propósito: solo el evento distingue
# event.login_error=invalid_user_credentials (exactamente un fallo nuevo, de ese motivo).
realm_rejected() {
  local before after i o
  # Más que quickLoginCheckMilliSeconds (1 s): dos fallos más seguidos bloquean la cuenta
  sleep 2
  before="$(login_error_counts "$3")"
  realm_login "$1" "$2" "$4"
  after="$(login_error_counts "$3")"
  read -r i o <<< "${before:-x x}"
  if [ -n "${before}" ] && [ "${after}" = "$((i + 1)) ${o}" ]; then
    echo event.login_error=invalid_user_credentials >> "${LOGS}/browser-$1.txt"
  else
    echo "event.login_error=unexpected:${before:-?}->${after:-?}" >> "${LOGS}/browser-$1.txt"
  fi
}
# Una contraseña que Keycloak no acepta todavía por una acción requerida: ni código, ni cookie
# de identidad, ni reautorización silenciosa en el mismo navegador
REALM_PENDING_SETUP=('realm_login.outcome=required_action:login-update-password' 'realm_login.identity_cookie=absent' 'realm_login.silent_reauth=login_required')
REALM_REJECTED=('realm_login.outcome=invalid_credentials' 'realm_login.identity_cookie=absent' 'realm_login.silent_reauth=login_required' 'event.login_error=invalid_user_credentials')
# check usa bash -c: las comprobaciones necesitan estas funciones en el subshell
export -f sql temp_count realm_id realms sa_roles users_in_realm kc_env_keys kc_client_auth kc_user_auth log_has task_ran
export -f kc_db_version kc_image_version kc_container
export -f platform_row platform_facts platform_count platform_status browser_has
export LOGS PLATFORM_USER

# Sondeo continuo: ¿alguna credencial del repositorio autentica mientras Keycloak corre?
watch_repo_credentials() { # <archivo de salida>; se detiene al borrar $W/watch
  : > "$1"
  while [ -f "${W}/watch" ]; do
    if [ "$(docker inspect keycloak --format '{{.State.Running}}' 2>/dev/null)" = true ]; then
      printf 'backend-repo-secret=%s admin-repo-user=%s\n' \
        "$(kc_client_auth piedrazul piedrazul-backend "${REPO_BACKEND_SECRET}")" \
        "$(kc_user_auth master "${REPO_ADMIN_USER}" "${REPO_ADMIN_PASSWORD}")" >> "$1"
    fi
    sleep 1
  done
}

# ══ P2: base nueva ═══════════════════════════════════════════════════════════
section "P2 — base nueva"
render_env p2 || { ko "render .env"; exit 1; }
# La contraseña inicial del administrador humano se exige en P2 antes de cualquier paso del
# evento: sin ella (o débil) no se crea autoridad temporal ni se escribe en la base de Keycloak
for case in "missing||KC_PLATFORM_ADMIN_INITIAL_PASSWORD vacío" "short|synthetic-x|con menos de 16 caracteres" \
            "spaces| $(synthetic pad) |con espacios o saltos de línea en los extremos"; do
  IFS='|' read -r name PLATFORM_ENV_PW reason <<< "${case}"
  converge "p2-pw-${name}" false; RC=$?
  check "P2 sin contraseña inicial válida (${name}): falla (rc=${RC})" test "${RC}" -ne 0
  check "P2 sin contraseña inicial válida (${name}): motivo visible, valor no mostrado" \
    bash -c "log_has 'p2-pw-${name}' 'necesita KC_PLATFORM_ADMIN_INITIAL_PASSWORD válida' && log_has 'p2-pw-${name}' '${reason}'"
  check "P2 sin contraseña inicial válida (${name}): ni evento ni escritura en Keycloak (schema vacío)" \
    bash -c "! log_has 'p2-pw-${name}' 'Crear la autoridad temporal' && test \"\$(sql \"select count(*) from pg_class where relnamespace='keycloak'::regnamespace\")\" = 0"
done
PLATFORM_ENV_PW="${PLATFORM_PW}"
check ".env sin credenciales de administración de Keycloak ni de automatización" \
  bash -c "! grep -Eq '^(KC_BOOTSTRAP_ADMIN_|KC_ADMIN_|KC_AUTOMATION_)' '${W}/.env'"
touch "${W}/watch"
watch_repo_credentials "${LOGS}/p2-watch.txt" &
WATCHER=$!
converge p2 false; RC=$?
rm -f "${W}/watch"; wait "${WATCHER}" 2>/dev/null
check "P2: la convergencia termina bien (rc=${RC})" test "${RC}" -eq 0
check "P2: clasificado como P2" log_has p2 'KEYCLOAK: estado P2'
check "P2: corrió el evento de inicialización con autoridad temporal" log_has p2 'evento P2 (inicialización)'
check "P2: autoridad temporal eliminada y rechazada por Keycloak" log_has p2 'eliminada y rechazada por Keycloak'
check "P2: reclasificado como P1 después del evento" log_has p2 'KEYCLOAK: estado P1 después del evento'
check "P2: el realm se importó solo en el evento (kc.sh import de un solo uso)" task_ran p2 'Importar el realm de la aplicación'
check "P2: Keycloak de producción arranca sin --import-realm" \
  test "$(docker inspect keycloak --format '{{json .Config.Cmd}}')" = '["start"]'
check "P2: la salida solo cuenta admins humanos de master (0)" log_has p2 'Usuarios humanos con rol admin en master: 0'
check "P2: reconciliación P1 con el backend autenticando" log_has p2 'el backend autentica con él'
check "P2: la base no tiene autoridad temporal" test "$(temp_count)" = 0
# Antes: master sin usuarios humanos. Ahora P2 crea exactamente uno, el administrador humano
# permanente; el invariante sigue siendo que no hay ningún otro (ni del repositorio ni temporal)
check "P2: el único usuario humano de master es el administrador permanente" \
  test "$(sql "select string_agg(u.username, ',') from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id where r.name='master' and u.service_account_client_link is null")" = "${PLATFORM_USER}"
check "P2: administrador humano creado y verificado por el evento" log_has p2 'KEYCLOAK: administrador humano creado'
check "P2: se crea después de establecer la automatización y antes de la limpieza" \
  test "$(grep -oE 'TASK \[app : (Verificar el establecimiento|Crear el administrador humano permanente \(solo P2\)|Eliminar la autoridad temporal)\]' "${LOGS}/p2.log" | tr '\n' '|')" \
    = 'TASK [app : Verificar el establecimiento]|TASK [app : Crear el administrador humano permanente (solo P2)]|TASK [app : Eliminar la autoridad temporal]|'
check "P2: habilitado, rol admin de master, contraseña temporal, acciones CONFIGURE_TOTP+UPDATE_PASSWORD, sin OTP, no temporal" \
  test "$(platform_facts)" = "true|CONFIGURE_TOTP+UPDATE_PASSWORD|admin,default-roles-master|password:1,otp:0|false"
check "P2: estado informado aparte del técnico: creada, primer login pendiente" platform_status p2 'CREADA, primer login pendiente'
check "P2: política de contraseñas de master = length(16) (fijada en el evento, informada)" \
  bash -c "test \"\$(sql \"select password_policy from keycloak.realm where name='master'\")\" = 'length(16)' && log_has p2 'política de contraseñas de master length(16)'"
check "P2: la política del realm de la aplicación es la del repositorio" \
  test "$(sql "select coalesce(password_policy,'') from keycloak.realm where name='piedrazul'")" = "$(jq -r '.passwordPolicy // ""' "${ROOT}/infra/keycloak/realm/piedrazul-realm.json")"
check "P2: el hash de la contraseña inicial es el de Keycloak por defecto (argon2)" \
  test "$(sql "select string_agg(c.credential_data::json->>'algorithm', ',') from keycloak.credential c join keycloak.user_entity u on u.id=c.user_id where u.username='${PLATFORM_USER}' and c.type='password'")" = argon2
check "P2: admin-cli del realm de la aplicación sin password grant" \
  test "$(sql "select c.direct_access_grants_enabled from keycloak.client c join keycloak.realm r on r.id=c.realm_id where r.name='piedrazul' and c.client_id='admin-cli'")" = f
KBL_PASSWORD="${PLATFORM_PW}" browser direct-grant p2-initial-pw KBL_PASSWORD
check "P2: la contraseña inicial llegó literal (' \" \$ \\ # & espacio {{ }} {% %}) y sola no da acceso: Keycloak exige las acciones" \
  browser_has p2-initial-pw 'direct_grant.without_otp=account_is_not_fully_set_up'
KBL_PASSWORD="$(synthetic wrong)" browser direct-grant p2-wrong-pw KBL_PASSWORD
check "P2: control: otra contraseña es rechazada como credencial inválida" \
  browser_has p2-wrong-pw 'direct_grant.without_otp=invalid_user_credentials'
printf '%s' "${PLATFORM_PW}" > "${W}/.needle"
check "P2: la contraseña inicial no queda en el host (.env, Compose ni archivos del despliegue)" \
  bash -c "! grep -rqF --exclude-dir=evidence --exclude-dir=otp --exclude=.needle -f '${W}/.needle' '${W}'"
rm -f "${W}/.needle"
check "P2: no quedan sesiones de kcadm en /dev/shm de Keycloak" test -z "$(docker exec keycloak ls /dev/shm 2>/dev/null)"
check "P2: sondeo durante el evento corrió (≥3 muestras con Keycloak arriba)" test "$(wc -l < "${LOGS}/p2-watch.txt")" -ge 3
check "P2: ninguna muestra con el secreto del backend del repositorio válido" bash -c "! grep -q 'backend-repo-secret=ok' '${LOGS}/p2-watch.txt'"
check "P2: ninguna muestra con el admin de desarrollo del repositorio válido" bash -c "! grep -q 'admin-repo-user=ok' '${LOGS}/p2-watch.txt'"
check "P2: el secreto del backend del repositorio es rechazado" test "$(kc_client_auth piedrazul piedrazul-backend "${REPO_BACKEND_SECRET}")" = rejected
check "P2: el secreto deseado del backend autentica" test "$(kc_client_auth piedrazul piedrazul-backend "${BACKEND_SECRET}")" = ok
check "P2: la automatización autentica" test "$(kc_client_auth master piedrazul-keycloak-automation "${AUTOMATION_SECRET}")" = ok
check "P2: el contenedor keycloak no recibe KC_BOOTSTRAP_ADMIN_*, KC_ADMIN_*, KC_PLATFORM_ADMIN_* ni secretos de clientes" \
  bash -c "! kc_env_keys | grep -Eq '^(KC_BOOTSTRAP_ADMIN_|KC_ADMIN_|KC_PLATFORM_ADMIN_|KC_BACKEND_|KC_AUTOMATION_|KC_TMP_)'"
check "P2: sin identidades de negocio (solo el service account del backend en el realm)" \
  test "$(users_in_realm)" = "service-account-piedrazul-backend"
check "P2: roles exactos de la automatización (solo piedrazul-realm)" \
  test "$(sql "select string_agg(rc.client_id||':'||k.name, ',' order by k.name) from keycloak.user_entity u join keycloak.client c on c.id=u.service_account_client_link join keycloak.user_role_mapping m on m.user_id=u.id join keycloak.keycloak_role k on k.id=m.role_id join keycloak.client rc on rc.id=k.client where c.client_id='piedrazul-keycloak-automation'")" = "piedrazul-realm:manage-clients,piedrazul-realm:manage-realm,piedrazul-realm:view-clients"
check "P2: no quedan contenedores de un solo uso de bootstrap-admin" \
  test -z "$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" --filter 'label=com.docker.compose.oneoff=True')"
REALM_ID="$(realm_id)"
PLATFORM_SNAP="$(platform_row)"
# Convergencias normales: sin la contraseña inicial (el secreto de un solo uso ya no hace falta)
PLATFORM_ENV_PW=""

# Identidad de negocio de prueba (con la autoridad real del backend: manage-users) para
# demostrar que nada se recrea ni borra
kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul create users -r piedrazul -s username=harness-sentinel -s enabled=true >/dev/null 2>&1
check "sentinela de negocio creado por el backend" bash -c "users_in_realm | grep -q harness-sentinel"

# Correo: el primer administrador de negocio no depende de él. La cuenta se crea como el
# bootstrap: contraseña temporal + UPDATE_PASSWORD, correo sin verificar
first_admin_expect() { # <etiqueta>
  local user="harness-admin-$1" tag="first-admin-$1" pw new bad id
  pw="$(synthetic firstadmin)"; new="$(synthetic firstadminnew)"; bad="$(synthetic firstadminwrong)"
  SECRETS_SEEN+=("${pw}" "${new}" "${bad}")
  kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul create users -r piedrazul -s "username=${user}" -s enabled=true \
    -s firstName=Primer -s lastName=Administrador -s "email=${user}@harness.test" -s emailVerified=false \
    -s 'requiredActions=["UPDATE_PASSWORD"]' >/dev/null 2>&1
  id="$(kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get users -r piedrazul -q "username=${user}" -q exact=true --fields id --format csv --noquotes 2>/dev/null)"
  jq -cn --arg p "${pw}" '{type: "password", value: $p, temporary: true}' \
    | kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul update "users/${id}/reset-password" -r piedrazul -f - -n >/dev/null 2>&1
  # Cada sondeo, por el flujo browser del frontend (realm_login), es un intento independiente
  realm_login "first-admin-$1-temp" "${user}" "${pw}"
  check "$1: administrador inicial: la contraseña temporal sola no da acceso" \
    browser_all "first-admin-$1-temp" "${REALM_PENDING_SETUP[@]}"
  realm_login "first-admin-$1-probe" "${user}" "${pw}" admin-cli
  check "$1: control: un sondeo inutilizable (cliente sin flujo browser) es un error del sondeo, no un resultado" \
    bash -c "! grep -q '^realm_login.outcome=' '${LOGS}/browser-first-admin-$1-probe.txt' && grep -q '^error=Unrecognized:' '${LOGS}/browser-first-admin-$1-probe.txt'"
  realm_rejected "first-admin-$1-wrong-before" "${user}" "${id}" "${bad}"
  check "$1: administrador inicial: antes del cambio, una contraseña incorrecta se rechaza por credenciales" \
    browser_all "first-admin-$1-wrong-before" "${REALM_REJECTED[@]}"
  KBL_REALM=piedrazul KBL_CLIENT=piedrazul-frontend KBL_REDIRECT=http://localhost:4200/ \
    KBL_USER="${user}" KBL_PASSWORD="${pw}" KBL_NEW_PASSWORD="${new}" \
    browser realm-first-login "first-admin-$1" KBL_REALM KBL_CLIENT KBL_REDIRECT KBL_USER KBL_PASSWORD KBL_NEW_PASSWORD
  check "$1: administrador inicial: UPDATE_PASSWORD por el tema real y entra, sin correo" \
    bash -c "browser_has 'first-admin-$1' 'realm_first_login.page=login-update-password' && browser_has 'first-admin-$1' 'browser.token=ok'"
  realm_login "first-admin-$1-new" "${user}" "${new}"
  realm_rejected "first-admin-$1-old" "${user}" "${id}" "${pw}"
  check "$1: administrador inicial: su contraseña elegida autentica y la temporal ya no" \
    eval 'browser_has "${tag}-new" realm_login.outcome=authenticated && browser_all "${tag}-old" "${REALM_REJECTED[@]}"'
  realm_rejected "first-admin-$1-wrong" "${user}" "${id}" "${bad}"
  check "$1: administrador inicial: una contraseña incorrecta se rechaza por credenciales (no por cliente, bloqueo ni sondeo)" \
    browser_all "first-admin-$1-wrong" "${REALM_REJECTED[@]}"
}
smtp_rows() { sql "select count(*) from keycloak.realm_smtp_config c join keycloak.realm r on r.id=c.realm_id where r.name='piedrazul'"; }
reset_allowed() { sql "select reset_password_allowed from keycloak.realm where name='piedrazul'"; }
if [ "${TARGET}" != smtp ]; then
  check "P2 sin SES: ninguna entrada de correo y el realm sin SMTP ni recuperación" \
    test "$(smtp_rows)|$(reset_allowed)" = "0|f"
  check "P2 sin SES: no se llamó a la API e informa lo deshabilitado" \
    bash -c "! task_ran p2 'Aplicar SMTP y recuperación al realm' && log_has p2 'sin SMTP y recuperación de contraseña deshabilitada'"
  check "P2 sin SES: Keycloak no recibe ninguna clave de correo" bash -c "! kc_env_keys | grep -Eq '^(SES_|NOTIFICATIONS_)'"
  first_admin_expect p2-sin-ses
fi

check "P2: Keycloak corre la identidad exacta recibida (manifiesto linux/amd64 de ${KC_DECLARED})" \
  test "$(docker inspect keycloak --format '{{.Config.Image}}')" = "${KC_REF}"
check "P2: la base quedó en la versión de la imagen ($(kc_image_version "${KC_REF}"))" \
  test "$(kc_db_version)" = "$(kc_image_version "${KC_REF}")"
check "P2: converge informó base nueva y verificó identidad en ejecución y versión de la base" \
  bash -c "log_has p2 'base nueva; la inicializa' && log_has p2 'en ejecución (${KC_REF}); base en'"
PLATFORM_STATUS_P1='CREADA, primer login pendiente'

# ══ SCOPE: alcance de roles explícito de los clientes propios ═════════════════
SCOPE_AUTO='piedrazul-realm:manage-clients,piedrazul-realm:manage-realm,piedrazul-realm:view-clients'
SCOPE_BACK='realm-management:manage-users,realm-management:query-users,realm-management:view-events,realm-management:view-realm,realm-management:view-users'
SCOPE_FRONT='realm:ADMIN,realm:AUDITOR,realm:DOCTOR,realm:PATIENT,realm:SCHEDULER'
SCOPE_TARGET="piedrazul-backend=full:false;${SCOPE_BACK} piedrazul-frontend=full:false;${SCOPE_FRONT} piedrazul-keycloak-automation=full:false;${SCOPE_AUTO}"
# <clientId>=full:<b>;<asignaciones directas ordenadas>, de los tres clientes, leído de la base
scope_row() {
  sql "select string_agg(c.client_id||'=full:'||c.full_scope_allowed||';'||coalesce((select string_agg(case when k.client_role then rc.client_id else 'realm' end||':'||k.name, ',' order by case when k.client_role then rc.client_id else 'realm' end, k.name) from keycloak.scope_mapping s join keycloak.keycloak_role k on k.id=s.role_id left join keycloak.client rc on rc.id=k.client where s.client_id=c.id),''), ' ' order by c.client_id) from keycloak.client c where c.client_id in ('piedrazul-backend','piedrazul-frontend','piedrazul-keycloak-automation')"
}
# Avisos de Full Scope de Keycloak para los tres clientes propios desde <instante RFC 3339>
fullscope_warnings() {
  docker logs --since "$1" keycloak 2>&1 | grep -cE "Client '(piedrazul-backend|piedrazul-frontend|piedrazul-keycloak-automation)' in the realm '[^']+' has 'Full scope allowed'"
}
# kc_try <clientId> <secreto> <realm de autenticación> <args kcadm...> → ok | forbidden | error
kc_try() {
  local o
  if o="$(kc_as "$@" 2>&1)"; then echo ok; elif grep -qiE '403|forbidden' <<< "${o}"; then echo forbidden; else echo error; fi
}
export -f scope_row fullscope_warnings
export SCOPE_TARGET

section "SCOPE — alcance de roles explícito establecido en P2"
check "SCOPE-P2: los tres clientes propios con Full Scope deshabilitado y exactamente su alcance aprobado" \
  test "$(scope_row)" = "${SCOPE_TARGET}"
check "SCOPE-P2: el evento estableció la automatización; backend y frontend ya venían del realm importado" \
  bash -c "log_has p2 'scope.piedrazul-keycloak-automation=updated' && log_has p2 'scope.piedrazul-backend=unchanged' && log_has p2 'scope.piedrazul-frontend=unchanged'"
check "SCOPE-P2: clientes integrados sin cambios (admin-cli y security-admin-console con Full Scope en ambos realms)" \
  test "$(sql "select string_agg(r.name||'/'||c.client_id||':'||c.full_scope_allowed, ',' order by r.name, c.client_id) from keycloak.client c join keycloak.realm r on r.id=c.realm_id where c.client_id in ('admin-cli','security-admin-console')")" \
    = "master/admin-cli:true,master/security-admin-console:true,piedrazul/admin-cli:true,piedrazul/security-admin-console:true"
check "SCOPE-P2: ninguna política de cliente full-scope-disabled en la base" \
  test "$(sql "select count(*) from keycloak.realm_attribute where value like '%full-scope-disabled%'")" = 0

if [ -z "${TARGET}" ]; then
section "SCOPE — tokens y autoridad reales de los tres clientes"
SCOPE_T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
declare -A FE_PW
fe_user() { # <usuario> <roles de realm, por comas> [rol técnico <clientId>:<rol>]: lo crea el backend (su autoridad real)
  local u="$1" pw id r
  pw="$(synthetic fe)"; SECRETS_SEEN+=("${pw}"); FE_PW["${u}"]="${pw}"
  kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul create users -r piedrazul -s "username=${u}" -s enabled=true \
    -s firstName=Sintetico -s lastName=Harness -s "email=${u}@harness.test" -s emailVerified=true >/dev/null 2>&1
  id="$(kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get users -r piedrazul -q "username=${u}" -q exact=true --fields id --format csv --noquotes 2>/dev/null)"
  jq -cn --arg p "${pw}" '{type: "password", value: $p, temporary: false}' \
    | kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul update "users/${id}/reset-password" -r piedrazul -f - -n >/dev/null 2>&1
  for r in ${2//,/ }; do
    kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul add-roles -r piedrazul --uusername "${u}" --rolename "${r}" >/dev/null 2>&1
  done
  # Rol de cliente: por role-mappings del usuario (el backend no tiene view-clients para buscar
  # el cliente por nombre, y no lo necesita)
  if [ -n "${3:-}" ]; then
    local rc
    rc="$(sql "select c.id from keycloak.client c join keycloak.realm r on r.id=c.realm_id and r.name='piedrazul' where c.client_id='${3%%:*}'")"
    kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get "users/${id}/role-mappings/clients/${rc}/available" -r piedrazul 2>/dev/null \
      | jq -c --arg n "${3#*:}" '[.[] | select(.name == $n)]' \
      | kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul create "users/${id}/role-mappings/clients/${rc}" -r piedrazul -f - >/dev/null 2>&1
  fi
}
fe_token() { # <salida> <usuario> [realm-token|realm-refresh|realm-logout]: como keycloak-js, por el flujo browser del frontend
  KBL_REALM=piedrazul KBL_CLIENT=piedrazul-frontend KBL_REDIRECT=http://localhost:4200/ KBL_USER="$2" \
    KBL_PASSWORD="${FE_PW[$2]}" KBL_TOKEN_FILE=/otp/fe-refresh \
    browser "${3:-realm-token}" "$1" KBL_REALM KBL_CLIENT KBL_REDIRECT KBL_USER KBL_PASSWORD KBL_TOKEN_FILE
}
for role in ADMIN SCHEDULER DOCTOR PATIENT AUDITOR; do
  u="harness-fe-${role,,}"; fe_user "${u}" "${role}"; fe_token "fe-${role,,}" "${u}"
  check "SCOPE-FE ${role}: login PKCE S256 del frontend; el token lleva solo ${role}, ningún rol de cliente" \
    browser_all "fe-${role,,}" token.status=200 token.azp=piedrazul-frontend "token.realm_roles=${role}" token.resource_access=none
done
check "SCOPE-FE: PKCE sigue exigido (autorización sin code_challenge rechazada)" \
  bash -c "for r in admin scheduler doctor patient auditor; do browser_has fe-\$r token.authorize_without_pkce=refused || exit 1; done"
fe_user harness-fe-multi DOCTOR,SCHEDULER,AUDITOR,PATIENT; fe_token fe-multi harness-fe-multi
check "SCOPE-FE: varios roles de negocio llegan todos al token" \
  browser_all fe-multi token.status=200 token.realm_roles=AUDITOR,DOCTOR,PATIENT,SCHEDULER token.resource_access=none
fe_user harness-fe-tech PATIENT realm-management:view-users; fe_token fe-tech harness-fe-tech
check "SCOPE-FE: preparación: el usuario tiene asignado un rol técnico (realm-management:view-users)" \
  test "$(sql "select count(*) from keycloak.user_role_mapping m join keycloak.user_entity u on u.id=m.user_id join keycloak.keycloak_role k on k.id=m.role_id where u.username='harness-fe-tech' and k.name='view-users'")" = 1
check "SCOPE-FE: ese rol técnico no llega al token del frontend" \
  browser_all fe-tech token.status=200 token.realm_roles=PATIENT token.resource_access=none
check "SCOPE-FE: los roles del token son exactamente los que Spring convierte (enum Role); default-roles, offline_access y uma_authorization ya no viajan" \
  bash -c "! grep -hE '^token[.]realm_roles=' '${LOGS}'/browser-fe-*.txt | grep -Eq 'default-roles|offline_access|uma_authorization'"
# Renovación tras otorgar y quitar un rol de negocio (lo que hace el frontend con updateToken)
fe_token fe-refresh-login harness-fe-doctor
kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul add-roles -r piedrazul --uusername harness-fe-doctor --rolename SCHEDULER >/dev/null 2>&1
fe_token fe-refresh-granted harness-fe-doctor realm-refresh
kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul remove-roles -r piedrazul --uusername harness-fe-doctor --rolename SCHEDULER >/dev/null 2>&1
fe_token fe-refresh-revoked harness-fe-doctor realm-refresh
check "SCOPE-FE: renovar tras otorgar SCHEDULER lo agrega al token" browser_all fe-refresh-granted refresh.status=200 refresh.realm_roles=DOCTOR,SCHEDULER
check "SCOPE-FE: renovar tras quitarlo lo retira del token" browser_all fe-refresh-revoked refresh.status=200 refresh.realm_roles=DOCTOR
fe_token fe-logout harness-fe-doctor realm-logout
check "SCOPE-FE: cerrar sesión invalida la renovación" browser_all fe-logout logout.status=204 logout.refresh_after=rejected
rm -f "${W}/otp/fe-refresh"
# Backend y automatización: roles efectivos de su token (con la expansión de compuestos de Keycloak)
KBL_REALM=piedrazul KBL_CLIENT=piedrazul-backend KBL_SECRET="${BACKEND_SECRET}" browser client-roles scope-backend KBL_REALM KBL_CLIENT KBL_SECRET
KBL_REALM=master KBL_CLIENT=piedrazul-keycloak-automation KBL_SECRET="${AUTOMATION_SECRET}" browser client-roles scope-automation KBL_REALM KBL_CLIENT KBL_SECRET
check "SCOPE-BACK: el token lleva exactamente sus cinco roles (+ query-groups, compuesto de view-users), ningún rol de realm" \
  browser_all scope-backend client.status=200 client.realm_roles=none \
    client.resource_access=realm-management:manage-users,realm-management:query-groups,realm-management:query-users,realm-management:view-events,realm-management:view-realm,realm-management:view-users
check "SCOPE-AUTO: el token lleva exactamente sus tres roles (+ query-clients, compuesto de view-clients), nada en master" \
  browser_all scope-automation client.status=200 client.realm_roles=none \
    client.resource_access=piedrazul-realm:manage-clients,piedrazul-realm:manage-realm,piedrazul-realm:query-clients,piedrazul-realm:view-clients
B=(piedrazul-backend "${BACKEND_SECRET}" piedrazul)
check "SCOPE-BACK: gestión de usuarios, lectura del realm y eventos de login permitidos" \
  test "$(kc_try "${B[@]}" get users -r piedrazul -q max=1)|$(kc_try "${B[@]}" get "realms/piedrazul" --fields realm)|$(kc_try "${B[@]}" get events -r piedrazul -q type=LOGIN -q max=1)" = "ok|ok|ok"
check "SCOPE-BACK: fuera de su autoridad, denegado (escribir el realm, crear clientes, roles de realm, master)" \
  test "$(kc_try "${B[@]}" update realms/piedrazul -s enabled=true)|$(kc_try "${B[@]}" create clients -r piedrazul -s clientId=harness-denied)|$(kc_try "${B[@]}" create roles -r piedrazul -s name=HARNESS_DENIED)|$(kc_try "${B[@]}" get users -r master -q max=1)" \
    = "forbidden|forbidden|forbidden|forbidden"
A=(piedrazul-keycloak-automation "${AUTOMATION_SECRET}" master)
check "SCOPE-AUTO: ve los clientes de la aplicación (lo que usan probe y reconcile)" \
  test "$(kc_try "${A[@]}" get clients -r piedrazul -q clientId=piedrazul-backend --fields id)" = ok
check "SCOPE-AUTO: sin autoridad sobre usuarios ni sobre master" \
  test "$(kc_try "${A[@]}" get users -r piedrazul -q max=1)|$(kc_try "${A[@]}" get users -r master -q max=1)|$(kc_try "${A[@]}" get clients -r master -q max=1)" = "forbidden|forbidden|forbidden"
sleep 2
check "SCOPE: tras emitir tokens reales de los tres clientes, Keycloak no avisa Full Scope para ninguno" \
  test "$(fullscope_warnings "${SCOPE_T0}")" = 0
for u in harness-fe-admin harness-fe-scheduler harness-fe-doctor harness-fe-patient harness-fe-auditor harness-fe-multi harness-fe-tech; do
  id="$(kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get users -r piedrazul -q "username=${u}" -q exact=true --fields id --format csv --noquotes 2>/dev/null)"
  [ -z "${id}" ] || kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul delete "users/${id}" -r piedrazul >/dev/null 2>&1
done
check "SCOPE-FE: usuarios sintéticos retirados" bash -c "! users_in_realm | grep -q harness-fe-"
fi

# ══ UPG: actualización de Keycloak sobre una base existente ══════════════════
if [ "${TARGET}" = upgrade ]; then
KC_FROM_REF="${KC_REF}"
FROM_VERSION="$(kc_image_version "${KC_FROM_REF}")"
section "UPG — estado previo en ${KC_FROM_DECLARED} (${FROM_VERSION})"
# Primer login del administrador humano en la versión anterior: contraseña propia y OTP
KBL_PASSWORD="${PLATFORM_PW}" KBL_NEW_PASSWORD="${PLATFORM_NEW_PW}" KBL_SHORT_PASSWORD="${PLATFORM_SHORT_PW}" \
  browser first-login first-login KBL_PASSWORD KBL_NEW_PASSWORD KBL_SHORT_PASSWORD
check "previo: primer login del administrador humano completo (contraseña nueva y OTP)" \
  bash -c "browser_has first-login 'first_login.actions=CONFIGURE_TOTP,UPDATE_PASSWORD' && browser_has first-login 'browser.token=ok'"
check "previo: administrador humano configurado (OTP, sin acciones pendientes)" \
  test "$(platform_facts)" = "true||admin,default-roles-master|password:1,otp:1|false"
# Identidad de negocio pendiente de su primer login (contraseña temporal + UPDATE_PASSWORD),
# como el primer ADMIN de negocio: creada con la autoridad real del backend
PENDING_PW="$(synthetic pending)"; SECRETS_SEEN+=("${PENDING_PW}")
kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul create users -r piedrazul -s username=harness-pending -s enabled=true \
  -s 'requiredActions=["UPDATE_PASSWORD"]' >/dev/null 2>&1
PENDING_ID="$(kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get users -r piedrazul -q username=harness-pending -q exact=true --fields id --format csv --noquotes 2>/dev/null)"
jq -cn --arg p "${PENDING_PW}" '{type: "password", value: $p, temporary: true}' \
  | kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul update "users/${PENDING_ID}/reset-password" -r piedrazul -f - -n >/dev/null 2>&1
realm_login pending-before harness-pending "${PENDING_PW}"
check "previo: identidad de negocio pendiente: la contraseña temporal sola no da acceso" \
  browser_all pending-before "${REALM_PENDING_SETUP[@]}"
pending_row() {
  sql "select u.id||'|'||u.enabled||'|'||coalesce((select string_agg(required_action,'+' order by required_action) from keycloak.user_required_action where user_id=u.id),'')
         ||'|'||coalesce((select string_agg(c.id||':'||c.type||':'||md5(coalesce(c.secret_data,'')||coalesce(c.credential_data,'')),',' order by c.id) from keycloak.credential c where c.user_id=u.id),'')
       from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='piedrazul' where u.username='harness-pending'"
}
PLATFORM_SNAP="$(platform_row)"
PENDING_SNAP="$(pending_row)"
TECH_SNAP="$(technical_row)"
USERS_SNAP="$(users_in_realm)"
CONTAINER_BEFORE="$(kc_container)"
check "previo: la base está en ${FROM_VERSION}" test "$(kc_db_version)" = "${FROM_VERSION}"

section "UPG — actualización por convergencia normal a ${KC_TO_DECLARED}"
use_keycloak "${KC_TO_DECLARED}"
TO_VERSION="$(docker pull -q --platform linux/amd64 "${KC_REF}" >/dev/null; kc_image_version "${KC_REF}")"
converge upgrade false; RC=$?
check "UPG: la convergencia normal termina bien (rc=${RC})" test "${RC}" -eq 0
if [ "${FROM_VERSION}" != "${TO_VERSION}" ]; then
check "UPG: informa la migración hacia adelante ${FROM_VERSION} → ${TO_VERSION} antes de arrancar" \
  bash -c "log_has upgrade 'base migrada por ${FROM_VERSION}, arrancar ${TO_VERSION} la migra hacia adelante (sin vuelta atrás)'"
else
# Reemplazo de digest con la misma versión (p. ej. reconstrucción corregida del mismo release)
check "UPG: misma versión ${TO_VERSION}, otro digest: informa que no hay migración" \
  log_has upgrade "base migrada por ${FROM_VERSION}, misma versión que la imagen: sin migración (imagen ${KC_REF}"
fi
check "UPG: P1 sin evento ni autoridad temporal (la actualización no es un evento de identidad)" \
  bash -c "log_has upgrade 'KEYCLOAK: estado P1' && ! log_has upgrade 'Crear la autoridad temporal' && test \$(temp_count) = 0"
check "UPG: Keycloak recreado con la identidad exacta nueva" \
  bash -c "c=\"\$(kc_container)\"; test \"\${c%%|*}\" != '${CONTAINER_BEFORE%%|*}' && test \"\${c##*|}\" = '${KC_REF}'"
check "UPG: la base quedó migrada a ${TO_VERSION} (y conserva el registro de ${FROM_VERSION})" \
  bash -c "test \"\$(kc_db_version)\" = '${TO_VERSION}' && test \"\$(sql \"select count(*) from keycloak.migration_model where version='${FROM_VERSION}'\")\" = 1"
check "UPG: converge verificó identidad en ejecución y versión de la base" \
  log_has upgrade "KEYCLOAK ${TO_VERSION} en ejecución (${KC_REF}); base en ${TO_VERSION}"
check "UPG: mismo realm, mismas identidades de negocio" \
  bash -c "test '$(realm_id)' = '${REALM_ID}' && test \"\$(users_in_realm)\" = '${USERS_SNAP}'"
check "UPG: clientes técnicos con el mismo secreto (hash), forma y roles" test "$(technical_row)" = "${TECH_SNAP}"
check "UPG: backend y automatización autentican con sus secretos; el del repositorio sigue rechazado" \
  bash -c "test \"\$(kc_client_auth piedrazul piedrazul-backend '${BACKEND_SECRET}')\" = ok && test \"\$(kc_client_auth master piedrazul-keycloak-automation '${AUTOMATION_SECRET}')\" = ok && test \"\$(kc_client_auth piedrazul piedrazul-backend '${REPO_BACKEND_SECRET}')\" = rejected"
check "UPG: administrador humano intacto (id, estado, acciones, roles, atributos, credenciales)" \
  test "$(platform_row)" = "${PLATFORM_SNAP}"
check "UPG: informa la preparación humana: CONFIGURADA" platform_status upgrade 'CONFIGURADA'
check "UPG: identidad de negocio pendiente intacta (acción UPDATE_PASSWORD y contraseña temporal)" \
  test "$(pending_row)" = "${PENDING_SNAP}"
realm_login pending-after harness-pending "${PENDING_PW}"
check "UPG: su contraseña temporal sigue sin dar acceso sola (Keycloak exige la acción)" \
  browser_all pending-after "${REALM_PENDING_SETUP[@]}"
KBL_PASSWORD="${PLATFORM_NEW_PW}" browser login upg-login KBL_PASSWORD
check "UPG: login del administrador humano en ${TO_VERSION}: pide OTP, rechaza uno incorrecto, entra con el correcto" \
  bash -c "browser_has upg-login 'browser.otp_challenge=yes' && browser_has upg-login 'browser.wrong_otp=rejected' && browser_has upg-login 'browser.token=ok'"
KBL_PASSWORD="${PLATFORM_NEW_PW}" browser direct-grant upg-dg KBL_PASSWORD
check "UPG: admin-cli sin OTP no entrega token; con OTP sí" \
  bash -c "browser_has upg-dg 'direct_grant.without_otp=invalid_user_credentials' && browser_has upg-dg 'direct_grant.with_otp=token'"
ansible_run upg-verify-ok verify.yml; RC=$?
check "UPG: verificación de identidad en ejecución con la identidad nueva: pasa (rc=${RC})" test "${RC}" -eq 0
ansible_run upg-verify-old verify.yml -e "keycloak_image_ref=${KC_FROM_REF}"; RC=$?
check "UPG: verificación con la identidad anterior: falla y no se registra como éxito (rc=${RC})" \
  bash -c "test ${RC} -ne 0 && log_has upg-verify-old 'IDENTIDAD EN EJECUCIÓN: keycloak no corre ${KC_FROM_REF}'"
converge upgrade-again false; RC=$?
check "UPG: la convergencia siguiente no recrea ni migra (misma versión) (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has upgrade-again 'misma versión que la imagen: sin migración' && log_has upgrade-again 'KEYCLOAK: estado P1'"
KC_TO_REF="${KC_REF}"

if [ "${FROM_VERSION}" = "${TO_VERSION}" ]; then
# Misma versión: la base no se migró, así que volver al digest anterior es compatible para el
# ciclo de vida y converge lo admite. Rechazar ese digest (p. ej. por un CRITICAL) es de la
# política de vulnerabilidades del workflow, no de converge; este harness no la ejecuta.
section "UPG — volver a ${KC_FROM_DECLARED} (misma versión ${TO_VERSION}): compatible con la base"
CONTAINER_UPGRADED="$(kc_container)"
use_keycloak "${KC_FROM_DECLARED}"
converge downgrade-same-version false; RC=$?
check "vuelta al digest anterior (misma versión): converge lo admite (rc=${RC})" test "${RC}" -eq 0
check "vuelta al digest anterior: sin migración, base en ${TO_VERSION} sin registros nuevos" \
  bash -c "log_has downgrade-same-version 'misma versión que la imagen: sin migración (imagen ${KC_FROM_REF}' && test \"\$(kc_db_version)\" = '${TO_VERSION}' && test \"\$(sql \"select count(*) from keycloak.migration_model where version='${TO_VERSION}'\")\" = 1"
check "vuelta al digest anterior: Keycloak recreado con esa identidad exacta" \
  bash -c "c=\"\$(kc_container)\"; test \"\${c%%|*}\" != '${CONTAINER_UPGRADED%%|*}' && test \"\${c##*|}\" = '${KC_FROM_REF}'"
check "vuelta al digest anterior: P1 sin evento ni autoridad temporal" \
  bash -c "log_has downgrade-same-version 'KEYCLOAK: estado P1' && ! log_has downgrade-same-version 'Crear la autoridad temporal' && test \$(temp_count) = 0"
use_keycloak "${KC_TO_DECLARED}"
converge upgrade-restore false; RC=$?
check "vuelta atrás: restaurada la identidad nueva para lo que sigue (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && test '${KC_REF}' = '${KC_TO_REF}' && c=\"\$(kc_container)\" && test \"\${c##*|}\" = '${KC_TO_REF}'"
check "restauración: mismo realm, identidades, clientes técnicos y administrador humano" \
  test "$(realm_id)|$(users_in_realm)|$(technical_row)|$(platform_row)" = "${REALM_ID}|${USERS_SNAP}|${TECH_SNAP}|${PLATFORM_SNAP}"
else
section "UPG — volver atrás a ${KC_FROM_DECLARED} sobre la base migrada: falla cerrado"
CONTAINER_UPGRADED="$(kc_container)"
use_keycloak "${KC_FROM_DECLARED}"
for mode in false true; do
  converge "downgrade-rec-${mode}" "${mode}"; RC=$?
  check "vuelta atrás (recuperación=${mode}): falla (rc=${RC})" test "${RC}" -ne 0
  check "vuelta atrás (recuperación=${mode}): motivo visible (base migrada por ${TO_VERSION}, imagen ${FROM_VERSION})" \
    log_has "downgrade-rec-${mode}" "la base ya fue migrada por Keycloak ${TO_VERSION} y la imagen de este converge es ${FROM_VERSION}"
  check "vuelta atrás (recuperación=${mode}): Keycloak no se detuvo, recreó ni arrancó con la imagen anterior" \
    test "$(kc_container)" = "${CONTAINER_UPGRADED}"
  check "vuelta atrás (recuperación=${mode}): sin evento, autoridad temporal ni reconciliación" \
    bash -c "! log_has downgrade-rec-${mode} 'Crear la autoridad temporal' && ! log_has downgrade-rec-${mode} 'Reconciliar el secreto' && test \$(temp_count) = 0"
  check "vuelta atrás (recuperación=${mode}): la base sigue en ${TO_VERSION}" test "$(kc_db_version)" = "${TO_VERSION}"
done
use_keycloak "${KC_TO_DECLARED}"
check "vuelta atrás: restaurada la identidad nueva para lo que sigue" test "${KC_REF}" = "${KC_TO_REF}"
fi
PLATFORM_STATUS_P1='CONFIGURADA'
fi

if [ "${TARGET}" != smtp ]; then
# ══ IDENT: identidad de Keycloak inválida o distinta ═════════════════════════
section "IDENT — identidad de Keycloak inválida o distinta: falla antes de tocar Keycloak"
declare_keycloak() { printf 'services:\n  keycloak:\n    image: %s\n' "$1" > "${W}/declared/keycloak.yml"; }
ident_expect() { # <etiqueta> <motivo> [extra args]
  local label="$1" reason="$2" before rc; shift 2
  before="$(kc_container)"
  converge "${label}" false "$@"; rc=$?
  check "${label}: falla (rc=${rc})" test "${rc}" -ne 0
  check "${label}: motivo visible: ${reason}" log_has "${label}" "${reason}"
  check "${label}: Keycloak intacto (mismo contenedor, sin reinicio); sin clasificar ni reconciliar" \
    bash -c "test \"\$(kc_container)\" = '${before}' && ! log_has '${label}' 'KEYCLOAK: estado' && ! log_has '${label}' 'Reconciliar el secreto'"
}
check "IDENT: preparación: Keycloak corre ${KC_REF}" \
  bash -c "c=\"\$(kc_container)\"; [[ \"\${c}\" == *'|true|${KC_REF}' ]]"
FAKE_DIGEST="sha256:$(printf '0%.0s' {1..64})"
declare_keycloak "${KC_DECLARED%@*}"
ident_expect ident-tag-only 'IDENTIDAD DE KEYCLOAK inválida o distinta de la declarada'
declare_keycloak "${KC_DECLARED%%:*}:$(sed 's/[0-9]*$/999/' <<< "${TO_VERSION:-$(kc_image_version "${KC_REF}")}")@${KC_INDEX}"
ident_expect ident-tag-mismatch 'no es la imagen linux/amd64 de la versión declarada'
declare_keycloak "${KC_DECLARED}"
ident_expect ident-index-mismatch 'IDENTIDAD DE KEYCLOAK inválida o distinta de la declarada' -e "keycloak_image_index_digest=${FAKE_DIGEST}"
ident_expect ident-index-as-ref 'IDENTIDAD DE KEYCLOAK inválida o distinta de la declarada' -e "keycloak_image_ref=quay.io/keycloak/keycloak@${KC_INDEX}"
ident_expect ident-other-repo 'IDENTIDAD DE KEYCLOAK inválida o distinta de la declarada' -e "keycloak_image_ref=docker.io/keycloak/keycloak@${KC_REF#*@}"
ident_expect ident-tag-ref 'IDENTIDAD DE KEYCLOAK inválida o distinta de la declarada' -e "keycloak_image_ref=${KC_DECLARED%@*}"
write_image_env "quay.io/keycloak/keycloak@${FAKE_DIGEST}"
ident_expect ident-image-env 'no arrancaría exactamente'
write_image_env "${KC_REF}"
ansible_run ident-running-other verify.yml -e "keycloak_image_ref=quay.io/keycloak/keycloak@${FAKE_DIGEST}"; RC=$?
check "IDENT: verificación en ejecución contra otra identidad: falla (rc=${RC})" \
  bash -c "test ${RC} -ne 0 && log_has ident-running-other 'IDENTIDAD EN EJECUCIÓN: keycloak no corre'"
ansible_run ident-running-tag verify.yml -e "keycloak_image_ref=${KC_DECLARED%@*}"; RC=$?
check "IDENT: verificación en ejecución con una identidad esperada por tag: falla (rc=${RC})" \
  bash -c "test ${RC} -ne 0 && log_has ident-running-tag 'no es exacta'"
ansible_run ident-running-ok verify.yml; RC=$?
check "IDENT: verificación en ejecución con la identidad exacta: pasa (rc=${RC})" test "${RC}" -eq 0

# ══ MV: versión registrada de la base desconocida ════════════════════════════
if [ "${TARGET}" = migration-version ]; then
section "MV — base inicializada sin versión registrada conocida: falla antes de tocar Keycloak"
sql "drop table if exists public.harness_mv_backup; create table public.harness_mv_backup as table keycloak.migration_model" >/dev/null
check "MV: preparación: la base registra $(kc_db_version) (copia guardada)" \
  test "$(sql "select count(*) from public.harness_mv_backup")" -ge 1
mv_restore() {
  sql "alter table if exists keycloak.harness_mv_renamed rename to migration_model" >/dev/null 2>&1
  sql "delete from keycloak.migration_model; insert into keycloak.migration_model select * from public.harness_mv_backup" >/dev/null
}
mv_expect() { # <etiqueta> <valor informado> <preparación SQL>
  local label="$1" shown="$2" before rc mode
  mv_restore
  sql "$3" >/dev/null
  before="$(kc_container)"
  for mode in false true; do
    converge "${label}-rec-${mode}" "${mode}"; rc=$?
    check "${label} (recuperación=${mode}): falla (rc=${rc})" test "${rc}" -ne 0
    check "${label} (recuperación=${mode}): motivo visible: versión desconocida '${shown}'" \
      bash -c "log_has '${label}-rec-${mode}' 'su versión registrada (migration_model) es desconocida' && log_has '${label}-rec-${mode}' '${shown}'"
    check "${label} (recuperación=${mode}): Keycloak intacto (mismo contenedor, sin reinicio)" test "$(kc_container)" = "${before}"
    check "${label} (recuperación=${mode}): sin clasificar, evento, autoridad temporal ni reconciliación" \
      bash -c "! log_has '${label}-rec-${mode}' 'KEYCLOAK: estado' && ! log_has '${label}-rec-${mode}' 'Crear la autoridad temporal' && ! log_has '${label}-rec-${mode}' 'Reconciliar el secreto' && test \$(temp_count) = 0"
  done
}
mv_expect mv-absent '?absent' "alter table keycloak.migration_model rename to harness_mv_renamed"
mv_expect mv-empty '?empty' "delete from keycloak.migration_model"
# Ambigua (dos filas con el update_time más reciente) no se puede construir: Keycloak la
# impide con UK_MIGRATION_UPDATE_TIME (changelog 26.2.6)
check "MV: Keycloak rechaza dos registros con el mismo update_time (contrato de la tabla)" \
  bash -c "! sql \"insert into keycloak.migration_model(id, version, update_time) select 'harness-mv', '26.7.0', max(update_time) from keycloak.migration_model\""
mv_expect mv-null '?null' "update keycloak.migration_model set version=null where update_time=(select max(update_time) from keycloak.migration_model)"
mv_expect mv-malformed 'harness-not-a-version' "update keycloak.migration_model set version='harness-not-a-version' where update_time=(select max(update_time) from keycloak.migration_model)"
mv_restore
sql "drop table public.harness_mv_backup" >/dev/null
converge mv-restored false; RC=$?
check "MV: restaurada la versión registrada, la convergencia vuelve a P1 sin evento (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has mv-restored 'misma versión que la imagen: sin migración' && log_has mv-restored 'KEYCLOAK: estado P1' && ! log_has mv-restored 'Crear la autoridad temporal'"
fi

if [ -z "${TARGET}" ] || [ "${TARGET}" = upgrade ]; then
# ══ P1: convergencias repetidas ══════════════════════════════════════════════
section "P1 — conocido/reconciliado, repetible"
for n in 1 2; do
  converge "p1-${n}" false; RC=$?
  check "P1#${n}: termina bien (rc=${RC})" test "${RC}" -eq 0
  check "P1#${n}: clasificado como P1" log_has "p1-${n}" 'KEYCLOAK: estado P1'
  check "P1#${n}: no crea autoridad temporal" bash -c "! log_has p1-${n} 'Crear la autoridad temporal'"
  check "P1#${n}: secreto del backend sin cambios" log_has "p1-${n}" 'secreto del backend sin cambios'
  check "P1#${n}: mismo realm (no recreado)" test "$(realm_id)" = "${REALM_ID}"
  check "P1#${n}: sentinela de negocio intacto" bash -c "users_in_realm | grep -q harness-sentinel"
  check "P1#${n}: sin autoridad temporal en la base" test "$(temp_count)" = 0
  check "P1#${n}: alcance de roles intacto y sin escribirlo (no corre establish)" \
    bash -c "test \"\$(scope_row)\" = \"\${SCOPE_TARGET}\" && ! task_ran p1-${n} 'Establecer la automatización'"
  check "P1#${n}: no exige KC_PLATFORM_ADMIN_INITIAL_PASSWORD (ausente) y no toca al administrador humano" \
    bash -c "test '$(platform_row)' = '${PLATFORM_SNAP}' && ! task_ran p1-${n} 'Crear el administrador humano'"
  check "P1#${n}: informa la preparación humana aparte: ${PLATFORM_STATUS_P1}" platform_status "p1-${n}" "${PLATFORM_STATUS_P1}"
  check "P1#${n}: la política de master sigue length(16) y no se reaplica (sin evento)" \
    bash -c "test \"\$(sql \"select password_policy from keycloak.realm where name='master'\")\" = 'length(16)' && ! task_ran p1-${n} 'Crear el administrador humano'"
done
check "P1: el conteo de admins humanos de master incluye al administrador permanente (1)" \
  log_has p1-1 'Usuarios humanos con rol admin en master: 1'
check "P1: el marker histórico del host no existe (se retira si estuviera)" test ! -e "${W}/.keycloak_initialized"

# ══ ROT-B: rotación del secreto del backend ══════════════════════════════════
# Valor con caracteres que .env/Compose/JSON podrían alterar: no hay restricción de formato
section "ROT-B — rotación de KC_BACKEND_CLIENT_SECRET (con ' \" \$ \\ # & espacio)"
OLD_BACKEND="${BACKEND_SECRET}"
BACKEND_SECRET="$(synthetic backend2)${TRICKY}"; SECRETS_SEEN+=("${BACKEND_SECRET}")
render_env rot-b
converge rot-b false; RC=$?
check "ROT-B: termina bien (rc=${RC})" test "${RC}" -eq 0
check "ROT-B: P1 sin evento" bash -c "log_has rot-b 'KEYCLOAK: estado P1' && ! log_has rot-b 'Crear la autoridad temporal'"
check "ROT-B: Compose entrega el secreto intacto al backend (verificado por Ansible)" \
  bash -c "log_has rot-b 'Verificar que el backend recibe KC_BACKEND_CLIENT_SECRET intacto' && ! log_has rot-b 'no llega intacto'"
check "ROT-B: secreto actualizado" log_has rot-b 'secreto del backend actualizado'
check "ROT-B: el secreto anterior es rechazado" test "$(kc_client_auth piedrazul piedrazul-backend "${OLD_BACKEND}")" = rejected
check "ROT-B: el secreto nuevo (literal) autentica" test "$(kc_client_auth piedrazul piedrazul-backend "${BACKEND_SECRET}")" = ok
check "ROT-B: el backend recibiría exactamente el secreto nuevo" \
  test "$(compose config --format json 2>/dev/null | jq -r '.services.backend.environment.KC_BACKEND_CLIENT_SECRET' | sed 's/\$\$/$/g')" = "${BACKEND_SECRET}"
converge rot-b-again false; RC=$?
check "ROT-B: convergencia siguiente idempotente (sin cambios) (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has rot-b-again 'secreto del backend sin cambios'"
check "ROT-B: mismo realm y sentinela intacto" bash -c "test '$(realm_id)' = '${REALM_ID}' && users_in_realm | grep -q harness-sentinel"
check "ROT-B: administrador humano intacto" test "$(platform_row)" = "${PLATFORM_SNAP}"

# ══ ADMIN-H: primer login y OTP ══════════════════════════════════════════════
# En upgrade el primer login ya ocurrió en la versión anterior (UPG)
if [ "${TARGET}" != upgrade ]; then
section "ADMIN-H — primer login de navegador: cambio de contraseña y enrolamiento OTP"
KBL_PASSWORD="${PLATFORM_PW}" KBL_NEW_PASSWORD="${PLATFORM_NEW_PW}" KBL_SHORT_PASSWORD="${PLATFORM_SHORT_PW}" \
  browser first-login first-login KBL_PASSWORD KBL_NEW_PASSWORD KBL_SHORT_PASSWORD
check "primer login: Keycloak exige CONFIGURE_TOTP y UPDATE_PASSWORD antes de entregar el código" \
  browser_has first-login 'first_login.actions=CONFIGURE_TOTP,UPDATE_PASSWORD'
check "primer login: una contraseña nueva de 14 caracteres se rechaza (mínimo 16)" \
  bash -c "browser_has first-login 'first_login.short_password=rejected' && browser_has first-login 'first_login.short_password_error=min_length_16'"
check "primer login: una de ${#PLATFORM_NEW_PW} caracteres solo minúsculas se acepta (sin categorías obligatorias)" \
  bash -c "[[ '${PLATFORM_NEW_PW}' =~ ^[a-z]{16,}$ ]] && browser_has first-login 'browser.token=ok'"
check "primer login: termina con código y token de la cuenta (consola de administración)" browser_has first-login 'browser.token=ok'
check "primer login: queda OTP enrolado y sin acciones pendientes" \
  test "$(platform_facts)" = "true||admin,default-roles-master|password:1,otp:1|false"
KBL_PASSWORD="${PLATFORM_PW}" browser password old-pw KBL_PASSWORD
check "primer login: la contraseña temporal deja de valer" browser_has old-pw 'browser.code=none'
fi
converge admin-configured false; RC=$?
check "ADMIN-H: la convergencia sigue P1 (rc=${RC})" bash -c "test ${RC} -eq 0 && log_has admin-configured 'KEYCLOAK: estado P1'"
check "ADMIN-H: informa CONFIGURADA y que el login con OTP se verifica a mano" \
  bash -c "platform_status admin-configured 'CONFIGURADA' && log_has admin-configured 'el login con OTP se verifica a mano'"

section "ADMIN-H — logins siguientes: segundo factor y rutas alternativas"
KBL_PASSWORD="${PLATFORM_NEW_PW}" browser login login KBL_PASSWORD
check "login: tras la contraseña, el flujo browser de master pide OTP" browser_has login 'browser.otp_challenge=yes'
check "login: un OTP incorrecto es rechazado" browser_has login 'browser.wrong_otp=rejected'
check "login: con el OTP correcto entra (token de la cuenta)" browser_has login 'browser.token=ok'
KBL_PASSWORD="${PLATFORM_NEW_PW}" browser direct-grant direct-grant KBL_PASSWORD
check "ruta alternativa admin-cli (password grant): sin OTP no entrega token" browser_has direct-grant 'direct_grant.without_otp=invalid_user_credentials'
check "ruta alternativa admin-cli (password grant): OTP incorrecto rechazado" browser_has direct-grant 'direct_grant.wrong_otp=invalid_user_credentials'
check "ruta alternativa admin-cli (password grant): con OTP correcto entrega token" browser_has direct-grant 'direct_grant.with_otp=token'
check "rutas: master sin IdP, sin user storage, sin olvido de contraseña ni registro (lo que se evaluó)" \
  test "$(sql "select (select count(*) from keycloak.identity_provider i join keycloak.realm r on r.id=i.realm_id and r.name='master')||'|'||(select count(*) from keycloak.component c join keycloak.realm r on r.id=c.realm_id and r.name='master' where c.provider_type='org.keycloak.storage.UserStorageProvider')||'|'||reset_password_allowed||'|'||registration_allowed from keycloak.realm where name='master'")" = "0|0|false|false"
check "rutas: el flujo browser y direct grant de master son los de Keycloak (sin cambios del ciclo)" \
  test "$(sql "select browser_flow = (select id from keycloak.authentication_flow f where f.realm_id=r.id and f.alias='browser') and direct_grant_flow = (select id from keycloak.authentication_flow f where f.realm_id=r.id and f.alias='direct grant') from keycloak.realm r where name='master'")" = t
PLATFORM_SNAP="$(platform_row)"

section "ADMIN-H — P1 con la contraseña inicial todavía en el entorno: aviso, sin cambios"
PLATFORM_ENV_PW="${PLATFORM_PW}"
converge admin-pw-left false; RC=$?
PLATFORM_ENV_PW=""
check "ADMIN-H: P1 termina bien (rc=${RC})" bash -c "test ${RC} -eq 0 && log_has admin-pw-left 'KEYCLOAK: estado P1'"
check "ADMIN-H: avisa que el secreto de un solo uso ya no se usa y debe eliminarse" \
  log_has admin-pw-left 'KC_PLATFORM_ADMIN_INITIAL_PASSWORD está presente pero solo la usa una instalación nueva'
check "ADMIN-H: contraseña, OTP, roles, atributos e identidad sin cambios" test "$(platform_row)" = "${PLATFORM_SNAP}"
KBL_PASSWORD="${PLATFORM_PW}" browser direct-grant pw-left KBL_PASSWORD
check "ADMIN-H: la contraseña inicial sigue sin valer (no se restableció)" browser_has pw-left 'direct_grant.without_otp=invalid_user_credentials'

fi

fi

# ══ P3: divergencias → falla cerrada, sin mutar ══════════════════════════════
p3_expect() { # <etiqueta> <motivo esperado (texto)> [extra args]: P3 recuperable
  local label="$1" reason="$2" before_temp before_users rc; shift 2
  before_temp="$(temp_count)"; before_users="$(users_in_realm)"
  converge "${label}" false "$@"; rc=$?
  check "${label}: la convergencia normal falla (rc=${rc})" test "${rc}" -ne 0
  check "${label}: falla cerrada como P3 recuperable con explicación accionable" \
    bash -c "log_has '${label}' 'KEYCLOAK P3 (desconocido/divergente)' && log_has '${label}' 'keycloak_recovery=true'"
  check "${label}: motivo visible: ${reason}" log_has "${label}" "${reason}"
  check "${label}: no corre ningún evento ni crea autoridad temporal" \
    bash -c "! log_has '${label}' 'Crear la autoridad temporal' && test '$(temp_count)' = '${before_temp}'"
  check "${label}: no reconcilia credenciales" bash -c "! log_has '${label}' 'Reconciliar el secreto del backend'"
  check "${label}: administrador humano intacto" test "$(platform_row)" = "${PLATFORM_SNAP}"
  check "${label}: realm y usuarios intactos" bash -c "test '$(realm_id)' = '${REALM_ID}' && test '$(users_in_realm)' = '${before_users}'"
}
blocking_expect() { # <etiqueta> <motivo esperado>: P3 bloqueante, también con recuperación
  local label="$1" reason="$2" before_temp before_realms rc mode
  before_temp="$(temp_count)"; before_realms="$(realms)"
  for mode in false true; do
    converge "${label}-rec-${mode}" "${mode}"; rc=$?
    check "${label} (recuperación=${mode}): falla (rc=${rc})" test "${rc}" -ne 0
    check "${label} (recuperación=${mode}): P3 BLOQUEANTE con escalada, sin ofrecer recuperación" \
      bash -c "log_has '${label}-rec-${mode}' 'KEYCLOAK P3 BLOQUEANTE' && log_has '${label}-rec-${mode}' 'Escalar' && ! log_has '${label}-rec-${mode}' 'correr la recuperación autorizada'"
    check "${label} (recuperación=${mode}): motivo visible: ${reason}" log_has "${label}-rec-${mode}" "${reason}"
    check "${label} (recuperación=${mode}): sin evento, sin importación, sin autoridad temporal nueva" \
      bash -c "! log_has '${label}-rec-${mode}' 'Crear la autoridad temporal' && ! log_has '${label}-rec-${mode}' 'Importar el realm' && test '$(temp_count)' = '${before_temp}'"
    check "${label} (recuperación=${mode}): no informa P1 ni reconcilia" \
      bash -c "! log_has '${label}-rec-${mode}' 'KEYCLOAK: estado P1' && ! log_has '${label}-rec-${mode}' 'Reconciliar el secreto del backend'"
    check "${label} (recuperación=${mode}): realms sin cambios (no se recrea ni adopta nada)" test "$(realms)" = "${before_realms}"
  done
}
recover_expect() { # <etiqueta> [extra args]
  local label="$1"; shift
  converge "${label}" true "$@"; local rc=$?
  check "${label}: la recuperación autorizada termina bien (rc=${rc})" test "${rc}" -eq 0
  check "${label}: evento de recuperación con autoridad temporal" log_has "${label}" 'de recuperación P3 autorizada'
  check "${label}: la recuperación no importa realms" bash -c "! task_ran '${label}' 'Importar el realm de la aplicación'"
  check "${label}: autoridad temporal eliminada y rechazada" log_has "${label}" 'eliminada y rechazada por Keycloak'
  check "${label}: P1 después del evento" log_has "${label}" 'KEYCLOAK: estado P1 después del evento'
  check "${label}: sin autoridad temporal en la base" test "$(temp_count)" = 0
  check "${label}: mismo realm y sentinela intacto (no se recrea ni borra)" \
    bash -c "test '$(realm_id)' = '${REALM_ID}' && users_in_realm | grep -q harness-sentinel"
  check "${label}: backend y automatización autentican con los deseados" \
    bash -c "test '$(kc_client_auth piedrazul piedrazul-backend "${BACKEND_SECRET}")' = ok && test '$(kc_client_auth master piedrazul-keycloak-automation "${AUTOMATION_SECRET}")' = ok"
  check "${label}: la recuperación no crea ni toca al administrador humano" \
    bash -c "test '$(platform_row)' = '${PLATFORM_SNAP}' && ! task_ran '${label}' 'Crear el administrador humano'"
}
# Autoridad ajena creada por "un operador" (el harness), con Keycloak detenido como exige
harness_admin() { # <clientId> <secreto>
  compose stop keycloak >/dev/null 2>&1
  ( cd "${W}/compose" && HA="$2" docker compose --project-name "${PROJECT}" --file prod.yml --env-file ../.env --env-file ../image.env \
      run --rm --no-deps --env HA keycloak bootstrap-admin service --client-id "$1" --client-secret:env HA --no-prompt ) \
    >> "${LOGS}/harness-admin.log" 2>&1
  compose up -d --wait keycloak >/dev/null 2>&1
}

harness_admin_user() { # <username> <contraseña>: usuario temporal (no cliente), ajeno por definición
  compose stop keycloak >/dev/null 2>&1
  ( cd "${W}/compose" && HA="$2" docker compose --project-name "${PROJECT}" --file prod.yml --env-file ../.env --env-file ../image.env \
      run --rm --no-deps --env HA keycloak bootstrap-admin user --username "$1" --password:env HA --no-prompt ) \
    >> "${LOGS}/harness-admin.log" 2>&1
  compose up -d --wait keycloak >/dev/null 2>&1
}

if [ "${TARGET}" != smtp ]; then
# ══ P3 BLOQUEANTE: usuario temporal ajeno con nombre reconocible ═════════════
section "P3 BLOQUEANTE — usuario temporal ajeno: intacto y sin exponer su nombre"
PERSON="maria.fernanda.perez.$(head -c 3 /dev/urandom | od -An -tx1 | tr -d ' \n')"
PERSON_PW="$(synthetic person)"; SECRETS_SEEN+=("${PERSON_PW}")
harness_admin_user "${PERSON}" "${PERSON_PW}"
PERSON_ID="$(sql "select u.id from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='master' where u.username='${PERSON}'")"
check "usuario-ajeno: preparación: el usuario existe y tiene id interno" bash -c "[[ '${PERSON_ID}' =~ ^[0-9a-f-]{36}$ ]]"
check "usuario-ajeno: preparación: usuario temporal (is_temporary_admin) con rol admin en master" \
  test "$(sql "select count(*) from keycloak.user_attribute a join keycloak.user_role_mapping m on m.user_id=a.user_id join keycloak.keycloak_role k on k.id=m.role_id and k.name='admin' where a.user_id='${PERSON_ID}' and a.name='is_temporary_admin' and a.value='true'")" = 1
check "usuario-ajeno: preparación: el usuario autentica" test "$(kc_user_auth master "${PERSON}" "${PERSON_PW}")" = ok
PERSON_BEFORE="$(sql "select u.username||'|'||u.enabled||'|'||coalesce(u.email,'')||'|'||(select string_agg(k.name, ',' order by k.name) from keycloak.user_role_mapping m join keycloak.keycloak_role k on k.id=m.role_id where m.user_id=u.id)||'|'||(select count(*) from keycloak.credential c where c.user_id=u.id) from keycloak.user_entity u where u.id='${PERSON_ID}'")"
blocking_expect p3-foreign-user "autoridad temporal ajena o ambigua presente (1): user:id-${PERSON_ID}"
check "usuario-ajeno: intacto tras ambos intentos (nombre, estado, roles, credenciales)" \
  test "$(sql "select u.username||'|'||u.enabled||'|'||coalesce(u.email,'')||'|'||(select string_agg(k.name, ',' order by k.name) from keycloak.user_role_mapping m join keycloak.keycloak_role k on k.id=m.role_id where m.user_id=u.id)||'|'||(select count(*) from keycloak.credential c where c.user_id=u.id) from keycloak.user_entity u where u.id='${PERSON_ID}'")" = "${PERSON_BEFORE}"
check "usuario-ajeno: sigue autenticando" test "$(kc_user_auth master "${PERSON}" "${PERSON_PW}")" = ok
check "usuario-ajeno: el escalamiento lo identifica solo por id interno" \
  log_has p3-foreign-user-rec-false "user:id-${PERSON_ID}"
check "usuario-ajeno: su username nunca aparece en logs de Ansible, muestras ni resultados" \
  bash -c "! grep -rqF --exclude=harness-admin.log -e '${PERSON}' '${LOGS}' && ! grep -qF -e '${PERSON}' '${RESULTS}'"
# Escalada resuelta por el operador: el usuario ajeno se retira a sí mismo
docker exec -i -e "S=${PERSON_PW}" keycloak bash -c '
  c="$(mktemp -u /dev/shm/h.XXXXXX)"; trap "rm -f $c" EXIT
  KC_CLI_PASSWORD="$S" /opt/keycloak/bin/kcadm.sh config credentials --config "$c" --server http://localhost:8180 --realm master --user "$0" >/dev/null 2>&1 &&
  /opt/keycloak/bin/kcadm.sh delete "users/$1" -r master --config "$c"' "${PERSON}" "${PERSON_ID}" >/dev/null 2>&1
converge after-foreign-user false; RC=$?
check "usuario-ajeno: retirado por el operador, la convergencia vuelve a P1 (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has after-foreign-user 'KEYCLOAK: estado P1'"

if [ -z "${TARGET}" ] || [ "${TARGET}" = upgrade ]; then
section "P3/REC — rotación de KC_AUTOMATION_CLIENT_SECRET (credencial rechazada)"
OLD_AUTOMATION="${AUTOMATION_SECRET}"
AUTOMATION_SECRET="$(synthetic automation2)${TRICKY}"; SECRETS_SEEN+=("${AUTOMATION_SECRET}")
p3_expect p3-automation-rotated 'la credencial de automatización fue rechazada'
recover_expect rec-automation-rotated
check "REC: el secreto anterior de automatización es rechazado" \
  test "$(kc_client_auth master piedrazul-keycloak-automation "${OLD_AUTOMATION}")" = rejected
converge rec-then-p1 false; RC=$?
check "REC: la convergencia siguiente es P1 sin evento (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has rec-then-p1 'KEYCLOAK: estado P1' && ! log_has rec-then-p1 'Crear la autoridad temporal'"

section "P3 BLOQUEANTE — autoridad temporal ajena: nunca se toca"
STRAY_SECRET="$(synthetic stray)"; SECRETS_SEEN+=("${STRAY_SECRET}")
harness_admin harness-stray-admin "${STRAY_SECRET}"
check "P3-ajena: preparación: una autoridad temporal ajena en la base" test "$(temp_count)" = 1
# Un admin humano en master (creado por la autoridad ajena) para comprobar que la salida solo cuenta
HUMAN_ADMIN="harness-human-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
kc_as harness-stray-admin "${STRAY_SECRET}" master create users -r master -s "username=${HUMAN_ADMIN}" -s enabled=true >/dev/null 2>&1
kc_as harness-stray-admin "${STRAY_SECRET}" master add-roles -r master --uusername "${HUMAN_ADMIN}" --rolename admin >/dev/null 2>&1
check "P3-ajena: preparación: un usuario humano con rol admin en master" \
  test "$(sql "select count(*) from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='master' where u.username='${HUMAN_ADMIN}'")" = 1
blocking_expect p3-foreign-temporary 'autoridad temporal ajena o ambigua presente (1): client:harness-stray-admin'
check "P3-ajena: la autoridad ajena sigue existiendo y válida tras ambos intentos" \
  test "$(kc_client_auth master harness-stray-admin "${STRAY_SECRET}")" = ok
# Escalada resuelta por el operador: la autoridad ajena se retira a sí misma
SID="$(kc_as harness-stray-admin "${STRAY_SECRET}" master get clients -r master -q clientId=harness-stray-admin --fields id --format csv --noquotes 2>/dev/null)"
kc_as harness-stray-admin "${STRAY_SECRET}" master delete "clients/${SID}" -r master >/dev/null 2>&1
converge after-foreign false; RC=$?
check "P3-ajena: retirada por el operador, la convergencia vuelve a P1 (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has after-foreign 'KEYCLOAK: estado P1'"
# 2 = el administrador humano permanente + el creado por la autoridad ajena (antes: 1)
check "salida: informa el conteo de admins humanos de master" log_has after-foreign 'Usuarios humanos con rol admin en master: 2'
check "salida: nunca el nombre de un admin humano de master" bash -c "! grep -rqF '${HUMAN_ADMIN}' '${LOGS}' --include='*.log' --exclude=harness-admin.log"

section "P3/REC — escalada de la automatización, forma y roles del backend"
sql "insert into keycloak.user_role_mapping(role_id, user_id) select k.id, u.id from keycloak.keycloak_role k join keycloak.realm r on r.id=k.realm_id and r.name='master', keycloak.user_entity u join keycloak.client c on c.id=u.service_account_client_link where k.name='admin' and not k.client_role and c.client_id='piedrazul-keycloak-automation'" >/dev/null
BID="$(kc_as piedrazul-keycloak-automation "${AUTOMATION_SECRET}" master get clients -r piedrazul -q clientId=piedrazul-backend --fields id --format csv --noquotes 2>/dev/null)"
kc_as piedrazul-keycloak-automation "${AUTOMATION_SECRET}" master update "clients/${BID}" -r piedrazul -s directAccessGrantsEnabled=true >/dev/null 2>&1
sql "delete from keycloak.user_role_mapping m using keycloak.user_entity u, keycloak.client c, keycloak.keycloak_role k, keycloak.client rc where m.user_id=u.id and u.service_account_client_link=c.id and c.client_id='piedrazul-backend' and m.role_id=k.id and k.client=rc.id and rc.client_id='realm-management' and k.name='query-users'" >/dev/null
check "P3-escalada: preparación: la automatización tiene realm:admin" bash -c "[[ '$(sa_roles piedrazul-keycloak-automation)' == *realm:admin* ]]"
check "P3-escalada: preparación: el backend permite direct grants" \
  test "$(sql "select direct_access_grants_enabled from keycloak.client where client_id='piedrazul-backend'")" = t
check "P3-escalada: preparación: el backend perdió query-users" bash -c "[[ '$(sa_roles piedrazul-backend)' != *query-users* ]]"
p3_expect p3-escalation-shape-roles 'roles del service account de automatización divergentes'
check "P3-escalada: también informa la forma del backend" log_has p3-escalation-shape-roles 'forma del cliente piedrazul-backend divergente'
check "P3-escalada: también informa los roles del backend" log_has p3-escalation-shape-roles 'roles del service account de piedrazul-backend divergentes'
recover_expect rec-escalation-shape-roles
converge rec-escalation-then-p1 false; RC=$?
check "REC-escalada: siguiente convergencia P1 (roles y forma exactos) (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has rec-escalation-then-p1 'KEYCLOAK: estado P1'"
check "REC-escalada: el usuario humano admin de master no se tocó" \
  test "$(sql "select count(*) from keycloak.user_entity where username='${HUMAN_ADMIN}'")" = 1

section "P3/REC — alcance de roles divergente en los tres clientes"
SCOPE_T1="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Estado inconsistente: automatización con Full Scope y sin manage-realm; backend sin ninguna
# asignación (Full Scope deshabilitado: el orden equivocado); frontend con Full Scope y un rol de
# más. En la base y con reinicio (la caché de Keycloak no ve cambios de SQL).
sql "update keycloak.client set full_scope_allowed=true where client_id in ('piedrazul-keycloak-automation','piedrazul-frontend');
     delete from keycloak.scope_mapping s using keycloak.client c, keycloak.keycloak_role k where s.client_id=c.id and s.role_id=k.id and c.client_id='piedrazul-keycloak-automation' and k.name='manage-realm';
     delete from keycloak.scope_mapping s using keycloak.client c where s.client_id=c.id and c.client_id='piedrazul-backend';
     insert into keycloak.scope_mapping(client_id, role_id) select c.id, k.id from keycloak.client c join keycloak.realm r on r.id=c.realm_id and r.name='piedrazul' join keycloak.keycloak_role k on k.realm_id=r.id and not k.client_role and k.name='offline_access' where c.client_id='piedrazul-frontend'" >/dev/null
compose restart keycloak >/dev/null 2>&1; compose up -d --wait keycloak >/dev/null 2>&1
SCOPE_DRIFT="$(scope_row)"
check "P3-alcance: preparación: los tres clientes divergen" bash -c "test '${SCOPE_DRIFT}' != \"\${SCOPE_TARGET}\""
check "P3-alcance: un backend sin asignaciones queda sin autoridad en la API (403): el orden importa" \
  test "$(kc_try piedrazul-backend "${BACKEND_SECRET}" piedrazul get users -r piedrazul -q max=1)" = forbidden
kc_client_auth master piedrazul-keycloak-automation "${AUTOMATION_SECRET}" >/dev/null; sleep 2
check "P3-alcance: control: con Full Scope habilitado Keycloak sí avisa al emitir el token" \
  test "$(fullscope_warnings "${SCOPE_T1}")" -ge 1
p3_expect p3-scope 'alcance de roles de piedrazul-keycloak-automation divergente: Full Scope Allowed habilitado; faltan piedrazul-realm:manage-realm'
check "P3-alcance: también informa el backend sin sus roles" \
  log_has p3-scope "alcance de roles de piedrazul-backend divergente: faltan ${SCOPE_BACK}"
check "P3-alcance: también informa el frontend con Full Scope y un rol de más" \
  log_has p3-scope 'alcance de roles de piedrazul-frontend divergente: Full Scope Allowed habilitado; sobran realm:offline_access'
check "P3-alcance: la convergencia normal no tocó el alcance" test "$(scope_row)" = "${SCOPE_DRIFT}"
recover_expect rec-scope
check "REC-alcance: la recuperación existente (establish) restablece los tres" \
  bash -c "test \"\$(scope_row)\" = \"\${SCOPE_TARGET}\" && log_has rec-scope 'scope.piedrazul-keycloak-automation=updated' && log_has rec-scope 'scope.piedrazul-backend=updated' && log_has rec-scope 'scope.piedrazul-frontend=updated'"
check "REC-alcance: el backend recupera su autoridad en la API" \
  test "$(kc_try piedrazul-backend "${BACKEND_SECRET}" piedrazul get users -r piedrazul -q max=1)" = ok
converge rec-scope-again true; RC=$?
check "REC-alcance: pedir otra recuperación ya en P1 no crea autoridad temporal ni toca el alcance (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has rec-scope-again 'se pidió recuperación pero el estado es P1' && ! log_has rec-scope-again 'Crear la autoridad temporal' && test \"\$(scope_row)\" = \"\${SCOPE_TARGET}\""
compose restart keycloak >/dev/null 2>&1; compose up -d --wait keycloak >/dev/null 2>&1
converge rec-scope-then-p1 false; RC=$?
check "REC-alcance: tras reiniciar Keycloak, la convergencia normal es P1 sin evento y la automatización reconcilia (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has rec-scope-then-p1 'KEYCLOAK: estado P1' && ! log_has rec-scope-then-p1 'Crear la autoridad temporal' && log_has rec-scope-then-p1 'el backend autentica con él' && test \"\$(scope_row)\" = \"\${SCOPE_TARGET}\""

# ══ INT: recuperación interrumpida ═══════════════════════════════════════════
section "INT — recuperación interrumpida antes de limpiar (huérfana propia)"
AUTOMATION_SECRET="$(synthetic automation3)"; SECRETS_SEEN+=("${AUTOMATION_SECRET}")
p3_expect p3-before-interrupted 'la credencial de automatización fue rechazada'
# Keycloak no llega a healthy dentro del plazo: el evento falla con la autoridad temporal
# creada y la limpieza no puede autenticarse
converge int-recovery true -e kc_start_timeout=1; RC=$?
check "INT: la recuperación interrumpida falla (rc=${RC}), no informa éxito" \
  bash -c "test ${RC} -ne 0 && ! log_has int-recovery 'KEYCLOAK: estado P1 después del evento'"
check "INT: el fallo nombra la autoridad temporal que quedó" log_has int-recovery 'NO se pudo eliminar la autoridad temporal'
compose up -d --wait keycloak >/dev/null 2>&1
check "INT: la autoridad temporal quedó en la base (inutilizable: secreto descartado)" test "$(temp_count)" -ge 1
p3_expect p3-after-interrupted 'autoridad temporal propia de un evento interrumpido'
recover_expect rec-after-interrupted
check "INT: la recuperación eliminó también la huérfana propia del intento interrumpido" test "$(temp_count)" = 0

section "INT — recuperación que falla después de reconciliar parcialmente"
BAD_ROLES='{"kc_automation_roles": ["piedrazul-realm:manage-clients", "piedrazul-realm:manage-realm", "piedrazul-realm:view-clients", "piedrazul-realm:no-such-role"]}'
p3_expect p3-before-partial 'roles del service account de automatización divergentes' -e "${BAD_ROLES}"
converge int-partial true -e "${BAD_ROLES}"; RC=$?
check "INT-parcial: la recuperación falla (rc=${RC}) sin informar éxito" \
  bash -c "test ${RC} -ne 0 && ! log_has int-partial 'KEYCLOAK: estado P1 después del evento'"
check "INT-parcial: la automatización se reconcilió antes del fallo (visible en el error)" log_has int-partial 'automation.client=reconciled'
check "INT-parcial: falla visible en la asignación de roles" log_has int-partial 'add-roles:piedrazul-realm:no-such-role'
check "INT-parcial: la autoridad temporal igual se eliminó" bash -c "log_has int-partial 'el evento recovery falló' && test \$(temp_count) = 0"
converge int-partial-next false; RC=$?
check "INT-parcial: sin dependencia nueva: la convergencia normal siguiente es P1 (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has int-partial-next 'KEYCLOAK: estado P1' && ! log_has int-partial-next 'Crear la autoridad temporal'"

# ══ ADMIN-H: colisión, OTP eliminado, borrado intencional ═════════════════════
section "ADMIN-H — colisión de nombre: platform-admin no adopta ni modifica"
HL_SECRET="$(synthetic humanlab)"; SECRETS_SEEN+=("${HL_SECRET}")
harness_admin harness-human-lab "${HL_SECRET}"
# La acción real, con una autoridad de laboratorio, sobre un master donde el nombre ya existe
KCL_TEMP_SECRET="${HL_SECRET}" KCL_PLATFORM_ADMIN_PASSWORD="${PLATFORM_PW}" \
KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64="$(jq -cn --arg p "${PLATFORM_PW}" '{type: "password", value: $p, temporary: true}' | base64 -w0)" \
  docker exec -e KCL_SERVER=http://localhost:8180 -e KCL_REALM=piedrazul -e KCL_AUTOMATION_CLIENT_ID=piedrazul-keycloak-automation \
    -e KCL_BACKEND_CLIENT_ID=piedrazul-backend -e KCL_TEMP_CLIENT_ID=harness-human-lab -e KCL_TEMP_SECRET \
    -e "KCL_PLATFORM_ADMIN_USERNAME=${PLATFORM_USER}" -e KCL_PLATFORM_ADMIN_ROLE=admin -e 'KCL_MASTER_PASSWORD_POLICY=length(16)' \
    -e KCL_PLATFORM_ADMIN_PASSWORD -e KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64 keycloak \
    bash -c "$(cat "${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh")" keycloak-admin platform-admin > "${LOGS}/collision.txt" 2>&1
check "colisión: falla con platform_admin_collision" grep -qx 'error=platform_admin_collision' "${LOGS}/collision.txt"
check "colisión: nada creado ni modificado (una sola cuenta, misma huella)" \
  bash -c "test '$(platform_count)' = 1 && test '$(platform_row)' = '${PLATFORM_SNAP}'"

section "ADMIN-H — política de contraseñas de master distinta: no se pisa ni se reaplica"
# Un operador endurece la política (cambio legítimo, realm completo)
kc_as harness-human-lab "${HL_SECRET}" master update realms/master -s 'passwordPolicy=length(20)' >/dev/null 2>&1
check "política: preparación: master con una política distinta" \
  test "$(sql "select password_policy from keycloak.realm where name='master'")" = 'length(20)'
KCL_TEMP_SECRET="${HL_SECRET}" KCL_PLATFORM_ADMIN_PASSWORD="${PLATFORM_PW}" \
KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64="$(jq -cn --arg p "${PLATFORM_PW}" '{type: "password", value: $p, temporary: true}' | base64 -w0)" \
  docker exec -e KCL_SERVER=http://localhost:8180 -e KCL_REALM=piedrazul -e KCL_AUTOMATION_CLIENT_ID=piedrazul-keycloak-automation \
    -e KCL_BACKEND_CLIENT_ID=piedrazul-backend -e KCL_TEMP_CLIENT_ID=harness-human-lab -e KCL_TEMP_SECRET \
    -e KCL_PLATFORM_ADMIN_USERNAME=harness-policy-probe -e KCL_PLATFORM_ADMIN_ROLE=admin -e 'KCL_MASTER_PASSWORD_POLICY=length(16)' \
    -e KCL_PLATFORM_ADMIN_PASSWORD -e KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64 keycloak \
    bash -c "$(cat "${ANSIBLE_DIR}/roles/app/files/keycloak-admin.sh")" keycloak-admin platform-admin > "${LOGS}/policy-unexpected.txt" 2>&1
check "política: platform-admin falla con master_password_policy_unexpected" grep -qx 'error=master_password_policy_unexpected' "${LOGS}/policy-unexpected.txt"
check "política: no la pisa ni crea la cuenta" \
  bash -c "test \"\$(sql \"select password_policy from keycloak.realm where name='master'\")\" = 'length(20)' && test \"\$(sql \"select count(*) from keycloak.user_entity where username='harness-policy-probe'\")\" = 0"

section "ADMIN-H — OTP retirado: se informa, no se restituye (comportamiento nativo aceptado)"
PID="$(sql "select u.id from keycloak.user_entity u join keycloak.realm r on r.id=u.realm_id and r.name='master' where u.username='${PLATFORM_USER}'")"
OTPID="$(sql "select id from keycloak.credential where user_id='${PID}' and type='otp'")"
kc_as harness-human-lab "${HL_SECRET}" master delete "users/${PID}/credentials/${OTPID}" -r master >/dev/null 2>&1
HLID="$(kc_as harness-human-lab "${HL_SECRET}" master get clients -r master -q clientId=harness-human-lab --fields id --format csv --noquotes 2>/dev/null)"
kc_as harness-human-lab "${HL_SECRET}" master delete "clients/${HLID}" -r master >/dev/null 2>&1
check "OTP eliminado: preparación: sin OTP y sin autoridad de laboratorio" \
  bash -c "test '$(platform_facts)' = 'true||admin,default-roles-master|password:1,otp:0|false' && test '$(temp_count)' = 0"
converge admin-otp-deleted false; RC=$?
check "OTP eliminado: la convergencia sigue P1 (rc=${RC}): no es dependencia técnica" \
  bash -c "test ${RC} -eq 0 && log_has admin-otp-deleted 'KEYCLOAK: estado P1'"
check "OTP eliminado: informa SIN OTP y que la contraseña sola da acceso" \
  bash -c "platform_status admin-otp-deleted 'SIN OTP' && log_has admin-otp-deleted 'la contraseña sola da acceso'"
check "OTP eliminado: no se re-enrola, restablece ni agrega acción requerida" \
  test "$(platform_facts)" = "true||admin,default-roles-master|password:1,otp:0|false"
check "política: P1 no reescribe la política cambiada por el operador" \
  test "$(sql "select password_policy from keycloak.realm where name='master'")" = 'length(20)'
KBL_PASSWORD="${PLATFORM_NEW_PW}" browser password otp-deleted KBL_PASSWORD
check "nativo aceptado: sin OTP enrolado, el flujo browser de master entrega código solo con contraseña" \
  browser_has otp-deleted 'browser.password_only=code_issued'
KBL_PASSWORD="${PLATFORM_NEW_PW}" browser direct-grant otp-deleted-dg KBL_PASSWORD
check "nativo aceptado: sin OTP enrolado, admin-cli entrega token solo con contraseña" \
  browser_has otp-deleted-dg 'direct_grant.without_otp=token'

section "ADMIN-H — borrado intencional: FALTA, sin recreación (ni con la contraseña inicial presente)"
harness_admin harness-human-lab "${HL_SECRET}"
kc_as harness-human-lab "${HL_SECRET}" master delete "users/${PID}" -r master >/dev/null 2>&1
HLID="$(kc_as harness-human-lab "${HL_SECRET}" master get clients -r master -q clientId=harness-human-lab --fields id --format csv --noquotes 2>/dev/null)"
kc_as harness-human-lab "${HL_SECRET}" master delete "clients/${HLID}" -r master >/dev/null 2>&1
check "borrado: preparación: la cuenta no existe y no queda autoridad de laboratorio" \
  bash -c "test '$(platform_count)' = 0 && test '$(temp_count)' = 0"
PLATFORM_ENV_PW="${PLATFORM_PW}"
converge admin-deleted false; RC=$?
PLATFORM_ENV_PW=""
check "borrado: la convergencia sigue P1 (rc=${RC})" bash -c "test ${RC} -eq 0 && log_has admin-deleted 'KEYCLOAK: estado P1'"
check "borrado: informa FALTA y que no se recrea" \
  bash -c "platform_status admin-deleted 'FALTA' && log_has admin-deleted 'no se recrea'"
check "borrado: no se recreó (ni se creó autoridad temporal)" \
  bash -c "test '$(platform_count)' = 0 && ! log_has admin-deleted 'Crear la autoridad temporal' && ! task_ran admin-deleted 'Crear el administrador humano'"

# ══ P3 BLOQUEANTE: realm de la aplicación ausente ════════════════════════════
section "P3 BLOQUEANTE — realm de la aplicación ausente: no se recrea ni con recuperación"
LAB_SECRET="$(synthetic lab)"; SECRETS_SEEN+=("${LAB_SECRET}")
harness_admin harness-lab-admin "${LAB_SECRET}"
kc_as harness-lab-admin "${LAB_SECRET}" master delete realms/piedrazul >/dev/null 2>&1
LID="$(kc_as harness-lab-admin "${LAB_SECRET}" master get clients -r master -q clientId=harness-lab-admin --fields id --format csv --noquotes 2>/dev/null)"
kc_as harness-lab-admin "${LAB_SECRET}" master delete "clients/${LID}" -r master >/dev/null 2>&1
check "realm-ausente: preparación: falta piedrazul, master intacto, sin autoridad temporal" \
  bash -c "test '$(realms)' = 'master' && test '$(temp_count)' = 0"
blocking_expect p3-app-realm-missing 'falta el realm piedrazul'
compose restart keycloak >/dev/null 2>&1; compose up -d --wait keycloak >/dev/null 2>&1
check "realm-ausente: un reinicio de Keycloak tampoco lo recrea (sin --import-realm)" test "$(realms)" = "master"

# ══ P3 BLOQUEANTE: esquema parcial ═══════════════════════════════════════════
section "P3 BLOQUEANTE — esquema de Keycloak parcial sin tabla realm (no es P2)"
compose down --volumes >/dev/null 2>&1
docker network rm piedrazul_net >/dev/null 2>&1
compose up -d --wait postgres >/dev/null 2>&1
check "parcial: preparación: el schema de P-1 existe vacío" \
  test "$(sql "select count(*) from pg_class where relnamespace='keycloak'::regnamespace")" = 0
# Restos de una inicialización de Liquibase interrumpida, con la autoridad de Keycloak
sql "set role keycloak_user; create table keycloak.databasechangeloglock(id integer primary key, locked boolean not null); create table keycloak.databasechangelog(id varchar(255) not null)" >/dev/null
check "parcial: preparación: objetos de Keycloak sin tabla realm" \
  bash -c "test \"\$(sql \"select to_regclass('keycloak.realm') is null\")\" = t"
blocking_expect p3-partial-schema 'esquema de Keycloak parcialmente inicializado'
check "parcial: Keycloak no se arrancó ni se creó la tabla realm" \
  bash -c "test \"\$(sql \"select to_regclass('keycloak.realm') is null\")\" = t && ! docker container inspect keycloak >/dev/null 2>&1"

# ══ P3 BLOQUEANTE: Keycloak inicializado fuera del ciclo ═════════════════════
section "P3 BLOQUEANTE — base inicializada fuera del ciclo (arranque no gestionado)"
compose down --volumes >/dev/null 2>&1
docker network rm piedrazul_net >/dev/null 2>&1
compose up -d --wait postgres >/dev/null 2>&1
compose up -d --wait --no-deps keycloak >/dev/null 2>&1
check "fuera-del-ciclo: preparación: Keycloak arrancó sin el ciclo (solo master, sin admin)" \
  bash -c "test '$(realms)' = 'master' && test \"\$(sql \"select count(*) from keycloak.user_entity\")\" = 0"
blocking_expect p3-unmanaged-init 'falta el realm piedrazul'
check "fuera-del-ciclo: nada del repositorio autentica" \
  bash -c "test '$(kc_user_auth master "${REPO_ADMIN_USER}" "${REPO_ADMIN_PASSWORD}")' != ok && test '$(kc_client_auth piedrazul piedrazul-backend "${REPO_BACKEND_SECRET}")' != ok"

# ══ ADMIN-H: creación interrumpida en una P2 nueva ════════════════════════════
section "ADMIN-H — P2 nueva con la creación del administrador humano interrumpida"
compose down --volumes >/dev/null 2>&1
docker network rm piedrazul_net >/dev/null 2>&1
# Falla genuina a mitad de la creación (como INT-parcial): el rol no existe → add-roles falla
# con la cuenta ya creada (deshabilitada) y la contraseña temporal fijada
PLATFORM_ENV_PW="${PLATFORM_PW}"
converge p2-admin-interrupted false -e kc_platform_admin_role=no-such-role; RC=$?
check "interrumpida: la convergencia falla (rc=${RC}) sin informar éxito del administrador" \
  bash -c "test ${RC} -ne 0 && log_has p2-admin-interrupted 'el administrador humano NO quedó establecido' && ! log_has p2-admin-interrupted 'administrador humano creado'"
check "interrumpida: el error muestra el avance y el paso que falló" \
  bash -c "log_has p2-admin-interrupted 'platform_admin.user=created_disabled' && log_has p2-admin-interrupted 'error=platform_admin_role'"
check "interrumpida: la política de master quedó fijada antes de la cuenta (length(16))" \
  test "$(sql "select password_policy from keycloak.realm where name='master'")" = 'length(16)'
check "interrumpida: la autoridad temporal igual se eliminó" \
  bash -c "log_has p2-admin-interrupted 'el evento fresh falló' && test \$(temp_count) = 0"
check "interrumpida: la cuenta quedó deshabilitada y sin rol admin (no es una cuenta privilegiada accesible)" \
  test "$(platform_facts)" = "false|CONFIGURE_TOTP+UPDATE_PASSWORD|default-roles-master|password:1,otp:0|false"
INTERRUPTED_SNAP="$(platform_row)"
converge p2-admin-interrupted-next false; RC=$?
PLATFORM_ENV_PW=""
check "interrumpida: la convergencia siguiente es P1 técnico sin evento (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && log_has p2-admin-interrupted-next 'KEYCLOAK: estado P1' && ! log_has p2-admin-interrupted-next 'Crear la autoridad temporal'"
check "interrumpida: informa INCOMPLETA y escalar" \
  bash -c "platform_status p2-admin-interrupted-next 'INCOMPLETA' && log_has p2-admin-interrupted-next 'habilitada=no'"
check "interrumpida: no se repara, habilita ni recrea" test "$(platform_row)" = "${INTERRUPTED_SNAP}"
KBL_PASSWORD="${PLATFORM_PW}" browser direct-grant interrupted KBL_PASSWORD
check "interrumpida: la contraseña inicial no da acceso (cuenta deshabilitada)" browser_has interrupted 'direct_grant.without_otp=account_disabled'

fi

fi

# ══ SMTP: correo nativo con el interruptor de SES ════════════════════════════
if [ "${TARGET}" = smtp ]; then
section "SMTP — instalación nueva con SES habilitado"
SMTP_LOG="${W}/smtp/sink.log"
mkdir -p "${W}/smtp"; : > "${SMTP_LOG}"
docker run -d --name "${PROJECT}-smtp-sink" --label "com.docker.compose.project=${PROJECT}" --network piedrazul_net \
  --network-alias smtp-sink --network-alias smtp-sink-2 --user "$(id -u):$(id -g)" \
  -v "${ROOT}/infra/tests/smtp-sink.py:/sink.py:ro" -v "${W}/smtp:/out" "${PY_IMAGE}" python -I /sink.py >/dev/null
credential_hash() { python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()+bytes(1)+sys.argv[2].encode()).hexdigest())' "$1" "$2"; }
smtp_value() { sql "select c.value from keycloak.realm_smtp_config c join keycloak.realm r on r.id=c.realm_id where r.name='piedrazul' and c.name='$1'"; }
# Realm completo menos lo que W4 gobierna, y huella de lo que la representación no trae
realm_fingerprint() {
  kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul get realms/piedrazul 2>/dev/null \
    | jq -S 'del(.smtpServer, .resetPasswordAllowed, .attributes.scimApiEnabled)' | md5sum
  sql "select md5(string_agg(x, ',' order by x)) from (
         select 'client:'||id||enabled from keycloak.client
         union all select 'role:'||id||name from keycloak.keycloak_role
         union all select 'user:'||id||username||enabled from keycloak.user_entity
         union all select 'flow:'||id||alias from keycloak.authentication_flow
         union all select 'exec:'||id||requirement from keycloak.authentication_execution
         union all select 'action:'||id||enabled from keycloak.required_action_provider) t(x)"
  technical_row
}
reset_expect() { # <etiqueta>: una recuperación real termina en una autenticación y un correo
  local label="$1" mails rc
  mails="$(grep -c '^mail ' "${SMTP_LOG}")"
  KBL_REALM=piedrazul KBL_CLIENT=piedrazul-frontend KBL_REDIRECT=http://localhost:4200/ KBL_USER=harness-reset \
    browser reset-request "reset-${label}" KBL_REALM KBL_CLIENT KBL_REDIRECT KBL_USER
  check "${label}: la página de login ofrece la recuperación y Keycloak la acepta" \
    bash -c "browser_has 'reset-${label}' 'reset.advertised=true' && browser_has 'reset-${label}' 'reset.page=200' && browser_has 'reset-${label}' 'reset.submitted=200'"
  for _ in 1 2 3 4 5 6 7 8 9 10; do [ "$(grep -c '^mail ' "${SMTP_LOG}")" -gt "${mails}" ] && break; sleep 1; done
  check "${label}: llegó un correo más al SMTP sintético" test "$(grep -c '^mail ' "${SMTP_LOG}")" -eq "$((mails + 1))"
}
last_auth() { sed -n 's/^auth=//p' "${SMTP_LOG}" | tail -1; }
last_from() { sed -n 's/^mail .* from=//p' "${SMTP_LOG}" | tail -1; }
check "SMTP: P2 con SES termina bien y lo declara sin afirmar entrega" \
  bash -c "log_has p2 'SES configurado y recuperación de contraseña habilitada; no prueba credenciales válidas ni entrega de correo'"
check "SMTP: P2 configuró realm y recuperación en la misma convergencia" test "$(smtp_rows)|$(reset_allowed)" = "9|t"
check "SMTP: valores de conexión, endpoint y remitente exactos" \
  test "$(smtp_value host)|$(smtp_value port)|$(smtp_value starttls)|$(smtp_value ssl)|$(smtp_value auth)|$(smtp_value from)|$(smtp_value fromDisplayName)" \
    = "smtp-sink|2525|false|false|true|$(yq -r .notifications_email_from "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")|$(yq -r .notifications_email_from_name "${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml")"
check "SMTP: Keycloak no recibe ninguna clave de correo en su entorno" bash -c "! kc_env_keys | grep -Eq '^(SES_|NOTIFICATIONS_)'"
kc_as piedrazul-backend "${BACKEND_SECRET}" piedrazul create users -r piedrazul -s username=harness-reset -s enabled=true \
  -s email=reset@harness.test -s emailVerified=true >/dev/null 2>&1
reset_expect smtp-enabled
check "SMTP: Keycloak autentica con la credencial exacta (caracteres especiales incluidos)" \
  test "$(last_auth)" = "$(credential_hash "${SES_USER}" "${SES_PASSWORD}")"
check "SMTP: el correo sale al destinatario desde el remitente configurado" \
  grep -q '^mail rcpt=reset@harness.test from=Piedrazul Salud <no-reply@mail.piedrazul.org>$' "${SMTP_LOG}"
MAILS="$(grep -c '^mail ' "${SMTP_LOG}")"
KBL_REALM=piedrazul KBL_CLIENT=piedrazul-frontend KBL_REDIRECT=http://localhost:4200/ KBL_USER=harness-sentinel \
  browser reset-request reset-no-email KBL_REALM KBL_CLIENT KBL_REDIRECT KBL_USER
check "SMTP: una cuenta sin correo recibe la misma respuesta y ningún correo" \
  test "$(grep -c '^reset.submitted=200$' "${LOGS}/browser-reset-no-email.txt")|$(grep -c '^mail ' "${SMTP_LOG}")" = "1|${MAILS}"
first_admin_expect smtp-enabled

section "SMTP — convergencia repetida, sin reiniciar Keycloak"
CONTAINER_SMTP="$(kc_container)"
REALM_SNAP="$(realm_fingerprint)"
converge smtp-repeat false -vvv; RC=$?
check "repetición: termina bien (rc=${RC})" test "${RC}" -eq 0
check "repetición: mismo realm salvo lo gobernado; mismos usuarios, clientes, roles y flujos" test "$(realm_fingerprint)" = "${REALM_SNAP}"
check "repetición: Keycloak sin reiniciar" test "$(kc_container)" = "${CONTAINER_SMTP}"

section "SMTP — rotación, remitente y endpoint"
SES_USER="$(synthetic smtpuser)"; SES_PASSWORD="$(synthetic smtppw)'\"\$\\#&%"; SES_HOST=smtp-sink-2
SECRETS_SEEN+=("${SES_USER}" "${SES_PASSWORD}")
# Muestreo de argv en el host y de argv y entorno dentro de Keycloak durante la escritura. El
# entorno del exec trae el secreto de automatización, así que queda fuera de la evidencia.
SAMPLES="${W}/samples"; mkdir -p "${SAMPLES}"; touch "${W}/sample"
(
  while [ -f "${W}/sample" ]; do
    ps -eww -o args >> "${SAMPLES}/host.txt" 2>/dev/null
    docker exec keycloak bash -c 'for p in /proc/[0-9]*; do tr "\0" " " < "$p/cmdline"; echo; tr "\0" "\n" < "$p/environ"; done' >> "${SAMPLES}/keycloak.txt" 2>/dev/null
  done
) &
SAMPLER=$!
converge smtp-rotate false -e '{"notifications_email_from": "otro@mail.piedrazul.org", "notifications_email_from_name": "Otro Remitente"}'; RC=$?
rm -f "${W}/sample"; wait "${SAMPLER}" 2>/dev/null
SMTP_NEEDLE_USER="${SES_USER}"; SMTP_NEEDLE_PW="${SES_PASSWORD%%\'*}"
check "rotación: termina bien (rc=${RC})" test "${RC}" -eq 0
reset_expect smtp-rotated
check "rotación: Keycloak autentica con la credencial nueva" test "$(last_auth)" = "$(credential_hash "${SES_USER}" "${SES_PASSWORD}")"
check "rotación: remitente y endpoint nuevos" \
  test "$(smtp_value host)|$(last_from)" = "smtp-sink-2|Otro Remitente <otro@mail.piedrazul.org>"
check "rotación: mismo realm salvo lo gobernado; Keycloak sin reiniciar" \
  test "$(realm_fingerprint)|$(kc_container)" = "${REALM_SNAP}|${CONTAINER_SMTP}"
check "muestreo: control positivo: vio el exec smtp y su entorno" \
  bash -c "grep -q 'keycloak-admin smtp' '${SAMPLES}/keycloak.txt' && grep -q '^KCL_AUTOMATION_SECRET=' '${SAMPLES}/keycloak.txt'"

section "SMTP — deshabilitar"
MAILS="$(grep -c '^mail ' "${SMTP_LOG}")"
SES_ENABLED=false
converge smtp-disable false; RC=$?
check "deshabilitar con las entradas aún en el runner: termina bien (rc=${RC})" test "${RC}" -eq 0
check "deshabilitar: sin filas SMTP, sin contraseña retenida y recuperación apagada" \
  test "$(smtp_rows)|$(sql "select count(*) from keycloak.realm_smtp_config where name='password'")|$(reset_allowed)" = "0|0|f"
SES_HOST="" SES_USER_KEPT="${SES_USER}" SES_PASSWORD_KEPT="${SES_PASSWORD}"; SES_ENABLED="" SES_USER="" SES_PASSWORD=""
converge smtp-disable-again false; RC=$?
check "deshabilitado sin entradas, ya limpio: termina bien sin llamar a la API (rc=${RC})" \
  bash -c "test ${RC} -eq 0 && ! task_ran smtp-disable-again 'Aplicar SMTP y recuperación al realm'"
KBL_REALM=piedrazul KBL_CLIENT=piedrazul-frontend KBL_REDIRECT=http://localhost:4200/ KBL_USER=harness-reset \
  browser reset-request reset-disabled KBL_REALM KBL_CLIENT KBL_REDIRECT KBL_USER
check "deshabilitado: la página no ofrece recuperación y Keycloak la rechaza" \
  bash -c "browser_has reset-disabled 'reset.advertised=false' && browser_has reset-disabled 'reset.page=400'"
check "deshabilitado: ningún correo más" test "$(grep -c '^mail ' "${SMTP_LOG}")" = "${MAILS}"
first_admin_expect smtp-disabled

section "SMTP — falla al escribir y reintento"
SES_ENABLED=true SES_HOST=smtp-sink SES_USER="${SES_USER_KEPT}" SES_PASSWORD="${SES_PASSWORD_KEPT}"
sql "create function keycloak.harness_smtp_block() returns trigger language plpgsql as \$\$ begin raise exception 'harness'; end \$\$;
     create trigger harness_smtp_block before insert or update or delete on keycloak.realm_smtp_config for each row execute function keycloak.harness_smtp_block();" >/dev/null
converge smtp-write-fail false; RC=$?
check "falla al escribir: el converge falla (rc=${RC}) y lo dice" \
  bash -c "test ${RC} -ne 0 && log_has smtp-write-fail 'el realm no aceptó la configuración' && log_has smtp-write-fail 'error=smtp_update'"
check "falla al escribir: no informa éxito" bash -c "! log_has smtp-write-fail 'SES configurado y recuperación'"
check "falla al escribir: transacción única: ni SMTP ni recuperación quedaron a medias" test "$(smtp_rows)|$(reset_allowed)" = "0|f"
sql "drop trigger harness_smtp_block on keycloak.realm_smtp_config; drop function keycloak.harness_smtp_block();" >/dev/null
converge smtp-write-retry false; RC=$?
check "falla al escribir: el reintento converge (rc=${RC})" bash -c "test ${RC} -eq 0 && test \"\$(sql \"select count(*) from keycloak.realm_smtp_config\")\" = 9"
check "falla al escribir: recuperación habilitada tras el reintento" test "$(reset_allowed)" = t

section "SMTP — falla al releer y reintento"
SES_ENABLED=false; converge smtp-before-readback false >/dev/null; SES_ENABLED=true
sql "create function keycloak.harness_smtp_drop() returns trigger language plpgsql as \$\$ begin if new.name = 'from' then return null; end if; return new; end \$\$;
     create trigger harness_smtp_drop before insert on keycloak.realm_smtp_config for each row execute function keycloak.harness_smtp_drop();" >/dev/null
converge smtp-readback-fail false; RC=$?
check "falla al releer: el converge falla (rc=${RC}) y no informa éxito" \
  bash -c "test ${RC} -ne 0 && log_has smtp-readback-fail 'No se informa como éxito' && ! log_has smtp-readback-fail 'SES configurado y recuperación'"
sql "drop trigger harness_smtp_drop on keycloak.realm_smtp_config; drop function keycloak.harness_smtp_drop();" >/dev/null
converge smtp-readback-retry false; RC=$?
check "falla al releer: el reintento converge sin reemplazar el realm (rc=${RC})" \
  test "${RC}|$(smtp_rows)|$(realm_id)" = "0|9|${REALM_ID}"
reset_expect smtp-after-retry

section "SMTP — configuración ajena"
foreign_smtp() { jq -cn "$1" | kc_as piedrazul-keycloak-automation "${AUTOMATION_SECRET}" master update realms/piedrazul -f - >/dev/null 2>&1; }
foreign_smtp '{smtpServer: {host: "mail.ajeno.test", port: "25", from: "ajeno@ajeno.test"}}'
FOREIGN_SNAP="$(sql "select string_agg(name||'='||value, ',' order by name) from keycloak.realm_smtp_config")"
check "ajena: preparación: otro host en el realm" test "$(smtp_value host)" = mail.ajeno.test
for flag in true false; do
  SES_ENABLED="${flag}"
  converge "smtp-foreign-${flag}" false; RC=$?
  check "ajena (SES=${flag}): el converge falla (rc=${RC}) sin escribir" \
    bash -c "test ${RC} -ne 0 && log_has 'smtp-foreign-${flag}' 'no es de SES; este ciclo no lo puso y no se sobrescribe' && ! task_ran 'smtp-foreign-${flag}' 'Aplicar SMTP y recuperación al realm'"
  check "ajena (SES=${flag}): configuración intacta" \
    test "$(sql "select string_agg(name||'='||value, ',' order by name) from keycloak.realm_smtp_config")" = "${FOREIGN_SNAP}"
done
SES_ENABLED=true
foreign_smtp '{smtpServer: {}}'
converge smtp-foreign-cleared false; RC=$?
check "ajena: retirada por el operador, converge reconcilia (rc=${RC})" test "${RC}|$(smtp_rows)" = "0|9"

section "SMTP — realm anterior a manage-realm: P3 y recuperación autorizada"
OLD_ROLES='{"kc_automation_roles": ["piedrazul-realm:manage-clients", "piedrazul-realm:view-clients"]}'
automation_roles() { sa_roles piedrazul-keycloak-automation | tr ',' '\n' | grep -v default-roles | paste -sd, -; }
SES_ENABLED=false; converge smtp-clean-for-old false >/dev/null
converge smtp-to-old-roles true -e "${OLD_ROLES}"; RC=$?
check "transición: preparación: automatización sin manage-realm (rc=${RC})" \
  test "${RC}|$(automation_roles)" = "0|piedrazul-realm:manage-clients,piedrazul-realm:view-clients"
SES_ENABLED=true
SMTP_BEFORE="$(smtp_rows)|$(reset_allowed)"
p3_expect smtp-p3-old-roles 'roles del service account de automatización divergentes'
check "transición: P3 no toca SMTP ni amplía la autoridad" \
  bash -c "! task_ran smtp-p3-old-roles 'Aplicar SMTP y recuperación al realm' && test '$(smtp_rows)|$(reset_allowed)' = '${SMTP_BEFORE}' && ! sa_roles piedrazul-keycloak-automation | grep -q manage-realm"
recover_expect smtp-recovery
check "transición: la recuperación autorizada agrega solo manage-realm, nada en master" \
  test "$(automation_roles)" = "piedrazul-realm:manage-clients,piedrazul-realm:manage-realm,piedrazul-realm:view-clients"
check "transición: la misma recuperación deja el SMTP en P1" test "$(smtp_rows)|$(reset_allowed)" = "9|t"
reset_expect smtp-after-recovery
fi

# ══ Fugas ════════════════════════════════════════════════════════════════════
section "Secretos en la salida"
LEAKS=0
# Todo secreto sintético empieza por "synthetic": basta buscar esa marca (independiente de los
# valores). harness-admin.log es salida de los comandos del propio harness, no de Ansible.
grep -rlF --exclude=harness-admin.log -e synthetic "${LOGS}" > "${W}/leaks.txt" 2>/dev/null
LEAKS=$(wc -l < "${W}/leaks.txt")
grep -rqF --exclude=harness-admin.log -e "${REPO_BACKEND_SECRET}" "${LOGS}" && LEAKS=$((LEAKS + 1))
check "la marca de los secretos sintéticos existe en el harness (control positivo)" grep -q 'synthetic' <<< "${BACKEND_SECRET}"
check "ningún secreto (sintético o del repositorio) aparece en los logs de Ansible ni en las muestras" test "${LEAKS}" -eq 0
if [ "${TARGET}" = smtp ]; then
  # En base64, con las tres alineaciones posibles dentro de un valor mayor: la marca común para los
  # logs; para las muestras, la credencial SMTP, porque reconcile lleva otros secretos así en el
  # entorno de su exec (KCL_*_JSON_B64)
  encoded() { python3 -c 'import base64, sys
for pad in range(3):
    text = base64.b64encode(b"x" * pad + sys.argv[1].encode()).decode().rstrip("=")
    print(text[4 if pad else 0:-2])' "$1"; }
  ENCODED="$(encoded synthetic)"
  ENCODED_PW="$(encoded "${SMTP_NEEDLE_PW}")"
  check "fugas codificadas: ninguna en logs de Ansible, incluida la corrida con -vvv" \
    bash -c "! grep -rqF --exclude=harness-admin.log -e '$(sed -n 1p <<< "${ENCODED}")' -e '$(sed -n 2p <<< "${ENCODED}")' -e '$(sed -n 3p <<< "${ENCODED}")' '${LOGS}'"
  check "muestras: las credenciales SMTP no están en argv del host ni en argv o entorno de Keycloak" \
    bash -c "! grep -qF -e '${SMTP_NEEDLE_USER}' -e '${SMTP_NEEDLE_PW}' '${SAMPLES}/host.txt' '${SAMPLES}/keycloak.txt'"
  check "muestras: tampoco codificadas" \
    bash -c "! grep -qF -e '$(sed -n 1p <<< "${ENCODED_PW}")' -e '$(sed -n 2p <<< "${ENCODED_PW}")' -e '$(sed -n 3p <<< "${ENCODED_PW}")' '${SAMPLES}/host.txt' '${SAMPLES}/keycloak.txt'"
  printf '%s' "${SES_PASSWORD}" > "${W}/.needle"
  check "persistencia: la contraseña SMTP no queda en el espacio de trabajo ni en temporales de Ansible" \
    bash -c "! grep -rqF --exclude-dir=evidence --exclude-dir=samples --exclude=.needle -f '${W}/.needle' '${W}' \"\${HOME}/.ansible/tmp\" 2>/dev/null"
  rm -f "${W}/.needle"
  check "persistencia: sin sesiones de kcadm en /dev/shm de Keycloak" test -z "$(docker exec keycloak ls /dev/shm 2>/dev/null)"
  cp "${SMTP_LOG}" "${LOGS}/smtp-sink.log"
fi

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "keycloak-lifecycle-harness: ${FAILS} fallo(s) — evidencia en ${EVIDENCE}" | tee -a "${RESULTS}"
  exit 1
fi
echo "keycloak-lifecycle-harness: todo PASS — evidencia en ${EVIDENCE}" | tee -a "${RESULTS}"
