#!/usr/bin/env bash
# Validación sin red ni secretos de la configuración del stack de observabilidad, con las mismas
# imágenes fijadas que despliega infra/observability/compose.yml (cada herramienta es la de su
# servicio). La corre el job observability_validate antes de cualquier aprobación o mutación; un
# fallo deja el stack sin tocar.
#
#   Prometheus  promtool check config (incluye las reglas) y test rules de infra/observability/tests
#   Alertmanager amtool check-config de discord.yml y none.yml (con la plantilla)
#   Loki        -verify-config
#   Alloy       alloy fmt (sintaxis del archivo de configuración)
#   Compose     docker compose config (con valores sintéticos)
#
# Uso: observability-validate.sh [<directorio infra/observability>]
# Requiere: docker, yq (mikefarah v4). Salida: 0 si todo es válido; 1 si algo falla.
set -uo pipefail

OBS="$(cd "${1:-$(dirname "$0")/../../infra/observability}" && pwd)"
FAILS=0
ok() { printf 'OK    %s\n' "$1"; }
ko() { printf 'ERROR %s\n' "$1"; FAILS=$((FAILS + 1)); }
image() { yq -r ".services.$1.image" "${OBS}/compose.yml"; }
run() { # <servicio> <entrypoint> [args...]: contenedor sin red, configuración de solo lectura
  # Además de /w, prometheus/ en /etc/prometheus: prometheus.yml carga las reglas por su ruta de
  # ejecución (/etc/prometheus/rules/*.yml); montado solo en /w, promtool no encontraría ninguna
  # regla y la validaría vacía.
  local svc="$1" ep="$2"; shift 2
  docker run --rm --network none -v "${OBS}:/w:ro" -v "${OBS}/prometheus:/etc/prometheus:ro" -w /w \
    --entrypoint "${ep}" "$(image "${svc}")" "$@"
}
step() { # <descripción> <comando...>
  local d="$1" log; shift
  if log="$("$@" 2>&1)"; then ok "${d}"; else ko "${d}"; printf '%s\n' "${log}" | tail -20 | sed 's/^/      /'; fi
}

for svc in prometheus alertmanager loki alloy grafana; do
  [[ "$(image "${svc}")" =~ ^[a-z0-9./_-]+:[A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}$ ]] ||
    ko "compose.yml: ${svc} sin referencia fijada <tag>@sha256:<índice>"
done

mapfile -t RULES < <(cd "${OBS}/prometheus" && find rules -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
step "Prometheus: promtool check config (con las reglas que carga en ejecución)" \
  run prometheus promtool check config /etc/prometheus/prometheus.yml
if [ "${#RULES[@]}" -gt 0 ]; then
  step "Prometheus: promtool check rules (${#RULES[@]} archivos)" run prometheus promtool check rules "${RULES[@]/#//etc/prometheus/}"
else
  ko "Prometheus: sin archivos de reglas en prometheus/rules"
fi
mapfile -t TESTS < <(cd "${OBS}" && find tests -maxdepth 1 -name '*.test.yml' | sort)
if [ "${#TESTS[@]}" -gt 0 ]; then
  step "Prometheus: promtool test rules (${#TESTS[@]} archivos)" run prometheus promtool test rules "${TESTS[@]/#//w/}"
fi
step "Alertmanager: amtool check-config (discord.yml, none.yml)" \
  run alertmanager amtool check-config /w/alertmanager/discord.yml /w/alertmanager/none.yml
step "Loki: -verify-config" run loki /usr/bin/loki -config.file=/w/loki/config.yaml -verify-config
step "Alloy: sintaxis de config.alloy" run alloy /bin/alloy fmt /w/alloy/config.alloy

# Compose con valores sintéticos (sin .env real): solo que el archivo es válido y que los
# servicios esperados están
step "Compose: compose.yml y compose.access.yml renderizan" bash -c "
  cd '${OBS}' && GRAFANA_ADMIN_PASSWORD=x OBS_EDGE_NETWORK=validate-edge \
  docker compose -f compose.yml -f compose.access.yml config --quiet"

echo
[ "${FAILS}" -eq 0 ] && { echo "Configuración del stack válida"; exit 0; }
echo "${FAILS} problema(s) en la configuración del stack"; exit 1
