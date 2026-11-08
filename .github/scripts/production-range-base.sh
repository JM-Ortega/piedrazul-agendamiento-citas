#!/usr/bin/env bash
# Determina la base del rango de un componente de producción: el commit del último run de main
# que PROCESÓ el rango de ese componente.
#
# Uso: production-range-base.sh <workflow_path> <head_sha> <run_id_actual> < runs.json
#   runs.json: respuesta de GET /repos/{repo}/actions/workflows/{archivo}/runs?branch=main&
#              status=completed (más reciente primero; cualquier conclusión).
# Entorno: GITHUB_REPOSITORY y las credenciales de `gh`. RANGE_MAX_LOOKUPS (por defecto 50)
#   limita cuántos runs candidatos se consultan.
#   SIGNAL_JOB    job cuya conclusión success en el último intento del run es la señal
#                 (por defecto "Rango procesado", el núcleo; "Observabilidad procesada" para el
#                 stack de observabilidad).
#   RANGE_EVENTS  eventos admitidos, separados por espacios (por defecto "push"). La autoridad no
#                 es el evento sino el job de señal: solo corre cuando su componente se aplicó y
#                 verificó (en un push ordinario, o en un dispatch cuyas condiciones lo prueban:
#                 Run I para el núcleo, una operación full para el stack).
#
# La conclusión del run no cuenta: un push diferido termina en success sin procesar nada, y un
# run con el núcleo aplicado y el stack fallido termina en failure aunque el núcleo esté hecho.
# La señal positiva es el job de señal del ÚLTIMO intento del run en success. Fallido,
# cancelado, saltado, reemplazado o anterior a ese job nunca avanza la base. Si GitHub no
# responde o responde algo ilegible, el script falla: la ausencia de datos no se lee como
# "procesado" ni como "no procesado".
#
# Salida: el SHA base, o vacío si no hay un rango confiable (sin runs procesados entre los
# candidatos, o el más reciente no es ancestro de head, p. ej. historia reescrita). Vacío
# significa "clasificar el árbol completo", nunca "no hay cambios".
set -euo pipefail

SIGNAL_JOB="${SIGNAL_JOB:-Rango procesado}"
RANGE_EVENTS="${RANGE_EVENTS:-push}"

fail() {
  echo "Error: $*" >&2
  exit 1
}

[[ "${RANGE_EVENTS}" =~ ^(push|workflow_dispatch)( (push|workflow_dispatch))*$ ]] || fail "RANGE_EVENTS inválido"

[ "$#" -eq 3 ] || fail "uso: $0 <workflow_path> <head_sha> <run_id_actual> < runs.json"
WORKFLOW_PATH="$1"
HEAD="$2"
CURRENT_RUN_ID="$3"
MAX_LOOKUPS="${RANGE_MAX_LOOKUPS:-50}"
[[ "${MAX_LOOKUPS}" =~ ^[1-9][0-9]*$ ]] || fail "RANGE_MAX_LOOKUPS inválido"

RUNS="$(cat)"
jq -e '.workflow_runs | type == "array"' <<< "${RUNS}" > /dev/null ||
  fail "respuesta de runs inválida"

mapfile -t CANDIDATES < <(jq -r --arg path "${WORKFLOW_PATH}" --arg current "${CURRENT_RUN_ID}" \
  --arg events "${RANGE_EVENTS}" '
  ($events | split(" ")) as $ev
  | [ .workflow_runs[]
    | select(.path == $path
             and (.event as $e | $ev | index($e)) != null
             and .head_branch == "main"
             and .status == "completed"
             and (.id | tostring) != $current) ]
  | sort_by(.created_at) | reverse | .[] | "\(.id) \(.head_sha)"
' <<< "${RUNS}")

# range_processed <run_id>: 0 si el último intento del run tiene la señal, 1 si no la tiene
range_processed() {
  local jobs
  [ -n "${GITHUB_REPOSITORY:-}" ] || fail "GITHUB_REPOSITORY no definido"
  jobs="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/$1/jobs?filter=latest&per_page=100" < /dev/null)" ||
    fail "no se pudieron leer los jobs del run $1"
  jq -e '(.jobs | type == "array") and (.total_count | type == "number") and (.total_count == (.jobs | length))' \
    <<< "${jobs}" > /dev/null 2>&1 || fail "respuesta de jobs ilegible o incompleta para el run $1"
  jq -e --arg job "${SIGNAL_JOB}" '
      [.jobs[] | select(.name == $job)] | length == 1 and .[0].status == "completed" and .[0].conclusion == "success"' \
    <<< "${jobs}" > /dev/null
}

LATEST=""
LOOKUPS=0
for candidate in "${CANDIDATES[@]}"; do
  [ "${LOOKUPS}" -lt "${MAX_LOOKUPS}" ] || break
  LOOKUPS=$((LOOKUPS + 1))
  RUN_ID="${candidate%% *}"
  SHA="${candidate#* }"
  [[ "${RUN_ID}" =~ ^[0-9]+$ ]] || fail "id de run inválido en la respuesta: '${RUN_ID}'"
  [[ "${SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "head_sha inválido en la respuesta: '${SHA}'"
  if range_processed "${RUN_ID}"; then
    LATEST="${SHA}"
    break
  fi
  echo "El run ${RUN_ID} no tiene la señal \"${SIGNAL_JOB}\" en success: no avanza la base" >&2
done

if [ -z "${LATEST}" ]; then
  echo "Sin run con la señal \"${SIGNAL_JOB}\": se clasifica el árbol completo" >&2
  exit 0
fi

if ! git cat-file -e "${LATEST}^{commit}" 2> /dev/null ||
   ! git merge-base --is-ancestor "${LATEST}" "${HEAD}"; then
  echo "El último run procesado (${LATEST}) no es ancestro de ${HEAD}: se clasifica el árbol completo" >&2
  exit 0
fi

echo "${LATEST}"
