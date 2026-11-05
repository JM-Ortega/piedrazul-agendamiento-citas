#!/usr/bin/env bash
# Contrato de los productores de logs: inventario / perfil → política (log_policy.yml, la misma en
# converge y observability) → .env (env.j2) → prod.yml (extends de logging.yml) → logging de
# Docker de traefik, keycloak, backend y postgres. Renderiza el env.j2 real, ejecuta la tarea real
# de la política y evalúa la configuración real de Compose; la parte de Docker solo crea
# contenedores busybox desechables. No usa red, credenciales ni hosts reales. Lo que Alloy hace
# con esas entradas lo prueba logs-stack-check.sh (journal sintético) y, con el driver journald
# real de un host Linux, logs-e2e-lab.sh.
#
# Uso: infra/tests/log-producers-contract-check.sh
#   RUNTIME=0  omite la parte de Docker
#   DIND=1     además, rotación efectiva con un daemon json-file 10m x 3 (docker:28-dind,
#              privilegiado, desechable)
# Requiere: docker compose, ansible-playbook, jq, yq (mikefarah v4), git; docker con daemon para la
# parte de ejecución.
#
# Verifica:
#   1. .env: por defecto TRAEFIK_LOGGING=journald y default en el resto; perfil de laboratorio,
#      journald en los cuatro con su marca; adopción en operación (los cuatro con la decisión de
#      privacidad, sin perfil), journald en los cuatro sin marca de laboratorio.
#   2. Compose: journald con las opciones del contrato (non-blocking, 4m, labels de Compose, tag)
#      solo donde .env lo pide; el resto sin logging propio, con el mismo hash que antes de este
#      cambio (no recrea, mismo comportamiento); sin las variables, ninguno; valor desconocido
#      rechazado; render determinista; migrate y desarrollo sin logging.
#   3. Diagnóstico de PostgreSQL y de los demás sin volcados.
#   4. Política (tarea real de setup.yml): por defecto, perfil y adopción en operación admitidos;
#      condicionados sin decisión ni perfil, autorizaciones con otro valor, decisión parcial,
#      excluidos y nombres no válidos rechazados.
#   5. Coherencia: una sola política y una sola lista para converge y observability; perfil
#      mínimo; todas las llamadas de Compose del rol app con prod.yml, ../.env e ../image.env.
#   6. Docker: default = configuración implícita del daemon (modo, opciones); journald con las
#      opciones del contrato; journald sin journald falla visiblemente. Con DIND=1, rotación
#      efectiva 10m x 3 y modo blocking en default.
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
GROUP_VARS="${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml"
OBS_DEFAULTS="${ANSIBLE_DIR}/roles/observability/defaults/main.yml"
PROFILE="${ANSIBLE_DIR}/profiles/logs-lab-synthetic.yml"
SERVICES=(traefik keycloak backend postgres)

for bin in docker ansible-playbook jq yq git; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
for s in "${SERVICES[@]}"; do
  v="${s^^}_LOGGING"
  [ -z "${!v+x}" ] || { echo "${v} está definida en el entorno: Compose la usaría en vez del .env" >&2; exit 2; }
done

TMP="$(mktemp -d)"
CLEANUP=()
cleanup() { for c in "${CLEANUP[@]}"; do eval "$c" >/dev/null 2>&1; done; rm -rf "${TMP}"; }
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
ansible_local() { # <playbook> [args...]
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" "$@" </dev/null
}
FOUR='["traefik", "keycloak", "backend", "postgres"]'
ADOPT=(-e "{\"obs_log_services\": ${FOUR}, \"obs_log_privacy_controls_done\": [\"keycloak\", \"backend\", \"postgres\"]}")

# ── 1. Productor: env.j2 con los group_vars reales ──────────────────────────
mkdir -p "${TMP}/app/compose" "${TMP}/base/compose"
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
cp "${TMP}/app/image.env" "${TMP}/base/image.env"

