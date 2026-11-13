#!/usr/bin/env bash
# Ensayo del procedimiento de reconciliación de logs (infra/ops/reconcile-container-logging.sh) sobre
# un stack DESECHABLE con datos sintéticos. No toca producción, ni el .env ni los secretos reales,
# ni el daemon de quien lo corre.
#
# El stack imita lo que importa del de producción: un postgres real (misma imagen base fijada por
# digest) con volumen nombrado y esquema `keycloak` sintético, y un contenedor que hace de Keycloak
# (sin Keycloak: su arranque real lo cubre el harness del ciclo técnico) con un healthcheck que cada
# escenario controla. Los contenedores "preexistentes" se crean sin las opciones de rotación (el
# estado del servidor real). Un daemon de desarrollo no puede declarar daemon.json, así que los
# valores por defecto del daemon se simulan con un override de Compose (daemon-defaults.yml) que
# solo participa en la reconciliación, y la sonda recibe sus mismas opciones (RECONCILE_PROBE_EXTRA,
# solo pruebas): lo que se ensaya es la secuencia, las salvaguardas y las verificaciones, no el
# daemon real de producción.
#
# Cubre: lectura sin efectos, autorización (id del plan, reconocimiento), recreación con volumen y
# datos intactos, omisión de lo ya conforme, repetición, continuación tras una interrupción,
# paradas por estado ambiguo (sin cambios), inyección de fallos (pg_dump, recreación, healthcheck) y
# configuración de Compose con cambios pendientes ajenos a los logs (rechazada antes de mutar).
#
# Uso: infra/tests/reconcile-container-logging-check.sh
# Requiere: docker (compose v2), python3
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="${ROOT}/infra/ops/reconcile-container-logging.sh"
IMAGE="$(sed -n 's/^FROM \(postgres:[^ ]*\).*/\1/p' "${ROOT}/infra/postgres/Dockerfile" | head -1)"
REF="postgres@${IMAGE#*@}"

for bin in docker python3; do command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }; done
docker info > /dev/null 2>&1 || { echo "el daemon de Docker no responde" >&2; exit 2; }
docker image inspect "${IMAGE}" > /dev/null 2>&1 || docker pull -q "${IMAGE}" > /dev/null || { echo "falta la imagen ${IMAGE}" >&2; exit 2; }
REAL_DOCKER="$(command -v docker)"

TMP="$(mktemp -d)"
P="pzrec$$"                      # proyecto, y prefijo de todo lo que crea la prueba
APP="${TMP}/app"
CANARY="pz-secret-canary-$$-$(date +%s)"
PG="${P}-pg"; KC="${P}-kc"; BACKEND="${P}-backend"

cleanup() {
  docker ps -aq --filter "label=com.docker.compose.project=${P}" | xargs -r docker rm -f > /dev/null 2>&1 || true
  docker ps -aq --filter "label=pz-test=${P}" | xargs -r docker rm -f > /dev/null 2>&1 || true
  docker volume rm -f "${P}_pgdata" "${P}_pgdata_other" > /dev/null 2>&1 || true
  docker network rm "${P}_default" > /dev/null 2>&1 || true
  rm -rf "${TMP}"
}
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }

mkdir -p "${APP}/compose" "${TMP}/bin" "${TMP}/flag"
cat > "${APP}/compose/prod.yml" << 'EOF'
name: ${PROJECT_NAME}
services:
  postgres:
    image: ${POSTGRES_IMAGE}
    container_name: ${PZ_PG}
    restart: unless-stopped
    environment:
      POSTGRES_USER: postgres
      POSTGRES_DB: piedrazul_db
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      KC_DB_USERNAME: keycloak_user
      APP_DB_USERNAME: app_user
      MIGRATION_DB_USERNAME: migration_user
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d piedrazul_db"]
      interval: 2s
      timeout: 3s
      retries: 20
  keycloak:
    image: ${KEYCLOAK_IMAGE}
    container_name: ${PZ_KC}
    restart: unless-stopped
    # /flag/unhealthy (solo lectura aquí) hace que un contenedor NUEVO no llegue a estar sano
    entrypoint: ["sh", "-c", "[ -e /flag/unhealthy ] || touch /tmp/ready; sleep 3600"]
    volumes:
      - ${PZ_FLAGDIR}:/flag:ro
    environment:
      KC_DB_SCHEMA: keycloak
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "${PZ_KC_HEALTH}"]
      interval: 2s
      timeout: 2s
      retries: 2
volumes:
  pgdata:
    name: ${PZ_PGDATA}
EOF
# Los valores por defecto del daemon de producción, simulados (ver la cabecera)
cat > "${APP}/compose/daemon-defaults.yml" << 'EOF'
services:
  postgres: {logging: {driver: json-file, options: {max-size: 10m, max-file: "3"}}}
  keycloak: {logging: {driver: json-file, options: {max-size: 10m, max-file: "3"}}}
