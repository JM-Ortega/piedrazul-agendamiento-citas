#!/usr/bin/env bash
# Verificación de integración del stack de métricas (infra/observability): configuración, Ansible y
# comportamiento en ejecución contra productores reales (backend, Keycloak, Traefik) levantados
# con los archivos Compose de la aplicación en un proyecto y una red propios. Los logs (Loki y la
# canalización del journal en Alloy) los verifica infra/tests/logs-stack-check.sh; aquí solo se
# comprueba que no alteran las métricas. Credenciales
# sintéticas que nunca se imprimen; no contacta producción, GitHub, Cloudflare ni Let's Encrypt.
#
# Uso: infra/tests/metrics-stack-check.sh
# Entorno opcional:
#   BACKEND_IMAGE   imagen del backend (por defecto: construye backend/)
#   POSTGRES_IMAGE  imagen de postgres (por defecto: construye infra/postgres/)
#   STATIC_ONLY=1   solo las comprobaciones sin contenedores de larga duración (1 y 2)
#   ALERT_FIRING=1  espera además a que PiedrazulTargetDown pase a firing (≈6 min)
#   GRAFANA_LOCAL_PORT  puerto de loopback para Grafana (por defecto 13000)
#   KEEP=1          no elimina los proyectos al terminar (inspección)
# Requiere: docker (compose v2), jq, yq (mikefarah v4), ansible-playbook con community.docker,
#   python3, openssl.
#
# Proyectos propios: pzobs-check-<pid> (productores, red pzobs_check_<pid>_net) y
# pzobs-check-<pid>-obs (stack de métricas). Sin nombres fijos de contenedor: convive con una
# aplicación local en marcha. La limpieza solo alcanza esos proyectos.
#
# Verifica:
#   1. Configuración: Compose renderiza (y sin GRAFANA_ADMIN_PASSWORD falla); imágenes por
#      digest; solo Grafana publica un puerto y solo en 127.0.0.1; sin socket de Docker,
#      privilegios, red ni PID del host; cap_drop ALL, no-new-privileges y raíz de solo
#      lectura; solo Prometheus en la red de la aplicación; destinos de prometheus.yml = contrato
#      de los productores (más alloy y loki por obs_net); promtool check config/rules y test
#      rules; Alloy con el exportador del host y sin remote_write; dashboards JSON válidos con
#      las fuentes de datos provisionadas; los archivos Compose de la aplicación no mencionan el
#      stack salvo la red de entrada de Grafana (traefik.yml). S3: Alertmanager como quinto
#      servicio (receptor none por defecto, webhook solo por archivo secreto) y Grafana sin JWT por
#      defecto; el acceso y la entrega los prueban grafana-access-check.sh y
#      alert-delivery-check.sh.
#   2. Ansible: playbook observability.yml sintácticamente válido y fuera del workflow de
#      producción.
#   3. Recolección: los cinco destinos up; muestras de cada productor; etiquetas acotadas
#      (rutas por plantilla, sin etiquetas de usuario, URL ni host); host = el kernel que ve Alloy.
#   4. Grafana: fuente de datos sana, dashboards de métricas, todas sus consultas sin error y con
#      datos donde los productores los tienen; sin acceso anónimo.
#   5. Aislamiento: Grafana y Alloy no alcanzan los productores; Prometheus, Alloy y los
#      listeners de métricas sin puerto en el host.
#   6. Fallas: productor detenido → up = 0, 502 contados en Traefik, alerta pending (firing con
#      ALERT_FIRING=1) y recuperación; Prometheus arranca sin el productor.
#   7. Persistencia y operación: datos de Prometheus y estado de Grafana sobreviven a `down` +
#      `up`; repetir `up` no recrea nada; detener o bajar el stack no afecta a la aplicación.
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OBS="${ROOT}/infra/observability"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
ID="$$"
APP_PROJECT="pzobs-check-${ID}"
OBS_PROJECT="pzobs-check-${ID}-obs"
APP_NET="pzobs_check_${ID}_net"
# Red de entrada de Grafana: la crea el proyecto de la aplicación (traefik.yml); el stack corre en
# modo privado (sin compose.access.yml) y no la usa. Nombre propio para no chocar con otra
EDGE_NET="pzobs_check_${ID}_edge"
GPORT="${GRAFANA_LOCAL_PORT:-13000}"

for bin in docker jq yq ansible-playbook python3 openssl; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

