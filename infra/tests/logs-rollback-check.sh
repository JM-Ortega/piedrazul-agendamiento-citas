#!/usr/bin/env bash
# Verificación local de la vuelta a métricas sin logs (docs/operations/runbooks/logs.md,
# "Retirada") con Docker, sin host remoto ni journald: stack con logs en un proyecto propio →
# paso 1 (configuración de la versión sin logs, como la deja observability.yml de esa versión) →
# paso 2 (playbooks/observability-logs-rollback.yml real, en local) → repetición → purge.
#
# El paso 1 se emula: se copian los archivos de la versión sin logs (ROLLBACK_BASE_REF) con la
# misma reconciliación que hace el rol (copiar y borrar lo retirado en los directorios
# gestionados) y se levanta y reinicia lo que el rol reiniciaría. El rol no corre en local
# (exige el usuario ansible, la red de la aplicación y los productores). El drop-in de journald
# no se prueba aquí (obs_journald_manage=false): lo cubre logs-lab-ansible-check.sh.
#
# Uso: ROLLBACK_BASE_REF=<commit sin logs> infra/tests/logs-rollback-check.sh
# Entorno opcional: GRAFANA_LOCAL_PORT (por defecto 13400), KEEP=1.
# Requiere: git, docker (compose v2), jq, ansible-playbook, ansible-galaxy (instala las colecciones
#   de requirements.yml en el temporal; red la primera vez), openssl, curl.
# S3: la versión actual trae además Alertmanager (receptor none, modo privado sin red de entrada).
# La retirada de logs no lo toca:
# queda en marcha con su volumen (su retirada es la de S3, docs/operations/runbooks/alertas.md).
#
# Salida: 0 si todo pasa; 1 si algo falla; 2 sin ROLLBACK_BASE_REF o herramientas.
set -uo pipefail

[ -n "${ROLLBACK_BASE_REF:-}" ] || { echo "ROLLBACK_BASE_REF=<commit de la versión sin logs> es obligatorio" >&2; exit 2; }
for bin in git docker jq ansible-playbook openssl curl; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
ID="$$"
P="pzrb-check-${ID}-obs"
NET="pzrb_check_${ID}_net"
GPORT="${GRAFANA_LOCAL_PORT:-13400}"
TMP="$(mktemp -d)"
H="${TMP}/host"          # hace de /opt/piedrazul-obs
BASE="${TMP}/base"       # árbol de la versión sin logs

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
wait_for() { local t="$1"; shift; for _ in $(seq 1 $(( t / 5 ))); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1; }

STARTED=false
cleanup() {
  if [ "${STARTED}" = true ] && [ "${KEEP:-0}" != 1 ]; then
    for c in $(docker ps -aq --filter label=com.docker.compose.project="${P}"); do docker container stop "$c" >/dev/null 2>&1; done
    docker container prune -f --filter label=com.docker.compose.project="${P}" >/dev/null 2>&1
    docker volume prune -af --filter label=com.docker.compose.project="${P}" >/dev/null 2>&1
    docker network prune -f --filter label=com.docker.compose.project="${P}" >/dev/null 2>&1
    docker network rm "${NET}" >/dev/null 2>&1
  fi
  [ "${KEEP:-0}" = 1 ] || rm -rf "${TMP}"
}
trap cleanup EXIT

git -C "${ROOT}" cat-file -e "${ROLLBACK_BASE_REF}^{commit}" 2>/dev/null || { echo "no existe ${ROLLBACK_BASE_REF}" >&2; exit 2; }
mkdir -p "${BASE}" "${H}"
git -C "${ROOT}" archive "${ROLLBACK_BASE_REF}" infra/observability | tar -x -C "${BASE}"
test ! -e "${BASE}/infra/observability/loki" || { echo "${ROLLBACK_BASE_REF} ya tiene logs" >&2; exit 2; }