render() { # <salida> [extra-vars...]
  local out="$1"; shift
  ansible_local "${TMP}/render.yml" -e "env_template=${ANSIBLE_DIR}/roles/app/templates/env.j2" -e "env_out=${out}" "$@" > "${out}.log" 2>&1
}
check "env.j2 renderiza con group_vars de producción" render "${TMP}/env.default"
check "env.j2 renderiza con el perfil de laboratorio" render "${TMP}/env.lab" -e "@${PROFILE}"
check "env.j2 renderiza la adopción en operación (cuatro servicios con decisión de privacidad)" render "${TMP}/env.adopt" "${ADOPT[@]}"
modes() { grep -E '^(TRAEFIK|KEYCLOAK|BACKEND|POSTGRES)_LOGGING=' "$1" | tr '\n' ' '; }
check ".env por defecto: journald solo para traefik" \
  test "$(modes "${TMP}/env.default")" = "TRAEFIK_LOGGING=journald KEYCLOAK_LOGGING=default BACKEND_LOGGING=default POSTGRES_LOGGING=default "
check ".env de laboratorio: journald para los cuatro" \
  test "$(modes "${TMP}/env.lab")" = "TRAEFIK_LOGGING=journald KEYCLOAK_LOGGING=journald BACKEND_LOGGING=journald POSTGRES_LOGGING=journald "
check ".env de adopción en operación: journald para los cuatro" \
  test "$(modes "${TMP}/env.adopt")" = "$(modes "${TMP}/env.lab")"
check ".env: marca del perfil sintético solo en el laboratorio (ni por defecto ni en la adopción)" \
  bash -c "grep -q 'laboratorio sintético' '${TMP}/env.lab' && ! grep -q 'laboratorio sintético' '${TMP}/env.default' && ! grep -q 'laboratorio sintético' '${TMP}/env.adopt'"

# ── 2. Consumidor: Compose de producción ────────────────────────────────────
config() { # <dir> <.env> → JSON de prod.yml (avisos en compose.err)
  cp "$2" "$1/.env"
  (cd "$1/compose" && docker compose --project-name piedrazul --file prod.yml \
    --env-file ../.env --env-file ../image.env --profile migrate config --format json 2>"${TMP}/compose.err")
}
hashes() { # <dir> <.env> → "servicio hash" por línea
  cp "$2" "$1/.env"
  (cd "$1/compose" && docker compose --project-name piedrazul --file prod.yml \
    --env-file ../.env --env-file ../image.env config --hash '*' 2>/dev/null) | sort
}
A="${TMP}/app"
config "$A" "${TMP}/env.default" > "${TMP}/default.json"
config "$A" "${TMP}/env.lab" > "${TMP}/lab.json"
config "$A" "${TMP}/env.adopt" > "${TMP}/adopt.json"
grep -vE '^(TRAEFIK|KEYCLOAK|BACKEND|POSTGRES)_LOGGING=' "${TMP}/env.default" > "${TMP}/env.legacy"
config "$A" "${TMP}/env.legacy" > "${TMP}/legacy.json"
check "Compose: un .env sin las variables no produce avisos de variable no definida" bash -c "! grep -q '_LOGGING' '${TMP}/compose.err'"
drivers() { jq -r '[.services.traefik, .services.keycloak, .services.backend, .services.postgres | .logging.driver // "-"] | join(",")' "$1"; }
check "Compose por defecto: traefik journald; keycloak, backend y postgres sin logging propio" test "$(drivers "${TMP}/default.json")" = "journald,-,-,-"
check "Compose de laboratorio: journald en los cuatro" test "$(drivers "${TMP}/lab.json")" = "journald,journald,journald,journald"
check "Compose de adopción en operación: la misma configuración que el laboratorio (una sola arquitectura)" \
  test "$(jq -cS '.services | map_values(.logging)' "${TMP}/adopt.json")" = "$(jq -cS '.services | map_values(.logging)' "${TMP}/lab.json")"
