#!/usr/bin/env bash
# Procedencia de la aprobación humana de un registro de seguridad versionado (disposición
# de una vulnerabilidad CRITICAL o autorización de break-glass).
#
# El registro nunca establece su propia autoridad: su campo `approver` solo nombra a quién
# hay que encontrar. La aprobación se verifica contra GitHub, fuera del registro:
#   1. El contenido evaluado es el blob del archivo en EVAL_COMMIT (el commit del run).
#   2. El commit de main (primer padre) que dejó el archivo con ese contenido es el merge
#      commit de exactamente un PR fusionado hacia main (ruta protegida: PR con revisión).
#   3. En ese PR, el último estado de revisión de un usuario humano autorizado es APPROVED,
#      y el commit que aprobó tiene EXACTAMENTE el mismo blob del archivo: la aprobación
#      está ligada al contenido y al alcance que se evalúan, no a una versión anterior.
#   4. Ese usuario está en RISK_APPROVERS (login e id numérico inmutable), no es el autor
#      del PR y es quien nombra el registro.
# Cualquier dato ausente, ambiguo o que no coincide falla cerrado.
#
# Uso: approval-provenance.sh <ruta-del-registro> <approver-declarado>
# Entorno:
#   GH_TOKEN, GITHUB_REPOSITORY  (permisos: contents: read, pull-requests: read)
#   EVAL_COMMIT       commit evaluado (por defecto GITHUB_SHA); requiere historia completa
#   RISK_APPROVERS    autoridad: "login:id" separados por espacios o comas
#                     (vars.VULNERABILITY_RISK_APPROVERS; solo administradores la cambian)
# Salida (stdout): JSON con la evidencia verificada (PR, merge, revisión, aprobador, blob).
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 2 ] || fail "uso: $0 <ruta-del-registro> <approver-declarado>"
FILE="$1"
DECLARED="$2"
: "${GITHUB_REPOSITORY:?}"
EVAL_COMMIT="${EVAL_COMMIT:-${GITHUB_SHA:-}}"
[[ "${EVAL_COMMIT}" =~ ^[0-9a-f]{40}$ ]] || fail "EVAL_COMMIT inválido: '${EVAL_COMMIT}'"

LOGIN_RE='^[A-Za-z0-9](-?[A-Za-z0-9]){0,38}$'
[[ "${DECLARED}" =~ ${LOGIN_RE} ]] || fail "approver declarado inválido: '${DECLARED}'"

