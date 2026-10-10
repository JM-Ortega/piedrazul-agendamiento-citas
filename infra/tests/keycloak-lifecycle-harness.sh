#!/usr/bin/env bash
# Prueba ejecutable del ciclo técnico de Keycloak (P1/P2/P3 y recuperación) con los task
# files reales del rol app (keycloak_credentials.yml → db_converge.yml →
# keycloak_lifecycle.yml) contra PostgreSQL (imagen de infra/postgres) y Keycloak
# desechables, con conexión local. No toca producción, GitHub ni credenciales reales: todo
# secreto es sintético, nunca se imprime, y al final se verifica que no aparece en ningún log.
#
# Uso: infra/tests/keycloak-lifecycle-harness.sh [directorio de evidencia]
#   HARNESS_TARGET=foreign-user → solo P2, el escenario de usuario temporal ajeno y fugas
# Requiere: docker (compose v2), ansible-playbook con community.docker 5.x
#   (ANSIBLE_COLLECTIONS_PATH) y python con requests para el intérprete local
#   (HARNESS_PYTHON, por defecto python3); la imagen HARNESS_PYTHON_IMAGE (por defecto
#   python:3.12-slim) corre keycloak-browser-login.py dentro de la red de Keycloak.
#
# Usa los nombres fijos de producción (contenedores postgres/keycloak, red piedrazul_net):
# se niega a correr si ya existen. Proyecto Compose propio y volumen propio; limpia al salir.
#
# Escenarios:
#   P2      base nueva → evento → P1; ninguna credencial del repositorio válida en ningún
#           momento (sondeo continuo durante el evento); sin autoridad temporal al final
#   P1      dos convergencias más: sin evento, idempotentes, sin recrear el realm
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
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
PYTHON="${HARNESS_PYTHON:-python3}"
KC_IMAGE="$(sed -n 's/^ *image: *//p' "${ROOT}/infra/compose/keycloak.yml" | head -1)"
PROJECT=piedrazul-kchl

for bin in docker ansible-playbook; do
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
printf 'BACKEND_IMAGE=%s\nPOSTGRES_IMAGE=%s\n' "busybox:harness-never-started" "${PROJECT}-postgres:local" > "${W}/image.env"
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
    - ansible.builtin.include_role: {name: app, tasks_from: keycloak_lifecycle.yml}
EOF

ansible_run() { # <log> <playbook> [extra args...]
  local log="$1" pb="$2"; shift 2
  env POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" APP_DB_PASSWORD="${APP_DB_PASSWORD}" \
      MIGRATION_DB_PASSWORD="${MIGRATION_DB_PASSWORD}" KC_DB_PASSWORD="${KC_DB_PASSWORD}" \
      KC_BACKEND_CLIENT_SECRET="${BACKEND_SECRET}" KC_AUTOMATION_CLIENT_SECRET="${AUTOMATION_SECRET}" \
      KC_PLATFORM_ADMIN_INITIAL_PASSWORD="${PLATFORM_ENV_PW}" \
      ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" \
      ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${W}/hosts.yml" "${W}/${pb}" \
      -e "app_dir=${W}" -e "compose_project_name=${PROJECT}" -e "db_lock_file=${W}/db.lock" \
      -e "repo_root=${ROOT}" "$@" </dev/null > "${LOGS}/${log}.log" 2>&1
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

# kc_client_auth <realm> <clientId> <secret> → ok|rejected|unavailable (secreto por entorno del exec)
kc_client_auth() {
  docker exec -e "S=$3" keycloak bash -c '
    c="$(mktemp -u /dev/shm/h.XXXXXX)"; trap "rm -f $c" EXIT
    if out="$(KC_CLI_CLIENT_SECRET="$S" /opt/keycloak/bin/kcadm.sh config credentials --config "$c" \
        --server http://localhost:8180 --realm "$0" --client "$1" 2>&1)"; then echo ok
    elif grep -qiE "invalid client|unauthorized_client|401" <<<"$out"; then echo rejected
    else echo unavailable; fi' "$1" "$2" 2>/dev/null || echo unavailable
}
kc_user_auth() { # <realm> <user> <password>
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
# check usa bash -c: las comprobaciones necesitan estas funciones en el subshell
export -f sql temp_count realm_id realms sa_roles users_in_realm kc_env_keys kc_client_auth kc_user_auth log_has task_ran
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
check "P2: la política del realm de la aplicación no cambia (la del repositorio: ninguna)" \
  test "$(sql "select coalesce(password_policy,'') from keycloak.realm where name='piedrazul'")" = "$(jq -r '.passwordPolicy // ""' "${ROOT}/infra/keycloak/realm/piedrazul-realm.json")"
check "P2: el hash de la contraseña inicial es el de Keycloak por defecto (argon2)" \
  test "$(sql "select string_agg(c.credential_data::json->>'algorithm', ',') from keycloak.credential c join keycloak.user_entity u on u.id=c.user_id where u.username='${PLATFORM_USER}' and c.type='password'")" = argon2
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
  test "$(sql "select string_agg(rc.client_id||':'||k.name, ',' order by k.name) from keycloak.user_entity u join keycloak.client c on c.id=u.service_account_client_link join keycloak.user_role_mapping m on m.user_id=u.id join keycloak.keycloak_role k on k.id=m.role_id join keycloak.client rc on rc.id=k.client where c.client_id='piedrazul-keycloak-automation'")" = "piedrazul-realm:manage-clients,piedrazul-realm:view-clients"
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

TARGET="${HARNESS_TARGET:-}"
if [ -z "${TARGET}" ]; then
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
  check "P1#${n}: no exige KC_PLATFORM_ADMIN_INITIAL_PASSWORD (ausente) y no toca al administrador humano" \
    bash -c "test '$(platform_row)' = '${PLATFORM_SNAP}' && ! task_ran p1-${n} 'Crear el administrador humano'"
  check "P1#${n}: informa la preparación humana aparte: primer login pendiente" platform_status "p1-${n}" 'CREADA, primer login pendiente'
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

if [ -z "${TARGET}" ]; then
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
BAD_ROLES='{"kc_automation_roles": ["piedrazul-realm:manage-clients", "piedrazul-realm:view-clients", "piedrazul-realm:no-such-role"]}'
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

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "keycloak-lifecycle-harness: ${FAILS} fallo(s) — evidencia en ${EVIDENCE}" | tee -a "${RESULTS}"
  exit 1
fi
echo "keycloak-lifecycle-harness: todo PASS — evidencia en ${EVIDENCE}" | tee -a "${RESULTS}"