check "Compose sin las variables (.env anterior, harness): ninguno con logging propio" test "$(drivers "${TMP}/legacy.json")" = "-,-,-,-"
EXPECTED='{"driver":"journald","options":{"labels":"com.docker.compose.project,com.docker.compose.service","max-buffer-size":"4m","mode":"non-blocking","tag":"{{.Name}}"}}'
check "Compose: journald con las opciones del contrato en los cuatro (non-blocking, 4m, labels, tag)" \
  bash -c "for s in ${SERVICES[*]}; do test \"\$(jq -cS --arg s \"\$s\" '.services[\$s].logging' '${TMP}/lab.json')\" = '${EXPECTED}' || exit 1; done"
check "Compose: default no aporta ninguna opción (ni mode ni max-buffer-size)" \
  test "$(jq -c '[.services.keycloak, .services.backend, .services.postgres | .logging] | unique' "${TMP}/default.json")" = '[null]'
printf 'KEYCLOAK_LOGGING=json\n' >> "${TMP}/env.bad"; cat "${TMP}/env.default" >> "${TMP}/env.bad"; sed -i '/^KEYCLOAK_LOGGING=default$/d' "${TMP}/env.bad"
bad_rejected() { ! config "$A" "${TMP}/env.bad" >/dev/null; }
check "Compose: un valor desconocido (json) hace fallar la configuración" bad_rejected
check "Compose: el proyecto es piedrazul (COM_DOCKER_COMPOSE_PROJECT de la selección)" test "$(jq -r '.name' "${TMP}/lab.json")" = piedrazul
check "Compose: migrate sin logging propio" test "$(jq -r '.services.migrate.logging // "ninguna"' "${TMP}/lab.json")" = ninguna
check "Compose: solo los cuatro con logging propio en el laboratorio" \
  test "$(jq -r '[.services | to_entries[] | select(.value.logging) | .key] | sort | join(",")' "${TMP}/lab.json")" = backend,keycloak,postgres,traefik
check "Compose: logging.yml no es un proyecto ni se incluye (solo extends de prod.yml)" \
  bash -c "! grep -rhvE '^\\s*#' '${ROOT}/infra/compose' '${ROOT}/docker-compose.yml' | grep -qE '^\\s*-\\s*\\./logging\\.yml' && test \"\$(grep -c 'file: ./logging.yml' '${ROOT}/infra/compose/prod.yml')\" = 4 && ! yq -e 'has(\"name\") or has(\"include\")' '${ROOT}/infra/compose/logging.yml'"
cp "${ROOT}/.env.example" "${TMP}/dev.env"
check "Desarrollo: docker-compose.yml sin logging propio" \
  bash -c "test \"\$(cd '${ROOT}' && docker compose --file docker-compose.yml --env-file '${TMP}/dev.env' config --format json 2>/dev/null | jq -r '[.services[] | select(.logging)] | length')\" = 0"
H1="$(hashes "$A" "${TMP}/env.lab")"; H2="$(hashes "$A" "${TMP}/env.lab")"; HD="$(hashes "$A" "${TMP}/env.default")"
check "Compose: render determinista (mismo .env → mismos hashes: un run repetido no recrea)" test -n "${H1}" -a "${H1}" = "${H2}"
CHANGED="$(diff <(echo "${HD}") <(echo "${H1}") | awk '/^>/ {print $2}' | sort | tr '\n' ',')"
check "Compose: de la configuración por defecto al laboratorio solo cambian keycloak, backend y postgres (${CHANGED})" \
  test "${CHANGED}" = backend,keycloak,postgres,
