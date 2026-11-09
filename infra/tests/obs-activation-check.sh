#!/usr/bin/env bash
# Activación del stack de observabilidad tras una interrupción y operación grafana-access: el rol
# observability real (playbooks/observability.yml) contra un Docker local, con un proyecto Compose
# y redes propios (pzact-<pid>), una copia del directorio infra/observability como "repositorio"
# (para cambiar configuración sin tocar el checkout) y un directorio temporal como
# /opt/piedrazul-obs. No contacta producción, GitHub, Cloudflare ni Discord: el receptor es none
# (Alertmanager monta el webhook pero no envía) y ningún JWT llega a Grafana. Correos y webhooks
# sintéticos; ninguno debe aparecer en la salida.
#
# Uso: infra/tests/obs-activation-check.sh
#   KEEP=1          no elimina el proyecto al terminar
#   SKIP_NEGATIVE=1 omite el control negativo con el rol anterior (HEAD)
# Requiere: docker (compose v2), ansible-playbook con community.docker, jq, git.
#
# Diferencias con producción, solo de prueba: sin become; dueños y UID de los secretos = el
# usuario actual y archivos secretos 0444 (los contenedores corren con otros UID); sin drop-in de
# journald; sin la verificación de la canalización de logs (obs_verify_logs_pipeline=false: el
# journal local puede no recibir entradas nuevas); alcance changed (sin aplicación: los destinos
# de la aplicación se informan sin bloquear).
#
# Verifica:
#   0. Validación sin red (observability-validate.sh): la configuración válida pasa; un receptor de
#      Alertmanager inexistente y una regla inválida fallan.
#   1. Instalación: termina; cada servicio con la etiqueta del digest de su configuración; el
#      contenedor ve el inodo vigente de cada archivo secreto; ningún correo en el entorno, las
#      etiquetas del contenedor ni la salida.
#   2. Sin cambios: ningún contenedor recreado.
#   3. Interrupción tras escribir una regla (sin recrear): el reintento recrea solo Prometheus y
#      Prometheus sirve la regla nueva. Lo mismo con una interrupción real (SIGTERM durante el run).
#   4. Retiro de un archivo anidado (un archivo de reglas): Prometheus deja de cargar su grupo.
#   5. Webhook rotado por un run interrumpido (archivo nuevo, Alertmanager sin recrear): el
#      reintento recrea solo Alertmanager y el contenedor ve el archivo nuevo.
#   6. grafana-access con Prometheus detenido: termina, recrea solo Grafana, Prometheus sigue
#      detenido; la expresión cargada cambia; listas vacías (none/none) válidas.
#   7. Listas inválidas o ausentes: fallan antes de cambiar nada (el archivo de acceso intacto).
#   8. Control negativo: el rol anterior (HEAD) con la misma interrupción termina bien mientras
#      Prometheus sigue con la regla anterior (el defecto que esta verificación detecta).
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
for bin in docker ansible-playbook jq git; do command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }; done

TMP="$(mktemp -d /tmp/pzact.XXXXXX)"
P="pzact-$$"
PROJECT="${P}-obs"
APP_NET="${P}_app"
EDGE_NET="${P}_edge"
H="${TMP}/obs"
SRC="${TMP}/src"
FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
check_not() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ko "${desc}"; else ok "${desc}"; fi; }
rnd() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

cleanup() {
  if [ "${KEEP:-0}" != 1 ]; then
    for proj in "${PROJECT}" "${P}-old-obs"; do
      ids="$(docker ps -aq --filter "label=com.docker.compose.project=${proj}")"
      [ -n "${ids}" ] && docker rm -f ${ids} >/dev/null 2>&1
      vols="$(docker volume ls -q --filter "label=com.docker.compose.project=${proj}")"
      [ -n "${vols}" ] && docker volume rm ${vols} >/dev/null 2>&1
      docker network rm "${proj}_obs_net" >/dev/null 2>&1
    done
    docker network rm "${APP_NET}" "${EDGE_NET}" >/dev/null 2>&1
    rm -rf "${TMP}"
  else
    echo "KEEP=1: ${TMP}, proyecto ${PROJECT}" >&2
  fi
}
trap cleanup EXIT

