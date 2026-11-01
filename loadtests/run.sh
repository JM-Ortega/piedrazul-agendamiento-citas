#!/usr/bin/env bash
# Corre loadtests/s1-available-slots.js con la imagen fijada de k6. Ver loadtests/README.md.
#
# Uso: loadtests/run.sh smoke|load
# Entorno: PZ_TARGET_URL (obligatorio), PZ_ACCESS_TOKEN o PZ_ACCESS_TOKEN_FILE (si faltan y
# hay terminal, se pide sin eco), PZ_DOCKER_NETWORK, PZ_TARGET_REVISION y PZ_DATASET (opcionales)
# y los parámetros de carga RATE, DURATION, PRE_VUS, MAX_VUS, SMOKE_ITERATIONS, MAX_PAIRS,
# DATES_PER_DOCTOR y SCHEDULING_ORIGIN.
set -euo pipefail

K6_IMAGE="grafana/k6:2.2.0@sha256:9bd01d6941fca969cb61bb57d2da5ee9b385fe2aa8881df3798c196564d6ace6"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PROFILE="${1:-}"
case "$PROFILE" in
  smoke | load) ;;
  *) echo "uso: $0 smoke|load" >&2; exit 2 ;;
esac
: "${PZ_TARGET_URL:?PZ_TARGET_URL no definido (p. ej. http://host.docker.internal:8080)}"
# Mismo criterio que el script: solo origen http(s). Se rechaza antes de crear resultados o
# llamar a Docker, y sin repetir el valor, por si trae un secreto en query o credenciales
if [[ ! "${PZ_TARGET_URL%/}" =~ ^https?://[A-Za-z0-9._-]+(:[0-9]{1,5})?$ ]]; then
  echo "PZ_TARGET_URL debe ser un origen http(s) sin ruta, query, fragmento ni credenciales" >&2; exit 2
fi
export TARGET_URL="${PZ_TARGET_URL%/}"

if [[ -n "${PZ_ACCESS_TOKEN_FILE:-}" ]]; then
  ACCESS_TOKEN="$(tr -d '[:space:]' < "$PZ_ACCESS_TOKEN_FILE")"
elif [[ -n "${PZ_ACCESS_TOKEN:-}" ]]; then
  ACCESS_TOKEN="$PZ_ACCESS_TOKEN"
elif [[ -t 0 ]]; then
  read -rsp "Access token (no se muestra): " ACCESS_TOKEN; echo >&2
else
  echo "Falta el token: defina PZ_ACCESS_TOKEN o PZ_ACCESS_TOKEN_FILE" >&2; exit 2
fi
export ACCESS_TOKEN

REV="$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo desconocida)"
[[ -n "$(git -C "$HERE" status --porcelain -- . 2>/dev/null)" ]] && REV="$REV-dirty"

RESULTS="$HERE/results/$(date -u +%Y%m%dT%H%M%SZ)-$PROFILE"
mkdir -p "$RESULTS"

# host.docker.internal: Docker Desktop (Windows, macOS, WSL) ya lo define; en Docker Engine de
# Linux lo agrega host-gateway. Con PZ_DOCKER_NETWORK el contenedor entra a esa red (p. ej.
# piedrazul_net) y el destino se nombra por su servicio, sin pasar por el host.
NET_ARGS=(--add-host host.docker.internal:host-gateway)
[[ -n "${PZ_DOCKER_NETWORK:-}" ]] && NET_ARGS+=(--network "$PZ_DOCKER_NETWORK")

# El token viaja por nombre (-e VAR): no aparece en la línea de comandos ni en los resultados
PASS=()
for v in RATE DURATION PRE_VUS MAX_VUS SMOKE_ITERATIONS MAX_PAIRS DATES_PER_DOCTOR SCHEDULING_ORIGIN; do
  [[ -n "${!v:-}" ]] && PASS+=(-e "$v=${!v}")
done

set +e
docker run --rm -i \
  -u "$(id -u):$(id -g)" \
  "${NET_ARGS[@]}" \
  -v "$HERE:/scripts:ro" \
  -v "$RESULTS:/results" \
  -e ACCESS_TOKEN \
  -e "PROFILE=$PROFILE" \
  -e TARGET_URL \
  -e "RESULTS_DIR=/results" \
  -e "META_TEST_REVISION=$REV" \
  -e "META_TARGET_REVISION=${PZ_TARGET_REVISION:-}" \
  -e "META_DATASET=${PZ_DATASET:-}" \
  -e "META_K6_IMAGE=$K6_IMAGE" \
  "${PASS[@]}" \
  "$K6_IMAGE" run --quiet --no-color --summary-export /results/summary.json /scripts/s1-available-slots.js \
  2>&1 | tee "$RESULTS/console.txt"
STATUS=${PIPESTATUS[0]}
set -e

echo "resultados: $RESULTS (k6 salió con $STATUS)"
exit "$STATUS"
