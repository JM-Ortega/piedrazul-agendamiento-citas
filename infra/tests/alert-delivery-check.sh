#!/usr/bin/env bash
# Entrega de alertas: Prometheus → Alertmanager → webhook con la interfaz de Discord.
#
# Usa compose.yml real (Prometheus y Alertmanager con sus imágenes, reglas y configuración del
# repositorio) en un proyecto y una red propios. El "Discord" es infra/tests/discord-sink.py dentro
# de la red de la prueba: no contacta Discord ni ningún servicio externo. El webhook es sintético,
# se entrega como en producción (archivo secreto del UID de Alertmanager, 0400) y nunca se
# imprime: la evidencia registra solo el sha256 de la ruta. Es evidencia de implementación, no de
# un canal real de Discord (eso se verifica en vivo, runbook de alertas).
#
# Diferencias con producción, solo de prueba: Prometheus lee una copia de prometheus/ con un
# destino y cinco reglas extra (delivery_probe, PiedrazulDeliveryProbe{A..E}, sin for) cuyo
# estado controla la prueba; las reglas y la configuración reales van sin cambios. Grafana, Alloy
# y Loki no se levantan (sus destinos y los de la aplicación quedan down: PiedrazulTargetDown real
# dispara a los 5 min y también se entrega).
#
# Uso: infra/tests/alert-delivery-check.sh   (≈ 15 min: group_interval real de 5 min)
#   KEEP=1         no elimina el proyecto al terminar
#   STATIC_ONLY=1  solo configuración y plantilla (1 y 12), sin contenedores de larga duración
# Requiere: docker (compose v2), jq, yq (mikefarah v4).
#
# Verifica:
#   1. Configuración: amtool check-config (discord y none); promtool check/test de las reglas de
#      notificación y de las 12 existentes (desde la base F4 solo cambió la expresión de
#      PiedrazulHttp5xxRatioHigh); Prometheus apunta a alertmanager:9093;
#      Alertmanager sin puerto, sin clúster, con el secreto solo en él; el webhook no figura en
#      ningún archivo versionado ni en la configuración renderizada.
#   2. Receptor none: Alertmanager recibe alertas y no envía nada.
#   3. Activación discord (recrear con OBS_ALERT_RECEIVER=discord): llega la sonda E (info) como
#      [INFO]; no hay latido ni otro mensaje periódico.
#   4. Disparo con reintentos: el receptor devuelve 500 dos veces y la tercera entrega llega; sin
#      contar como notificación perdida. Un solo mensaje por grupo (sin duplicados).
#   5. Resolución: al apagarse la condición llega el mensaje RESUELTA (✅ warning, ⚪ info).
#   6. Regla real: PiedrazulTargetDown (5 min) llega a Discord.
#   7. Falla definitiva (404 Unknown Webhook): se cuenta como perdida, PiedrazulAlertNotificationsFailing
#      dispara en Prometheus y, recuperado el webhook, también llega a Discord.
#   8. Rotación del webhook: reemplazar el archivo (atómico, como Ansible) no llega al contenedor
#      en marcha; tras recrear Alertmanager (lo que hace el rol) el siguiente envío va a la ruta
#      nueva y no se reenvía nada ya entregado.
#   9. Red caída: Alertmanager registra el error sin la URL del webhook.
#  10. Entorno: cada mensaje empieza por [OBS_ENVIRONMENT] (etiqueta externa de Prometheus), sin
#      tocar series, ALERTS ni reglas.
#  11. El token del webhook no aparece en logs, estado ni configuración de Prometheus ni de
#      Alertmanager.
#  12. Plantilla (amtool template render, sin red): [INFO]/🔵 para info, [FIRING] con 🟠 warning y
#      🔴 critical o sin severidad, [RESUELTA] con ✅ para incidentes y ⚪ con su descripción para
#      una informativa, [sin-entorno] y ×N. Con STATIC_ONLY=1 corre solo la configuración (1 y 12).
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OBS="${ROOT}/infra/observability"
ID="$$-$(head -c 3 /dev/urandom | od -An -tx1 | tr -d ' \n')"
PROJECT="pzadc-${ID}"
APP_NET="pzadc_${ID}_app"
EDGE_NET="pzadc_${ID}_edge"

for bin in docker jq yq; do command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }; done

TMP="$(mktemp -d)"
FAILS=0
RUNTIME_STARTED=false
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
obs() { docker compose -p "${PROJECT}" --project-directory "${OBS}" -f "${OBS}/compose.yml" -f "${TMP}/override.yml" --env-file "${TMP}/obs.env" "$@"; }
cleanup() {
  if [ "${RUNTIME_STARTED}" = true ] && [ "${KEEP:-0}" != 1 ]; then
    obs down -v --remove-orphans >/dev/null 2>&1 || true
    docker network rm "${APP_NET}" "${EDGE_NET}" >/dev/null 2>&1 || true
  fi
  # El secreto es del UID 65534: se borra con el mismo mecanismo que lo escribió
  docker run --rm -v "${TMP}:/t" --entrypoint sh "${AM_IMAGE:-busybox}" -c 'rm -rf /t/secrets' >/dev/null 2>&1 || true
  rm -rf "${TMP}"
}
trap cleanup EXIT