EOF
write_env() { # <comando de salud de keycloak>
  printf 'PROJECT_NAME=%s\nPOSTGRES_PASSWORD=%s\nPZ_PG=%s\nPZ_KC=%s\nPZ_KC_HEALTH=%s\nPZ_PGDATA=%s\nPZ_FLAGDIR=%s\n' "${P}" "${CANARY}" "${PG}" "${KC}" "$1" "${PZ_PGDATA_NAME:-${P}_pgdata}" "${TMP}/flag" > "${APP}/.env"
}
write_image_env() { printf 'POSTGRES_IMAGE=%s\nKEYCLOAK_IMAGE=%s\n' "${REF}" "${1:-${REF}}" > "${APP}/image.env"; }
printf '{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"},"live-restore":true}' > "${TMP}/daemon.json"

# docker con fallos inyectables (solo para esta prueba). Se usa en TODA ejecución del procedimiento:
# además simula lo que en producción es cierto y aquí no, porque la rotación del daemon se simula con un
# override de Compose (daemon-defaults.yml) y Compose SÍ incluye `logging` en el hash del servicio: en
# producción el daemon la aporta fuera de Compose y el hash de un contenedor sin rotación no cambia. Por
# eso un dry-run de UN servicio cuyo contenedor aún no tiene rotación se hace sin ese override: el hash
# coincide con el contenedor existente y solo una deriva real de la configuración lo altera.
export PZ_REAL_DOCKER="${REAL_DOCKER}" PZ_PGN="${PG}" PZ_KCN="${KC}" PZ_APP="${APP}"
cat > "${TMP}/bin/docker" << 'EOF'
#!/usr/bin/env bash
real="${PZ_REAL_DOCKER}"
all=" $* "
nonconf() { ! "${real}" inspect "$1" --format '{{json .HostConfig.LogConfig.Config}}' 2> /dev/null | grep -q max-size; }
if [ -n "${PZ_FAIL_PGDUMP:-}" ] && [[ "${all}" == *" pg_dump "* ]]; then echo "fallo simulado de pg_dump" >&2; exit 1; fi
if [ -n "${PZ_FAIL_PG_RECREATE:-}" ] && [[ "${all}" == *" --force-recreate "* ]] && [[ "${all}" == *" postgres "* ]]; then echo "fallo simulado de la recreación" >&2; exit 1; fi
# la configuración cambia entre la comprobación previa y la recreación de postgres
if [ -n "${PZ_SWAP_ENV:-}" ] && [[ "${all}" == *" --force-recreate "* ]] && [[ "${all}" == *" postgres "* ]]; then cp "${PZ_SWAP_ENV}" "${PZ_APP}/.env"; fi
if [[ "${all}" == *" --dry-run "* ]]; then
  phase=post; nonconf "${PZ_PGN}" && phase=pre   # pre: antes de recrear postgres; post: la comprobación final
  if [ "${PZ_FAIL_DRYRUN:-}" = "${phase}" ]; then echo "fallo simulado del dry-run" >&2; exit 1; fi
  if [ "${PZ_EMPTY_DRYRUN:-}" = "${phase}" ]; then exit 0; fi
  case "${!#}" in postgres) c="${PZ_PGN}" ;; keycloak) c="${PZ_KCN}" ;; *) c="" ;; esac
  if [ -n "${c}" ] && nonconf "${c}"; then
    args=("$@"); out=()
    for ((i = 0; i < ${#args[@]}; i++)); do
      if [ "${args[i]}" = "-f" ] && [ "${args[i + 1]:-}" = "${PZ_APP}/compose/daemon-defaults.yml" ]; then i=$((i + 1)); continue; fi
      out+=("${args[i]}")
    done
    exec "${real}" "${out[@]}"
  fi
fi
exec "${real}" "$@"
EOF
chmod +x "${TMP}/bin/docker"

# Compose como lo invocaría el procedimiento
dcp() { # <archivos compose separados por espacio> -- args
  local files=()
  while [ "$1" != "--" ]; do files+=(-f "${APP}/compose/$1"); shift; done; shift
  docker compose --project-directory "${APP}/compose" -p "${P}" "${files[@]}" --env-file "${APP}/.env" --env-file "${APP}/image.env" "$@"
}
PRE=(prod.yml)                         # como se crearon los contenedores preexistentes
POST=(prod.yml daemon-defaults.yml)    # con los valores por defecto del daemon (simulados)

wait_healthy() { # <contenedor> [segundos]
  for _ in $(seq 1 "${2:-90}"); do
    [ "$(docker inspect "$1" --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' 2> /dev/null)" = healthy ] && return 0
    sleep 1
  done
  return 1
}
psqlx() { docker exec --user postgres "${PG}" psql --no-psqlrc --quiet --tuples-only --no-align -U postgres -d piedrazul_db -c "$1"; }
idof() { docker inspect "$1" --format '{{.Id}}' 2> /dev/null || echo absent; }
confof() { docker inspect "$1" --format '{{json .HostConfig.LogConfig.Config}}' 2> /dev/null; }
volcreated() { docker volume inspect "${P}_pgdata" --format '{{.CreatedAt}}'; }

# Estado de partida: contenedores creados SIN rotación, con datos sintéticos
reset_stack() { # [comando de salud de keycloak]
  dcp "${PRE[@]}" -- down -v --remove-orphans > /dev/null 2>&1 || true
  docker ps -aq --filter "label=pz-test=${P}" | xargs -r docker rm -f > /dev/null 2>&1 || true
  write_env "${1:-test -f /tmp/ready}"; write_image_env
  rm -rf "${TMP}/backups"
  dcp "${PRE[@]}" -- up -d --wait --wait-timeout 120 > /dev/null 2>&1 || { echo "no se pudo crear el stack de prueba" >&2; exit 2; }
  psqlx "create role keycloak_user; create role app_user; create role migration_user;
         create schema keycloak; create table keycloak.realm(id text); create table keycloak.user_entity(id text); create table keycloak.client(id text);
         insert into keycloak.realm values ('master'),('piedrazul'); insert into keycloak.user_entity values ('u1'),('u2'),('u3');
         insert into keycloak.client select 'c' || g from generate_series(1,4) g;
         create table public.reconcile_marker(v text); insert into public.reconcile_marker values ('marker-${P}')" > /dev/null
}
fingerprint() { psqlx "select (select count(*) from keycloak.realm) || '/' || (select count(*) from keycloak.user_entity) || '/' || (select count(*) from keycloak.client)"; }

# run_script <args...>; entorno adicional como VAR=valor antes de --; salida en ${TMP}/out, código en $RC
RC=0
run_script() {
  local extra=()
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --) shift; break ;;
      [A-Z]*=*) extra+=("$1"); shift ;;   # VAR=valor: entorno adicional (puede ir antes de --)
      *) break ;;
    esac
  done
  RC=0
  env RECONCILE_APP_DIR="${APP}" RECONCILE_PROJECT="${P}" RECONCILE_COMPOSE_FILES="prod.yml:daemon-defaults.yml" \
      RECONCILE_ENV_FILES=".env:image.env" RECONCILE_DAEMON_JSON="${TMP}/daemon.json" RECONCILE_IMAGE_ENV="${APP}/image.env" \
      RECONCILE_PG_SERVICE=postgres RECONCILE_PG_CONTAINER="${PG}" RECONCILE_KC_SERVICE=keycloak RECONCILE_KC_CONTAINER="${KC}" \
      RECONCILE_BACKEND_CONTAINER="${BACKEND}" RECONCILE_BACKUP_DIR="${TMP}/backups" RECONCILE_LOCK_FILE="${TMP}/db.lock" \
      RECONCILE_PG_WAIT_TIMEOUT=120 RECONCILE_KC_WAIT_TIMEOUT=60 \
      RECONCILE_PROBE_EXTRA="--log-driver json-file --log-opt max-size=10m --log-opt max-file=3" \
      PATH="${TMP}/bin:${PATH}" "${extra[@]}" bash -s -- "$@" < "${SCRIPT}" > "${OUT_FILE:-${TMP}/out}" 2>&1 || RC=$?   # como lo ejecuta el procedimiento: el script por stdin
  cat "${OUT_FILE:-${TMP}/out}" >> "${TMP}/all.out"
}
out_has() { grep -qF -- "$1" "${TMP}/out"; }
plan_of() { sed -n 's/^RESULTADO: plan \([0-9a-f]\{12\}\).*/\1/p' "${TMP}/out" | head -1; }
conforming() { [ "$(confof "$1")" = '{"max-file":"3","max-size":"10m"}' ]; }
plan_now() { run_script check; plan_of; }