RUNTIME_STARTED=false
cleanup() {
  if [ "${RUNTIME_STARTED}" = true ] && [ "${KEEP:-0}" != 1 ]; then
    obs down -v --remove-orphans >/dev/null 2>&1 || true
    app down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

PROM_IMAGE="$(yq -r '.services.prometheus.image' "${OBS}/compose.yml")"
promtool() { docker run --rm -v "${OBS}:/w:ro" --entrypoint promtool "${PROM_IMAGE}" "$@"; }

# ── 1. Configuración ─────────────────────────────────────────────────────────
# Webhook de Discord: archivo vacío (receptor none por defecto), como lo deja el rol sin webhook
mkdir -p "${TMP}/secrets" && : > "${TMP}/secrets/discord_webhook_url" && chmod 644 "${TMP}/secrets/discord_webhook_url"
printf 'GRAFANA_ADMIN_PASSWORD=%s\nOBS_APP_NETWORK=%s\nGRAFANA_LOCAL_PORT=%s\nOBS_DISCORD_WEBHOOK_FILE=%s\n' \
  "$(openssl rand -hex 16)" "${APP_NET}" "${GPORT}" "${TMP}/secrets/discord_webhook_url" > "${TMP}/obs.env"
chmod 600 "${TMP}/obs.env"
obs() { (cd "${OBS}" && docker compose -p "${OBS_PROJECT}" --env-file "${TMP}/obs.env" "$@"); }

if obs config --format json > "${TMP}/obs.json" 2>"${TMP}/obs-config.err"; then
  ok "Compose: compose.yml renderiza con .env"
else
  ko "Compose: compose.yml renderiza con .env"; cat "${TMP}/obs-config.err" >&2
fi
check "Compose: sin GRAFANA_ADMIN_PASSWORD falla" \
  bash -c "! (cd '${OBS}' && env -u GRAFANA_ADMIN_PASSWORD docker compose -p x --env-file /dev/null config --quiet)"
C="${TMP}/obs.json"
check "Compose: nombre de proyecto declarado piedrazul-obs" \
  test "$(yq -r '.name' "${OBS}/compose.yml")" = piedrazul-obs
check "Compose: servicios prometheus, grafana, alloy, loki y alertmanager (sin otros)" \
  test "$(jq -r '.services | keys | sort | join(",")' "${C}")" = "alertmanager,alloy,grafana,loki,prometheus"
check "Compose: imágenes fijadas <tag>@sha256:<digest>" \
  bash -c "jq -r '.services[].image' '${C}' | grep -vqE '^[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}\$' && exit 1 || exit 0"
check "Compose: solo grafana publica un puerto, en 127.0.0.1" \
  bash -c "test \"\$(jq -r '[.services | to_entries[] | select((.value.ports // []) | length > 0) | .key] | join(\",\")' '${C}')\" = grafana && test \"\$(jq -r '[.services.grafana.ports[] | .host_ip] | unique | join(\",\")' '${C}')\" = 127.0.0.1"
check "Compose: sin socket de Docker, privileged, red ni PID del host" \
  bash -c "! jq -e '.services[] | select(.privileged == true or .network_mode == \"host\" or .pid == \"host\" or ((.volumes // []) | map(.source // \"\") | any(test(\"docker.sock\"))))' '${C}'"
check "Compose: cap_drop ALL, no-new-privileges y raíz de solo lectura en los cinco" \
  bash -c "test \"\$(jq '[.services[] | select((.cap_drop == [\"ALL\"]) and (.read_only == true) and ((.security_opt // []) | index(\"no-new-privileges:true\")))] | length' '${C}')\" = 5"
check "Compose: límites de memoria y CPU en los cinco" \
  bash -c "test \"\$(jq '[.services[] | select(.deploy.resources.limits.memory and .deploy.resources.limits.cpus)] | length' '${C}')\" = 5"
check "Compose: solo prometheus en la red de la aplicación (externa)" \
  bash -c "test \"\$(jq -r '[.services | to_entries[] | select(.value.networks | has(\"app_net\")) | .key] | join(\",\")' '${C}')\" = prometheus && jq -e '.networks.app_net.external == true' '${C}'"
check "Compose: Alloy sin root y montajes del host de solo lectura" \
  bash -c "test \"\$(jq -r '.services.alloy.user' '${C}')\" = 65534:65534 && jq -e '[.services.alloy.volumes[] | select(.type == \"bind\") | .read_only] | all' '${C}'"
check "Compose: retención por defecto 30d y 2GB en Prometheus" \
  bash -c "jq -r '.services.prometheus.command[]' '${C}' | grep -qx -- '--storage.tsdb.retention.time=30d' && jq -r '.services.prometheus.command[]' '${C}' | grep -qx -- '--storage.tsdb.retention.size=2GB'"
check "Compose: Prometheus sin API de administración ni receptor remoto" \
  bash -c "! jq -r '.services.prometheus.command[]' '${C}' | grep -qE 'enable-admin-api|remote-write-receiver|enable-feature'"
check "Compose: Grafana sin anónimos, registro, telemetría, avisos de versión ni plugins descargados" \
  bash -c "e=\$(jq -r '.services.grafana.environment | to_entries[] | \"\(.key)=\(.value)\"' '${C}'); for kv in GF_AUTH_ANONYMOUS_ENABLED=false GF_USERS_ALLOW_SIGN_UP=false GF_ANALYTICS_REPORTING_ENABLED=false GF_ANALYTICS_CHECK_FOR_UPDATES=false GF_PLUGINS_PREINSTALL_DISABLED=true GF_UNIFIED_ALERTING_ENABLED=false GF_AUTH_JWT_ENABLED=false; do grep -qx \"\$kv\" <<< \"\$e\" || exit 1; done"

# Contrato de los productores (docs/architecture/overview.md, "Health y métricas")
check "Prometheus: destinos = contrato (backend:8081/actuator/prometheus, keycloak:9000/metrics, traefik:8082/metrics, host, propio; alloy y loki de logs; alertmanager)" \
  test "$(yq -r '[.scrape_configs[] | .job_name + "=" + (.static_configs[0].targets | join(",")) + (.metrics_path // "/metrics")] | sort | join(" ")' "${OBS}/prometheus/prometheus.yml")" \
       = "alertmanager=alertmanager:9093/metrics alloy=alloy:12345/metrics backend=backend:8081/actuator/prometheus host=alloy:12345/api/v0/component/prometheus.exporter.unix.host/metrics keycloak=keycloak:9000/metrics loki=loki:3100/metrics prometheus=localhost:9090/metrics traefik=traefik:8082/metrics"
# S3: Prometheus entrega a Alertmanager (único destino de alertas); sigue sin remote_write
check "Prometheus: alertas solo a alertmanager:9093 y sin remote_write" \
  bash -c "test \"\$(yq -r '[.alerting.alertmanagers[].static_configs[].targets[]] | join(\",\")' '${OBS}/prometheus/prometheus.yml')\" = alertmanager:9093 && ! yq -e '.remote_write' '${OBS}/prometheus/prometheus.yml'"
check "Notificaciones: sin provisioning de alertas de Grafana (puntos de contacto, políticas)" \
  test ! -e "${OBS}/grafana/provisioning/alerting"
# S3: el único destino es Discord, por el archivo secreto de Alertmanager; ninguna URL ni token
# en la configuración versionada
check "Notificaciones: sin URL de webhook, bots ni tokens en la configuración, fuera de comentarios" \
  bash -c "! grep -rhvE '^\s*(#|//)' '${OBS}/compose.yml' '${OBS}/prometheus' '${OBS}/alloy' '${OBS}/alertmanager' '${OBS}/grafana/provisioning' | grep -qiE 'https?://[^ ]*(discord|webhook)|telegram|bot_token|chat_id|(^|[^_a-z])webhook_url:'"
check "Notificaciones: Discord solo en Alertmanager, por webhook_url_file" \
  test "$(grep -rlE '^[^#]*discord' "${OBS}/compose.yml" "${OBS}/prometheus" "${OBS}/alloy" "${OBS}/grafana/provisioning" "${OBS}/alertmanager" | xargs -n1 basename | sort | paste -sd,)" = "compose.yml,discord.tmpl,discord.yml"
check "promtool: check config" promtool check config /w/prometheus/prometheus.yml
check "promtool: check rules" promtool check rules /w/prometheus/rules/alerts.yml /w/prometheus/rules/logs.yml /w/prometheus/rules/notifications.yml
if promtool test rules /w/tests/alerts.test.yml /w/tests/notifications-alerts.test.yml > "${TMP}/promtool-test.log" 2>&1; then
  ok "promtool: test rules (casos normal, falla y sin datos de cada regla)"
else
  ko "promtool: test rules"; tail -20 "${TMP}/promtool-test.log" >&2
fi
check "Alloy: exportador del host prometheus.exporter.unix \"host\" (sin remote_write, otelcol, discovery.docker ni socket)" \
  bash -c "c=\$(grep -vE '^\s*//' '${OBS}/alloy/config.alloy'); grep -qE '^prometheus\.exporter\.unix \"host\" \\{' <<< \"\$c\" && ! grep -qiE 'remote_write|otelcol|discovery\.docker|docker\.sock' <<< \"\$c\""
check "Alloy: la configuración se valida (alloy fmt)" \
  docker run --rm -v "${OBS}/alloy:/a:ro" --entrypoint /bin/alloy "$(yq -r '.services.alloy.image' "${OBS}/compose.yml")" fmt /a/config.alloy
check "Grafana: dashboards de métricas JSON válidos, uid fijo y solo la fuente de datos prometheus" \
  bash -c "for f in '${OBS}'/grafana/dashboards/plataforma.json '${OBS}'/grafana/dashboards/backend-jvm.json; do jq -e '.uid and (.uid | length > 0) and ([.. | objects | select(has(\"datasource\")) | .datasource.uid] | unique == [\"prometheus\"])' \"\$f\" || exit 1; done"
check "Grafana: fuente de datos provisionada con uid prometheus" \
  test "$(yq -r '.datasources[0].uid + " " + .datasources[0].url' "${OBS}/grafana/provisioning/datasources/prometheus.yml")" = "prometheus http://prometheus:9090"
# S3: la única referencia permitida es la red de entrada de Grafana que crea traefik.yml
check "Aplicación: infra/compose y docker-compose.yml no mencionan el stack salvo la red de entrada (traefik.yml)" \
  bash -c "! grep -rqiE 'grafana|alloy|piedrazul-obs|obs_net' \$(ls '${COMPOSE_DIR}'/*.yml | grep -v '/traefik.yml\$') '${ROOT}/docker-compose.yml' && ! grep -vE '^\s*#' '${COMPOSE_DIR}/traefik.yml' | grep -qiE 'grafana|alloy|piedrazul-obs|obs_net'"

# ── 2. Ansible ───────────────────────────────────────────────────────────────
check "Ansible: playbooks/observability.yml sintácticamente válido" \
  bash -c "cd '${ANSIBLE_DIR}' && ANSIBLE_CONFIG=ansible.cfg ansible-playbook --syntax-check playbooks/observability.yml"
check "Ansible: el workflow de producción no corre observability.yml" \
  bash -c "! grep -rq 'observability' '${ROOT}/.github/workflows'"
check "Ansible: converge y deploy no incluyen el rol observability" \
  bash -c "! grep -q observability '${ANSIBLE_DIR}/playbooks/converge.yml' '${ANSIBLE_DIR}/playbooks/deploy.yml'"

if [ "${STATIC_ONLY:-0}" = 1 ]; then
  echo "STATIC_ONLY=1: sin pruebas en ejecución"
  [ "${FAILS}" -eq 0 ] && { echo "OK"; exit 0; } || { echo "${FAILS} comprobación(es) fallida(s)"; exit 1; }
fi

# ── Productores: archivos Compose de la aplicación en un proyecto y red propios ──
if [ -z "${BACKEND_IMAGE:-}" ]; then
  BACKEND_IMAGE=pzobs-check/backend:local
  docker build -q -t "${BACKEND_IMAGE}" "${ROOT}/backend" >/dev/null
fi
if [ -z "${POSTGRES_IMAGE:-}" ]; then
  POSTGRES_IMAGE=pzobs-check/postgres:local
  docker build -q -t "${POSTGRES_IMAGE}" "${ROOT}/infra/postgres" >/dev/null
fi
mkdir -p "${TMP}/dynamic"
grep -vE '^\s*(#|$)|^COMPOSE_' "${ROOT}/.env.example" | sed "s/^PROJECT_NAME=.*/PROJECT_NAME=${APP_PROJECT}/" > "${TMP}/app.env"
for k in POSTGRES_PASSWORD APP_DB_PASSWORD MIGRATION_DB_PASSWORD KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD KC_BACKEND_CLIENT_SECRET; do
  sed -i "s/^${k}=.*/${k}=$(openssl rand -hex 16)/" "${TMP}/app.env"
done
sed -i -e 's/^DB_HOST=.*/DB_HOST=postgres/' -e 's#^KC_SERVER_URL=.*#KC_SERVER_URL=http://keycloak:8180#' \
  -e 's#^KC_ISSUER_URI=.*#KC_ISSUER_URI=http://keycloak:8180/realms/piedrazul#' -e 's/^SPRING_PROFILES_ACTIVE=.*/SPRING_PROFILES_ACTIVE=prod/' \
  -e 's/^MANAGEMENT_HEALTH_DETAILS=.*/MANAGEMENT_HEALTH_DETAILS=when-authorized/' -e 's/^APP_DEMO_SEED_ENABLED=.*/APP_DEMO_SEED_ENABLED=false/' \
  "${TMP}/app.env"
printf 'BACKEND_IMAGE=%s\nACME_EMAIL=metrics-check@example.invalid\nCLOUDFLARE_DNS_API_TOKEN=\n' "${BACKEND_IMAGE}" >> "${TMP}/app.env"
# Diferencias con producción, solo de aislamiento: sin container_name fijo, red propia, sin
# puertos de Traefik en el host, rutas sintéticas (TLS por defecto, sin AOP ni ACME) y Keycloak
# en start-dev con el realm importado. Las métricas (puertos, flags, variables) son las de los
# archivos de la aplicación.
cat > "${TMP}/override.yml" <<EOF
name: ${APP_PROJECT}
networks:
  piedrazul_net:
    name: ${APP_NET}
  obs_edge:
    name: ${EDGE_NET}
services:
  postgres:
    image: ${POSTGRES_IMAGE}
    container_name: !reset null
  keycloak:
    container_name: !reset null
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: \${KC_BOOTSTRAP_ADMIN_USERNAME}
      KC_BOOTSTRAP_ADMIN_PASSWORD: \${KC_BOOTSTRAP_ADMIN_PASSWORD}
  backend:
    container_name: !reset null
  traefik:
    container_name: !reset null
    ports: !reset []
    volumes: !override
      - ${TMP}/dynamic:/etc/traefik/dynamic:ro
EOF
cat > "${TMP}/dynamic/routes.yml" <<'EOF'
http:
  routers:
    backend:
      rule: Host(`api.metrics-check.test`)
      entryPoints: [websecure]
      service: backend
      tls: {}
    keycloak:
      rule: Host(`auth.metrics-check.test`)
      entryPoints: [websecure]
      service: keycloak
      tls: {}
  services:
    backend:
      loadBalancer:
        servers:
          - url: http://backend:8080
    keycloak:
      loadBalancer:
        servers:
          - url: http://keycloak:8180
EOF
# -p explícito: name: del override no basta, COMPOSE_PROJECT_NAME (entorno o env file) tiene
# precedencia sobre él. Así up, run, stop, exec y el down de la limpieza solo tocan APP_PROJECT.
app() { (cd "${COMPOSE_DIR}" && docker compose -p "${APP_PROJECT}" --env-file "${TMP}/app.env" -f db.yml -f keycloak.yml -f backend.yml -f migrate.yml -f traefik.yml -f "${TMP}/override.yml" "$@"); }
# Peticiones por Traefik (websecure) desde dentro de su contenedor
traffic() { # traffic <n> <ruta> [host]
  app exec -T traefik sh -c "i=0; while [ \$i -lt $1 ]; do wget -q -O /dev/null --no-check-certificate --header 'Host: ${3:-api.metrics-check.test}' https://localhost$2 2>/dev/null; i=\$((i+1)); done; true"
}
# Tráfico en dos tandas separadas: un contador recién creado necesita al menos dos lecturas para
# que rate() tenga valor
traffic_rounds() {
  for _ in 1 2; do
    traffic 30 /actuator/health; traffic 10 /api/patients
    traffic 10 /realms/piedrazul/.well-known/openid-configuration auth.metrics-check.test
    sleep 35
  done
  sleep 35
}
pq() {
  local q; q="$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1")"
  obs exec -T prometheus wget -qO- "http://localhost:9090/api/v1/query?query=${q}${2:+&time=$2}"
}
pv() { pq "$@" | jq -r '.data.result[0].value[1] // empty'; }
wait_for() { # wait_for <segundos> <comando...>
  local t="$1"; shift
  for _ in $(seq 1 $(( t / 5 ))); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done
  return 1
}

