#!/usr/bin/env bash
# Reconciliación ÚNICA y explícitamente autorizada de la configuración de logs de postgres y keycloak.
#
# Para qué: un contenedor toma su LogConfig AL CREARSE. Si se creó antes de que el daemon corriera con
# /etc/docker/daemon.json (rotación json-file), conserva su configuración anterior sin rotación;
# Compose no lo recrea porque su hash no cambió y la verificación de producción
# (roles/app/tasks/verify_log_config.yml) falla mientras no se corrija. Esto recrea solo esos dos
# contenedores, sin tocar sus datos. Procedimiento completo: docs/operations/runbooks/reconciliar-logs-contenedores.md
#
# NO es parte de ningún workflow, converge ni deploy: lo ejecuta una persona autorizada, en el host.
#
# Uso (en el host, como root: Compose lee /opt/piedrazul/.env, que es root:root 0600):
#   reconciliar-logs.sh check                                          solo lectura: estado, plan y su id
#   reconciliar-logs.sh apply --confirm <id del plan> --ack-no-active-run
# Desde un puesto de trabajo:
#   ssh <host> 'sudo bash -s -- check' < infra/ops/reconcile-container-logging.sh
#
# `check` no cambia nada. `apply` exige el id que `check` calculó sobre el estado observado (si el
# estado cambió entre los dos, el id ya no corresponde y no se actúa) y el reconocimiento explícito
# de que ningún run de producción está en curso. No hay marcador de "ya hecho": el criterio de
# repetición es el estado: un contenedor cuya configuración de logs ya es conforme no se toca.
#
# Orden (cada paso se verifica antes del siguiente):
#   0. (check y apply, antes de cualquier cambio) Compose no debe tener otro cambio pendiente en esos
#      dos servicios: --force-recreate aplicaría toda su configuración, no solo los logs
#   1. sonda de los valores por defecto del daemon (un contenedor creado sin arrancar y borrado)
#   2. detiene Keycloak (escrituras en vuelo y membresía de clúster terminan limpias)
#   3. respaldo lógico de la base + validación del archivo (NO prueba una restauración)
#   4. recrea postgres (volumen conservado) y verifica volumen, imagen, salud, logs y datos
#   5. arranca o recrea Keycloak y verifica imagen, salud y logs
#   6. comprueba que Compose ya no tiene nada que recrear (un converge posterior no recreará)
# Ante cualquier estado ambiguo o inesperado se detiene con código 3 (escalar), sin reparar nada.
# Nunca borra volúmenes, restaura, ni limpia automáticamente; no imprime variables de entorno,
# contraseñas ni el contenido del respaldo.
#
# Códigos: 0 correcto (o nada que hacer) · 1 falló durante apply · 2 uso/precondición no cumplida
#          · 3 estado ambiguo o inesperado: detenerse y escalar
#
# Variables (por defecto: producción; las demás solo las usan las pruebas):
#   RECONCILE_APP_DIR, RECONCILE_PROJECT, RECONCILE_COMPOSE_FILES (relativos a APP_DIR/compose, separados por :),
#   RECONCILE_ENV_FILES (relativos a APP_DIR, separados por :), RECONCILE_DAEMON_JSON, RECONCILE_IMAGE_ENV,
#   RECONCILE_PG_SERVICE/CONTAINER, RECONCILE_KC_SERVICE/CONTAINER, RECONCILE_BACKEND_CONTAINER,
#   RECONCILE_KC_SCHEMA, RECONCILE_BACKUP_DIR, RECONCILE_LOCK_FILE,
#   RECONCILE_PG_WAIT_TIMEOUT, RECONCILE_KC_WAIT_TIMEOUT, RECONCILE_PROBE_EXTRA (solo pruebas)
# shellcheck disable=SC2154  # pg_*/kc_* las asigna observe() con printf -v
set -euo pipefail
umask 077