# Identidades y secretos sintéticos
V1="v1-$(rnd 4)@ops.example.org"; V2="v2-$(rnd 4)@ops.example.org"; E1="e1-$(rnd 4)@ops.example.org"
HOOK_A="https://discord.com/api/webhooks/123456789012345678/hookA$(rnd 20)"
HOOK_B="https://discord.com/api/webhooks/123456789012345678/hookB$(rnd 20)"
AUD="$(rnd 32)"
PW="synthetic-$(rnd 12)"
GPORT=$((20000 + RANDOM % 20000))

mkdir -p "${H}"
cp -a "${ROOT}/infra/observability" "${SRC}"
printf 'OBS_APP_NETWORK=%s\nGRAFANA_ADMIN_PASSWORD=%s\nGRAFANA_LOCAL_PORT=%s\n' "${APP_NET}" "${PW}" "${GPORT}" > "${H}/.env"
chmod 600 "${H}/.env"
docker network create "${APP_NET}" >/dev/null || { ko "red de la aplicación de prueba"; exit 1; }
docker network create --internal "${EDGE_NET}" >/dev/null || { ko "red de entrada de prueba"; exit 1; }
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n      ansible_connection: local\n      ansible_python_interpreter: %s\n' \
  "$(command -v python3)" > "${TMP}/hosts.yml"

COMMON=(-e "obs_dir=${H}" -e "obs_src_dir=${SRC}" -e "obs_compose_project_name=${PROJECT}"
  -e "obs_owner=$(id -un)" -e "obs_env_owner=$(id -un)" -e "obs_secrets_owner=$(id -un)"
  -e "obs_alertmanager_uid=$(id -u)" -e "obs_grafana_uid=$(id -u)" -e obs_secret_mode=0444
  -e '{"obs_journald_manage": false}' -e obs_verify_logs_pipeline=false -e obs_verify_scope=changed
  -e "obs_app_network=${APP_NET}" -e "obs_edge_network=${EDGE_NET}"
  -e obs_grafana_access_enabled=true -e obs_grafana_public_domain=grafana.example.org
  -e obs_grafana_access_team_domain=piedrazul.cloudflareaccess.com -e "obs_grafana_access_aud=${AUD}"
  -e obs_alert_receiver=none -e obs_environment=activation-check)