# Un COMPOSE_PROJECT_NAME heredado (aquí el de la aplicación local) no desvía ninguna operación:
# solo resuelve el proyecto, no crea ni toca contenedores
check "aislamiento: COMPOSE_PROJECT_NAME=piedrazul no desvía los productores de ${APP_PROJECT}" \
  test "$(COMPOSE_PROJECT_NAME=piedrazul app config --format json 2>/dev/null | jq -r '.name')" = "${APP_PROJECT}"
check "aislamiento: COMPOSE_PROJECT_NAME=piedrazul no desvía el stack de ${OBS_PROJECT}" \
  test "$(COMPOSE_PROJECT_NAME=piedrazul obs config --format json 2>/dev/null | jq -r '.name')" = "${OBS_PROJECT}"
check "aislamiento: app.env sin COMPOSE_* y con PROJECT_NAME=${APP_PROJECT}" \
  bash -c "! grep -q '^COMPOSE_' '${TMP}/app.env' && grep -qx 'PROJECT_NAME=${APP_PROJECT}' '${TMP}/app.env'"

RUNTIME_STARTED=true
app up -d --wait postgres >/dev/null 2>&1 || { ko "runtime: postgres de los productores"; exit 1; }
app --profile migrate run --rm --no-deps migrate > "${TMP}/migrate.log" 2>&1 || { ko "runtime: migración del esquema"; exit 1; }
if app up -d --wait --wait-timeout 300 keycloak backend traefik > "${TMP}/app-up.log" 2>&1; then
  ok "runtime: productores backend, keycloak y traefik healthy (proyecto ${APP_PROJECT})"