# ── 1. Estado de partida y lectura sin efectos ─────────────────────────────────────────────────
reset_stack
if conforming "${PG}" || conforming "${KC}"; then ko "el estado de partida debía ser sin rotación"; else ok "estado de partida: postgres y keycloak creados sin rotación (como en el servidor)"; fi
PG0="$(idof "${PG}")"; KC0="$(idof "${KC}")"; VOL0="$(volcreated)"; FP0="$(fingerprint)"

run_script check
PLAN="$(plan_of)"
if [ "${RC}" = 0 ] && [ -n "${PLAN}" ] && out_has "Plan (initial)"; then ok "check: plan ${PLAN} (initial) calculado sobre el estado observado"; else ko "check: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi
[ "$(idof "${PG}")" = "${PG0}" ] && [ "$(idof "${KC}")" = "${KC0}" ] && [ ! -e "${TMP}/backups" ] && ok "check no cambia nada (mismos contenedores, ningún respaldo)" || ko "check cambió algo"
grep -q "${CANARY}" "${TMP}/out" && ko "check imprimió un secreto del entorno" || ok "check no imprime el entorno ni secretos"

# ── 2. Autorización: sin id, con id ajeno o sin reconocimiento no se actúa ──────────────────────
run_script apply --ack-no-active-run
{ [ "${RC}" = 2 ] && out_has "--confirm"; } && ok "apply sin --confirm se rechaza" || ko "apply sin confirm: rc=${RC}"
run_script apply --confirm deadbeef0000 --ack-no-active-run
{ [ "${RC}" = 2 ] && out_has "no corresponde al estado actual"; } && ok "apply con el id de otro estado se rechaza" || ko "apply con id ajeno: rc=${RC}"
run_script apply --confirm "${PLAN}"
{ [ "${RC}" = 2 ] && out_has "--ack-no-active-run"; } && ok "apply sin el reconocimiento de que no hay un run de producción activo se rechaza" || ko "apply sin ack: rc=${RC}"
[ "$(idof "${PG}")" = "${PG0}" ] && [ "$(idof "${KC}")" = "${KC0}" ] && ok "los rechazos no cambian nada" || ko "un rechazo cambió contenedores"