AM_IMAGE="$(yq -r '.services.alertmanager.image' "${OBS}/compose.yml")"
PROM_IMAGE="$(yq -r '.services.prometheus.image' "${OBS}/compose.yml")"
amtool() { docker run --rm -v "${OBS}/alertmanager:/etc/alertmanager:ro" --entrypoint amtool "${AM_IMAGE}" "$@"; }
promtool() { docker run --rm -v "${OBS}:/w:ro" --entrypoint promtool "${PROM_IMAGE}" "$@"; }

# Webhook sintético con la forma de Discord, hacia el receptor de la prueba
TOKEN_A="synthTokenA$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
TOKEN_B="synthTokenB$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
HOOK_ID=123456789012345678
URL_A="http://sink:8080/api/webhooks/${HOOK_ID}/${TOKEN_A}"
URL_B="http://sink:8080/api/webhooks/${HOOK_ID}/${TOKEN_B}"
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
PATH_A="$(sha "/api/webhooks/${HOOK_ID}/${TOKEN_A}")"
PATH_B="$(sha "/api/webhooks/${HOOK_ID}/${TOKEN_B}")"

# ── 1. Configuración ─────────────────────────────────────────────────────────
check "amtool: discord.yml y none.yml válidos (con la plantilla)" \
  amtool check-config /etc/alertmanager/discord.yml /etc/alertmanager/none.yml
check "promtool: reglas de notificación válidas (3, sin latido)" \
  bash -c "docker run --rm -v '${OBS}:/w:ro' --entrypoint promtool '${PROM_IMAGE}' check rules /w/prometheus/rules/notifications.yml | grep -q 'SUCCESS: 3 rules found'"
if promtool test rules /w/tests/notifications-alerts.test.yml /w/tests/alerts.test.yml /w/tests/logs-alerts.test.yml > "${TMP}/promtool.log" 2>&1; then
  ok "promtool: test rules de notificaciones y de las reglas existentes (sin cambios)"
else ko "promtool: test rules"; tail -20 "${TMP}/promtool.log" >&2; fi
# Desde la base F4 (030b9978) solo cambió la expresión de PiedrazulHttp5xxRatioHigh (sin los 501 de
# Grafana) y se agregaron casos a alerts.test.yml; nombres, duraciones, etiquetas y anotaciones
# de las 12 reglas y cada caso anterior de alerts.test.yml siguen iguales
rules_vs_base() {
  local f="infra/observability/prometheus/rules/alerts.yml" t="infra/observability/tests/alerts.test.yml" n
  local norm='del(.groups[].rules[] | select(.alert == "PiedrazulHttp5xxRatioHigh") | .expr)'
  cd "${ROOT}" || return 1
  git diff --quiet 030b9978 -- infra/observability/prometheus/rules/logs.yml infra/observability/tests/logs-alerts.test.yml || return 1
  diff <(git show "030b9978:${f}" | yq -o=json "${norm}") <(yq -o=json "${norm}" "${f}") >/dev/null || return 1
  jq -n -e --slurpfile a <(git show "030b9978:${t}" | yq -o=json '.tests') --slurpfile b <(yq -o=json '.tests' "${t}") \
    '($a[0] - $b[0]) == [] and ($b[0] | length) > ($a[0] | length)' >/dev/null
}
check "reglas existentes: logs.yml igual a la base F4 (030b9978); alerts.yml solo cambia la expresión de PiedrazulHttp5xxRatioHigh y sus pruebas anteriores siguen" \
  rules_vs_base
check "Prometheus: envía a alertmanager:9093 y lo lee como destino" \
  test "$(yq -r '[.alerting.alertmanagers[].static_configs[].targets[]] | join(",")' "${OBS}/prometheus/prometheus.yml") $(yq -r '.scrape_configs[] | select(.job_name == "alertmanager") | .static_configs[0].targets[0]' "${OBS}/prometheus/prometheus.yml")" = "alertmanager:9093 alertmanager:9093"
check "discord.yml: send_resolved, webhook por archivo (sin URL en la configuración)" \
  test "$(yq -r '.receivers[0].discord_configs[0] | [(.send_resolved | tostring), .webhook_url_file, (.webhook_url // "none")] | join(" ")' "${OBS}/alertmanager/discord.yml")" = "true /run/secrets/discord_webhook_url none"
check "discord.yml: agrupa por alertname, recordatorio 12 h, una sola ruta (sin latido); sin inhibiciones ni silencios" \
  test "$(yq -r '[(.route.group_by | join(",")), .route.repeat_interval, ((.route.routes // []) | length | tostring), ((.inhibit_rules // []) | length | tostring)] | join(" ")' "${OBS}/alertmanager/discord.yml")" = "alertname 12h 0 0"