else
  ko "runtime: productores healthy"; tail -20 "${TMP}/app-up.log" >&2; exit 1
fi

# ── 3. Recolección ───────────────────────────────────────────────────────────
obs up -d --wait --wait-timeout 180 > "${TMP}/obs-up.log" 2>&1 \
  && ok "runtime: stack de métricas levantado (proyecto ${OBS_PROJECT})" \
  || { ko "runtime: stack de métricas levantado"; tail -20 "${TMP}/obs-up.log" >&2; exit 1; }
all_up() { test "$(pv 'count(up{job=~"backend|keycloak|traefik|host|prometheus"} == 1)')" = 5; }
check "recolección: los cinco destinos up" wait_for 120 all_up
obs exec -T prometheus wget -qO- http://localhost:9090/api/v1/targets > "${TMP}/targets.json"
traffic_rounds
check "recolección: backend (jvm_info, http por plantilla de ruta)" \
  bash -c "test -n \"\$1\" && test -n \"\$2\"" _ "$(pv 'jvm_info{job="backend"}')" "$(pv 'http_server_requests_seconds_count{job="backend",uri="/actuator/health"}')"
check "recolección: keycloak (JVM y HTTP)" \
  bash -c "test -n \"\$1\" && test -n \"\$2\"" _ "$(pv 'sum(jvm_memory_used_bytes{job="keycloak"})')" "$(pv 'sum(http_server_requests_seconds_count{job="keycloak"})')"