# Antes de este cambio (HEAD): mismos archivos en el mismo directorio (las rutas relativas de los
# montajes entran en el hash), sin logging.yml ni extends
if ! git -C "${ROOT}" cat-file -e HEAD:infra/compose/logging.yml 2>/dev/null; then
  cp -r "$A/compose" "${TMP}/compose.now"
  rm -f "$A"/compose/*.yml
  for f in $(git -C "${ROOT}" ls-tree --name-only HEAD infra/compose/); do git -C "${ROOT}" show "HEAD:${f}" > "$A/compose/$(basename "$f")"; done
  HB="$(hashes "$A" "${TMP}/env.legacy")"
  rm -rf "$A/compose" && mv "${TMP}/compose.now" "$A/compose"
  SAME="$(comm -12 <(echo "${HB}") <(echo "${HD}") | awk '{print $1}' | sort | tr '\n' ',')"
  check "Compose: por defecto keycloak y backend tienen el mismo hash que en HEAD (este cambio no los recrea) (${SAME})" \
    test "${SAME}" = backend,keycloak,
else
  echo "SKIP  comparación con HEAD (HEAD ya contiene logging.yml)"
fi

# ── 3. Diagnóstico sin volcados ─────────────────────────────────────────────
PGCMD="$(jq -r '.services.postgres.command | join(" ")' "${TMP}/default.json")"
check "postgres (siempre, no solo laboratorio): prefijo con rol@base y log_lock_waits" test "${PGCMD}" = "postgres -c log_line_prefix=%m [%p] %q%u@%d  -c log_lock_waits=on"
check "postgres: sin log_statement, log_min_duration_statement, log_connections ni parámetros" \
  bash -c "! grep -qE 'log_statement|log_min_duration|log_connections|log_parameter|log_error_verbosity|log_min_error_statement' <<< \"\$1\"" _ "${PGCMD}"
check "postgres: PGOPTIONS (siempre) silencia la sentencia de los clientes locales" \
  test "$(jq -r '.services.postgres.environment.PGOPTIONS' "${TMP}/default.json")" = "-c log_min_error_statement=panic"
check "postgres: PGOPTIONS solo en postgres (backend, keycloak y migrate conservan la sentencia)" \
  test "$(jq -r '[.services | to_entries[] | select(.value.environment.PGOPTIONS) | .key] | join(",")' "${TMP}/default.json")" = postgres
check "traefik: sin access log" bash -c "! jq -r '.services.traefik.command[]' '${TMP}/lab.json' | grep -qi accesslog"
check "backend: SQL y binds en WARN (sin sentencias ni valores)" \
  test "$(jq -r '.services.backend.environment | .LOG_LEVEL_SQL + "," + .LOG_LEVEL_SQL_BIND' "${TMP}/lab.json")" = WARN,WARN
check "keycloak: consola sin cambios de nivel (eventos se conservan)" \
  test "$(jq -r '.services.keycloak.environment | has("KC_LOG_LEVEL") or has("KC_LOG_CONSOLE_LEVEL")' "${TMP}/lab.json")" = false

# ── 4. Política (tarea real de setup.yml) ────────────────────────────────────
task_line() { grep -n -- "- name: $1\$" "${ANSIBLE_DIR}/roles/app/tasks/setup.yml" | cut -d: -f1; }
check "setup.yml: la política va antes de copiar Compose y de escribir .env" \
  bash -c "test -n \"\$1\" && test \"\$1\" -lt \"\$2\" && test \"\$1\" -lt \"\$3\"" _ \
    "$(task_line 'Verificar la política de logs de los servicios')" "$(task_line 'Copiar archivos compose al servidor')" "$(task_line 'Generar .env desde template')"
yq '[.[] | select(.name == "Verificar la política de logs de los servicios")]' "${ANSIBLE_DIR}/roles/app/tasks/setup.yml" > "${TMP}/gate-tasks.yml"
cat > "${TMP}/gate.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_tasks: ${TMP}/gate-tasks.yml
EOF
gate() { ansible_local "${TMP}/gate.yml" "$@" >/dev/null 2>&1; }
refused() { ! gate "$@"; }
AUTH='"logs_lab_authorization": "synthetic-data-only"'
check "política: por defecto (traefik) admitida" gate
check "política: perfil de laboratorio admitido" gate -e "@${PROFILE}"
check "política: adopción en operación (cuatro con decisión de privacidad, sin perfil) admitida" gate "${ADOPT[@]}"
for s in backend keycloak postgres; do
  check "política: ${s} sin decisión ni perfil rechazado" refused -e "{\"obs_log_services\": [\"traefik\", \"${s}\"]}"
done
check "política: decisión parcial (solo backend) con los cuatro rechazada" \
  refused -e "{\"obs_log_services\": ${FOUR}, \"obs_log_privacy_controls_done\": [\"backend\"]}"
for v in true '"yes"' '"synthetic"' '"Synthetic-Data-Only"'; do
  check "política: autorización con otro valor (${v}) rechazada" refused -e "{\"logs_lab_authorization\": ${v}, \"obs_log_services\": ${FOUR}}"
done
for s in migrate grafana loki 'traefik|.*'; do
  check "política: ${s} rechazado aun con autorización" refused -e "{${AUTH}, \"obs_log_services\": [\"${s}\"]}"
done
check "política: migrate rechazado aun con 'decisión de privacidad'" \
  refused -e '{"obs_log_services": ["migrate"], "obs_log_privacy_controls_done": ["migrate"]}'
check "política: lista no válida (cadena) rechazada" refused -e "{${AUTH}, \"obs_log_services\": \"backend\"}"

# ── 5. Coherencia ───────────────────────────────────────────────────────────
check "coherencia: converge y observability aplican la misma tarea de política (log_policy.yml)" \
  bash -c "test \"\$(yq -r '.[] | select(.name == \"Verificar la política de logs de los servicios\") | .\"ansible.builtin.include_role\" | .name + \"/\" + .tasks_from' '${ANSIBLE_DIR}/roles/app/tasks/setup.yml')\" = observability/log_policy.yml && \
    test \"\$(yq -r '.[] | select(.name == \"Comprobar la política de fuentes de logs\") | .\"ansible.builtin.include_tasks\"' '${ANSIBLE_DIR}/roles/observability/tasks/logs_preflight.yml')\" = log_policy.yml"
check "coherencia: una sola lista (obs_log_services) para activar y seleccionar: env.j2 la usa y no hay otra" \
  bash -c "grep -q 'svc in obs_log_services' '${ANSIBLE_DIR}/roles/app/templates/env.j2' && ! grep -rq 'app_journald_services' '${ANSIBLE_DIR}' '${ROOT}/infra/compose' '${ROOT}/docs'"
check "coherencia: valor por defecto igual en el inventario y en el rol (traefik)" \
  test "$(yq -o=json -I=0 '.obs_log_services' "${GROUP_VARS}")" = "$(yq -o=json -I=0 '.obs_log_services' "${OBS_DEFAULTS}")"
check "coherencia: env.j2 cubre exactamente admitidos + condicionados" \
  test "$(grep -oE "\{% for svc in \[[^]]*\]" "${ANSIBLE_DIR}/roles/app/templates/env.j2" | grep -oE "'[a-z]+'" | tr -d "'" | sort | tr '\n' ,)" = \
       "$(yq -r '(.obs_log_services_allowed + .obs_log_services_conditional) | sort | join(",")' "${OBS_DEFAULTS}"),"
check "coherencia: el perfil solo trae la autorización y la lista (no toca la decisión de privacidad)" \
  test "$(yq -r 'keys | sort | join(",")' "${PROFILE}")" = logs_lab_authorization,obs_log_services
check "coherencia: ninguna decisión de privacidad tomada (vacía en inventario y rol)" \
  test "$(yq -r '.obs_log_privacy_controls_done | length' "${GROUP_VARS}")/$(yq -r '.obs_log_privacy_controls_done | length' "${OBS_DEFAULTS}")" = 0/0
CALLS="$(yq -o=json '[.. | select(has("community.docker.docker_compose_v2")) | .["community.docker.docker_compose_v2"]]' "${ANSIBLE_DIR}"/roles/app/tasks/*.yml | jq -s 'add')"
check "coherencia: todas las llamadas de Compose del rol app ($(jq length <<< "${CALLS}")) usan solo prod.yml con ../.env e ../image.env" \
  jq -e 'length > 0 and all(.files == ["prod.yml"] and .env_files == ["../.env", "../image.env"])' <<< "${CALLS}"

# ── 6. Docker ───────────────────────────────────────────────────────────────
# Proyecto mínimo con el logging.yml real: lo mismo que hace prod.yml con cada servicio
mkdir -p "${TMP}/rt"
cp "${ROOT}/infra/compose/logging.yml" "${TMP}/rt/"
cat > "${TMP}/rt/compose.yml" <<'EOF'
name: pzlogcontract
services:
  implicit:
    image: busybox:1.36
    command: ["true"]
  def:
    image: busybox:1.36
    command: ["true"]
    extends: {file: ./logging.yml, service: default}
  jd:
    image: busybox:1.36
    command: ["true"]
    extends: {file: ./logging.yml, service: journald}
EOF
if [ "${RUNTIME:-1}" = 1 ] && docker info >/dev/null 2>&1; then
  docker image inspect busybox:1.36 >/dev/null 2>&1 || docker pull -q busybox:1.36 >/dev/null 2>&1
  RT=(docker compose --project-directory "${TMP}/rt" --file "${TMP}/rt/compose.yml")
  CLEANUP+=("${RT[*]} down --remove-orphans")
  "${RT[@]}" create >/dev/null 2>&1
  lc() { docker inspect -f '{{json .HostConfig.LogConfig}}' "pzlogcontract-$1-1" 2>/dev/null; }
  check "Docker: default = configuración implícita del daemon (mismo driver y opciones, sin mode ni buffer)" \
    bash -c "test -n \"\$1\" && test \"\$1\" = \"\$2\"" _ "$(lc implicit)" "$(lc def)"
  check "Docker: journald con las opciones del contrato" \
    test "$(lc jd | jq -cS '.Config')" = "$(jq -cS '.options' <<< "${EXPECTED}")"
  if "${RT[@]}" start jd > "${TMP}/journald.out" 2>&1; then
    ok "Docker: este host tiene journald y el contenedor arranca"
  elif grep -q 'journald is not enabled on this host' "${TMP}/journald.out"; then
    ok "Docker: sin journald (Docker Desktop) el contenedor journald no arranca: error visible"
  else
    ko "Docker: journald: resultado inesperado"; sed 's/^/      /' "${TMP}/journald.out"
  fi
  if [ "${DIND:-0}" = 1 ]; then
    D="pzlogcontract-dind-$$"
    CLEANUP+=("docker rm -f ${D}")
    docker run -d --rm --privileged --name "${D}" -v "${TMP}/rt:/rt:ro" docker:28-dind \
      --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 >/dev/null
    for _ in $(seq 1 30); do docker exec "${D}" docker info >/dev/null 2>&1 && break; sleep 1; done
    docker exec "${D}" sh -c 'docker pull -q busybox:1.36 >/dev/null && docker compose --project-directory /rt -f /rt/compose.yml create implicit def >/dev/null 2>&1'
    dl() { docker exec "${D}" docker inspect -f '{{json .HostConfig.LogConfig}}' "pzlogcontract-$1-1" 2>/dev/null; }
    check "DIND (daemon json-file 10m x 3): default hereda la rotación y queda en modo blocking (sin mode)" \
      test "$(dl def | jq -cS .)" = '{"Config":{"max-file":"3","max-size":"10m"},"Type":"json-file"}'
    check "DIND: default es idéntico al contenedor sin configuración (comportamiento anterior)" \
      test "$(dl def)" = "$(dl implicit)"
  fi
else
  echo "SKIP  Docker (RUNTIME=0 o sin daemon)"
fi

echo
if [ "${FAILS}" -eq 0 ]; then
  echo "Contrato de los productores de logs: OK"
else
  echo "Contrato de los productores de logs: ${FAILS} fallo(s)"
  exit 1
fi
