#!/usr/bin/env bash
# Prueba de extremo a extremo de los logs en un host Linux de laboratorio con Docker nativo y
# journald: contenedores sintéticos con el driver journald → journal real del host → Alloy →
# Loki → Grafana. Complementa infra/tests/logs-stack-check.sh (journal sintético, cualquier
# Docker) con lo que solo un host real muestra: los campos que escribe el driver journald de
# Docker, los permisos reales del journal, el límite de tasa de journald y el WAL de Loki ante
# un corte abrupto.
#
# SOLO en un host de laboratorio desechable: escribe entradas sintéticas en el journal del host
# (no se pueden borrar después; se identifican por el prefijo pzlogs-e2e-<pid>) y, con BURST=1,
# satura el límite de tasa de journald de docker.service durante ~30 s (suprime también los
# logs de otros contenedores en ese intervalo). No toca el stack instalado (piedrazul-obs) ni la
# aplicación: levanta su propio stack (pzlogs-e2e-<pid>-obs) desde este checkout y sus propios
# contenedores sintéticos (pzlogs-e2e-<pid>).
#
# Uso (en el host, con un usuario del grupo docker que pueda leer el journal, p. ej. en
# systemd-journal o adm, o con sudo):
#   LAB_CONFIRM=1 infra/tests/logs-e2e-lab.sh
# Entorno opcional:
#   BURST=1         prueba los avisos de journald por límite de tasa (afecta a docker.service)
#   LONG_OUTAGE=1   corte de Loki más largo que los reintentos de Alloy (≈11 min): pérdida
#   EXPECT_DROPIN=1 exige el drop-in de journald del rol con sus valores (por defecto 1)
#   GRAFANA_LOCAL_PORT  puerto de loopback para Grafana (por defecto 13200)
#   KEEP=1          no elimina los proyectos al terminar
# Requiere: Linux con systemd-journald y journal persistente, Docker nativo (no Docker Desktop)
#   con compose v2, jq, python3, openssl, curl, journalctl.
#
# Verifica:
#   1. Host: journald activo, journal persistente, drop-in del rol vigente (2G/128M/7 días),
#      grupo dueño de los archivos del journal.
#   2. Driver journald real: campos de confianza que exige la selección (_SYSTEMD_UNIT=
#      docker.service, _COMM=dockerd, _UID=0) y COM_DOCKER_COMPOSE_PROJECT/SERVICE, CONTAINER_ID,
#      _TRANSPORT=journal y PRIORITY 6/3 (stdout/stderr) que la selección de Alloy supone.
#   3. Ingesta: selección por proyecto y servicio, etiquetas, severidad, correlation_id; latencia.
#   4. Grafana: consultas del dashboard y búsqueda por id de correlación.
#   5. Procedencia: un productor independiente (logger --journald) que escribe los campos de
#      Compose del proyecto seleccionado e intenta fijar _SYSTEMD_UNIT/_COMM/_TRANSPORT y
#      OBJECT_SYSTEMD_UNIT: journald guarda los suyos y la entrada no llega a Loki.
#      Exclusión: postgres, servicio condicionado, otro proyecto, unidad del host (logger): en el
#      journal sí, en Loki no.
#   6. Permisos: Alloy sin el grupo del journal no lee nada y no rompe las métricas; al
#      corregirlo llega lo pendiente sin releer el journal.
#   7. PRE/POST: el marcador sensible anterior a la selección no entra al seleccionar; el
#      journal lo conserva.
#   8. Fallas: corte breve de Loki recuperado por reintentos; reinicio de Alloy sin releer;
#      SIGKILL de Loki sin perder lo confirmado (WAL); con LONG_OUTAGE=1, pérdida contada.
#   9. Avisos de journald (BURST=1): el aviso de supresión de docker.service llega como
#      service=journald.
#  10. Métricas y aislamiento: métricas del host, 12 reglas sanas, puertos, redes; el stack instalado
#      y la aplicación no cambian (mismos contenedores antes y después).
# Salida: 0 si todo pasa; 1 si algo falla; 2 si el entorno no es un laboratorio válido.
set -uo pipefail