# run <etiqueta> [args...]: rol completo; VIEW/EDIT/HOOK por entorno
run() {
  local tag="$1"; shift
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" \
  ANSIBLE_COLLECTIONS_PATH="${ANSIBLE_DIR}/collections:${HOME}/.ansible/collections" PYTHONUNBUFFERED=1 \
  GRAFANA_VIEWER_EMAILS="${VIEW-none}" GRAFANA_EDITOR_EMAILS="${EDIT-none}" OBS_DISCORD_WEBHOOK_URL="${HOOK:-}" \
    ansible-playbook -i "${TMP}/hosts.yml" "${ANSIBLE_DIR}/playbooks/observability.yml" "${COMMON[@]}" "$@" \
    </dev/null > "${TMP}/${tag}.log" 2>&1
}
dc() { docker compose --project-name "${PROJECT}" --project-directory "${H}" -f "${H}/compose.yml" --env-file "${H}/.env" "$@"; }
cid() { docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" --filter "label=com.docker.compose.service=$1"; }
ids() { for s in prometheus grafana alloy loki alertmanager; do printf '%s=%s ' "$s" "$(cid "$s")"; done; }
label() { docker inspect --format '{{index .Config.Labels "org.piedrazul.obs.config-digest"}}' "$(cid "$1")"; }
envv() { sed -n "s/^$1=//p" "${H}/.env"; }
prom_rules() { dc exec -T prometheus wget -qO- http://localhost:9090/api/v1/rules; }
rule_loaded() { prom_rules | jq -e --arg n "$1" '[.data.groups[].rules[].name] | index($n) != null'; }
group_loaded() { prom_rules | jq -e --arg g "$1" '[.data.groups[].name] | index($g) != null'; }
ctr_inode() { docker exec "$(cid "$1")" stat -c %i "$2"; }
no_leak() { ! grep -qE "${V1}|${V2}|${E1}|hookA|hookB|${PW}" "${TMP}/$1.log"; }
fail_tail() { tail -15 "${TMP}/$1.log" | sed 's/^/    /' >&2; }
add_rule() { # <archivo de reglas en SRC> <nombre>
  yq -i ".groups[0].rules += [{\"alert\": \"$2\", \"expr\": \"vector(0) > 1\", \"for\": \"5m\", \"labels\": {\"severity\": \"info\"}, \"annotations\": {\"summary\": \"prueba\"}}]" "$1"
}

# ── 0. Validación sin red (observability-validate.sh, la del job observability_validate) ─────
VAL="${ROOT}/.github/scripts/observability-validate.sh"
check "validación: la configuración del repositorio es válida" "${VAL}" "${SRC}"
cp -a "${SRC}" "${TMP}/bad-am" && printf 'route:\n  receiver: inexistente\nreceivers: []\n' > "${TMP}/bad-am/alertmanager/none.yml"
check_not "validación: un receptor inexistente en Alertmanager falla" "${VAL}" "${TMP}/bad-am"
cp -a "${SRC}" "${TMP}/bad-rule" && printf 'groups:\n  - name: x\n    rules:\n      - alert: Roto\n        expr: "sum(("\n' > "${TMP}/bad-rule/prometheus/rules/broken.yml"
check_not "validación: una regla con PromQL inválido falla" "${VAL}" "${TMP}/bad-rule"
rm -rf "${TMP}/bad-am" "${TMP}/bad-rule"

# ── 1. Instalación ───────────────────────────────────────────────────────────
VIEW="${V1},${V2}" EDIT="${E1}" HOOK="${HOOK_A}"
if run install; then ok "instalación: termina (${PROJECT})"; else ko "instalación: termina"; fail_tail install; exit 1; fi
check "instalación: cada servicio con el digest de su configuración (etiqueta = .env)" \
  bash -c "for s in prometheus grafana alloy loki alertmanager; do v=\$(sed -n \"s/^OBS_DIGEST_\${s^^}=//p\" '${H}/.env'); [ -n \"\$v\" ] && [ \"\$(docker inspect --format '{{index .Config.Labels \"org.piedrazul.obs.config-digest\"}}' \"\$(docker ps -aq --filter label=com.docker.compose.project=${PROJECT} --filter label=com.docker.compose.service=\$s)\")\" = \"\$v\" ] || exit 1; done"
check "instalación: Alertmanager y Grafana ven el inodo vigente de sus archivos secretos" \
  bash -c "test \"\$(stat -c %i '${H}/secrets/discord_webhook_url')\" = \"$(ctr_inode alertmanager /run/secrets/discord_webhook_url)\" && test \"\$(stat -c %i '${H}/secrets/grafana_access.ini')\" = \"$(ctr_inode grafana /run/secrets/grafana_access)\""
check "instalación: ningún correo en el entorno ni en las etiquetas de Grafana" \
  bash -c "! docker inspect '$(cid grafana)' --format '{{json .Config.Env}}{{json .Config.Labels}}' | grep -qE '${V1}|${V2}|${E1}'"
check "instalación: .env sin listas de correos" bash -c "! grep -qE 'GRAFANA_ACCESS_(EDITORS|VIEWERS)|ops\\.example\\.org' '${H}/.env'"
check "instalación: sin correos, webhook ni contraseña en la salida" no_leak install

# ── 2. Sin cambios ───────────────────────────────────────────────────────────
BEFORE="$(ids)"
if run noop; then ok "sin cambios: termina"; else ko "sin cambios: termina"; fail_tail noop; fi
check "sin cambios: ningún contenedor recreado" test "$(ids)" = "${BEFORE}"

# ── 3a. Interrupción simulada: regla escrita en el host, Prometheus sin recrear ─
add_rule "${SRC}/prometheus/rules/alerts.yml" PzActRuleA
cp "${SRC}/prometheus/rules/alerts.yml" "${H}/prometheus/rules/alerts.yml"
check_not "interrupción: Prometheus todavía sirve las reglas anteriores (estado de partida)" rule_loaded PzActRuleA
BEFORE="$(ids)"
if run retry-a; then ok "reintento tras escribir una regla: termina"; else ko "reintento: termina"; fail_tail retry-a; fi
check "reintento: Prometheus sirve la regla nueva" rule_loaded PzActRuleA
check "reintento: solo Prometheus recreado" \
  bash -c "a='${BEFORE}'; b='$(ids)'; for s in grafana alloy loki alertmanager; do [ \"\$(tr ' ' '\n' <<< \"\$a\" | grep ^\$s=)\" = \"\$(tr ' ' '\n' <<< \"\$b\" | grep ^\$s=)\" ] || exit 1; done; [ \"\$(tr ' ' '\n' <<< \"\$a\" | grep ^prometheus=)\" != \"\$(tr ' ' '\n' <<< \"\$b\" | grep ^prometheus=)\" ]"

# ── 3b. Interrupción real: SIGTERM al run cuando ya calculó el digest ─────────
add_rule "${SRC}/prometheus/rules/alerts.yml" PzActRuleB
( run kill-b ) & RUNPID=$!
for _ in $(seq 1 300); do
  grep -q 'TASK \[observability : Validar la configuración de Compose\]' "${TMP}/kill-b.log" 2>/dev/null && break
  sleep 0.5
done
pkill -TERM -P "${RUNPID}" >/dev/null 2>&1; kill -TERM "${RUNPID}" >/dev/null 2>&1; wait "${RUNPID}" 2>/dev/null
check "interrupción real: el run se detuvo después de escribir la configuración y antes de levantar" \
  bash -c "grep -q 'Validar la configuración de Compose' '${TMP}/kill-b.log' && ! grep -q 'Levantar el stack de métricas' '${TMP}/kill-b.log' && grep -q PzActRuleB '${H}/prometheus/rules/alerts.yml'"
check_not "interrupción real: Prometheus todavía sin la regla" rule_loaded PzActRuleB
if run retry-b; then ok "reintento tras SIGTERM: termina"; else ko "reintento tras SIGTERM"; fail_tail retry-b; fi
check "reintento tras SIGTERM: Prometheus sirve la regla nueva" rule_loaded PzActRuleB

# ── 4. Retiro de un archivo anidado ──────────────────────────────────────────
GROUP="$(yq -r '.groups[0].name' "${SRC}/prometheus/rules/notifications.yml")"
rm "${SRC}/prometheus/rules/notifications.yml"
if run retire; then ok "retiro de un archivo de reglas: termina"; else ko "retiro: termina"; fail_tail retire; fi
check "retiro: el archivo ya no está en el host" test ! -e "${H}/prometheus/rules/notifications.yml"
check "retiro: Prometheus sigue con reglas cargadas" bash -c "$(declare -f dc prom_rules); PROJECT='${PROJECT}' H='${H}' prom_rules | jq -e '.data.groups | length > 0'"
check_not "retiro: Prometheus ya no carga el grupo retirado" group_loaded "${GROUP}"

# ── 5. Webhook rotado por un run interrumpido ────────────────────────────────
printf '%s' "${HOOK_B}" > "${H}/secrets/.hook.tmp" && chmod 0444 "${H}/secrets/.hook.tmp" && mv "${H}/secrets/.hook.tmp" "${H}/secrets/discord_webhook_url"
check "rotación interrumpida: Alertmanager ve el inodo anterior (estado de partida)" \
  bash -c "test \"\$(stat -c %i '${H}/secrets/discord_webhook_url')\" != \"$(ctr_inode alertmanager /run/secrets/discord_webhook_url)\""
BEFORE="$(ids)"
HOOK="${HOOK_B}"
if run rotate; then ok "reintento de la rotación: termina"; else ko "reintento de la rotación"; fail_tail rotate; fi
check "rotación: Alertmanager ve el archivo vigente (inodo y contenido)" \
  bash -c "test \"\$(stat -c %i '${H}/secrets/discord_webhook_url')\" = \"$(ctr_inode alertmanager /run/secrets/discord_webhook_url)\" && docker exec '$(cid alertmanager)' cat /run/secrets/discord_webhook_url | grep -q hookB"
check "rotación: solo Alertmanager recreado" \
  bash -c "a='${BEFORE}'; b='$(ids)'; for s in prometheus grafana alloy loki; do [ \"\$(tr ' ' '\n' <<< \"\$a\" | grep ^\$s=)\" = \"\$(tr ' ' '\n' <<< \"\$b\" | grep ^\$s=)\" ] || exit 1; done"
check "rotación: sin el webhook en la salida" no_leak rotate

# ── 6. grafana-access con Prometheus detenido ────────────────────────────────
docker stop "$(cid prometheus)" >/dev/null
BEFORE="$(ids)"
INODE_BEFORE="$(stat -c %i "${H}/secrets/grafana_access.ini")"
VIEW="${V2}" EDIT="none"
if run ga-revoke -e obs_operation=grafana-access; then ok "grafana-access con Prometheus detenido: termina"; else ko "grafana-access con Prometheus detenido"; fail_tail ga-revoke; fi
check "grafana-access: solo Grafana recreado; Prometheus sigue detenido" \
  bash -c "a='${BEFORE}'; b='$(ids)'; for s in prometheus alloy loki alertmanager; do [ \"\$(tr ' ' '\n' <<< \"\$a\" | grep ^\$s=)\" = \"\$(tr ' ' '\n' <<< \"\$b\" | grep ^\$s=)\" ] || exit 1; done; [ \"\$(tr ' ' '\n' <<< \"\$a\" | grep ^grafana=)\" != \"\$(tr ' ' '\n' <<< \"\$b\" | grep ^grafana=)\" ] && [ \"\$(docker inspect -f '{{.State.Running}}' '$(cid prometheus)')\" = false ]"
gset() { curl -sf -u "admin:${PW}" "http://127.0.0.1:${GPORT}/api/admin/settings" | jq -r '."auth.jwt".role_attribute_path'; }
check "grafana-access: la expresión cargada ya no incluye al revocado ni al editor y conserva al restante" \
  bash -c "p=\"\$1\"; ! grep -q '${V1}' <<< \"\$p\" && ! grep -q '${E1}' <<< \"\$p\" && grep -q '${V2}' <<< \"\$p\"" _ "$(gset)"
check "grafana-access: el modo de acceso sigue habilitado (JWT)" \
  bash -c "curl -sf -u 'admin:${PW}' 'http://127.0.0.1:${GPORT}/api/admin/settings' | jq -e '.\"auth.jwt\".enabled == \"true\" and .\"auth.jwt\".role_attribute_strict == \"true\"'"
check "grafana-access: archivo de acceso nuevo (inodo)" test "$(stat -c %i "${H}/secrets/grafana_access.ini")" != "${INODE_BEFORE}"
VIEW="none" EDIT="none"
if run ga-empty -e obs_operation=grafana-access; then ok "grafana-access con listas none/none: termina"; else ko "grafana-access none/none"; fail_tail ga-empty; fi
check "grafana-access none/none: ningún correo en la expresión cargada y JWT habilitado" \
  bash -c "p=\"\$1\"; ! grep -q '@' <<< \"\$p\" && curl -sf -u 'admin:${PW}' 'http://127.0.0.1:${GPORT}/api/admin/settings' | jq -e '.\"auth.jwt\".enabled == \"true\"'" _ "$(gset)"
check "grafana-access: administrador local por el loopback sigue funcionando" \
  bash -c "curl -sf -u 'admin:${PW}' 'http://127.0.0.1:${GPORT}/api/user' | jq -e '.login == \"admin\"'"
check "grafana-access: sin correos ni contraseña en la salida" bash -c "$(declare -f no_leak); V1='${V1}' V2='${V2}' E1='${E1}' PW='${PW}' TMP='${TMP}' no_leak ga-revoke && V1='${V1}' V2='${V2}' E1='${E1}' PW='${PW}' TMP='${TMP}' no_leak ga-empty"
docker start "$(cid prometheus)" >/dev/null

# ── 7. Listas inválidas o ausentes ───────────────────────────────────────────
reject() { # <descripción> <VIEW> <EDIT> [unset]
  local d="$1" before; before="$(stat -c %i "${H}/secrets/grafana_access.ini")"
  if [ "${4:-}" = unset ]; then
    if (unset VIEW; VIEW= EDIT="$3" run "rej" -e obs_operation=grafana-access); then ko "${d}: debía fallar"; return; fi
  else
    if VIEW="$2" EDIT="$3" run "rej" -e obs_operation=grafana-access; then ko "${d}: debía fallar"; return; fi
  fi
  if grep -q 'LISTAS DE GRAFANA' "${TMP}/rej.log" && [ "$(stat -c %i "${H}/secrets/grafana_access.ini")" = "${before}" ] \
     && ! grep -qE "${V1}|${V2}|${E1}|ops\\.example\\.org|Upper" "${TMP}/rej.log"; then
    ok "${d}: falla antes de cambiar nada, sin mostrar correos"
  else ko "${d}: falla antes de cambiar nada, sin mostrar correos"; fail_tail rej; fi
}
reject "lista Viewer ausente" "" "none" unset
reject "lista vacía (no none)" "" "none"
reject "correo en mayúsculas" "Upper@ops.example.org" "none"
reject "entrada vacía (coma final)" "${V1}," "none"
reject "correo repetido" "${V1},${V1}" "none"
reject "mismo correo en las dos listas" "${V1}" "${V1}"
reject "dominio .invalid" "x@piedrazul-obs.invalid" "none"
reject "none mezclado con correos" "none,${V1}" "none"

# ── 8. Control negativo: el rol anterior no detecta la configuración pendiente ─
if [ "${SKIP_NEGATIVE:-0}" != 1 ]; then
  OLD="${TMP}/old"; OH="${TMP}/old-obs"; OP="${P}-old-obs"
  mkdir -p "${OLD}" "${OH}"
  git -C "${ROOT}" archive HEAD infra/ansible infra/observability | tar -x -C "${OLD}"
  printf 'OBS_APP_NETWORK=%s\nGRAFANA_ADMIN_PASSWORD=%s\nGRAFANA_LOCAL_PORT=%s\n' "${APP_NET}" "${PW}" "$((GPORT + 1))" > "${OH}/.env"
  oldrun() {
    ANSIBLE_CONFIG="${OLD}/infra/ansible/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 ANSIBLE_ROLES_PATH="${OLD}/infra/ansible/roles" \
    ANSIBLE_COLLECTIONS_PATH="${ANSIBLE_DIR}/collections:${HOME}/.ansible/collections" \
      ansible-playbook -i "${TMP}/hosts.yml" "${OLD}/infra/ansible/playbooks/observability.yml" \
      -e "obs_dir=${OH}" -e "obs_compose_project_name=${OP}" -e "obs_owner=$(id -un)" -e "obs_env_owner=$(id -un)" \
      -e "obs_secrets_owner=$(id -un)" -e "obs_alertmanager_uid=$(id -u)" -e '{"obs_journald_manage": false}' \
      -e "obs_app_network=${APP_NET}" -e "obs_expected_jobs=[\"prometheus\",\"alloy\",\"loki\",\"alertmanager\",\"host\"]" \
      </dev/null > "${TMP}/$1.log" 2>&1
  }
  # El rol anterior exige la canalización de logs (no disponible aquí): su verificación final puede
  # fallar; lo que se observa es si su reintento activa la regla que dejó escrita un run
  # interrumpido (escrita en el repositorio y en el host, sin reiniciar Prometheus).
  oldrun old-install || true
  if docker ps -q --filter "label=com.docker.compose.project=${OP}" --filter label=com.docker.compose.service=prometheus | grep -q .; then
    yq -i '.groups[0].rules += [{"alert": "PzActOld", "expr": "vector(0) > 1", "for": "5m", "labels": {"severity": "info"}, "annotations": {"summary": "prueba"}}]' \
      "${OLD}/infra/observability/prometheus/rules/alerts.yml"
    cp "${OLD}/infra/observability/prometheus/rules/alerts.yml" "${OH}/prometheus/rules/alerts.yml"
    oldrun old-retry || true
    OLDC="$(docker ps -q --filter "label=com.docker.compose.project=${OP}" --filter label=com.docker.compose.service=prometheus)"
    if grep -q 'Reiniciar los servicios cuya configuración cambió' "${TMP}/old-retry.log" \
       && ! docker exec "${OLDC}" wget -qO- http://localhost:9090/api/v1/rules | jq -e '[.data.groups[].rules[].name] | index("PzActOld") != null' >/dev/null; then
      ok "control negativo: el rol anterior pasa por la activación sin reiniciar y Prometheus sigue sin la regla escrita (el defecto que la sección 3 corrige)"
    else
      ko "control negativo: se esperaba que el rol anterior dejara la regla pendiente sin activar"; fail_tail old-retry
    fi
  else
    ko "control negativo: el rol anterior no levantó Prometheus"; fail_tail old-install
  fi
fi

echo
if [ "${FAILS}" -eq 0 ]; then echo "obs-activation-check: todo pasa"; exit 0; fi
echo "obs-activation-check: ${FAILS} fallo(s)"; exit 1
