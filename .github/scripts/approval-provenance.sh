#!/usr/bin/env bash
# Procedencia de la aprobación humana de un registro de seguridad versionado (disposición
# de una vulnerabilidad CRITICAL o autorización de break-glass).
#
# El registro nunca establece su propia autoridad: su campo `approver` solo nombra a quién
# hay que encontrar. La aprobación se verifica contra GitHub, fuera del registro:
#   1. El contenido evaluado es el blob del archivo en EVAL_COMMIT (el commit del run).
#   2. El commit de main (primer padre) que dejó el archivo con ese contenido es el merge
#      commit de exactamente un PR fusionado hacia main (ruta protegida: PR).
#   3. Una de dos vías, según quién es el autor del PR (nunca las dos, nunca otra):
#      independent_review — el autor NO es el aprobador declarado. En el PR, el último
#        estado de revisión de un usuario humano autorizado es APPROVED, enviado a más
#        tardar en el momento del merge, y el commit que aprobó tiene EXACTAMENTE el mismo
#        blob del archivo: la aprobación está ligada al contenido y al alcance que se
#        evalúan, no a una versión anterior.
#      self_merge — el autor ES el aprobador declarado. GitHub confirma que ese mismo
#        usuario humano autorizado (login e id) abrió y fusionó el PR con un merge commit
#        de dos padres cuyo segundo padre es la cabeza del PR y trae el blob evaluado, y
#        el cambio que el merge introdujo en main (respecto del primer padre) solo agrega,
#        modifica o borra registros de seguridad (security/vulnerability-dispositions/VD-*.json,
#        security/break-glass/BG-*.json, archivos regulares): nada de código, workflows ni
#        documentación. Las revisiones y comentarios del PR no se consultan: un texto
#        editable nunca autoriza.
#   4. Ese usuario está en RISK_APPROVERS (login e id numérico inmutable) y es quien nombra
#      el registro.
# Cualquier dato ausente, ambiguo, contradictorio o que no coincide falla cerrado.
#
# Uso: approval-provenance.sh <ruta-del-registro> <approver-declarado>
# Entorno:
#   GH_TOKEN, GITHUB_REPOSITORY  (permisos: contents: read, pull-requests: read)
#   EVAL_COMMIT       commit evaluado (por defecto GITHUB_SHA); requiere historia completa
#   RISK_APPROVERS    autoridad: "login:id" separados por espacios o comas
#                     (vars.VULNERABILITY_RISK_APPROVERS: entrada de la política; la cambia
#                     cualquiera con permiso para gestionar variables del repositorio, no
#                     es una frontera frente a quien tiene ese permiso)
# Salida (stdout): JSON con la evidencia verificada (vía, PR, merge, aprobador, blob e
#   historia del archivo en main: record_history, los commits de primer padre que lo
#   tocaron, del más reciente al más antiguo).
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
ISO_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
# Lo único que puede traer un PR de self_merge: registros con el nombre de su tipo
RECORD_RE='^security/(vulnerability-dispositions/VD|break-glass/BG)-[0-9]{4}-[0-9]{3}\.json$'
[[ "${DECLARED}" =~ ${LOGIN_RE} ]] || fail "approver declarado inválido: '${DECLARED}'"