[ "${LAB_CONFIRM:-0}" = 1 ] || { echo "Solo en un host de laboratorio desechable: LAB_CONFIRM=1 para continuar (ver cabecera)" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OBS="${ROOT}/infra/observability"
ID="$$"
RUN="pzlogs-e2e-${ID}"
SYN_PROJECT="${RUN}"
OBS_PROJECT="${RUN}-obs"
APP_NET="pzlogs_e2e_${ID}_net"
GPORT="${GRAFANA_LOCAL_PORT:-13200}"
# busybox fijado por digest: solo imprime líneas
BUSYBOX="busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec"

for bin in docker jq python3 openssl curl journalctl systemctl; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
[ "$(uname -s)" = Linux ] || { echo "requiere Linux" >&2; exit 2; }
case "$(docker info --format '{{.OperatingSystem}}' 2>/dev/null)" in
  *"Docker Desktop"*|"") echo "requiere Docker nativo (no Docker Desktop): el driver journald debe escribir en el journal de este host" >&2; exit 2 ;;
esac
systemctl is-active --quiet systemd-journald || { echo "systemd-journald no está activo" >&2; exit 2; }
MACHINE_ID="$(cat /etc/machine-id)"
JDIR="/var/log/journal/${MACHINE_ID}"
[ -d "${JDIR}" ] || { echo "sin journal persistente (${JDIR}); aplicar antes el drop-in del rol" >&2; exit 2; }
journalctl -n 1 -q >/dev/null 2>&1 || { echo "este usuario no puede leer el journal (systemd-journal, adm o sudo)" >&2; exit 2; }

TMP="$(mktemp -d)"
FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
wait_for() { local t="$1"; shift; for _ in $(seq 1 $(( t / 5 ))); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1; }