dc() { (cd "${H}" && docker compose -p "${P}" --env-file .env -f compose.yml "$@"); }
G="http://127.0.0.1:${GPORT}"
gauth() { printf 'admin:%s' "$(sed -n 's/^GRAFANA_ADMIN_PASSWORD=//p' "${H}/.env")"; }
ds_uids() { curl -sf -u "$(gauth)" "${G}/api/datasources" | jq -r '[.[].uid] | sort | join(",")'; }
dash_uids() { curl -sf -u "$(gauth)" "${G}/api/search?type=dash-db" | jq -r '[.[].uid] | sort | join(",")'; }
svc_running() { docker ps --filter label=com.docker.compose.project="${P}" --format '{{.Label "com.docker.compose.service"}}' | sort | tr '\n' ','; }
vols() { docker volume ls -q --filter label=com.docker.compose.project="${P}" | sed "s/^${P}_//" | sort | tr '\n' ','; }
prom_jobs() { dc exec -T prometheus wget -qO- http://localhost:9090/api/v1/targets 2>/dev/null | jq -r '[.data.activeTargets[].labels.job] | unique | join(",")'; }
rule_count() { dc exec -T prometheus wget -qO- http://localhost:9090/api/v1/rules 2>/dev/null | jq '[.data.groups[].rules[]] | length'; }
grafana_ok() { curl -sf "${G}/api/health" >/dev/null; }
rollback() { # rollback <log> [extra-vars...]: el playbook real, en local, sin become ni journald
  local log="${TMP}/$1.log"; shift
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_COLLECTIONS_PATH="${TMP}/collections" \
    ansible-playbook -i 'vps,' -c local -e ansible_python_interpreter=auto_silent \
      -e "obs_dir=${H}" -e "obs_compose_project_name=${P}" -e obs_journald_manage=false "$@" \
      "${ANSIBLE_DIR}/playbooks/observability-logs-rollback.yml" </dev/null > "${log}" 2>&1
}
changed() { grep -E '^vps\s+:' "${TMP}/$1.log" | sed -nE 's/.*changed=([0-9]+).*/\1/p'; }

# Colecciones fijadas en requirements.yml, en el temporal (no las globales del controlador)
ansible-galaxy collection install --force -r "${ANSIBLE_DIR}/requirements.yml" -p "${TMP}/collections" >/dev/null 2>&1 \
  || { echo "no se pudieron instalar las colecciones de requirements.yml" >&2; exit 2; }

# ── Estado con logs (versión actual) ─────────────────────────────────────────
cp -r "${ROOT}/infra/observability/." "${H}/"
printf 'GRAFANA_ADMIN_PASSWORD=%s\nOBS_APP_NETWORK=%s\nGRAFANA_LOCAL_PORT=%s\nOBS_JOURNAL_GID=65534\nOBS_LOG_PROJECT=piedrazul\nOBS_LOG_SERVICES=traefik\n' \
  "$(openssl rand -hex 16)" "${NET}" "${GPORT}" > "${H}/.env"
chmod 600 "${H}/.env"
mkdir -p "${H}/secrets" && : > "${H}/secrets/discord_webhook_url" && chmod 644 "${H}/secrets/discord_webhook_url"
docker network create "${NET}" >/dev/null
STARTED=true
dc up -d --wait --wait-timeout 180 > "${TMP}/up.log" 2>&1 || { ko "con logs: stack levantado"; tail -20 "${TMP}/up.log" >&2; exit 1; }
wait_for 90 grafana_ok
check "con logs: cinco servicios (con Alertmanager de S3)" test "$(svc_running)" = "alertmanager,alloy,grafana,loki,prometheus,"
check "con logs: fuentes de datos loki y prometheus" test "$(ds_uids)" = "loki,prometheus"
STAR="$(curl -s -o /dev/null -w '%{http_code}' -u "$(gauth)" -X POST "${G}/api/user/stars/dashboard/uid/piedrazul-plataforma")"
check "con logs: estado propio de Grafana (dashboard marcado)" test "${STAR}" = 200

# ── Paso 2 antes del paso 1: debe negarse sin cambiar nada ───────────────────
IDS_BEFORE="$(docker ps -q --filter label=com.docker.compose.project="${P}" | sort | tr '\n' ' ')"
if rollback early; then ko "orden: la retirada sin el paso 1 debía fallar"; else
  check "orden: la retirada sin el paso 1 falla en su comprobación" grep -q 'todavía tiene la configuración con logs' "${TMP}/early.log"
fi
check "orden: ese fallo no cambió contenedores, fuentes de datos ni .env" \
  bash -c "test \"\$1\" = \"\$2\" && test \"\$3\" = loki,prometheus && grep -q '^OBS_LOG_SERVICES=' '${H}/.env'" _ \
    "$(docker ps -q --filter label=com.docker.compose.project="${P}" | sort | tr '\n' ' ')" "${IDS_BEFORE}" "$(ds_uids)"

# ── Paso 1 (emulado): configuración de la versión sin logs ───────────────────
cp "${BASE}/infra/observability/compose.yml" "${H}/compose.yml"
for d in prometheus alloy grafana/provisioning grafana/dashboards; do
  (cd "${H}/${d}" && find . -type f) | while read -r f; do
    [ -e "${BASE}/infra/observability/${d}/${f}" ] || rm -f -- "${H}/${d}/${f}"
  done
  cp -r "${BASE}/infra/observability/${d}/." "${H}/${d}/"
done
dc up -d --wait --wait-timeout 180 > "${TMP}/step1.log" 2>&1
dc restart prometheus alloy grafana >> "${TMP}/step1.log" 2>&1
wait_for 90 grafana_ok
check "paso 1: Prometheus sin alloy ni loki" wait_for 60 bash -c "test \"\$($(declare -f dc prom_jobs); H='${H}' P='${P}' prom_jobs)\" = backend,host,keycloak,prometheus,traefik"
check "paso 1: siete reglas (las de logs retiradas)" test "$(rule_count)" = 7
check "paso 1: dashboard de logs retirado" wait_for 90 bash -c "test \"\$(curl -sf -u '$(gauth)' '${G}/api/search?type=dash-db' | jq -r '[.[].uid] | sort | join(\",\")')\" = piedrazul-backend-jvm,piedrazul-plataforma"
echo "      estado intermedio: servicios $(svc_running) fuentes de datos $(ds_uids)"
check "paso 1: estado intermedio sin degradación (Loki huérfano sirve su fuente de datos)" \
  bash -c "test \"\$1\" = alertmanager,alloy,grafana,loki,prometheus, && test \"\$2\" = loki,prometheus && curl -sf -u '$(gauth)' '${G}/api/datasources/uid/loki/health' | jq -e '.status == \"OK\"'" _ "$(svc_running)" "$(ds_uids)"

# ── Paso 2: retirada ─────────────────────────────────────────────────────────
if rollback step2; then ok "paso 2: la retirada termina (changed=$(changed step2))"; else ko "paso 2: la retirada"; tail -30 "${TMP}/step2.log" >&2; fi
check "paso 2: sin Loki (Alertmanager de S3 intacto)" test "$(svc_running)" = "alertmanager,alloy,grafana,prometheus,"
check "paso 2: fuente de datos Loki retirada de Grafana" test "$(ds_uids)" = prometheus
check "paso 2: sin archivo temporal de retiro" test ! -e "${H}/grafana/provisioning/datasources/zz-retire-loki.yml"
check "paso 2: sin configuración de Loki" test ! -e "${H}/loki"
check "paso 2: .env sin variables de logs, con la contraseña" \
  bash -c "! grep -qE '^OBS_(JOURNAL_GID|LOG_PROJECT|LOG_SERVICES)=' '${H}/.env' && grep -q '^GRAFANA_ADMIN_PASSWORD=.' '${H}/.env'"
check "paso 2: Grafana conserva su estado (dashboard marcado)" \
  bash -c "curl -sf -u '$(gauth)' '${G}/api/search?starred=true' | jq -e '[.[].uid] | index(\"piedrazul-plataforma\")'"
check "paso 2: fuente de datos prometheus sana" bash -c "curl -sf -u '$(gauth)' '${G}/api/datasources/uid/prometheus/health' | jq -e '.status == \"OK\"'"
check "paso 2: volúmenes de métricas y de logs conservados (sin purge)" test "$(vols)" = "alertmanager_data,alloy_data,grafana_data,loki_data,prometheus_data,"

rollback again
check "repetición: la retirada no cambia nada (changed=$(changed again))" test "$(changed again)" = 0

rollback purge -e obs_logs_purge=true
check "purge: borra loki_data y alloy_data, conserva los de métricas y alertas" test "$(vols)" = "alertmanager_data,grafana_data,prometheus_data,"
rollback purge-again -e obs_logs_purge=true
check "purge: repetirlo no cambia nada (changed=$(changed purge-again))" test "$(changed purge-again)" = 0
check "final: métricas en marcha (prometheus, grafana, alloy) y Alertmanager intacto" test "$(svc_running)" = "alertmanager,alloy,grafana,prometheus,"

if [ "${FAILS}" -eq 0 ]; then echo "OK"; exit 0; else echo "${FAILS} comprobación(es) fallida(s)"; exit 1; fi