check "reglas: ninguna siempre en firing ni mensaje periódico (sin PiedrazulNotificationHeartbeat ni vector())" \
  bash -c "! grep -rqE 'PiedrazulNotificationHeartbeat|expr: *vector\\(' '${OBS}/prometheus/rules' '${OBS}/alertmanager'"
check "repositorio: ninguna URL de webhook de Discord versionada" \
  bash -c "! git -C '${ROOT}' grep -qE 'discord(app)?\\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]{20,}' -- . ':!infra/tests/grafana-access-check.sh'"

# Plantilla: amtool la ejecuta con grupos sintéticos (sin red ni receptor) por severidad y estado
mkdir -p "${TMP}/tmpl"
tmpl_data() { # <archivo> <estado firing|resolved> <severidad|""> <entorno|""> [n alertas]
  jq -n --arg st "$2" --arg sev "$3" --arg env "$4" --argjson n "${5:-1}" '
    ({alertname: "PiedrazulPrueba"} + (if $sev == "" then {} else {severity: $sev} end)
      + (if $env == "" then {} else {environment: $env} end)) as $l
    | {receiver: "discord", status: $st, groupLabels: {alertname: "PiedrazulPrueba"}, commonLabels: $l,
       commonAnnotations: {}, externalURL: "http://alertmanager:9093",
       alerts: [range($n) | {status: $st, labels: ($l + {instance: "i\(.)"}),
         annotations: {summary: "Resumen de prueba", description: "Descripción de prueba"},
         startsAt: "2026-11-05T13:45:00Z", endsAt: (if $st == "resolved" then "2026-11-05T14:00:00Z" else "0001-01-01T00:00:00Z" end)}]}' \
    > "${TMP}/tmpl/$1.json"
}
render() { # <datos> <plantilla>
  docker run --rm -v "${OBS}/alertmanager:/etc/alertmanager:ro" -v "${TMP}/tmpl:/d:ro" --entrypoint amtool "${AM_IMAGE}" \
    template render --template.glob='/etc/alertmanager/templates/*.tmpl' --template.data="/d/$1.json" \
    --template.text="{{ template \"piedrazul.discord.$2\" . }}"
}
tmpl_case() { # <datos> <título esperado> <regex del mensaje> [regex que no debe estar]
  local t m
  t="$(render "$1" title)" && m="$(render "$1" message)" || return 1
  printf '%s | %s\n' "${t}" "$(tr '\n' ' ' <<< "${m}")" >> "${TMP}/tmpl/rendered.txt"
  [ "${t}" = "$2" ] && grep -qE -- "$3" <<< "${m}" && { [ -z "${4:-}" ] || ! grep -qE -- "$4" <<< "${m}"; }
}
tmpl_has() { render "$1" message | grep -qE -- "$2"; }
tmpl_data info-firing firing info staging;         tmpl_data info-resolved resolved info staging
tmpl_data warning-firing firing warning staging;   tmpl_data warning-resolved resolved warning staging
tmpl_data critical-firing firing critical staging; tmpl_data critical-resolved resolved critical staging
tmpl_data nosev-firing firing "" staging;          tmpl_data noenv-firing firing warning ""
tmpl_data critical-two firing critical produccion 2
check "plantilla: info en firing es [INFO] con 🔵, no un incidente rojo" \
  tmpl_case info-firing "[staging] [INFO] PiedrazulPrueba (info)" '^🔵 Resumen de prueba' '🔴|🟠'
check "plantilla: info resuelta es [RESUELTA] neutra (⚪, ya no está activa) con su descripción" \
  tmpl_case info-resolved "[staging] [RESUELTA] PiedrazulPrueba (info)" '^⚪ Resumen de prueba: ya no está activa' '✅'
check "plantilla: info resuelta conserva la descripción (qué significa que deje de estar activa)" \
  tmpl_has info-resolved '^Descripción de prueba$' 
check "plantilla: warning en firing es [FIRING] con 🟠" \
  tmpl_case warning-firing "[staging] [FIRING] PiedrazulPrueba (warning)" '^🟠 Resumen de prueba' '🔴|🔵'
check "plantilla: critical en firing es [FIRING] con 🔴" \
  tmpl_case critical-firing "[staging] [FIRING] PiedrazulPrueba (critical)" '^🔴 Resumen de prueba' '🟠|🔵'
check "plantilla: warning resuelta es [RESUELTA] con ✅" \
  tmpl_case warning-resolved "[staging] [RESUELTA] PiedrazulPrueba (warning)" '^✅ Resumen de prueba' '⚪'
check "plantilla: critical resuelta es [RESUELTA] con ✅" \
  tmpl_case critical-resolved "[staging] [RESUELTA] PiedrazulPrueba (critical)" '^✅ Resumen de prueba' '⚪' 
check "plantilla: sin severidad se trata como incidente ([FIRING], 🔴)" \
  tmpl_case nosev-firing "[staging] [FIRING] PiedrazulPrueba" '^🔴 Resumen de prueba'