epoch_ns() { # <iso8601 UTC> → nanosegundos desde epoch, con la fracción; falla si no es válida
  # Comparar solo segundos dejaría pasar una revisión posterior dentro del mismo segundo
  [[ "$1" =~ ${ISO_RE} ]] || return 1
  local frac="${BASH_REMATCH[1]#.}000000000" s
  s=$(date -u -d "${1:0:19}Z" +%s 2> /dev/null) || return 1
  echo $((s * 1000000000 + 10#${frac:0:9}))
}

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
# Con historia recortada, el merge encontrado (y la historia del registro) podría no ser el real
[ "$(git rev-parse --is-shallow-repository)" = false ] ||
  fail "historia incompleta (clon superficial): no se puede ubicar la integración del registro"
BLOB=$(git rev-parse --verify --quiet "${EVAL_COMMIT}:${FILE}") ||
  fail "${FILE} no existe en ${EVAL_COMMIT}"
HISTORY=$(git log --first-parent --format=%H "${EVAL_COMMIT}" -- "${FILE}")
MERGE=$(head -n1 <<< "${HISTORY}")
[[ "${MERGE}" =~ ^[0-9a-f]{40}$ ]] || fail "${FILE}: no se encontró el commit de main que lo integró"
[ "$(git rev-parse --verify --quiet "${MERGE}:${FILE}")" = "${BLOB}" ] ||
  fail "${FILE}: el commit ${MERGE} no deja el contenido evaluado"
HISTORY=$(jq -R . <<< "${HISTORY}" | jq -sc .)

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
MERGED_AT=$(jq -r '.merged_at' <<< "${PR}")
MERGED_EPOCH=$(epoch_ns "${MERGED_AT}") || fail "PR #${NUMBER}: fecha de merge inválida ('${MERGED_AT}')"

# ── Vía self_merge: el aprobador declarado abrió y fusionó un PR solo de registros ──
self_merge() {
  local pull user merger p1 p2 extra head meta path mode status found=false
  pull=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${NUMBER}") ||
    fail "no se pudo leer el PR #${NUMBER}"
  # El PR individual es la fuente de merged_by; debe coincidir con lo que listó el commit
  jq -e --argjson n "${NUMBER}" --arg m "${MERGE}" --arg at "${MERGED_AT}" --arg author "${AUTHOR}" '
      .number == $n and .merged == true and .merged_at == $at and .base.ref == "main"
      and .merge_commit_sha == $m and ((.user.login // "") | ascii_downcase) == $author' <<< "${pull}" > /dev/null 2>&1 ||
    fail "${FILE}: el PR #${NUMBER} no confirma un merge hacia main en ${MERGE} (datos ausentes o contradictorios)"

  user=$(jq -c '.user // {}' <<< "${pull}")
  merger=$(jq -c '.merged_by // {}' <<< "${pull}")
  jq -e '.type == "User" and (.id | type == "number")' <<< "${user}" > /dev/null 2>&1 ||
    fail "${FILE}: el autor del PR #${NUMBER} no es un usuario humano identificable"
  jq -e --argjson approvers "${APPROVERS}" \
      '. as $u | any($approvers[]; .login == ($u.login | ascii_downcase) and .id == $u.id)' <<< "${user}" > /dev/null ||
    fail "${FILE}: el autor del PR #${NUMBER} no es el aprobador autorizado ${DECLARED} (login e id)"
  jq -e '.type == "User" and (.id | type == "number")' <<< "${merger}" > /dev/null 2>&1 ||
    fail "${FILE}: quien fusionó el PR #${NUMBER} no es un usuario humano identificable"
  jq -e --argjson u "${user}" '.id == $u.id and (.login | ascii_downcase) == ($u.login | ascii_downcase)' <<< "${merger}" > /dev/null ||
    fail "${FILE}: el PR #${NUMBER} lo fusionó otra cuenta que su autor: no es una autorización propia"

  # Forma del merge: merge commit de dos padres, el segundo es la cabeza del PR
  read -r _ p1 p2 extra <<< "$(git rev-list --parents -n1 "${MERGE}")"
  [ -n "${p2:-}" ] && [ -z "${extra:-}" ] ||
    fail "${FILE}: ${MERGE} no es un merge commit de dos padres (squash, rebase u octopus no autorizan)"
  head=$(jq -r '.head.sha // ""' <<< "${pull}")
  [ "${head}" = "${p2}" ] ||
    fail "${FILE}: el segundo padre de ${MERGE} no es la cabeza del PR #${NUMBER} ('${head}')"
  [ "$(git rev-parse --verify --quiet "${p2}:${FILE}")" = "${BLOB}" ] ||
    fail "${FILE}: el contenido evaluado no es el de la cabeza del PR (cambió al fusionar)"

  # Cambio que el merge introdujo en main: solo registros de seguridad (el registro
  # evaluado, con la ruta de su tipo, entre ellos)
  [[ "${FILE}" =~ ${RECORD_RE} ]] || fail "${FILE}: ruta de registro no admitida para self_merge"
  while IFS= read -r -d '' meta && IFS= read -r -d '' path; do
    read -r _ mode _ _ status <<< "${meta#:}"
    [[ "${path}" =~ ${RECORD_RE} ]] ||
      fail "${FILE}: el PR #${NUMBER} cambia ${path}, que no es un registro de seguridad: un self_merge solo puede traer registros"
    case "${status}" in
      A | M) [ "${mode}" = 100644 ] || fail "${FILE}: ${path} no es un archivo regular (modo ${mode})" ;;
      D) ;;
      *) fail "${FILE}: el PR #${NUMBER} cambia ${path} de forma no admitida (${status})" ;;
    esac
    [ "${path}" != "${FILE}" ] || [ "${status}" = D ] || found=true
  done < <(git diff --raw --no-renames --no-abbrev -z "${p1}" "${MERGE}")
  [ "${found}" = true ] || fail "${FILE}: el merge ${MERGE} no agrega ni modifica este registro"

  jq -cn --arg file "${FILE}" --arg blob "${BLOB}" --arg merge "${MERGE}" --argjson pr "${NUMBER}" \
    --arg author "${AUTHOR}" --argjson user "${user}" --arg head "${head}" --arg at "${MERGED_AT}" \
    --argjson history "${HISTORY}" '{
      method: "self_merge", path: $file, blob: $blob, merge_commit: $merge, pr: $pr, pr_author: $author,
      approver_login: $user.login, approver_id: $user.id, review_id: null,
      reviewed_commit: $head, approved_at: $at, merged_at: $at, record_history: $history
    }'
}

