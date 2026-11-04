#!/usr/bin/env bash
# Verificación local de los logs del stack de observabilidad (infra/observability): configuración,
# Ansible y la canalización real Alloy → Loki → Grafana sobre archivos de journal SINTÉTICOS
# (systemd-journal-remote). No lee el journal de esta máquina ni de ninguna otra, no usa el driver
# journald de Docker ni levanta la aplicación. Lo que depende de un host Linux con Docker nativo y
# journald (metadatos reales del driver, permisos reales del journal, drop-in de journald,
# Ansible contra un host) lo cubre infra/tests/logs-e2e-lab.sh.
#
# Uso: infra/tests/logs-stack-check.sh
# Entorno opcional:
#   STATIC_ONLY=1       solo configuración y Ansible (1 y 2), sin contenedores de larga duración
#   GRAFANA_LOCAL_PORT  puerto de loopback para Grafana (por defecto 13100)
#   KEEP=1              no elimina el proyecto al terminar (inspección)
# Requiere: docker (compose v2), jq, yq (mikefarah v4), ansible-playbook, python3, openssl, curl;
#   red para construir la imagen que escribe los journals sintéticos (ubuntu +
#   systemd-journal-remote) la primera vez.
#
# Proyecto propio pzlogs-check-<pid>-obs y red externa propia pzlogs_check_<pid>_net (en lugar
# de la de la aplicación). Alloy monta una raíz sintética (/host/root) con el journal sintético en
# lugar de la raíz del host; el resto de compose.yml es el del repositorio. La limpieza solo
# alcanza ese proyecto, esa red y los archivos temporales.
#
# Verifica:
#   1. Configuración: Compose renderiza; Loki fijado por digest, sin puerto, solo en obs_net, sin
#      root, raíz de solo lectura, cap_drop ALL, no-new-privileges y 768 MiB; Alloy sin root, con
#      solo los grupos 473 y OBS_JOURNAL_GID, montajes del host de solo lectura y 256 MiB; sin
#      socket de Docker; Loki valida su configuración (retención, compactor, WAL bajo el límite);
#      destinos de Prometheus = contrato (7); promtool check/test de las dos reglas; Alloy lee
#      solo el journal y escribe solo a Loki; dashboards y fuentes de datos; la aplicación no
#      activa journald ni menciona el stack.
#   2. Ansible: playbooks sintácticamente válidos y fuera del workflow; política de fuentes
#      (admitidos, condicionados, excluidos, expresión inyectada); drop-in de journald.
#   3. Ingesta: entradas seleccionadas llegan a Loki con etiquetas acotadas (source, service,
#      stream, level) y metadatos (container_id, correlation_id); lo excluido (otro proyecto,
#      postgres, servicio condicionado, otras unidades, avisos de journald de otras unidades) no.
#   4. Grafana: fuente de datos Loki sana, dashboard de logs, consultas sin error, búsqueda por id
#      de correlación.
#   5. Prometheus: alloy y loki leídos, 12 reglas sanas, solo las series de la allowlist.
#   6. Fallas y persistencia: reinicio de Alloy sin duplicar (posiciones en el volumen); entradas
#      escritas con Alloy detenido llegan una vez; corte breve de Loki recuperado por reintentos;
#      journal ilegible → componente unhealthy con las métricas del host intactas; `down` + `up`
#      conserva los logs.
#   7. Privacidad PRE/POST: un marcador sensible de un servicio no seleccionado (PRE) no llega a
#      Loki ni después de seleccionarlo; lo POST sí llega y sin marcador. El journal conserva lo
#      PRE (Alloy no lo puede borrar).
#   8. Aislamiento: único puerto publicado Grafana en 127.0.0.1; Loki fuera de la red de la
#      aplicación.
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OBS="${ROOT}/infra/observability"
ANSIBLE_DIR="${ROOT}/infra/ansible"
ID="$$"
OBS_PROJECT="pzlogs-check-${ID}-obs"
APP_NET="pzlogs_check_${ID}_net"
GPORT="${GRAFANA_LOCAL_PORT:-13100}"
# Proyecto y GID sintéticos: no coinciden con la aplicación ni con systemd-journal
LOG_PROJECT="pzlogs-synth"
SYN_GID=4242
MACHINE_ID="5ca1ab1e5ca1ab1e5ca1ab1e5ca1ab1e"
RUN="r${ID}"

for bin in docker jq yq ansible-playbook python3 openssl curl; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
wait_for() { # wait_for <segundos> <comando...>
  local t="$1"; shift
  for _ in $(seq 1 $(( t / 5 ))); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done
  return 1
}