check "plantilla: sin entorno dice [sin-entorno]" \
  tmpl_case noenv-firing "[sin-entorno] [FIRING] PiedrazulPrueba (warning)" '^🟠 Resumen de prueba'
tmpl_two() { tmpl_case critical-two "[produccion] [FIRING ×2] PiedrazulPrueba (critical)" '^🔴 Resumen de prueba' && test "$(render critical-two message | grep -c '^🔴')" = 2; }
check "plantilla: un grupo con dos alertas cuenta ×2 y lista ambas" tmpl_two
if [ -n "${EVIDENCE_DIR:-}" ]; then mkdir -p "${EVIDENCE_DIR}" && cp "${TMP}/tmpl/rendered.txt" "${EVIDENCE_DIR}/template-rendered.txt"; fi

if [ "${STATIC_ONLY:-0}" = 1 ]; then
  echo "STATIC_ONLY=1: sin pruebas en ejecución"
  [ "${FAILS}" -eq 0 ] && { echo "OK"; exit 0; } || { echo "${FAILS} comprobación(es) fallida(s)"; exit 1; }
fi

mkdir -p "${TMP}/secrets" "${TMP}/ctl" "${TMP}/data" "${TMP}/prometheus"
chmod 0700 "${TMP}/secrets"
put_secret() { # <contenido> → archivo del UID 65534, 0400, como lo deja el rol de Ansible
  printf '%s' "$1" | docker run --rm -i -v "${TMP}/secrets:/s" --entrypoint sh "${AM_IMAGE}" -c \
    'cat > /s/.new && chmod 0400 /s/.new && mv -f /s/.new /s/discord_webhook_url' >/dev/null 2>&1 \
  || printf '%s' "$1" | docker run --rm -i -u 0 -v "${TMP}/secrets:/s" --entrypoint sh "${AM_IMAGE}" -c \
    'cat > /s/.new && chown 65534:65534 /s/.new && chmod 0400 /s/.new && mv -f /s/.new /s/discord_webhook_url'
}
chmod 0777 "${TMP}/secrets"   # el contenedor (65534) escribe aquí solo en la preparación
put_secret "${URL_A}"
chmod 0700 "${TMP}/secrets"
cat > "${TMP}/obs.env" <<EOF
GRAFANA_ADMIN_PASSWORD=unused-$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')
OBS_APP_NETWORK=${APP_NET}
OBS_EDGE_NETWORK=${EDGE_NET}
OBS_ALERT_RECEIVER=none
OBS_ENVIRONMENT=delivery-check
OBS_DISCORD_WEBHOOK_FILE=${TMP}/secrets/discord_webhook_url
EOF
check "secreto: archivo 0400 del UID 65534 dentro de un directorio 0700" \
  bash -c "test \"\$(docker run --rm -u 0 -v '${TMP}/secrets:/s:ro' --entrypoint stat '${AM_IMAGE}' -c '%a %u' /s/discord_webhook_url)\" = '400 65534' && test \"\$(stat -c %a '${TMP}/secrets')\" = 700"

# Copia de prometheus/ con el destino y las reglas de prueba
cp -r "${OBS}/prometheus/." "${TMP}/prometheus/"
yq -i '.scrape_configs += [{"job_name": "delivery_probe", "static_configs": [{"targets": ["probe:8080"]}]}]' "${TMP}/prometheus/prometheus.yml"
cat > "${TMP}/prometheus/rules/zz-delivery-probe.yml" <<'EOF'
groups:
  - name: delivery-probe
    rules:
      - {alert: PiedrazulDeliveryProbeA, expr: 'delivery_probe_state{probe="A"} == 1', labels: {severity: warning}, annotations: {summary: "Sonda A de entrega", description: "Alerta sintética de la prueba de entrega (A)."}}
      - {alert: PiedrazulDeliveryProbeB, expr: 'delivery_probe_state{probe="B"} == 1', labels: {severity: warning}, annotations: {summary: "Sonda B de entrega", description: "Alerta sintética de la prueba de entrega (B)."}}
      - {alert: PiedrazulDeliveryProbeC, expr: 'delivery_probe_state{probe="C"} == 1', labels: {severity: warning}, annotations: {summary: "Sonda C de entrega", description: "Alerta sintética de la prueba de entrega (C)."}}
      - {alert: PiedrazulDeliveryProbeD, expr: 'delivery_probe_state{probe="D"} == 1', labels: {severity: warning}, annotations: {summary: "Sonda D de entrega", description: "Alerta sintética de la prueba de entrega (D)."}}
      - {alert: PiedrazulDeliveryProbeE, expr: 'delivery_probe_state{probe="E"} == 1', labels: {severity: info}, annotations: {summary: "Sonda E de entrega", description: "Alerta informativa sintética de la prueba de entrega (E)."}}
