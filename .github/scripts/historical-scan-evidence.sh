#!/usr/bin/env bash
# Procedencia e integridad de evidencia histórica de escaneo (solo para break-glass).
#
# La evidencia elegible es un artifact image-scan-* que la acción trivy-scan subió en un
# run anterior de este mismo workflow de producción sobre main. Nada de lo que el artifact
# dice de sí mismo establece su origen; todo se verifica contra GitHub:
#   1. Origen: la API de Actions liga el artifact a un run de WORKFLOW_PATH, evento push o
#      workflow_dispatch, rama main, en este repositorio (no un fork), cuyo commit es
#      ancestro de EVAL_COMMIT (código integrado por la ruta protegida de main). El nombre
#      es image-scan-<fase>-<surface>-<run_id> de ESE run y no está vencido.
#   2. Integridad: el digest SHA-256 que GitHub registró para el artifact al subirlo es el
#      que aprobó la autorización, y el zip descargado tiene exactamente ese digest (no un
#      checksum que traiga el propio artifact).
#   3. Contenido: exactamente summary.json, trivy.json y trivy-version.json; summary.json
#      es un escaneo exitoso de <surface> y <image_ref> linux/amd64. vuln-policy.sh valida
#      después el reporte completo (referencia, plataforma, fechas, DB, hallazgos).
# Cualquier dato ausente, vencido o que no coincide falla cerrado.
#
# Uso: historical-scan-evidence.sh <surface> <image_ref> <run_id> <artifact_id> <artifact_digest> <dir_salida>
# Entorno: GH_TOKEN, GITHUB_REPOSITORY (permiso actions: read), EVAL_COMMIT (por defecto
#   GITHUB_SHA; requiere historia completa), WORKFLOW_PATH
# Salida (stdout): JSON con el origen verificado; los tres archivos quedan en <dir_salida>.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 6 ] || fail "uso: $0 <surface> <image_ref> <run_id> <artifact_id> <artifact_digest> <dir_salida>"
SURFACE="$1" REF="$2" RUN_ID="$3" ARTIFACT_ID="$4" DIGEST="$5" OUT="$6"
: "${GITHUB_REPOSITORY:?}" "${WORKFLOW_PATH:?}"
EVAL_COMMIT="${EVAL_COMMIT:-${GITHUB_SHA:-}}"
[[ "${EVAL_COMMIT}" =~ ^[0-9a-f]{40}$ ]] || fail "EVAL_COMMIT inválido"
[[ "${SURFACE}" =~ ^(backend|postgres|keycloak|traefik)$ ]] || fail "imagen inválida '${SURFACE}'"
[[ "${REF}" =~ ^[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}$ ]] || fail "referencia inexacta '${REF}'"
[[ "${RUN_ID}" =~ ^[1-9][0-9]*$ ]] || fail "run_id inválido"
[[ "${ARTIFACT_ID}" =~ ^[1-9][0-9]*$ ]] || fail "artifact_id inválido"
[[ "${DIGEST}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "artifact_digest inválido"
[ ! -e "${OUT}" ] || fail "${OUT} ya existe"
# shellcheck source=.github/scripts/scan-artifact-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/scan-artifact-lib.sh"

REPO_ID=$(gh api "repos/${GITHUB_REPOSITORY}" --jq '.id') || fail "no se pudo leer el repositorio"
[[ "${REPO_ID}" =~ ^[1-9][0-9]*$ ]] || fail "id de repositorio inválido"

# ── 1. Origen ────────────────────────────────────────────────────────────────
ARTIFACT=$(gh api "repos/${GITHUB_REPOSITORY}/actions/artifacts/${ARTIFACT_ID}") ||
  fail "artifact ${ARTIFACT_ID} no existe o no es legible"
jq -e --argjson id "${ARTIFACT_ID}" --argjson run "${RUN_ID}" --argjson repo "${REPO_ID}" \
  --arg name_re "^image-scan-(selected|production)-${SURFACE}-${RUN_ID}$" '
    .id == $id and .expired == false and (.name | test($name_re))
    and .workflow_run.id == $run and .workflow_run.head_branch == "main"
    and .workflow_run.repository_id == $repo and .workflow_run.head_repository_id == $repo' \
  <<< "${ARTIFACT}" > /dev/null ||
  fail "artifact ${ARTIFACT_ID}: no es un escaneo de ${SURFACE} del run ${RUN_ID} sobre main de este repositorio, o está vencido"
[ "$(jq -r '.digest // ""' <<< "${ARTIFACT}")" = "${DIGEST}" ] ||
  fail "artifact ${ARTIFACT_ID}: GitHub registró $(jq -r '.digest // "ningún digest"' <<< "${ARTIFACT}"), no el autorizado ${DIGEST}"

RUN=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}") || fail "run ${RUN_ID} no es legible"
WORKFLOW_ID=$(gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/$(basename "${WORKFLOW_PATH}")" --jq '.id') ||
  fail "no se pudo leer el workflow ${WORKFLOW_PATH}"
jq -e --argjson run "${RUN_ID}" --argjson repo "${REPO_ID}" --argjson wf "${WORKFLOW_ID}" --arg path "${WORKFLOW_PATH}" '
    .id == $run and .workflow_id == $wf
    and (.path == $path or (.path | startswith($path + "@")))
    and (.event == "push" or .event == "workflow_dispatch") and .head_branch == "main"
    and .repository.id == $repo and .head_repository.id == $repo
    and (.head_sha | test("^[0-9a-f]{40}$"))' <<< "${RUN}" > /dev/null ||
  fail "run ${RUN_ID}: no es un run de ${WORKFLOW_PATH} (push o dispatch) sobre main de este repositorio"
RUN_SHA=$(jq -r '.head_sha' <<< "${RUN}")
git merge-base --is-ancestor "${RUN_SHA}" "${EVAL_COMMIT}" 2> /dev/null ||
  fail "run ${RUN_ID}: su commit ${RUN_SHA} no es parte de la historia de main de este run"

# ── 2 y 3. Integridad y contenido (digest registrado por GitHub, archivos exactos) ──
fetch_verified_artifact "${ARTIFACT}" "${OUT}" || fail "artifact ${ARTIFACT_ID}: evidencia no verificable"
jq -e --arg s "${SURFACE}" --arg r "${REF}" \
  '.surface == $s and .image_ref == $r and .platform == "linux/amd64" and .outcome == "scanned"' \
  "${OUT}/summary.json" > /dev/null 2>&1 ||
  fail "artifact ${ARTIFACT_ID}: no es un escaneo exitoso de ${REF} (${SURFACE}, linux/amd64)"

jq -cn --argjson a "${ARTIFACT}" --argjson r "${RUN}" '{
  run_id: $r.id, run_commit: $r.head_sha, run_event: $r.event, run_attempt: ($r.run_attempt // null),
  run_url: ($r.html_url // null), artifact_id: $a.id, artifact_name: $a.name,
  artifact_digest: $a.digest, artifact_created_at: ($a.created_at // null)
}'