# ── Autoridad: quién puede aceptar riesgo ─────────────────────────────────────
APPROVERS='[]'
for ENTRY in ${RISK_APPROVERS//,/ }; do
  [[ "${ENTRY}" =~ ^([A-Za-z0-9-]+):([1-9][0-9]{0,14})$ ]] ||
    fail "RISK_APPROVERS mal formado ('${ENTRY}'; se espera login:id)"
  LOGIN="${BASH_REMATCH[1]}" USER_ID="${BASH_REMATCH[2]}"
  [[ "${LOGIN}" =~ ${LOGIN_RE} ]] || fail "RISK_APPROVERS: login inválido '${LOGIN}'"
  APPROVERS=$(jq -c --arg l "${LOGIN,,}" --argjson i "${USER_ID}" \
    '. + [{login: $l, id: $i}]' <<< "${APPROVERS}")
done
[ "$(jq 'length' <<< "${APPROVERS}")" -gt 0 ] ||
  fail "no hay aprobadores de riesgo configurados (vars.VULNERABILITY_RISK_APPROVERS): ninguna aprobación puede ser efectiva"
jq -e --arg l "${DECLARED,,}" 'any(.[]; .login == $l)' <<< "${APPROVERS}" > /dev/null ||
  fail "${DECLARED} no tiene autoridad para aceptar riesgo"

# ── Contenido evaluado y commit de main que lo integró ───────────────────────
BLOB=$(git rev-parse --verify --quiet "${EVAL_COMMIT}:${FILE}") ||
  fail "${FILE} no existe en ${EVAL_COMMIT}"
MERGE=$(git log --first-parent -1 --format=%H "${EVAL_COMMIT}" -- "${FILE}")
[[ "${MERGE}" =~ ^[0-9a-f]{40}$ ]] || fail "${FILE}: no se encontró el commit de main que lo integró"
[ "$(git rev-parse --verify --quiet "${MERGE}:${FILE}")" = "${BLOB}" ] ||
  fail "${FILE}: el commit ${MERGE} no deja el contenido evaluado"

# ── PR fusionado hacia main ──────────────────────────────────────────────────
PULLS=$(gh api "repos/${GITHUB_REPOSITORY}/commits/${MERGE}/pulls") ||
  fail "no se pudieron leer los PR del commit ${MERGE}"
PR=$(jq -c --arg m "${MERGE}" \
  '[.[]? | select(.merged_at != null and .base.ref == "main" and .merge_commit_sha == $m)]' <<< "${PULLS}")
[ "$(jq 'length' <<< "${PR}")" -eq 1 ] ||
  fail "${FILE}: ${MERGE} no es el merge de exactamente un PR fusionado hacia main (integración fuera de la ruta protegida)"
PR=$(jq -c '.[0]' <<< "${PR}")
NUMBER=$(jq -r '.number' <<< "${PR}")
[[ "${NUMBER}" =~ ^[1-9][0-9]*$ ]] || fail "número de PR inválido"
AUTHOR=$(jq -r '.user.login // "" | ascii_downcase' <<< "${PR}")
[ -n "${AUTHOR}" ] || fail "PR #${NUMBER} sin autor"

# ── Revisiones: último estado por usuario ────────────────────────────────────
REVIEWS=$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${NUMBER}/reviews?per_page=100" | jq -s 'add // []') ||
  fail "no se pudieron leer las revisiones del PR #${NUMBER}"
# COMMENTED no cambia el estado de aprobación; DISMISSED y CHANGES_REQUESTED sí lo anulan
CANDIDATES=$(jq -c --argjson approvers "${APPROVERS}" --arg author "${AUTHOR}" --arg declared "${DECLARED,,}" '
  [ .[] | select(.state == "APPROVED" or .state == "CHANGES_REQUESTED" or .state == "DISMISSED") ]
  | group_by(.user.id) | map(max_by(.id))
  | map(select(.state == "APPROVED"
      and .user.type == "User"
      and (.user.login | ascii_downcase) == $declared
      and (.user.login | ascii_downcase) != $author
      and (.commit_id | test("^[0-9a-f]{40}$"))
      and (.user as $u | any($approvers[]; .login == ($u.login | ascii_downcase) and .id == $u.id))))
' <<< "${REVIEWS}")
[ "$(jq 'length' <<< "${CANDIDATES}")" -eq 1 ] ||
  fail "${FILE}: el PR #${NUMBER} no tiene una aprobación vigente de ${DECLARED} (aprobador autorizado, humano, distinto del autor)"
REVIEW=$(jq -c '.[0]' <<< "${CANDIDATES}")
REVIEWED=$(jq -r '.commit_id' <<< "${REVIEW}")

# La aprobación cubre exactamente este contenido
REVIEWED_BLOB=$(gh api "repos/${GITHUB_REPOSITORY}/contents/${FILE}?ref=${REVIEWED}" --jq '.sha') ||
  fail "${FILE}: no existe en el commit aprobado ${REVIEWED}"
[ "${REVIEWED_BLOB}" = "${BLOB}" ] ||
  fail "${FILE}: ${DECLARED} aprobó otro contenido (${REVIEWED_BLOB} en ${REVIEWED}), no el evaluado (${BLOB})"

jq -cn --arg file "${FILE}" --arg blob "${BLOB}" --arg merge "${MERGE}" --argjson pr "${NUMBER}" \
  --arg author "${AUTHOR}" --argjson review "${REVIEW}" '{
    path: $file, blob: $blob, merge_commit: $merge, pr: $pr, pr_author: $author,
    approver_login: $review.user.login, approver_id: $review.user.id,
    review_id: $review.id, reviewed_commit: $review.commit_id, approved_at: $review.submitted_at
  }'