check "recolección: traefik (peticiones por servicio backend@file y keycloak@file)" \
  bash -c "test -n \"\$1\" && test -n \"\$2\"" _ "$(pv 'sum(traefik_service_requests_total{job="traefik",service="backend@file"})')" "$(pv 'sum(traefik_service_requests_total{job="traefik",service="keycloak@file"})')"
check "recolección: host (CPU, memoria, sistema de archivos /)" \
  bash -c "test -n \"\$1\" && test -n \"\$2\" && test -n \"\$3\"" _ "$(pv 'count(node_cpu_seconds_total{job="host",mode="idle"})')" "$(pv 'node_memory_MemTotal_bytes{job="host"}')" "$(pv 'node_filesystem_size_bytes{job="host",mountpoint="/"}')"
HOST_RELEASE="$(pq 'node_uname_info{job="host"}' | jq -r '.data.result[0].metric.release')"
check "recolección: el host es el kernel que ve Alloy (${HOST_RELEASE})" test "${HOST_RELEASE}" = "$(docker info --format '{{.KernelVersion}}')"
pq 'count by (__name__, job) ({job=~".+"})' > /dev/null
obs exec -T prometheus wget -qO- 'http://localhost:9090/api/v1/labels' > "${TMP}/labels.json"
# Excepción conocida y acotada: path de disk_{free,total}_bytes del backend (DiskSpaceMetrics de
# Spring Boot) es el directorio fijo de la aplicación, un único valor
DISK_PATHS="$(pq 'count by (path) (disk_free_bytes{job="backend"})' | jq -r '[.data.result[].metric.path] | join(",")')"
echo "      path de disk_free_bytes del backend: ${DISK_PATHS}"
check "etiquetas: path solo en disk_*_bytes del backend, con un único valor" \
  bash -c "test \"\$(printf '%s' \"\$1\" | jq -r '[.data.result[].metric | \"\(.job)/\(.__name__)\"] | sort | join(\" \")')\" = 'backend/disk_free_bytes backend/disk_total_bytes' && test \"\$(tr ',' '\n' <<< \"\$2\" | wc -l)\" = 1" \
  _ "$(pq 'count by (job, __name__) ({path!=""})')" "${DISK_PATHS}"