# Todo el cuerpo va dentro de main(), que se invoca en la última línea: con `ssh … 'sudo bash -s' <
# script` bash lee el script de stdin a medida que lo ejecuta; sin esto, un flujo cortado (conexión
# caída, transferencia truncada) ejecutaría un prefijo del script y dejaría el estado a medias. Con
# main(), un script incompleto es un error de sintaxis ANTES de ejecutar nada.
main() {
APP_DIR="${RECONCILE_APP_DIR:-/opt/piedrazul}"
PROJECT="${RECONCILE_PROJECT:-piedrazul}"
COMPOSE_FILES="${RECONCILE_COMPOSE_FILES:-prod.yml}"
ENV_FILES="${RECONCILE_ENV_FILES:-.env:image.env}"
DAEMON_JSON="${RECONCILE_DAEMON_JSON:-/etc/docker/daemon.json}"
IMAGE_ENV="${RECONCILE_IMAGE_ENV:-${APP_DIR}/image.env}"
PG_SVC="${RECONCILE_PG_SERVICE:-postgres}"
PG="${RECONCILE_PG_CONTAINER:-postgres}"
KC_SVC="${RECONCILE_KC_SERVICE:-keycloak}"
KC="${RECONCILE_KC_CONTAINER:-keycloak}"
BACKEND="${RECONCILE_BACKEND_CONTAINER:-backend}"
KC_SCHEMA="${RECONCILE_KC_SCHEMA:-keycloak}"
BACKUP_DIR="${RECONCILE_BACKUP_DIR:-/root/piedrazul-reconcile}"
LOCK_FILE="${RECONCILE_LOCK_FILE:-${APP_DIR}/db.lock}"
PG_WAIT="${RECONCILE_PG_WAIT_TIMEOUT:-300}"
KC_WAIT="${RECONCILE_KC_WAIT_TIMEOUT:-600}"
PROBE_EXTRA="${RECONCILE_PROBE_EXTRA:-}"

EXIT_FAIL=1; EXIT_REFUSED=2; EXIT_ESCALATE=3
say() { printf '%s\n' "$*" || true; }   # una salida cerrada (SSH caído) no debe abortar a medias
refuse() { say "RECHAZADO: $*" >&2; exit "${EXIT_REFUSED}"; }
escalate() { say "ESCALAR (no se cambió nada más): $*" >&2; exit "${EXIT_ESCALATE}"; }
fail() { say "FALLÓ: $*" >&2; exit "${EXIT_FAIL}"; }

MODE="${1:-}"; shift || true
CONFIRM="" ACK=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --confirm) CONFIRM="${2:-}"; shift 2 ;;
    --ack-no-active-run) ACK=yes; shift ;;
    *) refuse "argumento desconocido: $1" ;;
  esac
done
case "${MODE}" in check | apply) ;; *) refuse "uso: $0 check | apply --confirm <id del plan> --ack-no-active-run" ;; esac
[[ "${KC_SCHEMA}" =~ ^[a-z_][a-z0-9_]*$ ]] || refuse "RECONCILE_KC_SCHEMA no es un identificador de esquema válido"
for bin in docker python3 flock sha256sum pgrep; do command -v "$bin" > /dev/null || refuse "falta $bin"; done
docker info > /dev/null 2>&1 || refuse "el daemon de Docker no responde"

# ── Compose: el mismo contrato que producción (archivos, proyecto, env-files, privilegios) ──────
dc() {
  local args=(--project-directory "${APP_DIR}/compose" -p "${PROJECT}") f
  IFS=: read -ra _files <<< "${COMPOSE_FILES}"
  for f in "${_files[@]}"; do args+=(-f "${APP_DIR}/compose/${f}"); done
  IFS=: read -ra _envs <<< "${ENV_FILES}"
  for f in "${_envs[@]}"; do args+=(--env-file "${APP_DIR}/${f}"); done
  # Sin stdin: con `ssh … 'sudo bash -s' < script` el resto del script llega por stdin y ningún
  # comando puede leerlo
  docker compose "${args[@]}" "$@" < /dev/null
}
for f in ${ENV_FILES//:/ }; do
  [ -r "${APP_DIR}/${f}" ] || refuse "no se puede leer ${APP_DIR}/${f}: ejecutar como root (el .env es root:root 0600)"
done
[ -r "${DAEMON_JSON}" ] || refuse "no se puede leer ${DAEMON_JSON}"
[ -r "${IMAGE_ENV}" ] || refuse "no se puede leer ${IMAGE_ENV}"

# ── Autoridad: daemon.json (no se repite 10m x 3 en ningún otro lugar) ──────────────────────────
EXPECTED="$(python3 - "${DAEMON_JSON}" << 'PYEOF' || true
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    o = d.get("log-opts") or {}
    if d.get("log-driver") == "json-file" and o.get("max-size") and o.get("max-file"):
        print(json.dumps({"driver": "json-file", "opts": {k: str(v) for k, v in o.items()}}, sort_keys=True))
except Exception:
    pass
PYEOF
)"
[ -n "${EXPECTED}" ] || escalate "${DAEMON_JSON} no declara log-driver json-file con log-opts max-size y max-file (o no es JSON válido): no hay un objetivo definido al que reconciliar"