STARTED=false
cleanup() {
  if [ "${STARTED}" = true ] && [ "${KEEP:-0}" != 1 ]; then
    obs down -v --remove-orphans >/dev/null 2>&1 || true
    syn down --remove-orphans >/dev/null 2>&1 || true
    docker network rm "${APP_NET}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

# Estado previo del stack instalado y de la aplicación (no deben cambiar)
others() { docker ps -aq --filter label=com.docker.compose.project=piedrazul-obs; docker ps -aq --filter label=com.docker.compose.project=piedrazul; }
OTHERS_BEFORE="$(others | sort | tr '\n' ' ')"

# ── 1. Host ──────────────────────────────────────────────────────────────────
JGID="$(stat -c %g "${JDIR}")"
JGROUP="$(stat -c %G "${JDIR}")"
echo "      journal: ${JDIR} grupo ${JGROUP} (${JGID}); $(journalctl --disk-usage 2>/dev/null)"
check "host: el journal persistente es de un grupo distinto de root" test "${JGID}" != 0
check "host: archivos del journal legibles por su grupo" \
  bash -c "test -z \"\$(find '${JDIR}' -name '*.journal' ! -perm -g=r 2>/dev/null | head -1)\""
EFFECTIVE="$(systemd-analyze cat-config systemd/journald.conf 2>/dev/null | grep -E '^(Storage|SystemMaxUse|SystemMaxFileSize|MaxRetentionSec)=' | tr '\n' ' ')"
echo "      journald efectivo: ${EFFECTIVE}"
if [ "${EXPECT_DROPIN:-1}" = 1 ]; then
  check "host: drop-in del rol presente (/etc/systemd/journald.conf.d/60-piedrazul-obs.conf)" test -f /etc/systemd/journald.conf.d/60-piedrazul-obs.conf
  check "host: journald efectivo persistente, 2G, archivos de 128M, 7 días" \
    bash -c "for kv in Storage=persistent SystemMaxUse=2G SystemMaxFileSize=128M MaxRetentionSec=7day; do grep -q \"\$kv\" <<< '${EFFECTIVE}' || exit 1; done"
fi

# ── Contenedores sintéticos con el driver journald ───────────────────────────
# Mismas opciones de logging que exige la activación de una fuente real (runbook de logs)
cat > "${TMP}/synthetic.yml" <<EOF
name: ${SYN_PROJECT}
x-journald: &journald
  driver: journald
  options:
    mode: non-blocking
    max-buffer-size: 4m
    labels: com.docker.compose.project,com.docker.compose.service
    tag: "{{.Name}}"
services:
  traefik: {image: "${BUSYBOX}", logging: *journald, network_mode: none, command: ["true"]}
  backend: {image: "${BUSYBOX}", logging: *journald, network_mode: none, command: ["true"]}
  keycloak: {image: "${BUSYBOX}", logging: *journald, network_mode: none, command: ["true"]}
  postgres: {image: "${BUSYBOX}", logging: *journald, network_mode: none, command: ["true"]}
EOF
syn() { docker compose -p "${SYN_PROJECT}" -f "${TMP}/synthetic.yml" "$@"; }
# emit <servicio> <stdout|stderr> <línea>: un contenedor de un solo uso que escribe la línea
emit() {
  local redir=""; [ "$2" = stderr ] && redir=" >&2"
  syn run --rm --no-deps -T "$1" sh -c "printf '%s\n' \"\$0\"${redir}; sleep 2" "$3" >/dev/null 2>&1
}
# Sin grep -q: con pipefail, grep -q sale en la primera coincidencia, journalctl recibe SIGPIPE (141) y
# la tubería falla aunque la línea esté (un journal real tiene miles de entradas en 30 min)
in_journal() { journalctl -q --no-pager -o cat --since "-30min" | grep -F -- "$1" >/dev/null; }

STARTED=true
docker pull -q "${BUSYBOX}" >/dev/null
CID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
emit traefik stdout "2026-11-03T10:00:00Z ERR ${RUN}-traefik-error"
emit traefik stderr "2026-11-03T10:00:01Z WRN ${RUN}-traefik-stderr"
emit backend stdout "2026-11-03T10:00:02.000Z  INFO 1 --- [piedrazul] [exec-1] [${CID}] c.e.Api : ${RUN}-backend-request"
emit postgres stdout "${RUN}-postgres-excluded"
emit keycloak stdout "2026-11-03 10:00:03,000 INFO  [org.keycloak.events] (executor) ${RUN}-SENSITIVE-PRE userId=synthetic"
logger -t pzlogs-e2e "${RUN}-host-unit"
# Productor independiente que imita a dockerd: campos de Compose del proyecto seleccionado y
# campos de confianza falsificados (journald los ignora y pone los del proceso logger)
spoof() { # spoof <marca> [campos extra...]
  { printf 'MESSAGE=2026-11-03T10:00:09Z ERR %s\nPRIORITY=6\nCOM_DOCKER_COMPOSE_PROJECT=%s\nCOM_DOCKER_COMPOSE_SERVICE=traefik\nCONTAINER_ID=0123456789ab\n' "$1" "${SYN_PROJECT}"
    shift; for f in "$@"; do printf '%s\n' "$f"; done; } | logger --journald
}
spoof "${RUN}-spoof-fields"
spoof "${RUN}-spoof-trusted" _SYSTEMD_UNIT=docker.service _COMM=dockerd _UID=0 _TRANSPORT=driver
printf 'MESSAGE=Suppressed 99 messages from docker.service %s-spoof-notice\nMESSAGE_ID=a596d6fe7bfa4994828e72309e95d61e\nOBJECT_SYSTEMD_UNIT=docker.service\n_TRANSPORT=driver\n' "${RUN}" | logger --journald

# ── 2. Driver journald real ─────────────────────────────────────────────────
check "driver: las líneas están en el journal del host" wait_for 30 in_journal "${RUN}-traefik-error"
journalctl -q --no-pager -o json --since "-30min" COM_DOCKER_COMPOSE_PROJECT="${SYN_PROJECT}" > "${TMP}/driver.json" 2>/dev/null
FIELDS="$(jq -c --arg m "${RUN}-traefik-error" 'select(.MESSAGE == ("2026-11-03T10:00:00Z ERR " + $m)) | {t: ._TRANSPORT, p: .PRIORITY, proj: .COM_DOCKER_COMPOSE_PROJECT, svc: .COM_DOCKER_COMPOSE_SERVICE, cid: (.CONTAINER_ID | length), unit: ._SYSTEMD_UNIT, comm: ._COMM, uid: ._UID}' "${TMP}/driver.json" | head -1)"
echo "      campos del driver (stdout): ${FIELDS}"
check "driver: _TRANSPORT=journal, PRIORITY=6, etiquetas de Compose y CONTAINER_ID de 12" \
  test "${FIELDS}" = "{\"t\":\"journal\",\"p\":\"6\",\"proj\":\"${SYN_PROJECT}\",\"svc\":\"traefik\",\"cid\":12,\"unit\":\"docker.service\",\"comm\":\"dockerd\",\"uid\":\"0\"}"
SPOOFED="$(journalctl -q --no-pager -o json --since "-30min" COM_DOCKER_COMPOSE_PROJECT="${SYN_PROJECT}" SYSLOG_IDENTIFIER=logger 2>/dev/null; journalctl -q --no-pager -o json --since "-30min" _COMM=logger MESSAGE_ID=a596d6fe7bfa4994828e72309e95d61e 2>/dev/null)"
echo "      productor independiente en el journal: $(jq -c '{t: ._TRANSPORT, unit: ._SYSTEMD_UNIT, comm: ._COMM, uid: ._UID, obj: .OBJECT_SYSTEMD_UNIT}' <<< "${SPOOFED}" | sort -u | tr '\n' ' ')"
check "procedencia: journald guardó las entradas falsificadas con los campos de logger (no los enviados)" \
  bash -c "test -n \"\$1\" && ! jq -e 'select(._COMM == \"dockerd\" or ._SYSTEMD_UNIT == \"docker.service\" or ._TRANSPORT == \"driver\")' <<< \"\$1\"" _ "${SPOOFED}"
check "driver: stderr con PRIORITY=3" \
  test "$(jq -r --arg m "${RUN}-traefik-stderr" 'select(.MESSAGE | tostring | endswith($m)) | .PRIORITY' "${TMP}/driver.json" | head -1)" = 3

# ── Stack aislado desde este checkout, con la raíz real del host ─────────────
docker network create "${APP_NET}" >/dev/null
# S3 en modo privado: sin red de entrada; webhook vacío (receptor none)
mkdir -p "${TMP}/secrets" && : > "${TMP}/secrets/discord_webhook_url" && chmod 644 "${TMP}/secrets/discord_webhook_url"
# Archivo de acceso de Grafana (secreto grafana_access): vacío = nadie obtiene rol por JWT
: > "${TMP}/secrets/grafana_access.ini" && chmod 644 "${TMP}/secrets/grafana_access.ini"
printf 'GRAFANA_ADMIN_PASSWORD=%s\nOBS_APP_NETWORK=%s\nOBS_DISCORD_WEBHOOK_FILE=%s\nOBS_GRAFANA_ACCESS_FILE=%s\nGRAFANA_LOCAL_PORT=%s\nOBS_JOURNAL_GID=%s\nOBS_LOG_PROJECT=%s\nOBS_LOG_SERVICES=traefik|backend\n' \
  "$(openssl rand -hex 16)" "${APP_NET}" "${TMP}/secrets/discord_webhook_url" "${TMP}/secrets/grafana_access.ini" "${GPORT}" "${JGID}" "${SYN_PROJECT}" > "${TMP}/obs.env"
chmod 600 "${TMP}/obs.env"
obs() { (cd "${OBS}" && docker compose -p "${OBS_PROJECT}" --env-file "${TMP}/obs.env" -f compose.yml "$@"); }
T_UP="$(date +%s)"
obs up -d --wait --wait-timeout 180 > "${TMP}/obs-up.log" 2>&1 \
  && ok "stack: levantado (proyecto ${OBS_PROJECT})" || { ko "stack: levantado"; tail -20 "${TMP}/obs-up.log" >&2; exit 1; }

pexec() { obs exec -T prometheus "$@"; }
# Loki y Alloy por nombre absoluto (loki., alloy.): el wget de busybox de Prometheus no resuelve
# nombres cortos con "search ." en resolv.conf (systemd-resolved del host). Ver verify_logs.yml.
urlq() { python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1"; }
lq() { pexec wget -qO- "http://loki.:3100/loki/api/v1/query_range?query=$(urlq "$1")&start=$(( $(date +%s) - 3600 ))000000000&limit=1000" 2>/dev/null; }
lcount() { lq "$1" | jq '[.data.result[].values | length] | add // 0'; }
pv() { pexec wget -qO- "http://localhost:9090/api/v1/query?query=$(urlq "$1")" 2>/dev/null | jq -r '.data.result[0].value[1] // empty'; }
loki_ready() { test "$(pexec wget -qO- http://loki.:3100/ready 2>/dev/null)" = ready; }
journal_state() { pexec wget -qO- http://alloy.:12345/api/v0/web/components 2>/dev/null | jq -r '.[] | select(.localID == "loki.source.journal.host") | .health.state'; }
alloy_metric() { pexec wget -qO- http://alloy.:12345/metrics 2>/dev/null | awk -v m="$1" 'index($0, m"{") == 1 || $1 == m {s += $NF} END {print s + 0}'; }
healthy() { test "$(journal_state)" = healthy; }
has() { test "$(lcount "$1")" = "$2"; }

check "stack: Loki listo" wait_for 120 loki_ready
check "stack: loki.source.journal sano con el grupo ${JGROUP}" wait_for 60 healthy
reading() { test "$(alloy_metric loki_source_journal_target_lines_total | cut -d. -f1)" -gt 0; }
check "stack: Alloy lee el journal real (entradas leídas > 0)" wait_for 60 reading

# ── 3. Ingesta ───────────────────────────────────────────────────────────────
traefik_in() { has "{service=\"traefik\"} |= \"${RUN}-traefik-error\"" 1; }
check "ingesta: traefik en Loki" wait_for 120 traefik_in
echo "      latencia hasta Loki desde el arranque del stack: $(( $(date +%s) - T_UP )) s (incluye la lectura inicial)"
check "ingesta: etiquetas de traefik (docker, stdout, error)" \
  test "$(lq "{service=\"traefik\"} |= \"${RUN}-traefik-error\"" | jq -c '.data.result[0].stream | {source, service, stream, level}')" = '{"source":"docker","service":"traefik","stream":"stdout","level":"error"}'
check "ingesta: stderr real → stream=stderr, level=warn" \
  test "$(lq "{service=\"traefik\"} |= \"${RUN}-traefik-stderr\"" | jq -c '.data.result[0].stream | {stream, level}')" = '{"stream":"stderr","level":"warn"}'
check "ingesta: backend con correlation_id" has "{service=\"backend\"} | correlation_id=\"${CID}\"" 1
LABELS="$(pexec wget -qO- "http://loki.:3100/loki/api/v1/labels?start=$(( $(date +%s) - 3600 ))000000000" 2>/dev/null | jq -r '.data | sort | join(",")')"
check "ingesta: etiquetas solo source, service, stream, level (${LABELS})" \
  bash -c "test -z \"\$(tr ',' '\n' <<< '${LABELS}' | grep -vxE 'source|service|stream|level')\""
T0="$(date +%s%N)"; emit traefik stdout "2026-11-03T10:00:04Z INF ${RUN}-latency"
lat_in() { has "{service=\"traefik\"} |= \"${RUN}-latency\"" 1; }
wait_for 60 lat_in && echo "      latencia de una línea nueva: ≤ $(( ($(date +%s%N) - T0) / 1000000 )) ms (resolución 5 s)"

# ── 4. Grafana ───────────────────────────────────────────────────────────────
GRAFANA_AUTH="admin:$(sed -n 's/^GRAFANA_ADMIN_PASSWORD=//p' "${TMP}/obs.env")"; export GRAFANA_AUTH
G="http://127.0.0.1:${GPORT}"
check "Grafana: fuente de datos loki sana" bash -c "curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/datasources/uid/loki/health' | jq -e '.status == \"OK\"'"
cat > "${TMP}/cid.py" <<'EOF'
import base64, json, os, sys, urllib.request
base, cid = sys.argv[1], sys.argv[2]
auth = "Basic " + base64.b64encode(os.environ["GRAFANA_AUTH"].encode()).decode()
def call(path, body=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)
d = call("/api/dashboards/uid/piedrazul-logs")["dashboard"]
p = next(p for p in d["panels"] if p["title"].startswith("Líneas con el id de correlación"))
t = p["targets"][0]
r = call("/api/ds/query", {"from": "now-1h", "to": "now", "queries": [dict(t, expr=t["expr"].replace("$correlation_id", cid), maxLines=100)]})["results"][t["refId"]]
print(sum(len(f["data"]["values"][0]) for f in r.get("frames", []) if f["data"]["values"]))
EOF
check "Grafana: el panel de correlación encuentra la petición" test "$(python3 -I "${TMP}/cid.py" "${G}" "${CID}" 2>/dev/null)" = 1

# ── 5. Exclusión ─────────────────────────────────────────────────────────────
for m in spoof-fields spoof-trusted spoof-notice; do
  check "procedencia: ${m} no está en Loki" has "{service=~\".+\"} |= \"${RUN}-${m}\"" 0
done
for m in postgres-excluded SENSITIVE-PRE host-unit; do
  check "exclusión: ${m} en el journal" in_journal "${RUN}-${m}"
  check "exclusión: ${m} no está en Loki" has "{service=~\".+\"} |= \"${RUN}-${m}\"" 0
done
SERVICES_IN_LOKI="$(lq '{source="docker"}' | jq -r '[.data.result[].stream.service] | unique | join(",")')"
check "exclusión: en Loki solo los servicios seleccionados (${SERVICES_IN_LOKI})" test "${SERVICES_IN_LOKI}" = backend,traefik

# ── 6. Permisos del journal ──────────────────────────────────────────────────
# T_STOP antes de recrear Alloy: su posición guardada es la del Alloy anterior, así que lo que
# escribe el host durante la recreación (dockerd, red, kernel) también queda pendiente de leer
T_STOP="$(date '+%Y-%m-%d %H:%M:%S')"
sed -i 's/^OBS_JOURNAL_GID=.*/OBS_JOURNAL_GID=65534/' "${TMP}/obs.env"
obs up -d --wait alloy >/dev/null 2>&1
sleep 15
emit traefik stdout "2026-11-03T10:00:05Z INF ${RUN}-while-unreadable"
sleep 20
echo "      sin el grupo del journal: componente $(journal_state), entradas leídas $(alloy_metric loki_source_journal_target_lines_total)"
check "permisos: sin el grupo del journal Alloy no lee (unhealthy o 0 entradas)" \
  bash -c "test \"\$1\" = unhealthy || test \"\$2\" = 0" _ "$(journal_state)" "$(alloy_metric loki_source_journal_target_lines_total | cut -d. -f1)"
host_up() { test "$(pv 'up{job="host"}')" = 1; }
check "permisos: las métricas del host siguen" wait_for 90 host_up
sed -i "s/^OBS_JOURNAL_GID=.*/OBS_JOURNAL_GID=${JGID}/" "${TMP}/obs.env"
obs up -d --wait alloy >/dev/null 2>&1
unreadable_in() { has "{service=\"traefik\"} |= \"${RUN}-while-unreadable\"" 1; }
check "permisos: al corregir llega lo pendiente" wait_for 120 unreadable_in
sleep 10
SINCE_STOP="$(journalctl -q --no-pager --since "${T_STOP}" -o cat | wc -l)"
READ_AFTER="$(alloy_metric loki_source_journal_target_lines_total | cut -d. -f1)"
echo "      tras corregir: Alloy leyó ${READ_AFTER} entradas; el journal tiene ${SINCE_STOP} desde la parada"
check "permisos: al corregir Alloy no relee el journal (leídas ≤ escritas desde la parada)" test "${READ_AFTER}" -le "$(( SINCE_STOP + 5 ))"

# ── 7. PRE/POST ──────────────────────────────────────────────────────────────
sed -i 's/^OBS_LOG_SERVICES=.*/OBS_LOG_SERVICES=traefik|backend|keycloak/' "${TMP}/obs.env"
obs up -d --wait alloy >/dev/null 2>&1
emit keycloak stdout "2026-11-03 10:00:06,000 INFO  [org.keycloak.events] (executor) ${RUN}-keycloak-POST type=LOGIN"
post_in() { has "{service=\"keycloak\"} |= \"${RUN}-keycloak-POST\"" 1; }
check "POST: keycloak seleccionado llega" wait_for 120 post_in
check "POST: sin marcador sensible de keycloak en Loki" has '{service="keycloak"} |= "SENSITIVE"' 0
check "PRE: el marcador anterior no entra al seleccionar" has "{service=~\".+\"} |= \"${RUN}-SENSITIVE-PRE\"" 0
check "PRE: el journal lo conserva (Alloy no puede borrarlo)" in_journal "${RUN}-SENSITIVE-PRE"

# ── 8. Fallas ────────────────────────────────────────────────────────────────
obs stop loki >/dev/null 2>&1
emit traefik stdout "2026-11-03T10:00:07Z INF ${RUN}-loki-outage"
sleep 30
RETRIES="$(alloy_metric loki_write_batch_retries_total | cut -d. -f1)"
check "corte de Loki: Alloy reintenta (${RETRIES})" test "${RETRIES}" -gt 0
obs start loki >/dev/null 2>&1
outage_in() { has "{service=\"traefik\"} |= \"${RUN}-loki-outage\"" 1; }
check "corte breve de Loki: llega por reintento, una vez" wait_for 180 outage_in

T_RESTART="$(date '+%Y-%m-%d %H:%M:%S')"
obs restart alloy >/dev/null 2>&1
wait_for 60 healthy
sleep 15
SINCE_RESTART="$(journalctl -q --no-pager --since "${T_RESTART}" -o cat | wc -l)"
READ_RESTART="$(alloy_metric loki_source_journal_target_lines_total | cut -d. -f1)"
check "reinicio de Alloy: no relee (${READ_RESTART} ≤ ${SINCE_RESTART} + 5 escritas desde el reinicio)" test "${READ_RESTART}" -le "$(( SINCE_RESTART + 5 ))"

emit traefik stdout "2026-11-03T10:00:08Z INF ${RUN}-before-kill"
kill_in() { has "{service=\"traefik\"} |= \"${RUN}-before-kill\"" 1; }
wait_for 60 kill_in
docker kill "$(obs ps -q loki)" >/dev/null 2>&1
obs up -d loki >/dev/null 2>&1
check "SIGKILL de Loki: listo tras reproducir el WAL" wait_for 180 loki_ready
check "SIGKILL de Loki: lo confirmado antes sigue consultable" wait_for 60 kill_in

if [ "${LONG_OUTAGE:-0}" = 1 ]; then
  obs stop loki >/dev/null 2>&1
  emit traefik stdout "2026-11-03T10:00:09Z INF ${RUN}-long-outage"
  echo "      corte largo de Loki: 11 min"
  sleep 660
  DROPPED="$(alloy_metric loki_write_dropped_entries_total | cut -d. -f1)"
  check "corte largo: Alloy descarta tras agotar reintentos (${DROPPED})" test "${DROPPED}" -gt 0
  obs start loki >/dev/null 2>&1
  wait_for 180 loki_ready
  sleep 30
  check "corte largo: la línea descartada no está en Loki (pérdida)" has "{service=\"traefik\"} |= \"${RUN}-long-outage\"" 0
fi

# ── 9. Avisos de journald ────────────────────────────────────────────────────
if [ "${BURST:-0}" = 1 ]; then
  # Más que RateLimitBurst (10000 por defecto en 30 s) de docker.service
  syn run --rm --no-deps -T traefik sh -c 'i=0; while [ $i -lt 20000 ]; do echo "burst $i"; i=$((i+1)); done; sleep 2' >/dev/null 2>&1
  sleep 35
  emit traefik stdout "${RUN}-after-burst"
  notice_in() { test "$(lcount '{service="journald"} |= "docker.service"')" -gt 0; }
  check "journald: el aviso de supresión de docker.service llega como service=journald" wait_for 120 notice_in
  journalctl -q --no-pager -o json --since "-5min" MESSAGE_ID=a596d6fe7bfa4994828e72309e95d61e _TRANSPORT=driver | jq -c '{t: ._TRANSPORT, obj: .OBJECT_SYSTEMD_UNIT, m: .MESSAGE}' | head -2 | sed 's/^/      aviso real: /'
fi

# ── 10. Métricas y aislamiento ─────────────────────────────────────────────────────
check "métricas: host, alloy, loki y prometheus up" bash -c "test \"\$1\" = 4" _ "$(pv 'count(up{job=~"host|alloy|loki|prometheus"} == 1)')"
check "métricas: métricas del host (raíz /)" test -n "$(pv 'node_filesystem_size_bytes{job="host",mountpoint="/"}')"
check "métricas: 15 reglas sanas (12 de S1/S2 + 3 de notificaciones)" bash -c "pexec_rules=\$1; test \"\$(jq '[.data.groups[].rules[] | select(.health == \"ok\")] | length' <<< \"\$pexec_rules\")\" = 15" _ "$(pexec wget -qO- http://localhost:9090/api/v1/rules 2>/dev/null)"
BINDINGS="$(for c in $(obs ps -q); do docker inspect "$c" --format '{{index .Config.Labels "com.docker.compose.service"}} {{json .HostConfig.PortBindings}}' | jq -Rr 'split(" ") as [$s, $b] | ($b | fromjson // {}) | to_entries[] | .key as $p | .value[] | "\($s) \($p) \(.HostIp):\(.HostPort)"'; done)"
check "aislamiento: único puerto publicado grafana en 127.0.0.1:${GPORT}" test "${BINDINGS}" = "grafana 3000/tcp 127.0.0.1:${GPORT}"
check "aislamiento: el stack instalado y la aplicación no cambiaron" test "$(others | sort | tr '\n' ' ')" = "${OTHERS_BEFORE}"

echo "recursos (laboratorio):"
docker stats --no-stream --format '      {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' $(obs ps -q) 2>/dev/null || true
echo "      loki_data: $(docker run --rm --network none -v "${OBS_PROJECT}_loki_data:/d:ro" "${BUSYBOX}" du -sh /d 2>/dev/null | cut -f1)"
echo "      entradas sintéticas en el journal del host: prefijo ${RUN} (no se borran)"

if [ "${FAILS}" -eq 0 ]; then echo "OK"; exit 0; else echo "${FAILS} comprobación(es) fallida(s)"; exit 1; fi