BAD_LABELS="$(jq -r '.data[]' "${TMP}/labels.json" | grep -xiE 'user(name)?|user_?id|email|url|host|ip|client_?ip|remote_?addr|session|token' || true)"
for l in ${BAD_LABELS}; do
  echo "      etiqueta ${l}: $(pq "count by (job, __name__) ({${l}!=\"\"})" | jq -r '[.data.result[].metric | "\(.job)/\(.__name__)"] | join(" ")')"
done
check "etiquetas: ninguna de usuario, correo, URL, host, sesión, token ni IP" test -z "${BAD_LABELS}"
check "etiquetas: rutas por plantilla, sin identificadores numéricos concretos" \
  bash -c "! printf '%s' \"\$1\" | jq -r '.data[]' | grep -qE '/[0-9]{3,}(/|$)'" _ "$(obs exec -T prometheus wget -qO- 'http://localhost:9090/api/v1/label/uri/values')"
check "reglas: todas cargadas (7 de métricas, 5 de logs y 4 de notificaciones) y evaluadas sin error" \
  bash -c "obs_rules=\"\$1\"; test \"\$(jq '[.data.groups[].rules[]] | length' <<< \"\$obs_rules\")\" = 16 && jq -e '[.data.groups[].rules[] | .health] | all(. == \"ok\")' <<< \"\$obs_rules\"" _ "$(obs exec -T prometheus wget -qO- http://localhost:9090/api/v1/rules)"
check "reglas: la raíz del host (/) se reporta: PiedrazulHostRootFilesystemMissing sin condición" \
  test -n "$(pv 'node_filesystem_size_bytes{job="host",mountpoint="/"}')"
echo "      raíz local (indicativo, VM de WSL2): libre $(pv 'node_filesystem_avail_bytes{job="host",mountpoint="/"}') B de $(pv 'node_filesystem_size_bytes{job="host",mountpoint="/"}') B; umbral $(pv 'clamp_min(node_filesystem_size_bytes{job="host",mountpoint="/"} * 0.15, 10 * 1024 * 1024 * 1024)') B"
SERIES="$(pv 'prometheus_tsdb_head_series')"
check "etiquetas: series activas acotadas (${SERIES} < 10000)" test "${SERIES:-99999}" -lt 10000

# ── 4. Grafana ───────────────────────────────────────────────────────────────
set -a; . "${TMP}/obs.env"; set +a
export GRAFANA_AUTH="admin:${GRAFANA_ADMIN_PASSWORD}"
G="http://127.0.0.1:${GPORT}"
check "Grafana: fuente de datos prometheus sana" \
  bash -c "curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/datasources/uid/prometheus/health' | jq -e '.status == \"OK\"'"
check "Grafana: dashboards piedrazul-plataforma, piedrazul-backend-jvm y piedrazul-logs en la carpeta Piedra Azul" \
  bash -c "test \"\$(curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/search?type=dash-db' | jq -r '[.[] | select(.folderTitle == \"Piedra Azul\") | .uid] | sort | join(\",\")')\" = piedrazul-backend-jvm,piedrazul-logs,piedrazul-plataforma"
check "Grafana: sin acceso anónimo (401)" test "$(curl -s -o /dev/null -w '%{http_code}' "${G}/api/search")" = 401
cat > "${TMP}/panels.py" <<'EOF'
import base64, json, os, sys, urllib.request
base = sys.argv[1]
auth = "Basic " + base64.b64encode(os.environ["GRAFANA_AUTH"].encode()).decode()
def call(path, body=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)
errors, empty = [], []
for uid in ["piedrazul-plataforma", "piedrazul-backend-jvm"]:
    d = call(f"/api/dashboards/uid/{uid}")["dashboard"]
    for p in d["panels"]:
        for t in p.get("targets", []):
            q = dict(t, intervalMs=30000, maxDataPoints=200)
            q["expr"] = q["expr"].replace("$__rate_interval", "2m")
            try:
                r = call("/api/ds/query", {"from": "now-15m", "to": "now", "queries": [q]})["results"][t["refId"]]
            except urllib.error.HTTPError as e:
                errors.append(f"{uid}/{p['title']}/{t['refId']}: HTTP {e.code}"); continue
            if r.get("error"):
                errors.append(f"{uid}/{p['title']}/{t['refId']}: {r['error']}"); continue
            if not any(len(f["data"]["values"]) > 1 and any(v is not None for v in f["data"]["values"][-1]) for f in r.get("frames", [])):
                empty.append(f"{uid}/{p['title']}/{t['refId']}")
print(json.dumps({"errors": errors, "empty": empty}, ensure_ascii=False))
EOF
python3 -I "${TMP}/panels.py" "${G}" > "${TMP}/panels.json"
check "Grafana: todas las consultas de los dashboards sin error" jq -e '.errors == []' "${TMP}/panels.json"
# Vacías legítimas: no hay alertas, ni 5xx del backend
check "Grafana: consultas vacías solo donde no hay datos que mostrar (alertas, 5xx del backend)" \
  jq -e '[.empty[] | select(test("Alertas pendientes|Errores 5xx por ruta") | not)] == []' "${TMP}/panels.json"
jq -r '.empty[]' "${TMP}/panels.json" | sed 's/^/      vacía: /'

# ── 5. Aislamiento ───────────────────────────────────────────────────────────
check "aislamiento: Grafana no alcanza backend:8081" \
  bash -c "! (cd '${OBS}' && docker compose -p '${OBS_PROJECT}' --env-file '${TMP}/obs.env' exec -T grafana wget -q -T 3 -O /dev/null http://backend:8081/actuator/prometheus)"
check "aislamiento: Alloy no está en la red de la aplicación" \
  bash -c "! docker inspect \"\$(cd '${OBS}' && docker compose -p '${OBS_PROJECT}' --env-file '${TMP}/obs.env' ps -q alloy)\" --format '{{json .NetworkSettings.Networks}}' | jq -e 'has(\"${APP_NET}\")'"
# Enlaces reales de todos los contenedores de los dos proyectos (`compose port` imprime ":0" para
# un puerto no publicado)
BINDINGS="$(for c in $(docker ps -q --filter label=com.docker.compose.project="${OBS_PROJECT}") $(docker ps -q --filter label=com.docker.compose.project="${APP_PROJECT}"); do
  docker inspect "$c" --format '{{index .Config.Labels "com.docker.compose.service"}} {{json .HostConfig.PortBindings}}' | jq -Rr 'split(" ") as [$s, $b] | ($b | fromjson // {}) | to_entries[] | .key as $p | .value[] | "\($s) \($p) \(.HostIp):\(.HostPort)"'
done)"
printf '%s\n' "${BINDINGS}" | sed 's/^/      puerto publicado: /'
check "aislamiento: el único puerto publicado es grafana 3000 en 127.0.0.1:${GPORT}" \
  test "${BINDINGS}" = "grafana 3000/tcp 127.0.0.1:${GPORT}"