conforming() { # <contenedor> → 0 si su LogConfig efectiva cumple daemon.json
  docker inspect "$1" --format '{{json .HostConfig.LogConfig}}' 2> /dev/null | python3 -c '
import json, sys
want = json.loads(sys.argv[1]); got = json.load(sys.stdin)
cfg = got.get("Config") or {}
sys.exit(0 if got.get("Type") == want["driver"] and all(cfg.get(k) == v for k, v in want["opts"].items()) else 1)' "${EXPECTED}"
}
insp() { docker inspect "$1" --format "$2" 2> /dev/null || true; }
image_env() { sed -n "s/^ *$1 *= *//p" "${IMAGE_ENV}" | head -1; }

# ── Observación (solo lectura) ──────────────────────────────────────────────────────────────────
observe() {
  PG_IMAGE_WANT="$(image_env POSTGRES_IMAGE)"; KC_IMAGE_WANT="$(image_env KEYCLOAK_IMAGE)"
  [ -n "${PG_IMAGE_WANT}" ] && [ -n "${KC_IMAGE_WANT}" ] || escalate "image.env no declara POSTGRES_IMAGE y KEYCLOAK_IMAGE"
  local c
  for c in pg kc; do
    local name; name="$([ "$c" = pg ] && echo "${PG}" || echo "${KC}")"
    local exists=no state="" health="" image="" id="" conf=na
    if docker inspect "${name}" > /dev/null 2>&1; then
      exists=yes
      state="$(insp "${name}" '{{.State.Status}}')"
      health="$(insp "${name}" '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')"
      image="$(insp "${name}" '{{.Config.Image}}')"
      id="$(insp "${name}" '{{.Id}}')"
      if conforming "${name}"; then conf=yes; else conf=no; fi
    fi
    printf -v "${c}_exists" '%s' "${exists}"; printf -v "${c}_state" '%s' "${state}"
    printf -v "${c}_health" '%s' "${health}"; printf -v "${c}_image" '%s' "${image}"
    printf -v "${c}_id" '%s' "${id}"; printf -v "${c}_conf" '%s' "${conf}"
  done
  PG_VOL="" PG_VOL_CREATED=""
  if [ "${pg_exists}" = yes ]; then
    PG_VOL="$(insp "${PG}" '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}')"
    [ -z "${PG_VOL}" ] || PG_VOL_CREATED="$(docker volume inspect "${PG_VOL}" --format '{{.CreatedAt}}' 2> /dev/null || true)"
  fi
}

report() {
  say "Estado observado (sin variables de entorno ni secretos):"
  say "  objetivo (daemon.json): ${EXPECTED}"
  say "  postgres : existe=${pg_exists} estado=${pg_state:--} salud=${pg_health:--} logs-conformes=${pg_conf} volumen=${PG_VOL:--}"
  say "  keycloak : existe=${kc_exists} estado=${kc_state:--} salud=${kc_health:--} logs-conformes=${kc_conf}"
}

# ── Condiciones de parada: nunca se reparan automáticamente ─────────────────────────────────────
check_ambiguity() {
  local leftovers
  leftovers="$(docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" \
    --format '{{.Names}}|{{.Label "com.docker.compose.service"}}' | awk -F'|' -v pg="${PG}" -v kc="${KC}" -v ps="${PG_SVC}" -v ks="${KC_SVC}" '
      ($2 == ps && $1 != pg) || ($2 == ks && $1 != kc) { print $1 }')"
  [ -z "${leftovers}" ] || escalate "hay contenedores del proyecto con el servicio de postgres/keycloak pero otro nombre (${leftovers//$'\n'/, }): un recreate interrumpido deja el contenedor anterior renombrado. Decidir cuál es el vigente antes de seguir"
  if docker inspect "${BACKEND}" > /dev/null 2>&1; then
    escalate "existe el contenedor ${BACKEND}: depende de la base y este procedimiento no lo detiene ni lo contempla"
  fi
  [ "${pg_exists}" = yes ] || escalate "no existe el contenedor ${PG}: no hay forma de identificar el volumen de datos; no se crea una base nueva desde aquí"
  [ -n "${PG_VOL}" ] && [ -n "${PG_VOL_CREATED}" ] || escalate "${PG} no monta un volumen nombrado en /var/lib/postgresql/data (o el volumen no existe)"
  [ "${pg_image}" = "${PG_IMAGE_WANT}" ] || escalate "${PG} corre otra identidad que image.env (POSTGRES_IMAGE): no es un caso de logs"
  [ "${kc_exists}" = yes ] || escalate "no existe el contenedor ${KC}: arrancarlo es del ciclo de converge (clasifica P1/P2/P3 antes); no se crea desde aquí"
  [ "${kc_image}" = "${KC_IMAGE_WANT}" ] || escalate "${KC} corre otra identidad que image.env (KEYCLOAK_IMAGE): no es un caso de logs"
  if pgrep -f 'docker[ -]compose' | grep -vqx "$$"; then
    escalate "hay otro proceso docker compose en ejecución en el host (¿un run de producción?)"
  fi
}