# ── Journal sintético ────────────────────────────────────────────────────────
SYNJ_BASE="ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852"
SYNJ_IMAGE="pzlogs-check/journal-writer:local"
synj_build() {
  docker build -q -t "${SYNJ_IMAGE}" - >/dev/null <<EOF
FROM ${SYNJ_BASE}
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends systemd-journal-remote >/dev/null \\
 && rm -rf /var/lib/apt/lists/*
EOF
}
# synj_records <salida .export> <registro>...: cada registro, campos CAMPO=valor separados por "|".
# Un solo arranque (_BOOT_ID fijo) con reloj monótono creciente entre archivos, como journald: el
# journal ordena las entradas de un mismo arranque por ese reloj.
synj_records() {
  local out="$1"; shift
  python3 -I - "$out" "$@" <<'EOF'
import sys, time
out, recs = sys.argv[1], sys.argv[2:]
t = int(time.time() * 1_000_000)
with open(out, "w") as f:
    for i, r in enumerate(recs):
        f.write(f"__REALTIME_TIMESTAMP={t + i}\n__MONOTONIC_TIMESTAMP={t + i}\n")
        f.write("_BOOT_ID=0123456789abcdef0123456789abcdef\n")
        for kv in r.split("|"):
            f.write(kv + "\n")
        f.write("\n")
EOF
}
# synj_write <nombre.journal> <registro>...: archivo root:SYN_GID 0640 en un directorio
# root:SYN_GID 2755, como journald con systemd-journal en Ubuntu
JDIR="${TMP}/root/var/log/journal/${MACHINE_ID}"
synj_write() {
  local name="$1"; shift
  synj_records "${TMP}/${name}.export" "$@"
  docker run --rm --network none -v "${JDIR}:/j" -v "${TMP}/${name}.export:/in.export:ro" "${SYNJ_IMAGE}" sh -c "
    /usr/lib/systemd/systemd-journal-remote --output=/j/${name}.tmp.journal - < /in.export >/dev/null 2>&1 &&
    chown 0:${SYN_GID} /j/${name}.tmp.journal && chmod 0640 /j/${name}.tmp.journal &&
    mv /j/${name}.tmp.journal /j/${name}.journal && chown 0:${SYN_GID} /j && chmod 2755 /j"
}
synj_contains() {
  docker run --rm --network none -v "${JDIR}:/j:ro" "${SYNJ_IMAGE}" sh -c "journalctl --directory=/j -o cat --no-pager | grep -qF -- '$1'"
}
# Registros como los guarda journald para el driver journald de Docker (contenedor con
# logging.options.labels=com.docker.compose.project,com.docker.compose.service): los campos de
# confianza (_TRANSPORT, _SYSTEMD_UNIT, _COMM, _UID, _PID) los pone journald con las credenciales
# de dockerd; los demás, dockerd.
docker_rec() { # docker_rec <proyecto> <servicio> <prioridad> <mensaje>
  printf '_TRANSPORT=journal|_SYSTEMD_UNIT=docker.service|_COMM=dockerd|_EXE=/usr/bin/dockerd|_UID=0|_PID=812|PRIORITY=%s|CONTAINER_ID=%s|CONTAINER_NAME=%s-%s-1|SYSLOG_IDENTIFIER=%s|COM_DOCKER_COMPOSE_PROJECT=%s|COM_DOCKER_COMPOSE_SERVICE=%s|MESSAGE=%s' \
    "$3" "$(printf '%s' "$1$2" | sha256sum | cut -c1-12)" "$1" "$2" "$2" "$1" "$2" "$4"
}
# Aviso de journald por límite de tasa (SD_MESSAGE_JOURNAL_DROPPED): _TRANSPORT=driver y los
# OBJECT_* del proceso suprimido los pone journald
dropped_rec() { # dropped_rec <unidad del proceso suprimido> [texto]
  printf '_TRANSPORT=driver|PRIORITY=4|_SYSTEMD_UNIT=systemd-journald.service|_COMM=systemd-journal|_UID=0|MESSAGE_ID=a596d6fe7bfa4994828e72309e95d61e|N_DROPPED=17|OBJECT_SYSTEMD_UNIT=%s|MESSAGE=%s' \
    "$1" "${2:-Suppressed 17 messages from $1}"
}
# Productor independiente que imita los campos de Docker: lo que journald guardaría si otro
# proceso (logger --journald, sd_journal_send, systemd-cat) escribe COM_DOCKER_COMPOSE_*,
# MESSAGE_ID u OBJECT_SYSTEMD_UNIT. Los campos de confianza son los de ese proceso: journald
# descarta los "_" que manda un cliente y pone los suyos.
spoof_rec() { # spoof_rec <unidad> <comando> <uid> <transporte> <campos extra> <mensaje>
  printf '_TRANSPORT=%s|_SYSTEMD_UNIT=%s|_COMM=%s|_UID=%s|PRIORITY=6|%s|MESSAGE=%s' "$4" "$1" "$2" "$3" "$5" "$6"
}
SPOOF_FIELDS="COM_DOCKER_COMPOSE_PROJECT=${LOG_PROJECT}|COM_DOCKER_COMPOSE_SERVICE=traefik|CONTAINER_ID=0123456789ab"

RUNTIME_STARTED=false
cleanup() {
  if [ "${RUNTIME_STARTED}" = true ] && [ "${KEEP:-0}" != 1 ]; then
    obs down -v --remove-orphans >/dev/null 2>&1 || true
    docker network rm "${APP_NET}" >/dev/null 2>&1 || true
  fi
  if [ -d "${TMP}/root" ] && docker image inspect "${SYNJ_IMAGE}" >/dev/null 2>&1; then
    docker run --rm --network none -v "${TMP}:/d" "${SYNJ_IMAGE}" rm -rf /d/root >/dev/null 2>&1 || true
  fi
  [ "${KEEP:-0}" = 1 ] || rm -rf "${TMP}"
}
trap cleanup EXIT

PROM_IMAGE="$(yq -r '.services.prometheus.image' "${OBS}/compose.yml")"
LOKI_IMAGE="$(yq -r '.services.loki.image' "${OBS}/compose.yml")"
ALLOY_IMAGE="$(yq -r '.services.alloy.image' "${OBS}/compose.yml")"
promtool() { docker run --rm --network none -v "${OBS}:/w:ro" --entrypoint promtool "${PROM_IMAGE}" "$@"; }

# ── 1. Configuración ─────────────────────────────────────────────────────────
printf 'GRAFANA_ADMIN_PASSWORD=%s\nOBS_APP_NETWORK=%s\nGRAFANA_LOCAL_PORT=%s\n' \
  "$(openssl rand -hex 16)" "${APP_NET}" "${GPORT}" > "${TMP}/obs.env"
chmod 600 "${TMP}/obs.env"
# Raíz sintética para Alloy: el journal sintético en lugar del de la máquina
cat > "${TMP}/override.yml" <<EOF
services:
  alloy:
    volumes: !override
      - ./alloy:/etc/alloy:ro
      - alloy_data:/var/lib/alloy/data
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - ${TMP}/root:/host/root:ro
EOF
obs() { (cd "${OBS}" && docker compose -p "${OBS_PROJECT}" --env-file "${TMP}/obs.env" -f compose.yml -f "${TMP}/override.yml" "$@"); }
obs_plain() { (cd "${OBS}" && docker compose -p "${OBS_PROJECT}" --env-file "${TMP}/obs.env" -f compose.yml "$@"); }

if obs_plain config --format json > "${TMP}/obs.json" 2>"${TMP}/obs-config.err"; then
  ok "Compose: compose.yml renderiza con .env"
else
  ko "Compose: compose.yml renderiza con .env"; cat "${TMP}/obs-config.err" >&2
fi
C="${TMP}/obs.json"
check "Compose: servicios alloy, grafana, loki y prometheus" \
  test "$(jq -r '.services | keys | sort | join(",")' "${C}")" = "alloy,grafana,loki,prometheus"
check "Compose: imágenes fijadas <tag>@sha256:<digest>" \
  bash -c "jq -r '.services[].image' '${C}' | grep -vqE '^[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}\$' && exit 1 || exit 0"
check "Compose: Loki es la imagen oficial grafana/loki" \
  bash -c "jq -r '.services.loki.image' '${C}' | grep -qE '^grafana/loki:3\.[0-9]+\.[0-9]+@sha256:'"
check "Compose: solo grafana publica un puerto, en 127.0.0.1 (Loki sin puerto)" \
  bash -c "test \"\$(jq -r '[.services | to_entries[] | select((.value.ports // []) | length > 0) | .key] | join(\",\")' '${C}')\" = grafana && test \"\$(jq -r '[.services.grafana.ports[] | .host_ip] | unique | join(\",\")' '${C}')\" = 127.0.0.1"
check "Compose: Loki solo en obs_net, sin root (10001), raíz de solo lectura, cap_drop ALL, no-new-privileges" \
  jq -e '.services.loki | ((.networks | keys) == ["obs_net"]) and .user == "10001:10001" and .read_only == true and .cap_drop == ["ALL"] and ((.security_opt // []) | index("no-new-privileges:true"))' "${C}"
check "Compose: Loki con 768 MiB y datos en el volumen loki_data" \
  jq -e '.services.loki.deploy.resources.limits.memory == "805306368" and ([.services.loki.volumes[] | select(.type == "volume") | .source + ":" + .target] == ["loki_data:/loki"])' "${C}"
check "Compose: sin socket de Docker, privileged, red ni PID del host en ningún servicio" \
  bash -c "! jq -e '.services[] | select(.privileged == true or .network_mode == \"host\" or .pid == \"host\" or ((.volumes // []) | map(.source // \"\") | any(test(\"docker.sock\"))))' '${C}'"
check "Compose: los cuatro con cap_drop ALL, no-new-privileges, raíz de solo lectura y límites" \
  test "$(jq '[.services[] | select((.cap_drop == ["ALL"]) and (.read_only == true) and ((.security_opt // []) | index("no-new-privileges:true")) and .deploy.resources.limits.memory and .deploy.resources.limits.cpus)] | length' "${C}")" = 4
check "Compose: Alloy sin root, grupos 473 y nogroup por defecto, 256 MiB" \
  jq -e '.services.alloy.user == "65534:65534" and .services.alloy.group_add == ["473", "65534"] and .services.alloy.deploy.resources.limits.memory == "268435456"' "${C}"
check "Compose: OBS_JOURNAL_GID llega a group_add de Alloy" \
  test "$(cd "${OBS}" && OBS_JOURNAL_GID=4242 docker compose -p x --env-file "${TMP}/obs.env" config --format json 2>/dev/null | jq -c '.services.alloy.group_add')" = '["473","4242"]'
check "Compose: Alloy con montajes del host de solo lectura y solo alloy_data escribible" \
  jq -e '([.services.alloy.volumes[] | select(.type == "bind") | .read_only] | all) and ([.services.alloy.volumes[] | select(.type == "volume") | .source + ":" + .target] == ["alloy_data:/var/lib/alloy/data"])' "${C}"
check "Compose: Alloy guarda su estado en el volumen (--storage.path=/var/lib/alloy/data)" \
  bash -c "jq -r '.services.alloy.command[]' '${C}' | grep -qx -- '--storage.path=/var/lib/alloy/data'"
check "Compose: selección por defecto proyecto piedrazul, servicio traefik" \
  jq -e '.services.alloy.environment.OBS_LOG_PROJECT == "piedrazul" and .services.alloy.environment.OBS_LOG_SERVICES == "traefik"' "${C}"
check "Compose: solo prometheus en la red de la aplicación" \
  test "$(jq -r '[.services | to_entries[] | select(.value.networks | has("app_net")) | .key] | join(",")' "${C}")" = prometheus
check "Compose: métricas intactas (retención de Prometheus 30d/2GB, Grafana 13.3.0 por digest)" \
  bash -c "jq -r '.services.prometheus.command[]' '${C}' | grep -qx -- '--storage.tsdb.retention.time=30d' && jq -r '.services.grafana.image' '${C}' | grep -q '^grafana/grafana:13.3.0@sha256:488984b716c0862639f91a698adbe2862677a5a07bad42da5f03f3030883b205\$'"

check "Loki: la configuración se valida (-verify-config)" \
  docker run --rm --network none -v "${OBS}/loki:/etc/loki:ro" "${LOKI_IMAGE}" -config.file=/etc/loki/config.yaml -verify-config
LC="${OBS}/loki/config.yaml"
check "Loki: retención 14 d con compactor y borrados en el sistema de archivos" \
  test "$(yq -r '[.limits_config.retention_period, .compactor.retention_enabled, .compactor.delete_request_store] | join(" ")' "${LC}")" = "336h true filesystem"
check "Loki: almacenamiento local (filesystem, tsdb v13) en /loki" \
  test "$(yq -r '[.schema_config.configs[0].object_store, .schema_config.configs[0].store, .schema_config.configs[0].schema, .common.path_prefix] | join(" ")' "${LC}")" = "filesystem tsdb v13 /loki"
check "Loki: WAL en el volumen con techo de reproducción bajo el límite de memoria" \
  bash -c "test \"\$(yq -r '.ingester.wal.dir' '${LC}')\" = /loki/wal && c=\$(yq -r '.ingester.wal.replay_memory_ceiling' '${LC}' | sed 's/MB\$//') && test \"\$c\" -lt 768"
check "Loki: sin telemetría, sin descubrimiento de nivel ni de service_name" \
  test "$(yq -r '[.analytics.reporting_enabled, .limits_config.discover_log_levels, (.limits_config.discover_service_name | length)] | join(" ")' "${LC}")" = "false false 0"
check "Loki: escritura y consultas acotadas (tasa, streams, etiquetas, rango = retención)" \
  test "$(yq -r '[.limits_config.ingestion_rate_mb, .limits_config.max_global_streams_per_user, .limits_config.max_label_names_per_series, .limits_config.max_query_lookback] | join(" ")' "${LC}")" = "4 1000 8 336h"

check "Prometheus: destinos = contrato (5 de métricas + alloy y loki por obs_net)" \
  test "$(yq -r '[.scrape_configs[] | .job_name + "=" + (.static_configs[0].targets | join(",")) + (.metrics_path // "/metrics")] | sort | join(" ")' "${OBS}/prometheus/prometheus.yml")" \
       = "alloy=alloy:12345/metrics backend=backend:8081/actuator/prometheus host=alloy:12345/api/v0/component/prometheus.exporter.unix.host/metrics keycloak=keycloak:9000/metrics loki=loki:3100/metrics prometheus=localhost:9090/metrics traefik=traefik:8082/metrics"
check "Prometheus: alloy y loki con allowlist de series (metric_relabel keep)" \
  test "$(yq -r '[.scrape_configs[] | select(.job_name == "alloy" or .job_name == "loki") | .metric_relabel_configs[0].action] | join(",")' "${OBS}/prometheus/prometheus.yml")" = "keep,keep"
check "promtool: check config" promtool check config /w/prometheus/prometheus.yml
check "promtool: check rules (alerts.yml 7 y logs.yml 5)" \
  bash -c "o=\$(docker run --rm --network none -v '${OBS}:/w:ro' --entrypoint promtool '${PROM_IMAGE}' check rules /w/prometheus/rules/alerts.yml /w/prometheus/rules/logs.yml 2>&1); grep -q 'SUCCESS: 7 rules' <<< \"\$o\" && grep -q 'SUCCESS: 5 rules' <<< \"\$o\""
check "reglas de métricas: las siete alertas de métricas siguen definidas" \
  test "$(yq -r '[.groups[].rules[].alert] | sort | join(",")' "${OBS}/prometheus/rules/alerts.yml")" = "PiedrazulHostMemoryLow,PiedrazulHostRootFilesystemLow,PiedrazulHostRootFilesystemMissing,PiedrazulHttp5xxRatioHigh,PiedrazulPrometheusConfigReloadFailed,PiedrazulRuleEvaluationFailing,PiedrazulTargetDown"
for t in alerts logs-alerts; do
  if promtool test rules "/w/tests/${t}.test.yml" > "${TMP}/promtool-${t}.log" 2>&1; then
    ok "promtool: test rules ${t}.test.yml"
  else
    ko "promtool: test rules ${t}.test.yml"; tail -20 "${TMP}/promtool-${t}.log" >&2
  fi
done
AC="$(grep -vE '^\s*//' "${OBS}/alloy/config.alloy")"
check "Alloy: lee el journal por el montaje del host (/host/root/var/log/journal)" \
  grep -qE 'path += "/host/root/var/log/journal"' <<< "${AC}"
check "Alloy: escribe solo a loki:3100 (sin remote_write, otelcol, discovery.docker ni socket)" \
  bash -c "test \"\$(grep -oE 'url +=  *\"[^\"]+\"' <<< \"\$1\" | sed -E 's/.*\"(.*)\"/\\1/')\" = http://loki:3100/loki/api/v1/push && ! grep -qiE 'remote_write|otelcol|discovery\.docker|docker\.sock|loki\.source\.docker' <<< \"\$1\"" _ "${AC}"
check "Alloy: componentes = exportador del host + canalización del journal" \
  test "$(grep -oE '^[a-z_.]+( "[a-z_]+")? \{' <<< "${AC}" | sort | tr '\n' ' ')" = 'logging { loki.process "logs" { loki.relabel "journal_fields" { loki.relabel "select" { loki.source.journal "host" { loki.write "loki" { prometheus.exporter.unix "host" { '
check "Alloy: la configuración se valida (alloy fmt)" \
  docker run --rm --network none -v "${OBS}/alloy:/a:ro" --entrypoint /bin/alloy "${ALLOY_IMAGE}" fmt /a/config.alloy
check "Grafana: dashboards JSON válidos con uid y solo las fuentes prometheus y loki" \
  bash -c "for f in '${OBS}'/grafana/dashboards/*.json; do jq -e '.uid and ([.. | objects | select(has(\"datasource\")) | .datasource.uid] | unique - [\"prometheus\", \"loki\"] == [])' \"\$f\" || exit 1; done"
check "Grafana: dashboard de logs piedrazul-logs con filtros de servicio, severidad, correlación y búsqueda" \
  test "$(jq -r '.uid + " " + ([.templating.list[].name] | join(","))' "${OBS}/grafana/dashboards/logs.json")" = "piedrazul-logs service,level,correlation_id,search"
check "Grafana: fuentes de datos prometheus (por defecto) y loki" \
  test "$(yq -r '.datasources[0].uid + " " + (.datasources[0].isDefault | tostring)' "${OBS}/grafana/provisioning/datasources/prometheus.yml") $(yq -r '.datasources[0].uid + " " + .datasources[0].url + " " + (.datasources[0].isDefault | tostring)' "${OBS}/grafana/provisioning/datasources/loki.yml")" = "prometheus true loki http://loki:3100 false"
check "Aplicación: ningún archivo Compose activa journald ni menciona el stack" \
  bash -c "! grep -rqiE 'journald|loki|alloy|grafana|piedrazul-obs' '${ROOT}/infra/compose' '${ROOT}/docker-compose.yml'"

# ── 2. Ansible ───────────────────────────────────────────────────────────────
for p in observability.yml observability-logs-rollback.yml; do
  check "Ansible: playbooks/${p} sintácticamente válido" \
    bash -c "cd '${ANSIBLE_DIR}' && ANSIBLE_CONFIG=ansible.cfg ansible-playbook --syntax-check playbooks/${p}"
done
check "Ansible: el workflow de producción no corre los playbooks de observabilidad" \
  bash -c "! grep -rq 'observability' '${ROOT}/.github/workflows'"
check "Ansible: converge y deploy no incluyen el rol observability" \
  bash -c "! grep -q observability '${ANSIBLE_DIR}/playbooks/converge.yml' '${ANSIBLE_DIR}/playbooks/deploy.yml'"
# Política de fuentes: solo la tarea de cálculo y la aserción, en local, sin host
cat > "${TMP}/policy.yml" <<EOF
- hosts: localhost
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_vars: ${ANSIBLE_DIR}/roles/observability/defaults/main.yml
    - ansible.builtin.include_tasks: ${TMP}/policy-tasks.yml
EOF
yq '[.[] | select(.name == "Calcular las fuentes de logs no admitidas" or .name == "Verificar la política de fuentes de logs")]' \
  "${ANSIBLE_DIR}/roles/observability/tasks/logs_preflight.yml" > "${TMP}/policy-tasks.yml"
policy() { # policy <extra-vars JSON>: 0 si la política la admite
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ansible-playbook -i localhost, "${TMP}/policy.yml" -e "$1" </dev/null >/dev/null 2>&1
}
check "política: por defecto (traefik) admitida" policy '{}'
rejected() { ! policy "$1"; }
check "política: backend sin controles de privacidad rechazado" rejected '{"obs_log_services": ["traefik", "backend"]}'
check "política: backend y keycloak con controles completos admitidos" \
  policy '{"obs_log_services": ["traefik", "backend", "keycloak"], "obs_log_privacy_controls_done": ["backend", "keycloak"]}'
check "política: postgres rechazado aunque figure como admitido y con controles" \
  rejected '{"obs_log_services": ["postgres"], "obs_log_privacy_controls_done": ["postgres"], "obs_log_services_allowed": ["postgres"]}'
check "política: expresión inyectada en un servicio rechazada" \
  rejected '{"obs_log_services": ["traefik|.*"], "obs_log_services_allowed": ["traefik|.*"]}'
check "política: lista vacía rechazada" rejected '{"obs_log_services": []}'
cat > "${TMP}/dropin.yml" <<EOF
- hosts: localhost
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_vars: ${ANSIBLE_DIR}/roles/observability/defaults/main.yml
    - ansible.builtin.template:
        src: ${ANSIBLE_DIR}/roles/observability/templates/journald-piedrazul-obs.conf.j2
        dest: ${TMP}/dropin.conf
        mode: "0644"
EOF
ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ansible-playbook -i localhost, "${TMP}/dropin.yml" </dev/null >/dev/null 2>&1
check "drop-in de journald: persistente, 2G, archivos de 128M, 7 días y nada más" \
  test "$(grep -vE '^\s*(#|$)' "${TMP}/dropin.conf" 2>/dev/null | tr '\n' ' ')" = "[Journal] Storage=persistent SystemMaxUse=2G SystemMaxFileSize=128M MaxRetentionSec=7day "
check "drop-in de journald: ruta propia en journald.conf.d" \
  test "$(yq -r '.obs_journald_dropin' "${ANSIBLE_DIR}/roles/observability/defaults/main.yml")" = /etc/systemd/journald.conf.d/60-piedrazul-obs.conf
check "Ansible: el rol no toca daemon.json ni otras unidades (solo systemd-journald)" \
  bash -c "! grep -rqE 'daemon\.json|log-driver' '${ANSIBLE_DIR}/roles/observability' && test \"\$(grep -rhE -A1 'ansible.builtin.(systemd_service|systemd|service):' '${ANSIBLE_DIR}/roles/observability/tasks' | grep -oE 'name: [a-z.-]+' | sort -u)\" = 'name: systemd-journald'"

# Laboratorio: inventario aislado (sin inventories/prod) y destino inequívoco. Solo se genera y
# resuelve el inventario (LAB_RENDER_ONLY): no conecta con ningún host.
check "laboratorio: los scripts de laboratorio no usan inventories/prod" \
  bash -c "! grep -vE '^\s*#' '${ROOT}/infra/tests/logs-lab-ansible-check.sh' '${ROOT}/infra/tests/logs-e2e-lab.sh' | grep -q 'inventories/prod'"
LAB_RENDER="$(LAB_HOST=192.0.2.10 LAB_CONFIRM=192.0.2.10 LAB_RENDER_ONLY=1 LAB_KNOWN_HOSTS=/dev/null "${ROOT}/infra/tests/logs-lab-ansible-check.sh" 2>/dev/null)"
check "laboratorio: inventario resuelto = un host vps con solo variables de conexión" \
  bash -c "test \"\$(jq -c 'to_entries | map({k: .key, v: (.value | keys)})' <<< \"\$1\")\" = '[{\"k\":\"vps\",\"v\":[\"ansible_host\",\"ansible_ssh_common_args\",\"ansible_user\"]}]'" _ "${LAB_RENDER}"
check "laboratorio: SSH sin ~/.ssh/config y con StrictHostKeyChecking=yes" \
  bash -c "jq -r '.vps.ansible_ssh_common_args' <<< \"\$1\" | grep -q -- '-F /dev/null -o StrictHostKeyChecking=yes'" _ "${LAB_RENDER}"
check "laboratorio: rechaza un alias o varios destinos" \
  bash -c "! LAB_HOST=obs LAB_CONFIRM=obs LAB_RENDER_ONLY=1 '${ROOT}/infra/tests/logs-lab-ansible-check.sh' && ! LAB_HOST='192.0.2.10,192.0.2.11' LAB_CONFIRM='192.0.2.10,192.0.2.11' LAB_RENDER_ONLY=1 '${ROOT}/infra/tests/logs-lab-ansible-check.sh'"

# Procedencia: la selección se apoya en campos que pone journald, no en el texto
check "procedencia: contenedores solo si los escribió dockerd (_SYSTEMD_UNIT, _COMM, _UID de journald)" \
  bash -c "grep -q '__journal__systemd_unit\", \"__journal__comm\", \"__journal__uid' '${OBS}/alloy/config.alloy' && grep -q 'journal;docker\\\\\\\\.service;dockerd;0;%s;(%s)' '${OBS}/alloy/config.alloy'"
check "procedencia: avisos solo de _TRANSPORT=driver con OBJECT_SYSTEMD_UNIT=docker.service, sin filtro por texto" \
  bash -c "grep -q 'driver;a596d6fe7bfa4994828e72309e95d61e;docker' '${OBS}/alloy/config.alloy' && ! grep -vE '^\s*//' '${OBS}/alloy/config.alloy' | grep -q 'selector *= \"{source=\\\\\"journald'"

if [ "${STATIC_ONLY:-0}" = 1 ]; then
  echo "STATIC_ONLY=1: sin pruebas en ejecución"
  [ "${FAILS}" -eq 0 ] && { echo "OK"; exit 0; } || { echo "${FAILS} comprobación(es) fallida(s)"; exit 1; }
fi

# ── Ejecución con journal sintético ──────────────────────────────────────────
synj_build || { ko "runtime: imagen del escritor de journal"; exit 1; }
mkdir -p "${JDIR}"
# Entorno de ejecución: grupo sintético del journal y selección del proyecto sintético. keycloak
# queda fuera (servicio condicionado sin seleccionar) hasta la ventana POST.
cat >> "${TMP}/obs.env" <<EOF
OBS_JOURNAL_GID=${SYN_GID}
OBS_LOG_PROJECT=${LOG_PROJECT}
OBS_LOG_SERVICES=traefik|backend
EOF
CID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
synj_write pre \
  "$(docker_rec "${LOG_PROJECT}" traefik 6 "2026-11-03T10:00:00Z ERR ${RUN}-traefik-error")" \
  "$(docker_rec "${LOG_PROJECT}" traefik 3 "2026-11-03T10:00:01Z WRN ${RUN}-traefik-stderr")" \
  "$(docker_rec "${LOG_PROJECT}" backend 6 "2026-11-03T10:00:02.000Z  INFO 1 --- [piedrazul] [nio-8080-exec-1] [${CID}] c.e.u.p.Api : ${RUN}-backend-request")" \
  "$(docker_rec "${LOG_PROJECT}" backend 6 "	at co.edu.unicauca.Example(${RUN}-backend-continuation)")" \
  "$(docker_rec "${LOG_PROJECT}" postgres 6 "${RUN}-postgres-excluded")" \
  "$(docker_rec "${LOG_PROJECT}" keycloak 6 "2026-11-03 10:00:03,000 INFO  [org.keycloak.events] (executor) ${RUN}-SENSITIVE-PRE type=LOGIN userId=synthetic")" \
  "$(docker_rec "otro-proyecto" traefik 6 "${RUN}-other-project")" \
  "_TRANSPORT=syslog|_SYSTEMD_UNIT=ssh.service|PRIORITY=6|SYSLOG_IDENTIFIER=sshd|MESSAGE=${RUN}-host-unit" \
  "$(dropped_rec docker.service)" \
  "$(dropped_rec cron.service)" \
  "$(spoof_rec session-3.scope logger 1000 journal "${SPOOF_FIELDS}" "2026-11-03T10:00:04Z ERR ${RUN}-spoof-user-session")" \
  "$(spoof_rec cron.service python3 0 journal "${SPOOF_FIELDS}" "2026-11-03T10:00:05Z ERR ${RUN}-spoof-root-other-unit")" \
  "$(spoof_rec docker.service containerd 0 journal "${SPOOF_FIELDS}" "2026-11-03T10:00:06Z ERR ${RUN}-spoof-docker-unit-other-process")" \
  "$(spoof_rec session-3.scope logger 1000 journal "MESSAGE_ID=a596d6fe7bfa4994828e72309e95d61e|OBJECT_SYSTEMD_UNIT=docker.service" "Suppressed 99 messages from docker.service ${RUN}-spoof-notice")" \
  "$(dropped_rec cron.service "Suppressed 5 messages from cron.service (docker.service) ${RUN}-notice-text-only")" \
  "$(printf '_TRANSPORT=driver|_SYSTEMD_UNIT=systemd-journald.service|_COMM=systemd-journal|_UID=0|MESSAGE_ID=a596d6fe7bfa4994828e72309e95d61e|MESSAGE=Suppressed 3 messages from docker.service %s-notice-without-object' "${RUN}")" \
  || { ko "runtime: journal sintético PRE"; exit 1; }
ok "runtime: journal sintético PRE escrito (16 entradas, 6 de productores que imitan a Docker)"

docker network create "${APP_NET}" >/dev/null || { ko "runtime: red externa propia"; exit 1; }
RUNTIME_STARTED=true
obs up -d --wait --wait-timeout 180 > "${TMP}/obs-up.log" 2>&1 \
  && ok "runtime: stack levantado (proyecto ${OBS_PROJECT})" \
  || { ko "runtime: stack levantado"; tail -20 "${TMP}/obs-up.log" >&2; exit 1; }

pexec() { obs exec -T prometheus "$@"; }
urlq() { python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1"; }
lq() { # lq <LogQL> → JSON de query_range de la última hora
  pexec wget -qO- "http://loki:3100/loki/api/v1/query_range?query=$(urlq "$1")&start=$(( ($(date +%s) - 3600) ))000000000&limit=1000" 2>/dev/null
}
lcount() { lq "$1" | jq '[.data.result[].values | length] | add // 0'; }
pq() { pexec wget -qO- "http://localhost:9090/api/v1/query?query=$(urlq "$1")" 2>/dev/null; }
pv() { pq "$1" | jq -r '.data.result[0].value[1] // empty'; }
loki_ready() { test "$(pexec wget -qO- http://loki:3100/ready 2>/dev/null)" = ready; }
journal_state() { pexec wget -qO- http://alloy:12345/api/v0/web/components 2>/dev/null | jq -r '.[] | select(.localID == "loki.source.journal.host") | .health.state'; }
alloy_metric() { pexec wget -qO- http://alloy:12345/metrics 2>/dev/null | awk -v m="$1" 'index($0, m"{") == 1 || $1 == m {s += $NF} END {print s + 0}'; }

check "runtime: Loki listo (/ready por obs_net)" wait_for 120 loki_ready
# Regresión del laboratorio nativo (Ubuntu 24.04): con "search ." en resolv.conf (lo copia Docker
# de systemd-resolved sin dominios de búsqueda) el wget de busybox de la imagen de Prometheus no
# resuelve nombres cortos. Las sondas del rol y de logs-e2e-lab.sh usan nombres absolutos.
printf 'nameserver 127.0.0.11\nsearch .\noptions ndots:0\n' > "${TMP}/resolv-search-dot.conf"
chmod 644 "${TMP}/resolv-search-dot.conf"
dotwget() { docker run --rm --network "${OBS_PROJECT}_obs_net" -v "${TMP}/resolv-search-dot.conf:/etc/resolv.conf:ro" --entrypoint wget "${PROM_IMAGE}" -qO- -T 5 "$1"; }
short_name_fails() { ! dotwget http://loki:3100/ready; }
# Sin grep -q: con pipefail, el SIGPIPE de wget haría fallar la tubería aunque la serie esté
absolute_names_ok() { test "$(dotwget http://loki.:3100/ready)" = ready && dotwget http://alloy.:12345/metrics | grep '^loki_source_journal_target_lines_total' >/dev/null; }
check "resolución con \"search .\": se reproduce el fallo del nombre corto en la imagen de Prometheus" short_name_fails
check "resolución con \"search .\": loki. y alloy. responden" absolute_names_ok
# Los comandos de host() en logs-lab-ansible-check.sh pasan por la plantilla de Ansible: una llave
# de Go sin escapar ({{.Label …}}) rompe el comando y la comprobación falla sin mirar el host
check "laboratorio: host() de logs-lab-ansible-check.sh sin llaves de Go sin escapar" \
  bash -c "! grep -nE '\\{\\{ *\\.[A-Z]' '${ROOT}/infra/tests/logs-lab-ansible-check.sh'"
check "resolución: las sondas del rol y de logs-e2e-lab.sh a Loki y Alloy usan nombres absolutos" \
  bash -c "! grep -nE 'http://(loki|alloy):' '${ROOT}/infra/ansible/roles/observability/tasks/verify_logs.yml' '${ROOT}/infra/tests/logs-e2e-lab.sh' && grep -q 'http://loki.:3100/ready' '${ROOT}/infra/ansible/roles/observability/tasks/verify_logs.yml'"
healthy() { test "$(journal_state)" = healthy; }
check "runtime: loki.source.journal sano con el grupo del journal (OBS_JOURNAL_GID=${SYN_GID})" wait_for 60 healthy
traefik_in() { test "$(lcount "{service=\"traefik\"} |= \"${RUN}-traefik-error\"")" = 1; }
check "ingesta: entrada de traefik en Loki" wait_for 90 traefik_in

# ── 3. Ingesta ───────────────────────────────────────────────────────────────
check "ingesta: traefik ERR → source=docker, service=traefik, stream=stdout, level=error" \
  test "$(lq "{service=\"traefik\"} |= \"${RUN}-traefik-error\"" | jq -c '.data.result[0].stream | {source, service, stream, level}')" = '{"source":"docker","service":"traefik","stream":"stdout","level":"error"}'
check "ingesta: PRIORITY 3 → stream=stderr; WRN → level=warn" \
  test "$(lq "{service=\"traefik\"} |= \"${RUN}-traefik-stderr\"" | jq -c '.data.result[0].stream | {stream, level}')" = '{"stream":"stderr","level":"warn"}'
check "ingesta: backend INFO con correlation_id como metadato estructurado" \
  test "$(lcount "{service=\"backend\", level=\"info\"} | correlation_id=\"${CID}\"")" = 1
check "ingesta: container_id como metadato estructurado, no etiqueta" \
  test "$(lcount "{service=\"backend\"} | container_id=\"$(printf '%s' "${LOG_PROJECT}backend" | sha256sum | cut -c1-12)\"")" = 2
check "ingesta: línea de continuación sin severidad (sin etiqueta level)" \
  test "$(lq "{service=\"backend\"} |= \"${RUN}-backend-continuation\"" | jq -r '.data.result[0].stream.level // "ninguna"')" = ninguna
check "ingesta: aviso de journald de docker.service → service=journald" \
  test "$(lcount '{source="journald", service="journald"} |= "docker.service"')" = 1
check "exclusión: aviso de journald de otra unidad (cron.service)" test "$(lcount '{source="journald"} |= "cron.service"')" = 0
for m in spoof-user-session spoof-root-other-unit spoof-docker-unit-other-process spoof-notice notice-text-only notice-without-object; do
  check "procedencia: ${m} (campos de Docker o de aviso sin procedencia de confianza) no está en Loki" \
    test "$(lcount "{service=~\".+\"} |= \"${RUN}-${m}\"")" = 0
done
check "procedencia: el journal sí contiene las entradas falsificadas (el filtro está en Alloy)" synj_contains "${RUN}-spoof-user-session"
for m in postgres-excluded other-project host-unit SENSITIVE-PRE; do
  check "exclusión: ${m} no está en Loki" test "$(lcount "{service=~\".+\"} |= \"${RUN}-${m}\"")" = 0
done
check "exclusión: el journal conserva lo no seleccionado (Alloy no lo borra)" synj_contains "${RUN}-SENSITIVE-PRE"
LABELS="$(pexec wget -qO- "http://loki:3100/loki/api/v1/labels?start=$(( $(date +%s) - 3600 ))000000000" 2>/dev/null | jq -r '.data | sort | join(",")')"
echo "      etiquetas en Loki: ${LABELS}"
check "etiquetas: solo source, service, stream y level" \
  bash -c "test -z \"\$(tr ',' '\n' <<< '${LABELS}' | grep -vxE 'source|service|stream|level')\""
READ="$(alloy_metric loki_source_journal_target_lines_total)"
SELECTED="$(pexec wget -qO- http://alloy:12345/metrics 2>/dev/null | awk '/^loki_relabel_entries_written\{component_id="loki.relabel.select"/ {print $NF}')"
echo "      Alloy: leídas ${READ}, seleccionadas ${SELECTED}"
check "ingesta: Alloy cuenta todo lo leído (16) antes de la selección (5: traefik 2, backend 2, aviso de docker.service 1)" test "${READ%.*}/${SELECTED%.*}" = 16/5

# ── 4. Grafana ───────────────────────────────────────────────────────────────
GRAFANA_AUTH="admin:$(sed -n 's/^GRAFANA_ADMIN_PASSWORD=//p' "${TMP}/obs.env")"
export GRAFANA_AUTH
G="http://127.0.0.1:${GPORT}"
check "Grafana: fuente de datos loki sana" \
  bash -c "curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/datasources/uid/loki/health' | jq -e '.status == \"OK\"'"
check "Grafana: fuente de datos prometheus sana" \
  bash -c "curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/datasources/uid/prometheus/health' | jq -e '.status == \"OK\"'"
check "Grafana: tres dashboards en la carpeta Piedra Azul (plataforma, backend-jvm, logs)" \
  bash -c "test \"\$(curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/search?type=dash-db' | jq -r '[.[] | select(.folderTitle == \"Piedra Azul\") | .uid] | sort | join(\",\")')\" = piedrazul-backend-jvm,piedrazul-logs,piedrazul-plataforma"
cat > "${TMP}/panels.py" <<'EOF'
import base64, json, os, sys, urllib.request, urllib.error
base, cid = sys.argv[1], sys.argv[2]
auth = "Basic " + base64.b64encode(os.environ["GRAFANA_AUTH"].encode()).decode()
def call(path, body=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)
def subst(expr, cid_value):
    return (expr.replace("$service", ".+").replace("$level", ".*").replace("$correlation_id", cid_value)
                .replace("$search", "").replace("$__auto", "1m").replace("$__rate_interval", "2m"))
d = call("/api/dashboards/uid/piedrazul-logs")["dashboard"]
errors, rows = [], {}
for p in d["panels"]:
    for t in p.get("targets", []):
        q = dict(t, expr=subst(t["expr"], ""), intervalMs=60000, maxDataPoints=200, maxLines=1000)
        try:
            r = call("/api/ds/query", {"from": "now-1h", "to": "now", "queries": [q]})["results"][t["refId"]]
        except urllib.error.HTTPError as e:
            errors.append(f"{p['title']}/{t['refId']}: HTTP {e.code} {e.read()[:200]!r}"); continue
        if r.get("error"):
            errors.append(f"{p['title']}/{t['refId']}: {r['error']}")
# Búsqueda por id de correlación con la consulta del panel
p = next(p for p in d["panels"] if p["title"].startswith("Líneas con el id de correlación"))
t = p["targets"][0]
r = call("/api/ds/query", {"from": "now-1h", "to": "now", "queries": [dict(t, expr=subst(t["expr"], cid), maxLines=100)]})["results"][t["refId"]]
lines = [v for f in r.get("frames", []) for fld in f["schema"]["fields"] if fld["name"] in ("Line", "line")
         for v in f["data"]["values"][f["schema"]["fields"].index(fld)]]
print(json.dumps({"errors": errors, "cid_lines": len(lines), "cid_match": all(cid in l for l in lines)}, ensure_ascii=False))
EOF
python3 -I "${TMP}/panels.py" "${G}" "${CID}" > "${TMP}/panels.json" 2>"${TMP}/panels.err" || cat "${TMP}/panels.err" >&2
check "Grafana: todas las consultas del dashboard de logs sin error" jq -e '.errors == []' "${TMP}/panels.json"
jq -r '.errors[]?' "${TMP}/panels.json" | sed 's/^/      error: /'
check "Grafana: el panel de correlación devuelve la línea de la petición (y solo esa)" jq -e '.cid_lines == 1 and .cid_match' "${TMP}/panels.json"
check "Grafana: enlace derivado del id de correlación en la fuente de datos" \
  bash -c "curl -sf -u \"\${GRAFANA_AUTH}\" '${G}/api/datasources/uid/loki' | jq -e '.jsonData.derivedFields[0].name == \"correlation_id\"'"
check "Grafana: sin acceso anónimo (401)" test "$(curl -s -o /dev/null -w '%{http_code}' "${G}/api/search")" = 401

# ── 5. Prometheus ────────────────────────────────────────────────────────────
up_logs() { test "$(pv 'count(up{job=~"alloy|loki|host|prometheus"} == 1)')" = 4; }
check "Prometheus: alloy, loki, host y prometheus up" wait_for 120 up_logs
RULES="$(pexec wget -qO- http://localhost:9090/api/v1/rules 2>/dev/null)"
check "Prometheus: 12 reglas cargadas (7 de métricas + 5 de logs), todas sanas" \
  bash -c "test \"\$(jq '[.data.groups[].rules[]] | length' <<< \"\$1\")\" = 12 && jq -e '[.data.groups[].rules[] | .health] | all(. == \"ok\")' <<< \"\$1\"" _ "${RULES}"
ALLOY_NAMES="$(pq 'count by (__name__) ({job="alloy"})' | jq -r '[.data.result[].metric.__name__] | sort | join(" ")')"
echo "      series de job=alloy: ${ALLOY_NAMES}"
check "Prometheus: job=alloy solo con series de la allowlist (más up/scrape_*)" \
  bash -c "test -z \"\$(tr ' ' '\n' <<< '${ALLOY_NAMES}' | grep -vE '^(up|scrape_.*|loki_source_journal_target_.*|loki_relabel_entries_(processed|written)|loki_process_dropped_lines_total|loki_write_.*|alloy_component_controller_running_components|alloy_config_last_load_successful|alloy_build_info|process_resident_memory_bytes|process_start_time_seconds)\$')\""
check "Prometheus: Loki informa líneas recibidas" test -n "$(pv 'sum(loki_distributor_lines_received_total{job="loki"})')"

# ── 6. Fallas y persistencia ─────────────────────────────────────────────────
positions_saved() { obs exec -T alloy sh -c 'find /var/lib/alloy/data -path "*journal*" -name "positions*" | grep -q .'; }
check "posiciones: guardadas en el volumen alloy_data" wait_for 30 positions_saved
# Loki descarta un duplicado exacto (mismo stream, instante y línea): el conteo en Loki no
# muestra una relectura. Se mide en Alloy: tras un reinicio sus contadores empiezan en 0 y solo
# deben contar lo nuevo.
obs restart alloy >/dev/null 2>&1
check "reinicio de Alloy: componente sano" wait_for 60 healthy
sleep 15
check "reinicio de Alloy: no relee el journal (posiciones; 0 entradas leídas)" \
  test "$(alloy_metric loki_source_journal_target_lines_total | cut -d. -f1)" = 0
obs stop alloy >/dev/null 2>&1
synj_write stopped "$(docker_rec "${LOG_PROJECT}" traefik 6 "2026-11-03T10:01:00Z INF ${RUN}-while-alloy-stopped")"
obs start alloy >/dev/null 2>&1
stopped_in() { test "$(lcount "{service=\"traefik\"} |= \"${RUN}-while-alloy-stopped\"")" = 1; }
check "Alloy detenido: lo escrito mientras tanto llega una vez al volver" wait_for 90 stopped_in

# Corte breve de Loki: lo escrito durante el corte se reintenta y llega al volver
obs stop loki >/dev/null 2>&1
synj_write outage "$(docker_rec "${LOG_PROJECT}" traefik 6 "2026-11-03T10:02:00Z INF ${RUN}-during-loki-outage")"
sleep 25
RETRIES="$(alloy_metric loki_write_batch_retries_total)"
check "corte de Loki: Alloy reintenta (loki_write_batch_retries_total=${RETRIES})" test "${RETRIES%.*}" -gt 0
obs start loki >/dev/null 2>&1
outage_in() { test "$(lcount "{service=\"traefik\"} |= \"${RUN}-during-loki-outage\"")" = 1; }
check "corte breve de Loki: la entrada llega por reintento, una vez" wait_for 120 outage_in
check "corte breve de Loki: sin descartes" test "$(alloy_metric loki_write_dropped_entries_total | cut -d. -f1)" = 0

# Journal ilegible: Alloy con un grupo que no puede leer los archivos (directorio 2755 y archivos
# 0640, como en Ubuntu). Se registra cuál de las dos señales da: componente unhealthy o lectura
# detenida con el componente sano (la cubre PiedrazulLogsJournalNotRead y la verificación del rol).
sed -i "s/^OBS_JOURNAL_GID=.*/OBS_JOURNAL_GID=65534/" "${TMP}/obs.env"
obs up -d --wait alloy >> "${TMP}/alloy-recreate.log" 2>&1
check "journal ilegible: Alloy recreado con grupo 65534" \
  test "$(docker inspect "$(obs ps -q alloy)" --format '{{json .HostConfig.GroupAdd}}')" = '["473","65534"]'
sleep 15
synj_write unreadable "$(docker_rec "${LOG_PROJECT}" traefik 6 "2026-11-03T10:02:30Z INF ${RUN}-while-unreadable")"
sleep 20
STATE_UNREADABLE="$(journal_state)"
READ_UNREADABLE="$(alloy_metric loki_source_journal_target_lines_total)"
echo "      journal ilegible: componente ${STATE_UNREADABLE}, entradas leídas desde el arranque ${READ_UNREADABLE}"
check "journal ilegible: Alloy lo señala (componente unhealthy o 0 entradas leídas)" \
  bash -c "test '${STATE_UNREADABLE}' = unhealthy || test '${READ_UNREADABLE%.*}' = 0"
check "journal ilegible: la entrada nueva no llega a Loki" \
  test "$(lcount "{service=\"traefik\"} |= \"${RUN}-while-unreadable\"")" = 0
host_up() { test "$(pv 'up{job="host"}')" = 1 && test -n "$(pv 'node_memory_MemTotal_bytes{job="host"}')"; }
check "journal ilegible: las métricas del host siguen (up{job=host}=1)" wait_for 90 host_up
TOTAL_BEFORE_FIX="$(lcount '{service=~".+"}')"
sed -i "s/^OBS_JOURNAL_GID=.*/OBS_JOURNAL_GID=${SYN_GID}/" "${TMP}/obs.env"
obs up -d --wait alloy >> "${TMP}/alloy-recreate.log" 2>&1
check "journal legible otra vez: componente sano" wait_for 60 healthy
unreadable_in() { test "$(lcount "{service=\"traefik\"} |= \"${RUN}-while-unreadable\"")" = 1; }
check "journal legible otra vez: lo escrito mientras era ilegible llega una vez" wait_for 90 unreadable_in
sleep 10
TOTAL_AFTER_FIX="$(lcount '{service=~".+"}')"
READ_AFTER_FIX="$(alloy_metric loki_source_journal_target_lines_total | cut -d. -f1)"
echo "      tras corregir el grupo: líneas en Loki ${TOTAL_BEFORE_FIX} → ${TOTAL_AFTER_FIX}; entradas leídas por Alloy ${READ_AFTER_FIX}"
check "journal legible otra vez: Alloy lee solo lo pendiente (1 entrada, sin releer)" test "${READ_AFTER_FIX}" = 1

# ── 7. Privacidad PRE/POST ───────────────────────────────────────────────────
# POST: keycloak se selecciona (controles de privacidad completos) y escribe sin el marcador
sed -i "s/^OBS_LOG_SERVICES=.*/OBS_LOG_SERVICES=traefik|backend|keycloak/" "${TMP}/obs.env"
obs up -d --wait alloy >> "${TMP}/alloy-recreate.log" 2>&1
check "POST: Alloy recreado con keycloak seleccionado" wait_for 60 healthy
synj_write post "$(docker_rec "${LOG_PROJECT}" keycloak 6 "2026-11-03 10:03:00,000 INFO  [org.keycloak.events] (executor) ${RUN}-keycloak-POST type=LOGIN")"
post_in() { test "$(lcount "{service=\"keycloak\"} |= \"${RUN}-keycloak-POST\"")" = 1; }
check "POST: el servicio seleccionado llega a Loki" wait_for 90 post_in
check "POST: ninguna línea de keycloak en Loki con el marcador sensible" \
  test "$(lcount '{service="keycloak"} |= "SENSITIVE"')" = 0
check "PRE: el marcador anterior a la selección no entra al seleccionar (posiciones)" \
  test "$(lcount "{service=~\".+\"} |= \"${RUN}-SENSITIVE-PRE\"")" = 0
check "PRE: el journal lo conserva (no se exige su desaparición retroactiva)" synj_contains "${RUN}-SENSITIVE-PRE"

# down + up conserva los logs (volumen loki_data) y las posiciones (alloy_data)
TOTAL_BEFORE_DOWN="$(lcount '{service=~".+"}')"
obs down >/dev/null 2>&1
check "operación: down (sin -v) conserva los cuatro volúmenes" \
  test "$(docker volume ls -q --filter label=com.docker.compose.project="${OBS_PROJECT}" | wc -l)" = 4
obs up -d --wait --wait-timeout 180 >/dev/null 2>&1
check "persistencia: Loki listo tras recrear" wait_for 120 loki_ready
same_total() { test "$(lcount '{service=~".+"}')" = "${TOTAL_BEFORE_DOWN}"; }
check "persistencia: logs anteriores consultables y sin duplicados tras down + up (${TOTAL_BEFORE_DOWN})" wait_for 60 same_total
IDS_BEFORE="$(obs ps -q | sort | tr '\n' ' ')"
obs up -d --wait >/dev/null 2>&1
check "operación: repetir up no recrea contenedores" test "$(obs ps -q | sort | tr '\n' ' ')" = "${IDS_BEFORE}"

# ── 8. Aislamiento ───────────────────────────────────────────────────────────
BINDINGS="$(for c in $(docker ps -q --filter label=com.docker.compose.project="${OBS_PROJECT}"); do
  docker inspect "$c" --format '{{index .Config.Labels "com.docker.compose.service"}} {{json .HostConfig.PortBindings}}' | jq -Rr 'split(" ") as [$s, $b] | ($b | fromjson // {}) | to_entries[] | .key as $p | .value[] | "\($s) \($p) \(.HostIp):\(.HostPort)"'
done)"
check "aislamiento: el único puerto publicado es grafana 3000 en 127.0.0.1:${GPORT}" \
  test "${BINDINGS}" = "grafana 3000/tcp 127.0.0.1:${GPORT}"
check "aislamiento: Loki no está en la red de la aplicación" \
  bash -c "! docker inspect \"\$(cd '${OBS}' && docker compose -p '${OBS_PROJECT}' --env-file '${TMP}/obs.env' ps -q loki)\" --format '{{json .NetworkSettings.Networks}}' | jq -e 'has(\"${APP_NET}\")'"
check "aislamiento: COMPOSE_PROJECT_NAME=piedrazul-obs no desvía las operaciones del proyecto de prueba" \
  test "$(COMPOSE_PROJECT_NAME=piedrazul-obs obs config --format json 2>/dev/null | jq -r '.name')" = "${OBS_PROJECT}"

echo "recursos (indicativo, local):"
docker stats --no-stream --format '      {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' $(obs ps -q) 2>/dev/null || true

if [ "${FAILS}" -eq 0 ]; then echo "OK"; exit 0; else echo "${FAILS} comprobación(es) fallida(s)"; exit 1; fi