# ── 6. Fallas ────────────────────────────────────────────────────────────────
app stop backend >/dev/null 2>&1
backend_down() { test "$(pv 'up{job="backend"}')" = 0; }
check "falla: backend detenido → up{job=backend} = 0" wait_for 90 backend_down
traffic 20 /actuator/health
sleep 35
check "falla: Traefik cuenta los 502 del backend detenido" \
  test -n "$(pv 'sum(traefik_service_requests_total{service="backend@file",code="502"})')"
# Estado efectivo de la alerta: /api/v1/alerts (lo que Prometheus envía a Alertmanager). No la serie
# ALERTS: tras reiniciar Prometheus su última muestra sigue visible ≈ 5 min (sin marcador de
# obsolescencia) aunque la alerta ya no exista, y daba un falso negativo en la recuperación.
alert_state() { obs exec -T prometheus wget -qO- http://localhost:9090/api/v1/alerts | jq -r '[.data.alerts[] | select(.labels.alertname == "PiedrazulTargetDown" and .labels.job == "backend") | .state] | first // empty'; }
pending() { test "$(alert_state)" = pending; }
check "falla: PiedrazulTargetDown{job=backend} pending (for: 5m)" wait_for 60 pending
check "falla: otros destinos siguen up" test "$(pv 'count(up{job=~"keycloak|traefik|host"} == 1)')" = 3
obs restart prometheus >/dev/null 2>&1
check "falla: Prometheus arranca sin el productor (healthy)" wait_for 60 bash -c "test \"\$(docker inspect \"\$(cd '${OBS}' && docker compose -p '${OBS_PROJECT}' --env-file '${TMP}/obs.env' ps -q prometheus)\" --format '{{.State.Health.Status}}')\" = healthy"
check "falla: tras reiniciar Prometheus el backend sigue up = 0" wait_for 90 backend_down
if [ "${ALERT_FIRING:-0}" = 1 ]; then
  firing() { test "$(alert_state)" = firing; }
  check "falla: PiedrazulTargetDown{job=backend} firing tras 5 m" wait_for 420 firing
fi
app start backend >/dev/null 2>&1
app up -d --wait backend >/dev/null 2>&1
backend_up() { test "$(pv 'up{job="backend"}')" = 1; }
check "recuperación: backend vuelve a up = 1" wait_for 120 backend_up
no_alert() { test -z "$(alert_state)"; }
check "recuperación: la alerta del backend se resuelve" wait_for 90 no_alert

# ── 7. Persistencia y operación ─────────────────────────────────────────────
T0="$(date +%s)"
sleep 1
BEFORE="$(pv 'up{job="keycloak"}' "${T0}")"
STAR="$(curl -s -o /dev/null -w '%{http_code}' -u "${GRAFANA_AUTH}" -X POST "${G}/api/user/stars/dashboard/uid/piedrazul-plataforma")"
IDS_BEFORE="$(obs ps -q | sort | tr '\n' ' ')"
obs up -d --wait > /dev/null 2>&1
check "operación: repetir up no recrea contenedores" test "$(obs ps -q | sort | tr '\n' ' ')" = "${IDS_BEFORE}"
obs stop >/dev/null 2>&1
check "operación: con el stack detenido la aplicación sigue healthy" \
  bash -c "test \"\$(cd '${COMPOSE_DIR}' && docker compose -p '${APP_PROJECT}' --env-file '${TMP}/app.env' -f db.yml -f keycloak.yml -f backend.yml -f migrate.yml -f traefik.yml -f '${TMP}/override.yml' ps --format '{{.Service}}={{.Health}}' backend keycloak | sort | tr '\n' ' ')\" = 'backend=healthy keycloak=healthy '"
H="$(app exec -T traefik wget -qO- --no-check-certificate --header 'Host: api.metrics-check.test' https://localhost/actuator/health 2>/dev/null || true)"
check "operación: con el stack detenido el health público responde UP" grep -q '"status":"UP"' <<< "${H}"
obs down >/dev/null 2>&1
check "operación: down (sin -v) conserva los volúmenes" \
  test "$(docker volume ls -q --filter label=com.docker.compose.project="${OBS_PROJECT}" | wc -l)" = 5
obs up -d --wait --wait-timeout 180 >/dev/null 2>&1
same_before() { test -n "${BEFORE}" && test "$(pv 'up{job="keycloak"}' "${T0}")" = "${BEFORE}"; }
check "persistencia: datos de Prometheus anteriores a la recreación" wait_for 60 same_before
check "persistencia: estado de Grafana (dashboard marcado) tras la recreación" \
  bash -c "test '${STAR}' = 200 && curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/search?starred=true' | jq -e '[.[].uid] | index(\"piedrazul-plataforma\")'"
check "persistencia: dashboards y fuente de datos provisionados tras la recreación" \
  bash -c "test \"\$(curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/search?type=dash-db' | jq length)\" = 3 && curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/datasources/uid/prometheus/health' | jq -e '.status == \"OK\"'"
check "persistencia: los cinco destinos up tras la recreación" wait_for 120 all_up

# Muestra indicativa de recursos (entorno local, no representa el VPS)
echo "recursos (indicativo, local):"
docker stats --no-stream --format '      {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' $(obs ps -q) 2>/dev/null || true
echo "      TSDB: $(obs exec -T prometheus du -sh /prometheus 2>/dev/null | cut -f1), series activas: $(pv 'prometheus_tsdb_head_series')"

if [ "${FAILS}" -eq 0 ]; then echo "OK"; exit 0; else echo "${FAILS} comprobación(es) fallida(s)"; exit 1; fi