# ── Lo que hay que hacer ────────────────────────────────────────────────────────────────────────
decide() {
  NEEDS_PG=no; NEEDS_KC=no; KC_ACTION=none
  [ "${pg_conf}" = yes ] || NEEDS_PG=yes
  if [ "${kc_conf}" != yes ]; then NEEDS_KC=yes; KC_ACTION=recreate
  elif [ "${kc_state}" != running ]; then NEEDS_KC=yes; KC_ACTION=start
  fi
  # Primera ejecución: ambos corriendo y sanos, postgres aún sin corregir. El resto es continuación
  # de un intento anterior (Keycloak detenido, postgres ya recreado, etc.)
  if [ "${NEEDS_PG}" = yes ] && [ "${kc_state}" = running ]; then PHASE=initial; else PHASE=recovery; fi
  [ "${NEEDS_PG}${NEEDS_KC}" != nono ] || PHASE=none
}

check_health_preconditions() {
  # La base debe estar corriendo y sana en cualquier fase: de ella depende todo y es lo que se respalda
  [ "${pg_state}" = running ] && [ "${pg_health}" = healthy ] \
    || escalate "${PG} no está corriendo y healthy (estado ${pg_state}, salud ${pg_health}): no se respalda ni se recrea una base que no está sana. Inspeccionar sus logs"
  # Un Keycloak que corre debe estar sano: uno en arranque o unhealthy es un estado que este
  # procedimiento no sabe interpretar. Detenido es válido: es el estado de una continuación.
  if [ "${kc_state}" = running ] && [ "${kc_health}" != healthy ]; then
    escalate "${KC} corre pero su salud es '${kc_health}': esperar a que esté healthy, o diagnosticarlo, antes de seguir"
  fi
  case "${kc_state}" in running | exited | created) ;; *) escalate "${KC} está en estado '${kc_state}'" ;; esac
}

# Compose sin cambios pendientes en un servicio (dry-run, solo lectura, sin volcar la configuración).
# Un `--force-recreate` aplica TODA la configuración pendiente del servicio, no solo los logs: si Compose
# ya recrearía el contenedor por otra causa, este procedimiento no debe adelantarlo sin que se haya revisado.
# <servicio> <contenedor> <running|stopped>: «stopped» admite que Compose solo lo arranque. Deja el motivo
# en PENDING; 0 = nada pendiente, con evidencia positiva (falla cerrado si el comando falla o no informa).
compose_pending() {
  local out rc=0 flat bad='recreat|creat|stopp|remov|renam' want="Container ${2} +Running"
  out="$(dc up -d --no-deps --dry-run --pull never "$1" 2>&1)" || rc=$?
  flat="$(tr '\n' ' ' <<< "${out}" | tr -s ' ' | cut -c1-300)"
  if [ "$3" = running ]; then bad="${bad}|start"; else want="Container ${2} +Start"; fi
  if [ "${rc}" != 0 ]; then
    PENDING="el dry-run de Compose falló (código ${rc}): no se puede afirmar que no quede nada por recrear. Salida: ${flat}"; return 1
  fi
  # solo las líneas de acción sobre contenedores: un aviso suelto («remove it manually…») no es una acción
  if grep -Eiq "Container +[^ ]+ +(${bad})" <<< "${out}"; then
    PENDING="Compose todavía recrearía o cambiaría ${2} (la configuración del servicio cambió): ${flat}"; return 1
  fi
  grep -Eq "${want}" <<< "${out}" || {
    PENDING="el dry-run de Compose no informa ${2} como «$([ "$3" = running ] && echo Running || echo Start)» sin cambios: no se puede afirmar que no quede nada por recrear. Salida: ${flat:-vacía}"; return 1
  }
}

