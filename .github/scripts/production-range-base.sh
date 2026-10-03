#!/usr/bin/env bash
# Determina la base del rango de producción: el commit del último run push exitoso
# del workflow de producción en main.
#
# Uso: production-range-base.sh <workflow_path> <head_sha> <run_id_actual> < runs.json
#   runs.json: respuesta de GET /repos/{repo}/actions/workflows/{archivo}/runs
#              (más reciente primero).
#
# Solo cuentan los runs push porque solo ellos procesan un rango completo; un dispatch
# manual ejecuta una intención parcial y explícita, así que no avanza el rango (el
# siguiente push vuelve a cubrir esos cambios, lo que es seguro).
# Un run es "exitoso" solo si su conclusión es success: fallido, cancelado o
# reemplazado nunca avanza la base.
#
# Salida: el SHA base, o vacío si no hay un rango confiable (sin runs exitosos, o el
# más reciente no es ancestro de head, p. ej. historia reescrita). Vacío significa
# "clasificar el árbol completo", nunca "no hay cambios".
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 3 ] || fail "uso: $0 <workflow_path> <head_sha> <run_id_actual> < runs.json"
WORKFLOW_PATH="$1"
HEAD="$2"
CURRENT_RUN_ID="$3"

RUNS="$(cat)"
jq -e '.workflow_runs | type == "array"' <<< "${RUNS}" > /dev/null ||
  fail "respuesta de runs inválida"

LATEST="$(jq -r --arg path "${WORKFLOW_PATH}" --arg current "${CURRENT_RUN_ID}" '
  [ .workflow_runs[]
    | select(.path == $path
             and .event == "push"
             and .head_branch == "main"
             and .status == "completed"
             and .conclusion == "success"
             and (.id | tostring) != $current) ]
  | sort_by(.created_at) | reverse | .[0].head_sha // ""
' <<< "${RUNS}")"

if [ -z "${LATEST}" ]; then
  echo "Sin run push exitoso previo: se clasifica el árbol completo" >&2
  exit 0
fi

[[ "${LATEST}" =~ ^[0-9a-f]{40}$ ]] || fail "head_sha inválido en la respuesta: '${LATEST}'"

if ! git cat-file -e "${LATEST}^{commit}" 2>/dev/null ||
   ! git merge-base --is-ancestor "${LATEST}" "${HEAD}"; then
  echo "El último run exitoso (${LATEST}) no es ancestro de ${HEAD}: se clasifica el árbol completo" >&2
  exit 0
fi

echo "${LATEST}"