# ── Vía independent_review: revisión APPROVED de otro aprobador, antes del merge ──
independent_review() {
  local reviews candidates review reviewed reviewed_blob submitted
  reviews=$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${NUMBER}/reviews?per_page=100" | jq -s 'add // []') ||
    fail "no se pudieron leer las revisiones del PR #${NUMBER}"
  # COMMENTED no cambia el estado de aprobación; DISMISSED y CHANGES_REQUESTED sí lo anulan
  candidates=$(jq -c --argjson approvers "${APPROVERS}" --arg author "${AUTHOR}" --arg declared "${DECLARED,,}" '
    [ .[] | select(.state == "APPROVED" or .state == "CHANGES_REQUESTED" or .state == "DISMISSED") ]
    | group_by(.user.id) | map(max_by(.id))
    | map(select(.state == "APPROVED"
        and .user.type == "User"
        and (.user.login | ascii_downcase) == $declared
        and (.user.login | ascii_downcase) != $author
        and (.commit_id | test("^[0-9a-f]{40}$"))
        and (.user as $u | any($approvers[]; .login == ($u.login | ascii_downcase) and .id == $u.id))))
  ' <<< "${reviews}")
  [ "$(jq 'length' <<< "${candidates}")" -eq 1 ] ||
    fail "${FILE}: el PR #${NUMBER} no tiene una aprobación vigente de ${DECLARED} (aprobador autorizado, humano, distinto del autor)"
  review=$(jq -c '.[0]' <<< "${candidates}")
  reviewed=$(jq -r '.commit_id' <<< "${review}")

  # Una aprobación posterior al merge no autoriza lo que ya se integró
  submitted=$(epoch_ns "$(jq -r '.submitted_at // ""' <<< "${review}")") ||
    fail "${FILE}: la aprobación de ${DECLARED} no tiene una fecha válida"
  [ "${submitted}" -le "${MERGED_EPOCH}" ] ||
    fail "${FILE}: ${DECLARED} aprobó después del merge del PR #${NUMBER} (${MERGED_AT}): no autoriza retroactivamente"

  # La aprobación cubre exactamente este contenido
  reviewed_blob=$(gh api "repos/${GITHUB_REPOSITORY}/contents/${FILE}?ref=${reviewed}" --jq '.sha') ||
    fail "${FILE}: no existe en el commit aprobado ${reviewed}"
  [ "${reviewed_blob}" = "${BLOB}" ] ||
    fail "${FILE}: ${DECLARED} aprobó otro contenido (${reviewed_blob} en ${reviewed}), no el evaluado (${BLOB})"

  jq -cn --arg file "${FILE}" --arg blob "${BLOB}" --arg merge "${MERGE}" --argjson pr "${NUMBER}" \
    --arg author "${AUTHOR}" --argjson review "${review}" --arg at "${MERGED_AT}" \
    --argjson history "${HISTORY}" '{
      method: "independent_review", path: $file, blob: $blob, merge_commit: $merge, pr: $pr, pr_author: $author,
      approver_login: $review.user.login, approver_id: $review.user.id,
      review_id: $review.id, reviewed_commit: $review.commit_id, approved_at: $review.submitted_at,
      merged_at: $at, record_history: $history
    }'
}

# La vía la decide el autor real del PR, no el registro ni el PR: nunca ambas
if [ "${AUTHOR}" = "${DECLARED,,}" ]; then
  self_merge
else
  independent_review
fi