check_no_pending_config() { # antes de cualquier cambio: check y apply (este, ya con el lock)
  local state=stopped
  [ "${kc_state}" != running ] || state=running
  { compose_pending "${PG_SVC}" "${PG}" running && compose_pending "${KC_SVC}" "${KC}" "${state}"; } \
    || escalate "${PENDING}. Este procedimiento solo corrige logs y no recrea por otra causa: resolver ese cambio de configuración (converge normal) o revertirlo, y volver a ejecutar check"
}

PGUSER_="" PGDB_=""
pgq() { docker exec --user postgres "${PG}" psql --no-psqlrc --quiet --tuples-only --no-align --username="${PGUSER_}" --dbname="${PGDB_}" -c "$1" 2> /dev/null < /dev/null; }
discover_db() {
  # Solo estas variables no secretas del contenedor; nunca se vuelca el entorno
  PGUSER_="$(docker exec "${PG}" printenv POSTGRES_USER 2> /dev/null < /dev/null || true)"
  PGDB_="$(docker exec "${PG}" printenv POSTGRES_DB 2> /dev/null < /dev/null || true)"
  [ -n "${PGUSER_}" ] && [ -n "${PGDB_}" ] || escalate "no se pudo determinar la base y el rol administrativo de ${PG}"
  [ "$(pgq 'select 1' | tr -d '[:space:]')" = 1 ] || escalate "${PG} no acepta consultas del rol administrativo"
  [ "$(pgq "select count(*) from information_schema.schemata where schema_name = '${KC_SCHEMA}'" | tr -d '[:space:]')" = 1 ] \
    || escalate "la base ${PGDB_} no tiene el esquema ${KC_SCHEMA}"
  [ "$(pgq "select count(*) from ${KC_SCHEMA}.realm" | tr -d '[:space:]')" -ge 1 ] 2> /dev/null \
    || escalate "el esquema ${KC_SCHEMA} no tiene realms: Keycloak no está inicializado y no se arranca desde aquí (converge decide P1/P2/P3)"
  local names="" count=0 v n
  for v in KC_DB_USERNAME APP_DB_USERNAME MIGRATION_DB_USERNAME; do
    n="$(docker exec "${PG}" printenv "$v" 2> /dev/null < /dev/null || true)"
    [[ "${n}" =~ ^[A-Za-z0-9_]+$ ]] || continue
    names="${names}'${n}',"; count=$((count + 1))
  done
  if [ "${count}" -gt 0 ]; then
    [ "$(pgq "select count(*) from pg_roles where rolname in (${names%,})" | tr -d '[:space:]')" = "${count}" ] \
      || escalate "los roles de la base que declara ${PG} no existen todos en el clúster"
  fi
}
fingerprint() { # conteos de Keycloak: prueba de persistencia, sin datos
  printf 'realm=%s user=%s client=%s' \
    "$(pgq "select count(*) from ${KC_SCHEMA}.realm" | tr -d '[:space:]')" \
    "$(pgq "select count(*) from ${KC_SCHEMA}.user_entity" | tr -d '[:space:]')" \
    "$(pgq "select count(*) from ${KC_SCHEMA}.client" | tr -d '[:space:]')"
}

plan_id() {
  printf '%s\n' "${EXPECTED}" "$(sha256sum < "${DAEMON_JSON}")" "${pg_id}" "${pg_state}" "${pg_conf}" "${PG_VOL}" "${PG_VOL_CREATED}" \
    "${kc_id}" "${kc_state}" "${kc_conf}" "${PHASE}" "${NEEDS_PG}" "${KC_ACTION}" | sha256sum | cut -c1-12
}