# ── 3. Reconciliación inicial ───────────────────────────────────────────────────────────────────
run_script apply --confirm "${PLAN}" --ack-no-active-run
if [ "${RC}" = 0 ] && out_has "RESULTADO: reconciliado"; then ok "apply (initial) termina bien"; else ko "apply: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-500)"; fi
{ [ "$(idof "${PG}")" != "${PG0}" ] && [ "$(idof "${KC}")" != "${KC0}" ]; } && ok "postgres y keycloak se recrearon (contenedores nuevos)" || ko "no se recrearon"
{ conforming "${PG}" && conforming "${KC}"; } && ok "ambos tienen ahora la rotación (LogConfig efectiva: $(confof "${PG}"))" || ko "LogConfig tras apply: pg=$(confof "${PG}") kc=$(confof "${KC}")"
{ [ "$(volcreated)" = "${VOL0}" ] && [ "$(fingerprint)" = "${FP0}" ] && [ "$(psqlx "select v from public.reconcile_marker")" = "marker-${P}" ]; } \
  && ok "el volumen es el mismo y los datos están intactos (conteos ${FP0} y fila marcador)" || ko "datos o volumen cambiaron"
wait_healthy "${PG}" 5 && wait_healthy "${KC}" 5 && ok "ambos healthy" || ko "no healthy tras apply"
DUMP="$(ls "${TMP}"/backups/*.dump 2> /dev/null | head -1)"
{ [ -n "${DUMP}" ] && [ "$(stat -c %a "${DUMP}")" = 600 ] && [ "$(stat -c %a "${TMP}/backups")" = 700 ]; } && ok "respaldo creado con permisos restrictivos (archivo 0600, directorio 0700)" || ko "respaldo ausente o con permisos amplios"
docker exec -i "${PG}" pg_restore --list < "${DUMP}" 2> /dev/null | grep -q "TABLE DATA keycloak realm" && ok "el respaldo es legible y contiene el esquema de Keycloak con datos" || ko "respaldo ilegible"
out_has "La restauración NO está probada" && ok "el informe no presenta el índice del respaldo como una restauración probada" || ko "falta la salvedad sobre la restauración"
grep -q "${CANARY}" "${TMP}/all.out" && ko "algún secreto sintético apareció en la salida" || ok "ningún secreto sintético apareció en la salida de toda la prueba"

# ── 4. Repetición: lo conforme no se toca; un converge normal tampoco recrea ────────────────────
PG1="$(idof "${PG}")"; KC1="$(idof "${KC}")"
run_script check
{ [ "${RC}" = 0 ] && out_has "nada que hacer"; } && ok "segundo check: nada que hacer" || ko "segundo check: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-200)"
run_script apply --confirm cualquiera --ack-no-active-run
{ [ "${RC}" = 0 ] && out_has "nada que hacer"; } && [ "$(idof "${PG}")" = "${PG1}" ] && [ "$(idof "${KC}")" = "${KC1}" ] && ok "segundo apply: no recrea nada (el criterio es el estado, no un marcador)" || ko "segundo apply recreó o falló: rc=${RC}"
dcp "${POST[@]}" -- up -d --no-deps --pull never > /dev/null 2>&1
{ [ "$(idof "${PG}")" = "${PG1}" ] && [ "$(idof "${KC}")" = "${KC1}" ]; } && ok "un converge normal (docker compose up sin --force-recreate) no recrea los contenedores ya reconciliados" || ko "un up normal recreó contenedores"

# ── 5. Continuación tras una interrupción ───────────────────────────────────────────────────────
# a) interrumpido justo después de detener Keycloak (postgres aún sin corregir)
reset_stack; PGa="$(idof "${PG}")"
docker stop "${KC}" > /dev/null
run_script check; PLANA="$(plan_of)"
{ [ "${RC}" = 0 ] && out_has "Plan (recovery)" && [ -n "${PLANA}" ]; } && ok "recovery: con Keycloak detenido y postgres sin corregir, check ofrece un plan de continuación" || ko "recovery a: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
run_script apply --confirm "${PLANA}" --ack-no-active-run
{ [ "${RC}" = 0 ] && conforming "${PG}" && conforming "${KC}" && wait_healthy "${KC}" 5 && [ "$(idof "${PG}")" != "${PGa}" ]; } && ok "recovery: la continuación completa la reconciliación con Keycloak detenido al empezar" || ko "recovery a: apply rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-400)"