EOF
printf 'A 0\nB 0\nC 0\nD 0\nE 1\n' > "${TMP}/ctl/probes"
echo ok > "${TMP}/ctl/mode"
chmod -R a+rX "${TMP}/prometheus" "${TMP}/ctl"
cp "${ROOT}/infra/tests/discord-sink.py" "${TMP}/sink.py"; chmod a+r "${TMP}/sink.py"
cat > "${TMP}/override.yml" <<EOF
name: ${PROJECT}
services:
  prometheus:
    volumes: !override
      - ${TMP}/prometheus:/etc/prometheus:ro
      - prometheus_data:/prometheus
  sink:
    image: python:3.12-slim
    command: [python, /sink.py]
    user: "$(id -u):$(id -g)"
    volumes: ['${TMP}/sink.py:/sink.py:ro', '${TMP}/ctl:/ctl:ro', '${TMP}/data:/data']
    networks: [obs_net]
  probe:
    image: python:3.12-slim
    command: [python, /sink.py]
    user: "$(id -u):$(id -g)"
    volumes: ['${TMP}/sink.py:/sink.py:ro', '${TMP}/ctl:/ctl:ro', '${TMP}/probe-data:/data']
    networks: [obs_net]
EOF
mkdir -p "${TMP}/probe-data"
C="${TMP}/config.json"
obs config --format json > "${C}" 2>/dev/null
check "Compose: alertmanager sin puertos, sin clúster, solo en obs_net, raíz de solo lectura y sin privilegios" \
  bash -c "jq -e '.services.alertmanager | ((.ports // []) | length == 0) and (.networks | keys == [\"obs_net\"]) and .read_only == true and .cap_drop == [\"ALL\"] and ((.security_opt // []) | index(\"no-new-privileges:true\")) and (.command | index(\"--cluster.listen-address=\")) and .user == \"65534:65534\"' '${C}'"
check "Compose: el secreto del webhook solo lo monta alertmanager" \
  test "$(jq -r '[.services | to_entries[] | select((.value.secrets // []) | length > 0) | .key] | join(",")' "${C}")" = alertmanager
check "Compose: la configuración renderizada no contiene el webhook" bash -c "! grep -qF '${TOKEN_A}' '${C}'"
check "Compose: receptor none por defecto" \
  test "$(printf 'GRAFANA_ADMIN_PASSWORD=x\n' > "${TMP}/min.env"; docker compose -f "${OBS}/compose.yml" --env-file "${TMP}/min.env" config --format json 2>/dev/null | jq -r '.services.alertmanager.command[0]')" = "--config.file=/etc/alertmanager/none.yml"

# ── Runtime ──────────────────────────────────────────────────────────────────
RUNTIME_STARTED=true
docker network create "${APP_NET}" >/dev/null && docker network create --internal "${EDGE_NET}" >/dev/null \
  || { ko "runtime: redes de prueba"; exit 1; }
if obs up -d --wait --wait-timeout 180 prometheus alertmanager sink probe > "${TMP}/up.log" 2>&1; then
  ok "runtime: prometheus, alertmanager (receptor none), receptor sintético y sonda arriba (${PROJECT})"
else ko "runtime: arranque"; tail -20 "${TMP}/up.log" >&2; exit 1; fi

