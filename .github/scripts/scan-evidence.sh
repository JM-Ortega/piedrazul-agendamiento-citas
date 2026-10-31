#!/usr/bin/env bash
# Evidencia de escaneo antes de mutar el servidor.
#
# Corre dentro del job de producción después de seleccionar las identidades exactas y
# antes de cualquier mutación que pueda aplicar o recrear una imagen. Exige el ORDEN y
# una evidencia verificable del intento, no un resultado concreto:
#   backend / postgres con identidad conocida antes del gate (build, rollback), y
#   keycloak / traefik → image_scan terminó en success y existe un summary.json válido
#       de ESA referencia exacta
#   keycloak / traefik además: esa referencia es exactamente KEYCLOAK_IMAGE_REF /
#       TRAEFIK_IMAGE_REF, la que converge despliega (escaneado = desplegado); sin ella no
#       hay despliegue posible
#   backend / postgres en reuso (identidad leída del servidor tras el gate)
#       → se marca para escanear dentro de este job, antes de la mutación
#
# A. Intento completado (la decisión es de vuln-policy.sh, que recibe policy_input):
#      summary.json de la imagen, image_ref idéntico al esperado, y
#      outcome=scanned (con conteos enteros, que pueden ser cero) u
#      outcome=operational_failure (registry/DB/Trivy fallaron; conteos null; la política
#      lo bloquea salvo break-glass)
# B. Cadena incompleta o no verificable (bloquea la mutación; NO es operational_failure):
#      image_scan no terminó en success (falló fuera del intento modelado, cancelado...),
#      evidencia ausente, malformada o de otra referencia, o imagen aplicada sin intento.
#
# Qué aplica cada etapa:
#   converge (setup.yml) → postgres, keycloak, traefik
#   deploy.yml           → postgres, backend (conserva la identidad de keycloak del servidor)
#
# Entorno: TARGETS (JSON de image_targets), SCAN_RESULT (resultado de
#   image_scan), EVIDENCE_DIR (artifacts image-scan-selected-* descargados),
#   RUN_HOST_CONFIG, RUN_APP_DEPLOY, {BACKEND,POSTGRES}_{SOURCE,IMAGE_REF},
#   KEYCLOAK_IMAGE_REF y TRAEFIK_IMAGE_REF (las que recibe converge),
#   REUSE_SCAN_ROOT (directorio donde la acción trivy-scan deja scan-production-<imagen>)
# Salida (GITHUB_OUTPUT): scan_backend, scan_postgres (true = escanear el reuso aquí),
#   evidence (JSON: imagen, referencia y resultado de cada intento previo),
#   policy_input (JSON [{surface, image_ref, dir}]: TODA imagen que el run aplica, con el
#   directorio de su evidencia; el del reuso lo llena el escaneo siguiente)
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

TARGETS="${TARGETS:-}"
[ -n "${TARGETS}" ] || TARGETS='[]'
jq -e 'type == "array"' <<< "${TARGETS}" > /dev/null || fail "TARGETS no es un arreglo JSON"
EVIDENCE_DIR="${EVIDENCE_DIR:-/nonexistent}"

applied() { # <surface>
  case "$1" in
    postgres) [ "${RUN_HOST_CONFIG:-}" = true ] || [ "${RUN_APP_DEPLOY:-}" = true ] ;;
    backend) [ "${RUN_APP_DEPLOY:-}" = true ] ;;
    keycloak | traefik) [ "${RUN_HOST_CONFIG:-}" = true ] ;;
  esac
}

# El escáner nunca falla el job por un problema de Trivy (lo registra como
# operational_failure); si image_scan no terminó en success, la cadena de evidencia está
# rota (o cancelada) y no se puede verificar qué se intentó.
if [ "$(jq 'length' <<< "${TARGETS}")" -gt 0 ] && [ "${SCAN_RESULT:-}" != success ]; then
  fail "image_scan terminó en '${SCAN_RESULT:-ausente}': cadena de escaneo incompleta o no verificable"
fi

EVIDENCE='[]'
POLICY_INPUT='[]'
policy_input() { # <surface> <ref> <dir>
  POLICY_INPUT=$(jq -c --arg s "$1" --arg r "$2" --arg d "$3" '. + [{surface: $s, image_ref: $r, dir: $d}]' \
    <<< "${POLICY_INPUT}")
}
record() { # <surface> <ref> <outcome> [critical] [high]
  EVIDENCE=$(jq -c --arg s "$1" --arg r "$2" --arg o "$3" --arg c "${4:-}" --arg h "${5:-}" \
    '. + [{surface: $s, image_ref: $r, outcome: $o, critical: ($c | if . == "" then null else tonumber end), high: ($h | if . == "" then null else tonumber end)}]' \
    <<< "${EVIDENCE}")
  echo "  ${1}: ${3}${4:+ (CRITICAL ${4}, HIGH ${5})} — ${2}" >&2
}