# b) postgres ya recreado y Keycloak detenido: no hay respaldo ni segunda recreación de postgres
reset_stack
docker stop "${KC}" > /dev/null
dcp "${POST[@]}" -- up -d --no-deps --force-recreate --pull never --wait postgres > /dev/null 2>&1
PGb="$(idof "${PG}")"; rm -rf "${TMP}/backups"
run_script check; PLANB="$(plan_of)"
{ [ "${RC}" = 0 ] && out_has "recrear ${KC}" && ! out_has "respaldo"; } && ok "recovery: postgres ya conforme → el plan solo recrea Keycloak, sin respaldo" || ko "recovery b check: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
run_script apply --confirm "${PLANB}" --ack-no-active-run
{ [ "${RC}" = 0 ] && [ "$(idof "${PG}")" = "${PGb}" ] && [ ! -e "${TMP}/backups" ] && conforming "${KC}" && wait_healthy "${KC}" 5; } && ok "recovery: postgres no se vuelve a recrear y no se crea un respaldo innecesario" || ko "recovery b apply: rc=${RC}"

# c) ambos conformes y Keycloak solo detenido: se arranca el mismo contenedor, sin recrearlo
KCc="$(idof "${KC}")"; docker stop "${KC}" > /dev/null
run_script check; PLANC="$(plan_of)"
{ [ "${RC}" = 0 ] && out_has "arrancar ${KC} sin recrearlo"; } && ok "recovery: Keycloak conforme pero detenido → solo se arranca" || ko "recovery c check: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
run_script apply --confirm "${PLANC}" --ack-no-active-run
{ [ "${RC}" = 0 ] && [ "$(idof "${KC}")" = "${KCc}" ] && wait_healthy "${KC}" 20; } && ok "recovery: el mismo contenedor de Keycloak vuelve a correr healthy (no se recreó)" || ko "recovery c apply: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"

# ── 6. Paradas por estado ambiguo o inesperado: código 3 y nada cambia ──────────────────────────
reset_stack; PG6="$(idof "${PG}")"; KC6="$(idof "${KC}")"
unchanged() { [ "$(idof "${PG}")" = "${PG6}" ] && [ "$(idof "${KC}")" = "${KC6}" ] && [ "$(docker inspect "${KC}" --format '{{.State.Running}}')" = true ]; }
stops() { # <descripción> <texto esperado>
  if [ "${RC}" = 3 ] && out_has "$2" && unchanged; then ok "parada (escalar): $1"; else ko "parada: $1 — rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi
}

L="${P}-leftover"
docker create --name "${L}" --label "pz-test=${P}" --label "com.docker.compose.project=${P}" --label "com.docker.compose.service=postgres" "${REF}" > /dev/null
run_script check; stops "un contenedor del servicio con otro nombre (recreate interrumpido) no se interpreta" "otro nombre"
docker rm -f "${L}" > /dev/null

docker create --name "${BACKEND}" --label "pz-test=${P}" "${REF}" > /dev/null
run_script check; stops "existe un backend (depende de la base)" "existe el contenedor ${BACKEND}"
docker rm -f "${BACKEND}" > /dev/null

write_image_env "postgres@sha256:$(printf 'a%.0s' $(seq 1 64))"
run_script check; stops "Keycloak corre otra identidad que image.env" "otra identidad"
write_image_env

printf '{"log-driver":"json-file"}' > "${TMP}/daemon.json.bad"
run_script RECONCILE_DAEMON_JSON="${TMP}/daemon.json.bad" -- check; stops "daemon.json sin rotación declarada (sin objetivo)" "no declara log-driver json-file"

PLANP="$(plan_now)"
run_script RECONCILE_PROBE_EXTRA="" -- apply --confirm "${PLANP}" --ack-no-active-run
if [ "${RC}" = 3 ] && out_has "NO hereda la rotación" && unchanged && [ ! -e "${TMP}/backups" ]; then ok "parada: si un contenedor nuevo no hereda la rotación (daemon sin reiniciar), no se recrea nada ni se detiene Keycloak"; else ko "sonda: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi
docker ps -aq --filter "label=pz-reconcile-probe=1" | grep -q . && ko "quedó el contenedor de la sonda" || ok "la sonda no deja contenedores"

psqlx "delete from keycloak.realm" > /dev/null
run_script check; stops "el esquema de Keycloak no tiene realms (no se arranca Keycloak sobre una base sin inicializar)" "no tiene realms"
psqlx "insert into keycloak.realm values ('master'),('piedrazul')" > /dev/null

PLANW="$(plan_now)"
run_script RECONCILE_BACKUP_DIR=/proc/no-se-puede-escribir -- apply --confirm "${PLANW}" --ack-no-active-run
if [ "${RC}" = 2 ] && out_has "no se puede crear" && unchanged; then ok "sin dónde escribir el respaldo se rechaza ANTES de detener Keycloak"; else ko "respaldo no escribible: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi

