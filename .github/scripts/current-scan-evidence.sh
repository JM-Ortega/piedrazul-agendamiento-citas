#!/usr/bin/env bash
# Evidencia de escaneo de ESTE run, descargada y verificada (reemplaza a
# actions/download-artifact, que ante un digest que no coincide solo advierte y que elige
# en silencio el más reciente entre artifacts del mismo nombre).
#
# Para cada artifact image-scan-selected-<imagen>-<run_id> del run:
#   - origen: la API de Actions lo liga a ESTE run (GITHUB_RUN_ID) de este repositorio (no
#     un fork) y no está vencido; un nombre repetido (p. ej. de otro intento) es ambiguo y
#     falla cerrado;
#   - integridad y contenido: fetch_verified_artifact (digest registrado por GitHub).
# Los directorios quedan como <dir_salida>/<nombre>/{summary,trivy,trivy-version}.json, el
# formato que leen scan-evidence.sh y vuln-policy.sh. Que falte la evidencia de una imagen
# no es asunto de este script: esos dos la exigen y bloquean.
#
# Uso: current-scan-evidence.sh <dir_salida>
# Entorno: GH_TOKEN (actions: read), GITHUB_REPOSITORY, GITHUB_RUN_ID
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "uso: $0 <dir_salida>"
OUT="$1"
: "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}"
[[ "${GITHUB_RUN_ID}" =~ ^[1-9][0-9]*$ ]] || fail "GITHUB_RUN_ID inválido"
# shellcheck source=.github/scripts/scan-artifact-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/scan-artifact-lib.sh"

REPO_ID=$(gh api "repos/${GITHUB_REPOSITORY}" --jq '.id') || fail "no se pudo leer el repositorio"
[[ "${REPO_ID}" =~ ^[1-9][0-9]*$ ]] || fail "id de repositorio inválido"

LIST=$(gh api --paginate "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/artifacts?per_page=100" |
  jq -s '[.[].artifacts[]?]') || fail "no se pudieron listar los artifacts del run"
SCANS=$(jq -c --arg run "${GITHUB_RUN_ID}" \
  '[.[] | select(.name | test("^image-scan-selected-(backend|postgres|keycloak|traefik)-" + $run + "$"))]' <<< "${LIST}")
[ "$(jq '[.[].name] | length == (unique | length)' <<< "${SCANS}")" = true ] ||
  fail "hay artifacts de escaneo repetidos en el run (otro intento): evidencia ambigua"

mkdir -p "${OUT}"
COUNT=$(jq 'length' <<< "${SCANS}")
for ((i = 0; i < COUNT; i++)); do
  A=$(jq -c ".[${i}]" <<< "${SCANS}")
  NAME=$(jq -r '.name' <<< "${A}")
  jq -e --argjson run "${GITHUB_RUN_ID}" --argjson repo "${REPO_ID}" '
      .expired == false and .workflow_run.id == $run
      and .workflow_run.repository_id == $repo and .workflow_run.head_repository_id == $repo' <<< "${A}" > /dev/null ||
    fail "${NAME}: no es un artifact vigente de este run en este repositorio"
  fetch_verified_artifact "${A}" "${OUT}/${NAME}" || fail "${NAME}: evidencia no verificable"
  echo "${NAME}: $(jq -r '.digest' <<< "${A}") verificado" >&2
done
echo "artifacts=${COUNT}"