# Resultado del intento previo de <surface> sobre <ref>; sin evidencia válida, bloquea
prior_outcome() { # <surface> <ref>
  local f matches
  matches=$(find "${EVIDENCE_DIR}" -path "*/image-scan-selected-$1-*/summary.json" 2>/dev/null | wc -l)
  [ "${matches}" -eq 1 ] ||
    fail "$1: se esperaba exactamente un summary.json del escaneo de $2 y hay ${matches}: evidencia ausente o ambigua"
  f=$(find "${EVIDENCE_DIR}" -path "*/image-scan-selected-$1-*/summary.json" -print -quit)
  jq -e 'type == "object"' "${f}" > /dev/null 2>&1 || fail "$1: summary.json malformado"
  policy_input "$1" "$2" "$(cd "$(dirname "${f}")" && pwd)"
  jq -e --arg s "$1" '.surface == $s' "${f}" > /dev/null || fail "$1: summary.json de otra imagen"
  jq -e --arg r "$2" '.image_ref == $r' "${f}" > /dev/null ||
    fail "$1: la evidencia es de $(jq -r '.image_ref // "?"' "${f}"), no de $2"
  if jq -e '.outcome == "scanned"
            and (.critical.findings | type == "number") and (.high.findings | type == "number")' "${f}" > /dev/null; then
    record "$1" "$2" scanned "$(jq '.critical.findings' "${f}")" "$(jq '.high.findings' "${f}")"
  elif jq -e '.outcome == "operational_failure" and .critical == null and .high == null' "${f}" > /dev/null; then
    record "$1" "$2" operational_failure
  else
    fail "$1: summary.json con resultado inválido ($(jq -c '.outcome' "${f}"))"
  fi
}

SCAN_BACKEND=false
SCAN_POSTGRES=false
echo "Escaneos previos a la mutación:" >&2

for SURFACE in backend postgres; do
  applied "${SURFACE}" || continue
  SOURCE_VAR="${SURFACE^^}_SOURCE"
  REF_VAR="${SURFACE^^}_IMAGE_REF"
  SOURCE="${!SOURCE_VAR:-}"
  REF="${!REF_VAR:-}"
  [[ "${REF}" =~ @sha256:[0-9a-f]{64}$ ]] || fail "${SURFACE}: sin identidad exacta seleccionada"
  if [ "${SOURCE}" = reuse ]; then
    if [ "${SURFACE}" = backend ]; then SCAN_BACKEND=true; else SCAN_POSTGRES=true; fi
    policy_input "${SURFACE}" "${REF}" "${REUSE_SCAN_ROOT:-${PWD}}/scan-production-${SURFACE}"
    echo "  ${SURFACE}: reuso — se escanea ${REF} en este job antes de mutar" >&2
  else
    jq -e --arg s "${SURFACE}" --arg r "${REF}" 'any(.[]; .surface == $s and .image_ref == $r)' \
      <<< "${TARGETS}" > /dev/null ||
      fail "${SURFACE} (${SOURCE}): ${REF} no fue objeto de un escaneo previo (identidad no escaneada o distinta)"
    prior_outcome "${SURFACE}" "${REF}"
  fi
done

# Terceros fijados que aplica converge: el escaneo es exactamente la identidad desplegada
for SURFACE in keycloak traefik; do
  applied "${SURFACE}" || continue
  case "${SURFACE}" in
    keycloak) PATTERN='^quay\.io/keycloak/keycloak@sha256:[0-9a-f]{64}$' ;;
    traefik) PATTERN='^docker\.io/library/traefik@sha256:[0-9a-f]{64}$' ;;
  esac
  DEPLOY_VAR="${SURFACE^^}_IMAGE_REF"
  DEPLOY_REF="${!DEPLOY_VAR:-}"
  [[ "${DEPLOY_REF}" =~ ${PATTERN} ]] ||
    fail "${SURFACE}: sin identidad exacta para converge ('${DEPLOY_REF}')"
  [ "$(jq --arg s "${SURFACE}" '[.[] | select(.surface == $s)] | length' <<< "${TARGETS}")" -eq 1 ] ||
    fail "${SURFACE}: se aplica pero no hay exactamente un intento de escaneo de su identidad"
  REF=$(jq -r --arg s "${SURFACE}" '[.[] | select(.surface == $s) | .image_ref][0]' <<< "${TARGETS}")
  [ "${REF}" = "${DEPLOY_REF}" ] ||
    fail "${SURFACE}: se escaneó ${REF} pero converge desplegaría ${DEPLOY_REF}"
  prior_outcome "${SURFACE}" "${REF}"
done

echo "scan_backend=${SCAN_BACKEND}"
echo "scan_postgres=${SCAN_POSTGRES}"
echo "evidence=${EVIDENCE}"
echo "policy_input=${POLICY_INPUT}"