docker exec "${KC}" rm -f /tmp/ready
for _ in $(seq 1 30); do [ "$(docker inspect "${KC}" --format '{{.State.Health.Status}}')" = unhealthy ] && break; sleep 1; done
run_script check
{ [ "${RC}" = 3 ] && out_has "su salud es 'unhealthy'"; } && ok "parada (escalar): Keycloak corriendo pero unhealthy no se interpreta" || ko "keycloak unhealthy: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
docker exec "${KC}" touch /tmp/ready; wait_healthy "${KC}" 30 || true

docker rm -f "${KC}" > /dev/null
run_script check
{ [ "${RC}" = 3 ] && out_has "no existe el contenedor ${KC}"; } && ok "parada (escalar): Keycloak ausente no se crea desde aquí (converge clasifica P1/P2/P3)" || ko "keycloak ausente: rc=${RC}"
docker rm -f "${PG}" > /dev/null
run_script check
{ [ "${RC}" = 3 ] && out_has "no existe el contenedor ${PG}"; } && ok "parada (escalar): postgres ausente no se crea ni se reemplaza por una base nueva" || ko "postgres ausente: rc=${RC}"
docker volume inspect "${P}_pgdata" > /dev/null 2>&1 && ok "el volumen de datos sigue existiendo tras todas las paradas" || ko "el volumen desapareció"

# ── 7. Fallos inyectados durante apply ──────────────────────────────────────────────────────────
# a) pg_dump falla tras detener Keycloak: no se recrea postgres y Keycloak vuelve a correr
reset_stack; PG7="$(idof "${PG}")"
run_script check; PLAN7="$(plan_of)"
run_script PZ_FAIL_PGDUMP=1 -- apply --confirm "${PLAN7}" --ack-no-active-run
{ [ "${RC}" = 1 ] && out_has "pg_dump falló" && [ "$(idof "${PG}")" = "${PG7}" ] && wait_healthy "${KC}" 20; } \
  && ok "si el respaldo falla se aborta antes de recrear y Keycloak (que se detuvo para esto) vuelve a correr" || ko "fallo de pg_dump: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-400)"