# ── apply: autorización y lock ANTES de observar ───────────────────────────────────────────────
# El estado se observa y el id se recalcula con el lock tomado: una segunda ejecución (o una que
# esperó el lock) ve el estado que dejó la anterior y no actúa sobre un plan ya caducado.
# El lock es el mismo de la reconciliación de la base y la migración (db_converge.yml, migrate.yml):
# excluye una migración o reconciliación concurrentes que usen flock sobre este archivo. NO excluye
# a un converge/deploy en curso (Compose no lo toma) ni a otra persona con acceso a Docker: eso lo
# cubre --ack-no-active-run, no este lock.
if [ "${MODE}" = apply ]; then
  [ "${ACK}" = yes ] || refuse "apply exige --ack-no-active-run: confirmar que ningún run de producción (converge/deploy) está en curso o en cola"
  [ -n "${CONFIRM}" ] || refuse "apply exige --confirm <id del plan> (lo imprime check)"
  exec 9>> "${LOCK_FILE}"
  flock --exclusive --timeout 60 9 || escalate "no se obtuvo el lock ${LOCK_FILE} en 60 s: hay una migración o reconciliación de la base en curso (o otra ejecución de este procedimiento)"
  # Mejor esfuerzo, SIN garantía: se ignoran HUP y PIPE para que una conexión SSH caída no mate el
  # script en su siguiente salida. No impide que el proceso muera por otras causas (SIGKILL, el
  # reinicio del host, un sudo o sshd terminados). Tras cualquier desconexión el estado es
  # desconocido hasta ejecutar un check nuevo, de solo lectura
  trap '' HUP PIPE
fi

# ── check ───────────────────────────────────────────────────────────────────────────────────────
observe
check_ambiguity
report
decide
if [ "${PHASE}" = none ]; then
  say "RESULTADO: nada que hacer — la configuración de logs de postgres y keycloak ya es conforme. No se recrea nada."
  say "  (Este procedimiento no repara ni interpreta la salud de ningún servicio: la del estado de arriba es informativa.)"
  exit 0
fi
check_health_preconditions
check_no_pending_config
discover_db   # solo lectura: la base, el esquema de Keycloak con realms y los roles declarados
say "  base ${PGDB_}, esquema ${KC_SCHEMA}: $(fingerprint)"
PLAN="$(plan_id)"
STEPS=""
[ "${NEEDS_PG}" != yes ] || STEPS="respaldo lógico + recrear ${PG} (volumen conservado)"
case "${KC_ACTION}" in
  recreate) STEPS="${STEPS:+${STEPS} → }recrear ${KC}" ;;
  start) STEPS="${STEPS:+${STEPS} → }arrancar ${KC} sin recrearlo" ;;
esac
say "Plan (${PHASE}): ${STEPS}"
if [ "${MODE}" = check ]; then
  say "RESULTADO: plan ${PLAN} (${PHASE}). Autorizado el cambio: apply --confirm ${PLAN} --ack-no-active-run"
  say "  (el id solo vale mientras el estado observado no cambie; antes de ejecutar apply, confirmar que ningún run de producción está en curso o en cola.)"
  exit 0
fi

# ── apply ───────────────────────────────────────────────────────────────────────────────────────
[ "${CONFIRM}" = "${PLAN}" ] || refuse "el id '${CONFIRM}' no corresponde al estado actual (plan ${PLAN}): el estado cambió desde check; volver a ejecutar check"

PROBE="pz-reconcile-probe-$$"
cleanup_probe() { docker rm -f "${PROBE}" > /dev/null 2>&1 || true; }
trap cleanup_probe EXIT
trap 'say "INTERRUMPIDO: el estado puede ser parcial. Volver a ejecutar check; un Keycloak detenido o un postgres ya recreado se continúan (fase recovery). No se deshizo nada." >&2; exit 130' INT TERM

# 1. Sonda: un contenedor nuevo, sin arrancar, debe heredar la rotación del daemon. Si no, recrear no
#    serviría y el corte sería en vano.
say "[1/6] Sonda de los valores por defecto del daemon"
# shellcheck disable=SC2086
docker create --name "${PROBE}" --label pz-reconcile-probe=1 ${PROBE_EXTRA} --entrypoint true "${PG_IMAGE_WANT}" > /dev/null \
  || fail "no se pudo crear el contenedor de la sonda"
if ! conforming "${PROBE}"; then
  escalate "un contenedor nuevo NO hereda la rotación de daemon.json: el daemon no corre con esa configuración (¿falta el reinicio?). Recrear postgres/keycloak ahora no la daría; no se tocó nada"
fi
cleanup_probe
say "  ok: un contenedor nuevo hereda $(python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1])["opts"], sort_keys=True))' "${EXPECTED}")"

BEFORE_FP="$(fingerprint)"