pq() { obs exec -T prometheus wget -qO- "http://localhost:9090/api/v1/query?query=$(jq -rn --arg q "$1" '$q|@uri')"; }
pv() { pq "$1" | jq -r '.data.result[0].value[1] // empty'; }
am_metric() { obs exec -T prometheus wget -qO- http://alertmanager.:9093/metrics | awk -v m="$1" '$1 == m {print $2}'; }
reqs() { cat "${TMP}/data/requests.jsonl" 2>/dev/null || true; }
# reqs_for <alertname> [estado firing|resolved] [path_sha256] → peticiones con ese título
reqs_for() {
  local want="${2:-}"
  reqs | jq -c --arg a "$1" --arg w "${want}" --arg p "${3:-}" '
    select((.body.embeds[0].title // "") | contains($a))
    | select($w == "" or (($w == "firing") == ((.body.embeds[0].title // "") | contains("[RESUELTA") | not)))
    | select($p == "" or .path_sha256 == $p)'
}
delivered() { reqs_for "$@" | jq -s -e 'map(select(.status == 204)) | length > 0' >/dev/null; }
wait_for() { # <segundos> <comando...>
  local t="$1"; shift
  for _ in $(seq 1 $(( t / 5 ))); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done
  return 1
}
probe() { sed -i "s/^$1 .*/$1 $2/" "${TMP}/ctl/probes"; }
mode() { echo "$1" > "${TMP}/ctl/mode"; }

# ── 2. Receptor none ─────────────────────────────────────────────────────────
am_has_probe_e() { obs exec -T prometheus wget -qO- 'http://alertmanager.:9093/api/v2/alerts' | jq -e 'map(.labels.alertname) | index("PiedrazulDeliveryProbeE")'; }
check "none: Alertmanager recibe de Prometheus la sonda E (info)" wait_for 120 am_has_probe_e
check "none: Prometheus no evalúa un latido (sin ALERTS de PiedrazulNotificationHeartbeat)" \
  test "$(pv 'count(ALERTS{alertname="PiedrazulNotificationHeartbeat"})')" = ""
check "none: Prometheus tiene a Alertmanager como destino activo" \
  test "$(obs exec -T prometheus wget -qO- http://localhost:9090/api/v1/alertmanagers | jq -r '.data.activeAlertmanagers | length')" = 1
sleep 10
check "none: nada se envía al webhook" test -z "$(reqs)"

# ── 3. Activación discord ────────────────────────────────────────────────────
sed -i 's/^OBS_ALERT_RECEIVER=.*/OBS_ALERT_RECEIVER=discord/' "${TMP}/obs.env"
obs up -d --wait --wait-timeout 120 alertmanager > "${TMP}/up2.log" 2>&1 \
  && ok "activación: alertmanager recreado con OBS_ALERT_RECEIVER=discord" || ko "activación: alertmanager recreado"
check "activación: llega la sonda E (info) al webhook" \
  wait_for 120 delivered PiedrazulDeliveryProbeE firing "${PATH_A}"
PE="$(reqs_for PiedrazulDeliveryProbeE firing | head -1)"
check "mensaje: una alerta info llega como [entorno] [INFO] con 🔵 y el resumen de la regla" \
  bash -c "jq -e '(.body.embeds[0].title | startswith(\"[delivery-check] [INFO] PiedrazulDeliveryProbeE (info)\")) and (.body.embeds[0].description | startswith(\"🔵 Sonda E de entrega\"))' <<< \"\$1\"" _ "${PE}"
check "activación: solo llega la sonda E (ningún mensaje periódico ni de latido)" \
  test "$(reqs | jq -s '[.[] | select((.body.embeds[0].title // "") | contains("PiedrazulDeliveryProbeE") | not)] | length')" = 0

# ── 4. Disparo con reintentos ────────────────────────────────────────────────
FAILED0="$(am_metric 'alertmanager_notifications_failed_total{integration="discord",reason="serverError"}')"
mode fail:2
probe A 1
check "reintentos: PiedrazulDeliveryProbeA llega tras dos 500" wait_for 180 delivered PiedrazulDeliveryProbeA firing
A_FIRING="$(reqs_for PiedrazulDeliveryProbeA firing | jq -s -c 'map(.status)')"
echo "      intentos de PiedrazulDeliveryProbeA (firing): ${A_FIRING}"
check "reintentos: intentos = [500,500,204]" test "${A_FIRING}" = "[500,500,204]"
check "mensaje: una alerta warning llega como [FIRING] con 🟠" \
  bash -c "jq -s -e 'map(select(.status == 204)) | .[0] | (.body.embeds[0].title | startswith(\"[delivery-check] [FIRING] PiedrazulDeliveryProbeA (warning)\")) and (.body.embeds[0].description | startswith(\"🟠 Sonda A de entrega\"))' <<< \"\$1\"" _ "$(reqs_for PiedrazulDeliveryProbeA firing)"
check "reintentos: alertmanager_notification_requests_failed_total{integration=discord} ≥ 2" \
  bash -c "test \"\$(printf '%.0f' \"\$1\")\" -ge 2" _ "$(am_metric 'alertmanager_notification_requests_failed_total{integration="discord"}')"
check "reintentos: no cuenta como notificación perdida (failed_total sin cambios)" \
  test "$(am_metric 'alertmanager_notifications_failed_total{integration="discord",reason="serverError"}')" = "${FAILED0}"
mode ok

# ── 5. Resolución (group_interval real: 5 min) ───────────────────────────────
probe A 0
probe E 0
T0=$(date +%s)
check "resolución: llega [RESUELTA] PiedrazulDeliveryProbeA" wait_for 420 delivered PiedrazulDeliveryProbeA resolved
check "resolución: el mensaje de la warning resuelta lleva ✅" \
  bash -c "jq -s -e 'map(select(.status == 204)) | .[0].body.embeds[0].description | startswith(\"✅ Sonda A de entrega\")' <<< \"\$1\"" _ "$(reqs_for PiedrazulDeliveryProbeA resolved)"
echo "      resolución entregada a los $(( $(date +%s) - T0 )) s de apagar la condición"
check "deduplicación: un solo mensaje de disparo entregado para PiedrazulDeliveryProbeA" \
  test "$(reqs_for PiedrazulDeliveryProbeA firing | jq -s 'map(select(.status == 204)) | length')" = 1
check "deduplicación: un solo disparo entregado para la sonda E mientras siguió en firing (repeat_interval 12 h)" \
  test "$(reqs_for PiedrazulDeliveryProbeE firing | jq -s 'map(select(.status == 204)) | length')" = 1
check "resolución: llega [RESUELTA] de la sonda E (info)" wait_for 420 delivered PiedrazulDeliveryProbeE resolved
check "resolución: la sonda E (info) resuelta es neutra (⚪, ya no está activa)" \
  bash -c "jq -s -e 'map(select(.status == 204)) | .[0].body.embeds[0].description | startswith(\"⚪ Sonda E de entrega: ya no está activa\")' <<< \"\$1\"" _ "$(reqs_for PiedrazulDeliveryProbeE resolved)"

# ── 6. Regla real ────────────────────────────────────────────────────────────
check "regla real: PiedrazulTargetDown (productores ausentes 5 min) llega a Discord" \
  wait_for 240 delivered PiedrazulTargetDown firing
check "regla real: el mensaje agrupa los destinos caídos y no repite por destino" \
  test "$(reqs_for PiedrazulTargetDown firing | jq -s 'map(select(.status == 204)) | length')" = 1

# ── 7. Falla definitiva: webhook revocado (404) ──────────────────────────────
mode 404
probe B 1
lost() { test "$(printf '%.0f' "$(am_metric 'alertmanager_notifications_failed_total{integration="discord",reason="clientError"}')")" -ge 1; }
check "404: el envío se da por perdido sin reintentar (failed_total{reason=clientError} ≥ 1)" wait_for 120 lost
check "404: un solo intento por envío (no se reintenta un 4xx)" \
  test "$(reqs_for PiedrazulDeliveryProbeB firing | jq -s 'length')" = 1
fires() { test "$(pv "count(ALERTS{alertname=\"PiedrazulAlertNotificationsFailing\",alertstate=\"firing\"})")" = 1; }
check "404: PiedrazulAlertNotificationsFailing en firing en Prometheus (visible en Grafana)" wait_for 150 fires
mode ok
check "404 → recuperado: PiedrazulAlertNotificationsFailing llega a Discord" \
  wait_for 420 delivered PiedrazulAlertNotificationsFailing firing

# ── 8. Rotación del webhook ──────────────────────────────────────────────────
chmod 0777 "${TMP}/secrets"; put_secret "${URL_B}"; chmod 0700 "${TMP}/secrets"
check "rotación: el archivo montado no cambia en el contenedor en marcha (escritura atómica)" \
  bash -c "test \"\$(docker exec \"\$1\" cat /run/secrets/discord_webhook_url)\" = '${URL_A}'" _ "$(obs ps -q alertmanager)"
N_BEFORE_ROT="$(reqs | wc -l)"
obs up -d --force-recreate --wait --wait-timeout 120 alertmanager >/dev/null 2>&1
check "rotación: tras recrear Alertmanager (como hace el rol) el contenedor lee el webhook nuevo" \
  bash -c "test \"\$(docker exec \"\$1\" cat /run/secrets/discord_webhook_url)\" = '${URL_B}'" _ "$(obs ps -q alertmanager)"
probe C 1
T_C=$(date +%s)
# Contrato de entrega de una alerta nueva, por etapas, cada una con su cota y su tiempo medido
# (sondeo cada segundo) para saber cuál se atrasa si falla:
#   1. la sonda expone el cambio (archivo de control montado; inmediato)
#   2. Prometheus lo lee: ≤ scrape_interval (30 s)
#   3. la regla pasa a firing y Prometheus la envía: ≤ 2 × evaluation_interval (60 s). Medido: el
#      grupo se evalúa ~0,1 s después del inicio de cada lectura, antes de que su muestra se
#      agregue, así que la ve la evaluación siguiente (30 s); con el host cargado (dos pruebas a la
#      vez) se observó una evaluación más. Más que eso es una anomalía y falla.
#   4. el Alertmanager recreado la recibe (en la misma evaluación)
#   5. Alertmanager la entrega: group_wait (30 s)
# Las cotas 1, 2 y 4 llevan 15 s de margen; la 5, 30 s (un reintento del receptor).
wait_1s() { # <segundos de reloj> <comando...>: sondea cada segundo hasta el plazo
  local end=$(( $(date +%s) + $1 )); shift
  while :; do "$@" >/dev/null 2>&1 && return 0; [ "$(date +%s)" -ge "${end}" ] && return 1; sleep 1; done
}
since_c() { echo $(( $(date +%s) - T_C )); }
probe_exposes_c() { obs exec -T prometheus wget -qO- http://probe.:8080/metrics | grep -qx 'delivery_probe_state{probe="C"} 1'; }
scraped_c() { test "$(pv 'delivery_probe_state{job="delivery_probe",probe="C"}')" = 1; }
fires_c() { test "$(pv 'count(ALERTS{alertname="PiedrazulDeliveryProbeC",alertstate="firing"})')" = 1; }
am_has_c() { obs exec -T prometheus wget -qO- 'http://alertmanager.:9093/api/v2/alerts' | jq -e 'map(.labels.alertname) | index("PiedrazulDeliveryProbeC")'; }
STAGES=""
stage() { # <cota s> <nombre> <comando...>
  local b="$1" n="$2" t0 r; shift 2; t0=$(since_c)
  wait_1s "$b" "$@"; r=$?
  STAGES="${STAGES} ${n}=$(( $(since_c) - t0 ))s"; return "${r}"
}
check "rotación: la sonda expone C (archivo de control; ≤ 15 s)" stage 15 sonda probe_exposes_c
check "rotación: Prometheus lee C (scrape_interval 30 s + 15 s)" stage 45 lectura scraped_c
check "rotación: la regla de C pasa a firing (2 × evaluation_interval: la lectura cae justo tras una evaluación)" stage 60 evaluación fires_c
check "rotación: el Alertmanager recreado recibe C de Prometheus (misma evaluación; ≤ 15 s)" stage 15 envío am_has_c
check "rotación: el siguiente envío usa la ruta nueva (group_wait 30 s + 30 s)" stage 60 entrega delivered PiedrazulDeliveryProbeC firing "${PATH_B}"
echo "      sonda C tras la rotación (por etapa):${STAGES}; total $(since_c) s"
check "rotación: nada de la sonda C fue a la ruta anterior" \
  test "$(reqs_for PiedrazulDeliveryProbeC firing "${PATH_A}" | jq -s 'length')" = 0
# Reenvío = un mensaje idéntico (título y descripción) a uno ya entregado. Un cambio de grupo que no
# se pudo entregar antes (p. ej. más destinos caídos durante el 404) sí debe llegar después.
no_resend_after_rotation() {
  reqs | jq -s -e --argjson n "${N_BEFORE_ROT}" '
    def k: select(.status == 204) | [.body.embeds[0].title, .body.embeds[0].description];
    [.[:$n][] | k] as $b | [.[$n:][] | k | . as $x | select(any($b[]; . == $x))] | length == 0' >/dev/null
}
check "rotación: recrear no reenvía lo ya entregado (registro de notificaciones en el volumen)" no_resend_after_rotation

# ── 9. Red caída ─────────────────────────────────────────────────────────────
N_BEFORE="$(reqs | wc -l)"
obs stop sink >/dev/null 2>&1
probe D 1
net_err() { obs logs alertmanager 2>&1 | grep -qiE 'notify.*(dial|no such host|connection refused|i/o timeout|lookup)'; }
check "red caída: Alertmanager registra el error de conexión" wait_for 150 net_err
obs start sink >/dev/null 2>&1
check "red caída: al volver el receptor, la sonda D se entrega (reintento)" wait_for 360 delivered PiedrazulDeliveryProbeD firing

# ── 10. Sin fuga del webhook ─────────────────────────────────────────────────
obs logs alertmanager prometheus > "${TMP}/stack.log" 2>&1
obs exec -T prometheus wget -qO- http://alertmanager.:9093/api/v2/status > "${TMP}/am-status.json" 2>/dev/null
obs exec -T prometheus wget -qO- http://localhost:9090/api/v1/status/config > "${TMP}/prom-config.json" 2>/dev/null
docker inspect "$(obs ps -q alertmanager)" "$(obs ps -q prometheus)" > "${TMP}/inspect.json"
for f in stack.log am-status.json prom-config.json inspect.json config.json; do
  check "sin fuga: ningún token del webhook en ${f}" bash -c "test -s '${TMP}/${f}' && ! grep -qE '${TOKEN_A}|${TOKEN_B}' '${TMP}/${f}'"
done
check "sin fuga: el estado de Alertmanager muestra solo la ruta del archivo" \
  bash -c "jq -r '.config.original' '${TMP}/am-status.json' | grep -q 'webhook_url_file: /run/secrets/discord_webhook_url'"
check "entorno: todos los mensajes empiezan por [delivery-check] (OBS_ENVIRONMENT, etiqueta externa)" \
  bash -c "test \"\$(jq -r '.body.embeds[0].title' '${TMP}/data/requests.jsonl' | grep -vc '^\[delivery-check\] ')\" = 0 && test -s '${TMP}/data/requests.jsonl'"
check "entorno: la etiqueta no está en las series ni en ALERTS (reglas sin cambios)" \
  bash -c "test -z \"\$1\"" _ "$(pq 'count({environment!=""})' | jq -r '.data.result[0].value[1] // empty')"
check "mensajes: sin etiquetas de usuario, correo, URL ni IP en lo enviado" \
  bash -c "! jq -r '.body.embeds[]?.description' '${TMP}/data/requests.jsonl' | grep -qiE '@[a-z0-9.-]+\\.[a-z]{2,}|https?://|([0-9]{1,3}\\.){3}[0-9]{1,3}'"

# Evidencia: peticiones recibidas (sin token: solo sha256 de la ruta)
if [ -n "${EVIDENCE_DIR:-}" ]; then
  mkdir -p "${EVIDENCE_DIR}" && jq -c '{t, status, path: (if .path_sha256 == "'"${PATH_A}"'" then "A" elif .path_sha256 == "'"${PATH_B}"'" then "B" else "?" end), title: .body.embeds[0].title}' \
    "${TMP}/data/requests.jsonl" > "${EVIDENCE_DIR}/alert-delivery-requests.jsonl"
fi
echo "      peticiones al webhook: $(reqs | wc -l) (antes de la caída de red: ${N_BEFORE})"
echo
if [ "${FAILS}" -eq 0 ]; then echo "OK: todas las comprobaciones pasaron"; exit 0; else echo "FALLAS: ${FAILS}"; exit 1; fi