ls "${TMP}"/backups/*.dump > /dev/null 2>&1 && ko "quedó un respaldo parcial" || ok "no queda un respaldo parcial"

# b) la recreación de postgres falla: Keycloak queda detenido, check ofrece continuar y apply la completa
run_script check; PLAN7="$(plan_of)"
run_script PZ_FAIL_PG_RECREATE=1 -- apply --confirm "${PLAN7}" --ack-no-active-run
{ [ "${RC}" = 1 ] && [ "$(idof "${PG}")" = "${PG7}" ] && [ "$(docker inspect "${KC}" --format '{{.State.Running}}')" = false ]; } \
  && ok "si la recreación de postgres falla: la base no se tocó, Keycloak queda detenido y el mensaje lo dice" || ko "fallo de la recreación: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
run_script check; PLAN7B="$(plan_of)"
{ [ "${RC}" = 0 ] && out_has "Plan (recovery)"; } && ok "tras el fallo, check reconoce la continuación (recovery)" || ko "check tras el fallo: rc=${RC}"
run_script apply --confirm "${PLAN7B}" --ack-no-active-run
{ [ "${RC}" = 0 ] && conforming "${PG}" && conforming "${KC}" && [ "$(fingerprint)" = "${FP0}" ]; } && ok "la continuación completa la reconciliación con los datos intactos" || ko "continuación tras el fallo: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"

# c) healthcheck de Keycloak que no pasa tras recrearlo: falla cerrado; la base queda verificada; no se interpreta
reset_stack
PLAN7C="$(plan_now)"
touch "${TMP}/flag/unhealthy"   # el Keycloak actual ya está sano; el contenedor NUEVO no llegará a estarlo
run_script apply --confirm "${PLAN7C}" --ack-no-active-run
{ [ "${RC}" = 1 ] && out_has "${KC} no quedó healthy" && out_has "La base está intacta" && conforming "${PG}" && [ "$(fingerprint)" = "${FP0}" ]; } \
  && ok "un Keycloak recreado que no pasa su healthcheck falla cerrado; la base queda recreada, verificada y con los datos intactos" || ko "healthcheck: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-400)"
run_script check
{ [ "${RC}" = 0 ] && out_has "nada que hacer" && out_has "salud=unhealthy" && out_has "no repara ni interpreta la salud"; } \
  && ok "y un nuevo check no lo repara ni lo interpreta: logs conformes → nada que hacer, con la salud real a la vista (el diagnóstico es de converge / el operador)" || ko "check tras healthcheck fallido: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
rm -f "${TMP}/flag/unhealthy"

# ── 8. Entrega por stdin y ejecución concurrente ────────────────────────────────────────────────
# a) un script truncado (conexión caída o transferencia cortada) no ejecuta un prefijo: es un error de
#    sintaxis antes de cualquier acción
reset_stack; PG8="$(idof "${PG}")"; KC8="$(idof "${KC}")"
PLAN8="$(plan_now)"
head -c "$(( $(stat -c %s "${SCRIPT}") * 6 / 10 ))" "${SCRIPT}" > "${TMP}/truncated.sh"
SCRIPT_REAL="${SCRIPT}"; SCRIPT="${TMP}/truncated.sh"
run_script apply --confirm "${PLAN8}" --ack-no-active-run
SCRIPT="${SCRIPT_REAL}"
if [ "${RC}" != 0 ] && [ "$(idof "${PG}")" = "${PG8}" ] && [ "$(idof "${KC}")" = "${KC8}" ] \
    && [ "$(docker inspect "${KC}" --format '{{.State.Running}}')" = true ] && [ ! -e "${TMP}/backups" ]; then
  ok "un script truncado falla antes de ejecutar nada (rc=${RC}): ni se detuvo Keycloak ni se recreó ni se respaldó"
else ko "script truncado: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi

# b) dos apply con el mismo id a la vez: solo uno actúa; el otro ve el estado ya cambiado o no obtiene el lock
rm -f "${TMP}/rc1" "${TMP}/rc2"
( OUT_FILE="${TMP}/o1" run_script apply --confirm "${PLAN8}" --ack-no-active-run; echo "${RC}" > "${TMP}/rc1" ) &
sleep 1
( OUT_FILE="${TMP}/o2" run_script apply --confirm "${PLAN8}" --ack-no-active-run; echo "${RC}" > "${TMP}/rc2" ) &
wait
R1="$(cat "${TMP}/rc1")"; R2="$(cat "${TMP}/rc2")"
NDUMPS="$(ls "${TMP}"/backups/*.dump 2> /dev/null | wc -l)"
cat "${TMP}/o1" "${TMP}/o2" > "${TMP}/o12"
if { [ "${R1}" = 0 ] || [ "${R2}" = 0 ]; } && [ "${NDUMPS}" = 1 ] && conforming "${PG}" && conforming "${KC}" \
    && grep -qE "nada que hacer|no se obtuvo el lock|docker compose en ejecución|no corresponde al estado actual" "${TMP}/o12"; then
  ok "dos apply simultáneos con el mismo id: uno actúa (rc=${R1}) y el otro no repite nada (rc=${R2}; un solo respaldo)"
else ko "apply concurrentes: rc1=${R1} rc2=${R2} respaldos=${NDUMPS} / $(tr '\n' ' ' < "${TMP}/o12" | cut -c1-300)"; fi

# ── 9. Verificaciones reforzadas tras la revisión ───────────────────────────────────────────────
# a) un volumen de reemplazo inesperado se rechaza: la identidad del volumen se conserva ANTES de recrear
#    y se compara con lo que monta el contenedor nuevo (no consigo mismo)
reset_stack; VOL9="$(volcreated)"
PLAN9="$(plan_now)"
PZ_PGDATA_NAME="${P}_pgdata_other" write_env "test -f /tmp/ready"; cp "${APP}/.env" "${TMP}/env.other"
write_env "test -f /tmp/ready"
# la configuración cambia DESPUÉS de la comprobación previa (que la ve limpia): el contenedor nuevo montará OTRO volumen
run_script PZ_SWAP_ENV="${TMP}/env.other" -- apply --confirm "${PLAN9}" --ack-no-active-run
write_env "test -f /tmp/ready"
if [ "${RC}" = 1 ] && out_has "no montó el volumen de datos original" && out_has "${P}_pgdata_other" \
    && docker volume inspect "${P}_pgdata" > /dev/null 2>&1 && [ "$(volcreated)" = "${VOL9}" ] \
    && [ "$(docker inspect "${KC}" --format '{{.State.Running}}')" = false ]; then
  ok "un volumen de reemplazo inesperado se rechaza (rc=1), el volumen original sigue intacto y Keycloak sigue detenido"
else ko "volumen de reemplazo: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-400)"; fi
docker rm -f "${PG}" > /dev/null 2>&1; docker volume rm -f "${P}_pgdata_other" > /dev/null 2>&1 || true

# b) el dry-run final fracasa o no da evidencia: no se informa como éxito
reset_stack; PLAN9="$(plan_now)"
run_script PZ_FAIL_DRYRUN=post -- apply --confirm "${PLAN9}" --ack-no-active-run
if [ "${RC}" = 1 ] && out_has "el dry-run de Compose falló" && ! out_has "RESULTADO: reconciliado" && conforming "${PG}" && conforming "${KC}"; then
  ok "un dry-run de Compose que falla (código ≠ 0) no se informa como éxito; el mensaje dice que la reconciliación ya se aplicó y manda a check"
else ko "dry-run con fallo: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-400)"; fi

reset_stack; PLAN9="$(plan_now)"
run_script PZ_EMPTY_DRYRUN=post -- apply --confirm "${PLAN9}" --ack-no-active-run
if [ "${RC}" = 1 ] && out_has "no informa ${PG} como" && ! out_has "RESULTADO: reconciliado"; then
  ok "un dry-run con código 0 pero sin evidencia de «Running» tampoco se informa como éxito"
else ko "dry-run vacío: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-400)"; fi

# c) un script con finales de línea CRLF (p. ej. copiado por PowerShell) se rechaza antes de actuar
reset_stack; PG9="$(idof "${PG}")"; KC9="$(idof "${KC}")"; PLAN9="$(plan_now)"
sed 's/$/\r/' "${SCRIPT}" > "${TMP}/crlf.sh"
SCRIPT_REAL="${SCRIPT}"; SCRIPT="${TMP}/crlf.sh"
run_script apply --confirm "${PLAN9}" --ack-no-active-run
SCRIPT="${SCRIPT_REAL}"
if [ "${RC}" != 0 ] && [ "$(idof "${PG}")" = "${PG9}" ] && [ "$(idof "${KC}")" = "${KC9}" ] \
    && [ "$(docker inspect "${KC}" --format '{{.State.Running}}')" = true ] && [ ! -e "${TMP}/backups" ]; then
  ok "un script con CRLF falla antes de ejecutar nada (rc=${RC}): ni se detuvo Keycloak ni se recreó ni se respaldó"
else ko "script CRLF: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi

# ── 10. Configuración de Compose con cambios pendientes ajenos a los logs ───────────────────────────
# --force-recreate aplicaría TODA la configuración pendiente del servicio. Check y apply (con el lock, antes
# de detener o respaldar nada) lo rechazan con código 3; el id del plan sigue siendo válido a propósito:
# lo que prueba el apply es que la comprobación se repite.
drift_pg() { ( CANARY="${CANARY}-drift"; write_env "test -f /tmp/ready" ); }   # cambia el entorno de postgres
drift_kc() { write_env "test -f /tmp/ready && true"; }                         # cambia el healthcheck de keycloak
untouched() { # <descripción> <texto esperado> <¿Keycloak corriendo?>
  if [ "${RC}" = 3 ] && out_has "$2" && ! out_has "RESULTADO: plan" && ! out_has "Deteniendo" \
      && [ "$(idof "${PG}")" = "${PG10}" ] && [ "$(idof "${KC}")" = "${KC10}" ] \
      && [ "$(docker inspect "${KC}" --format '{{.State.Running}}')" = "$3" ] && [ ! -e "${TMP}/backups" ]; then
    ok "$1"
  else ko "$1 — rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"; fi
}

for who in pg kc; do
  reset_stack; PG10="$(idof "${PG}")"; KC10="$(idof "${KC}")"
  PLAN10="$(plan_now)"
  [ -n "${PLAN10}" ] && ok "sin deriva: check ofrece el plan (la comprobación previa no da falsos positivos sobre contenedores sin rotación)" || ko "check sin deriva: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
  "drift_${who}"
  run_script check; untouched "deriva en ${who}: check la rechaza y no emite plan" "la configuración del servicio cambió" true
  run_script apply --confirm "${PLAN10}" --ack-no-active-run
  untouched "deriva en ${who} tras emitir el plan: apply la rechaza antes de detener Keycloak, respaldar o recrear nada" "la configuración del servicio cambió" true
  write_env "test -f /tmp/ready"
done

# recovery: Keycloak detenido (puede arrancar), pero su deriva de configuración no se acepta en silencio
reset_stack; docker stop "${KC}" > /dev/null; PG10="$(idof "${PG}")"; KC10="$(idof "${KC}")"
run_script check; PLAN10="$(plan_of)"   # no plan_now: corre en un subshell y RC quedaría del paso anterior
{ [ "${RC}" = 0 ] && out_has "Plan (recovery)"; } && ok "recovery sin deriva: Keycloak detenido solo necesita arrancar y se acepta" || ko "recovery sin deriva: rc=${RC} $(tr '\n' ' ' < "${TMP}/out" | cut -c1-300)"
drift_kc
run_script check; untouched "recovery con deriva en Keycloak detenido: check la rechaza" "la configuración del servicio cambió" false
run_script apply --confirm "${PLAN10}" --ack-no-active-run
untouched "recovery con deriva en Keycloak detenido: apply la rechaza y deja todo como estaba" "la configuración del servicio cambió" false
write_env "test -f /tmp/ready"

# falla cerrado: un Compose que falla, o que no informa, no se toma por «sin cambios pendientes»
reset_stack; PG10="$(idof "${PG}")"; KC10="$(idof "${KC}")"
run_script PZ_FAIL_DRYRUN=pre -- check; untouched "un dry-run de Compose que falla antes de mutar escala (código 3) sin cambiar nada" "el dry-run de Compose falló" true
run_script PZ_EMPTY_DRYRUN=pre -- check; untouched "un dry-run sin evidencia antes de mutar escala (código 3) sin cambiar nada" "no informa ${PG} como" true

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "${FAILS} comprobación(es) fallaron"
  exit 1
fi
echo "Todo correcto"