# Respaldo: se comprueba que se puede escribir ANTES de detener nada
if [ "${NEEDS_PG}" = yes ]; then
  install -d -m 0700 "${BACKUP_DIR}" 2> /dev/null || refuse "no se puede crear ${BACKUP_DIR} (0700)"
  [ -w "${BACKUP_DIR}" ] || refuse "${BACKUP_DIR} no es escribible"
  [ "$(stat -c '%a %u' "${BACKUP_DIR}")" = "700 $(id -u)" ] || refuse "${BACKUP_DIR} debe ser 0700 y de quien ejecuta (el respaldo contiene credenciales)"
  dbsize="$(pgq "select pg_database_size(current_database())" | tr -d '[:space:]')"
  free_kb="$(df -Pk "${BACKUP_DIR}" | awk 'NR==2{print $4}')"
  [ "$((free_kb * 1024))" -gt "$((dbsize * 2 + 1073741824))" ] || refuse "espacio insuficiente en ${BACKUP_DIR} (se exige el doble de la base más 1 GiB)"
fi

# 2. Keycloak se detiene antes que la base: sus transacciones y su membresía de clúster terminan
#    limpias, y no queda conectado a una base que se reinicia.
KC_STOPPED_BY_US=no
if [ "${kc_state}" = running ]; then
  say "[2/6] Deteniendo ${KC} (la base sigue arriba)"
  dc stop -t 30 "${KC_SVC}" || fail "no se pudo detener ${KC}"
  [ "$(insp "${KC}" '{{.State.Running}}')" = false ] || fail "${KC} sigue corriendo tras detenerlo"
  KC_STOPPED_BY_US=yes
else
  say "[2/6] ${KC} ya está detenido (continuación)"
fi

restore_kc_if_unchanged() { # antes de tocar la base, volver al estado de partida es seguro
  if [ "${KC_STOPPED_BY_US}" = yes ]; then
    say "  restaurando ${KC} (lo detuvimos nosotros y la base no se tocó)…" >&2
    dc start "${KC_SVC}" > /dev/null 2>&1 || say "  no se pudo reiniciar ${KC}: arrancarlo con converge o escalar" >&2
  fi
}

if [ "${NEEDS_PG}" = yes ]; then
  # 3. Respaldo lógico con la base quieta
  STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  DUMP="${BACKUP_DIR}/pre-logs-reconcile-${STAMP}-${PLAN}.dump"
  say "[3/6] Respaldo lógico → ${DUMP} (0600; contiene credenciales y datos: tratarlo como secreto)"
  if ! docker exec --user postgres "${PG}" pg_dump --username="${PGUSER_}" --dbname="${PGDB_}" --format=custom --no-password > "${DUMP}" < /dev/null; then
    rm -f -- "${DUMP}"; restore_kc_if_unchanged; fail "pg_dump falló; no se recreó nada"
  fi
  TOC="$(docker exec -i "${PG}" pg_restore --list < "${DUMP}" 2> /dev/null || true)"
  if [ ! -s "${DUMP}" ] || ! grep -q "^; Archive created at" <<< "${TOC}" \
      || ! grep -Eq "SCHEMA - ${KC_SCHEMA} " <<< "${TOC}" \
      || ! grep -Eq "TABLE DATA ${KC_SCHEMA} realm " <<< "${TOC}"; then
    restore_kc_if_unchanged; fail "el respaldo no se pudo leer o no contiene el esquema ${KC_SCHEMA} con datos de realm: no se recreó nada (conservado en ${DUMP})"
  fi
  [ "$(stat -c %a "${DUMP}")" = 600 ] || { restore_kc_if_unchanged; fail "permisos inesperados del respaldo"; }
  say "  respaldo legible: $(wc -l <<< "${TOC}") entradas en el índice, esquema ${KC_SCHEMA} con datos. La restauración NO está probada."

  # 4. postgres: mismo volumen, misma imagen, nueva configuración de logs
  say "[4/6] Recreando ${PG} (volumen ${PG_VOL} conservado)"
  OLD_PG_ID="${pg_id}"
  # Identidad del volumen ANTES de recrear: observe reasigna PG_VOL/PG_VOL_CREATED con lo que monta el
  # contenedor nuevo, y comparar eso consigo mismo no probaría nada
  ORIG_VOL="${PG_VOL}"; ORIG_VOL_CREATED="${PG_VOL_CREATED}"
  ORIG_VOL_MOUNTPOINT="$(docker volume inspect "${ORIG_VOL}" --format '{{.Mountpoint}}' 2> /dev/null || true)"
  dc up -d --no-deps --force-recreate --pull never -t 60 --wait --wait-timeout "${PG_WAIT}" "${PG_SVC}" \
    || fail "${PG} no quedó healthy tras recrearlo. Keycloak sigue detenido. El volumen no se tocó. Diagnosticar (docker logs ${PG}) y continuar con check/apply, o escalar; el respaldo está en ${DUMP}"
  observe
  [ "${pg_id}" != "${OLD_PG_ID}" ] || fail "${PG} conserva el mismo contenedor tras --force-recreate"
  [ "${pg_state}" = running ] && [ "${pg_health}" = healthy ] || fail "${PG}: estado ${pg_state}, salud ${pg_health}"
  [ "${pg_image}" = "${PG_IMAGE_WANT}" ] || fail "${PG} corre otra imagen que image.env"
  [ "${pg_conf}" = yes ] || fail "${PG} recreado sin la configuración de logs esperada (revisar el daemon)"
  # observe ya releyó lo que monta el contenedor nuevo (PG_VOL, PG_VOL_CREATED): se compara con el original
  if [ "${PG_VOL}" != "${ORIG_VOL}" ] \
      || [ "${PG_VOL_CREATED}" != "${ORIG_VOL_CREATED}" ] \
      || [ "$(docker volume inspect "${ORIG_VOL}" --format '{{.Mountpoint}}' 2> /dev/null || true)" != "${ORIG_VOL_MOUNTPOINT}" ]; then
    fail "${PG} no montó el volumen de datos original (antes: ${ORIG_VOL}, creado ${ORIG_VOL_CREATED}; ahora: ${PG_VOL:-ninguno}, creado ${PG_VOL_CREATED:-?}). Keycloak sigue detenido. No se borra ni se reasigna nada: escalar; el volumen original y el respaldo (${DUMP}) siguen ahí"
  fi
  AFTER_FP="$(fingerprint)"
  [ "${AFTER_FP}" = "${BEFORE_FP}" ] || fail "los datos de ${KC_SCHEMA} cambiaron (${BEFORE_FP} → ${AFTER_FP}). Keycloak sigue detenido; escalar, el respaldo está en ${DUMP}"
  say "  ok: contenedor nuevo, mismo volumen, healthy, logs conformes, datos intactos (${AFTER_FP})"
else
  say "[3/6] postgres ya es conforme: sin respaldo ni recreación"
  say "[4/6] (omitido)"
fi

# 5. Keycloak: recrear si no es conforme; si solo estaba detenido, arrancarlo sin recrearlo
if [ "${KC_ACTION}" != none ] || [ "${KC_STOPPED_BY_US}" = yes ]; then
  FLAGS=(up -d --no-deps --pull never -t 30 --wait --wait-timeout "${KC_WAIT}")
  [ "${kc_conf}" = yes ] || FLAGS+=(--force-recreate)
  say "[5/6] $([ "${kc_conf}" = yes ] && echo 'Arrancando' || echo 'Recreando') ${KC}"
  dc "${FLAGS[@]}" "${KC_SVC}" \
    || fail "${KC} no quedó healthy. La base está intacta y verificada. Diagnosticar (docker logs ${KC}); un converge lo arranca con su ciclo, o repetir check/apply (continuación)"
  observe
  [ "${kc_state}" = running ] && [ "${kc_health}" = healthy ] || fail "${KC}: estado ${kc_state}, salud ${kc_health}"
  [ "${kc_image}" = "${KC_IMAGE_WANT}" ] || fail "${KC} corre otra imagen que image.env"
  [ "${kc_conf}" = yes ] || fail "${KC} sin la configuración de logs esperada"
  say "  ok: ${KC} healthy, misma imagen, logs conformes"
else
  say "[5/6] ${KC} ya es conforme y corre: sin cambios"
fi

# 6. Estabilidad: Compose no debe tener nada que recrear (un converge posterior no recreará)
say "[6/6] Comprobando que Compose no tiene nada más que recrear"
# Misma comprobación que antes de empezar (compose_pending, falla cerrado), ahora con ambos corriendo
compose_pending "${PG_SVC}" "${PG}" running || fail "${PENDING}. La reconciliación ya se aplicó; volver a ejecutar check"
compose_pending "${KC_SVC}" "${KC}" running || fail "${PENDING}. La reconciliación ya se aplicó; volver a ejecutar check"
say "  ok: sin cambios pendientes"
say "RESULTADO: reconciliado. Siguiente: Run I (su converge clasifica Keycloak y la verificación de logs lo confirma)."
[ "${NEEDS_PG}" != yes ] || say "  Respaldo ${DUMP}: sensible. Conservarlo hasta que Run I verifique; después borrarlo."
}

main "$@"
