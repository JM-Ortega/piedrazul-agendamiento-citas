#!/usr/bin/env bash
# Registro de auditoría de un break-glass, ANTES de cualquier mutación.
#
# Corre dentro del job de producción solo si vuln-policy.sh decidió break_glass. Falla
# cerrado (y producción no muta) si:
#   - la decisión no es break_glass o falta la autorización verificada;
#   - este run no tiene una aprobación humana registrada del environment de producción;
#   - la autorización ya se consumió, o su registro de consumo es ilegible o ambiguo;
#   - el consumo no se pudo registrar;
#   - el issue no se pudo crear o no quedó con su etiqueta.
# Una autorización cubre UNA operación: el primer intento la consume (incluso si después
# falla o se cancela); un re-run u otra operación necesitan una autorización nueva.
#
# Registro de consumo: un commit status con contexto
# security/break-glass-consumption/<id> sobre el merge commit de main que integró la
# autorización (el que verificó approval-provenance.sh). El consumo previo se busca en TODOS
# los commits de main que alguna vez tocaron security/break-glass/<id>.json
# (consumption_anchors, de la historia completa del commit evaluado): editar, borrar y
# recrear o re-fusionar el registro con el mismo id deja otro merge commit, pero no borra el
# consumo de los anteriores; el id queda agotado y hace falta otro. GitHub no permite editar ni borrar
# commit statuses y la API los lista todos, así que un consumo no puede desaparecer
# cambiando metadatos. El issue security-break-glass es el registro operativo de auditoría,
# pero NO se usa para decidir el consumo: su título y etiquetas son editables y puede
# transferirse a otro repositorio. Cualquier status con ese contexto (de quien sea) cuenta
# como consumo: agregar uno falso solo puede bloquear (falla cerrado). La concurrencia del
# job de producción serializa comprobar y registrar.
#
# Uso: break-glass-record.sh
# Entorno: POLICY_RESULT (JSON de vuln-policy.sh), ENVIRONMENT_NAME, GH_TOKEN,
#   GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, GITHUB_SHA, GITHUB_ACTOR,
#   GITHUB_TRIGGERING_ACTOR, GITHUB_SERVER_URL
#   (permisos: actions: read, statuses: write, issues: write)
# Salida (GITHUB_OUTPUT): issue_number, issue_url
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

: "${POLICY_RESULT:?}" "${ENVIRONMENT_NAME:?}" "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}"
: "${GITHUB_RUN_ATTEMPT:?}" "${GITHUB_SHA:?}" "${GITHUB_ACTOR:?}" "${GITHUB_TRIGGERING_ACTOR:?}"
LABEL=security-break-glass

jq -e '.decision == "break_glass" and .break_glass != null and (.break_glass.id | test("^BG-[0-9]{4}-[0-9]{3}$"))
       and (.break_glass.operation | type == "object")
       and (.break_glass.authorization_commit | type == "string" and test("^[0-9a-f]{40}$"))
       and (.break_glass.authorization_method == "self_merge" or .break_glass.authorization_method == "independent_review")
       and (.break_glass.consumption_anchors | type == "array" and length >= 1 and length == (unique | length)
            and all(.[]; type == "string" and test("^[0-9a-f]{40}$")))
       and .break_glass.consumption_anchors[0] == .break_glass.authorization_commit' "${POLICY_RESULT}" > /dev/null 2>&1 ||
  fail "la política no autorizó un break-glass verificado"
ID=$(jq -r '.break_glass.id' "${POLICY_RESULT}")
AUTH_COMMIT=$(jq -r '.break_glass.authorization_commit' "${POLICY_RESULT}")
mapfile -t ANCHORS < <(jq -r '.break_glass.consumption_anchors[]' "${POLICY_RESULT}")
CONTEXT="security/break-glass-consumption/${ID}"
RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/attempts/${GITHUB_RUN_ATTEMPT}"

# Aprobación humana del environment en este run (no basta con que el job haya empezado:
# un environment sin revisores requeridos no deja aprobación)
APPROVALS=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/approvals") ||
  fail "no se pudieron leer las aprobaciones del run"
APPROVER=$(jq -r --arg env "${ENVIRONMENT_NAME}" '
  [.[]? | select(.state == "approved" and .user.type == "User" and any(.environments[]?; .name == $env))]
  | last | .user.login // ""' <<< "${APPROVALS}")
[ -n "${APPROVER}" ] || fail "este run no tiene una aprobación humana del environment ${ENVIRONMENT_NAME}"

# Consumos registrados (todos los statuses del contexto, de cualquier autor, en cada ancla)
# Solo una enumeración completa y válida prueba "cero consumos": la salida de cada página
# debe ser un arreglo JSON (el vacío `[]` es válido) de statuses con id y contexto. Salida
# vacía, JSON malformado, otra estructura o una página fallida, en cualquier ancla → no hay
# prueba, bloquea.
consumptions() {
  local raw anchor found all='[]'
  for anchor in "${ANCHORS[@]}"; do
    raw=$(gh api --paginate "repos/${GITHUB_REPOSITORY}/commits/${anchor}/statuses?per_page=100") || return 1
    [ -n "${raw//[[:space:]]/}" ] || return 1
    found=$(jq -s -c -e --arg c "${CONTEXT}" --arg a "${anchor}" '
      if length >= 1 and all(.[]; type == "array"
           and all(.[]; type == "object" and (.id | type == "number") and (.context | type == "string")))
      then [.[][] | select(.context == $c) | . + {anchor: $a}]
      else error("lista de statuses inválida") end' <<< "${raw}" 2> /dev/null) || return 1
    all=$(jq -c --argjson f "${found}" '. + $f' <<< "${all}")
  done
  echo "${all}"
}
PRIOR=$(consumptions) || fail "no se pudo leer el registro de consumo de ${ID}: no se puede probar que no se usó"
[ "$(jq 'length' <<< "${PRIOR}")" -eq 0 ] ||
  fail "${ID} ya se consumió ($(jq -r '[.[] | "\(.creator.login // "?") \(.created_at // "?") \(.target_url // "") sobre \(.anchor[0:10])"] | join("; ")' <<< "${PRIOR}")): una autorización cubre una sola operación y su id no se reutiliza; hace falta una nueva con otro id"

# Consumir: desde aquí la autorización queda usada aunque algo falle después
CREATED=$(jq -n --arg c "${CONTEXT}" --arg u "${RUN_URL}" \
  --arg d "Consumida por el run ${GITHUB_RUN_ID}/${GITHUB_RUN_ATTEMPT}" \
  '{state: "success", context: $c, target_url: $u, description: $d}' |
  gh api -X POST "repos/${GITHUB_REPOSITORY}/statuses/${AUTH_COMMIT}" --input -) ||
  fail "no se pudo registrar el consumo de ${ID}: producción no muta"
STATUS_ID=$(jq -r --arg c "${CONTEXT}" --arg u "${RUN_URL}" \
  'if .context == $c and .target_url == $u and (.id | type == "number") then .id else "" end' <<< "${CREATED}")
[ -n "${STATUS_ID}" ] || fail "el registro de consumo de ${ID} no quedó como se pidió"
# Exactamente un consumo, el de este run: otro simultáneo hace ambiguo el uso
AFTER=$(consumptions) || fail "no se pudo releer el registro de consumo de ${ID}"
jq -e --argjson id "${STATUS_ID}" --arg a "${AUTH_COMMIT}" 'length == 1 and .[0].id == $id and .[0].anchor == $a' <<< "${AFTER}" > /dev/null ||
  fail "el registro de consumo de ${ID} es ambiguo ($(jq 'length' <<< "${AFTER}") consumos): producción no muta"

TITLE="[break-glass][${ID}] run ${GITHUB_RUN_ID}/${GITHUB_RUN_ATTEMPT}"
BODY=$(jq -r --arg run "${RUN_URL}" --arg sha "${GITHUB_SHA}" --arg actor "${GITHUB_ACTOR}" \
  --arg trig "${GITHUB_TRIGGERING_ACTOR}" --arg envapp "${APPROVER}" --arg env "${ENVIRONMENT_NAME}" \
  --arg ctx "${CONTEXT}" '
  "## Break-glass de escaneo de imágenes: SIN escaneo fresco",
  "",
  "Operación excepcional por fallo operativo del escáner, registry o DB de vulnerabilidades.",
  "Las imágenes `break_glass` se autorizan con evidencia histórica verificada: **no hubo un escaneo fresco**",
  "y este run no da la garantía escaneado = autorizado = desplegado. No desactiva la política.",
  "",
  "| campo | valor |",
  "|---|---|",
  "| run | \($run) |",
  "| commit | `\($sha)` |",
  "| actor / quien disparó | \($actor) / \($trig) |",
  "| aprobación del environment `\($env)` | \($envapp) |",
  "| autorización | `\(.break_glass.id)` — \(.break_glass.approver), PR #\(.break_glass.pr) (commit aprobado `\(.break_glass.approved_commit)`), vigente \(.break_glass.effective) → \(.break_glass.expires), una sola operación |",
  "| vía de autorización | `\(.break_glass.authorization_method)`\(if .break_glass.authorization_method == "self_merge" then " (el aprobador abrió y fusionó un PR solo de registros)" else " (revisión APPROVED de otro aprobador antes del merge)" end) |",
  "| operación autorizada | `\(.break_glass.operation | tojson)` (sin Terraform Apply) |",
  "| consumo (inmutable) | commit status `\($ctx)` sobre `\(.break_glass.authorization_commit)`; sin consumo previo en \(.break_glass.consumption_anchors | length) commit(s) de main que tocaron el registro |",
  "| justificación de la operación | \(.break_glass.operation_justification | gsub("[\r\n|]"; " ")) |",
  "| justificación de la autorización | \(.break_glass.authorization_justification | gsub("[\r\n|]"; " ")) |",
  "",
  "### Imágenes",
  "",
  "| imagen | veredicto | evidencia | CRITICAL conocidos | HIGH conocidos |",
  "|---|---|---|---|---|",
  (.surfaces[] | "| \(.surface) `\(.image_ref)` | \(.verdict) (\(.evaluation // "—")) | \(if .historical_evidence then "histórica: run \(.historical_evidence.run_id), artifact \(.historical_evidence.artifact_id) `\(.historical_evidence.artifact_digest)`, escaneo \(.scan.scanned_at) (hace \(.scan.age_hours) h), DB \(.scan.db_updated_at)" elif .scan then "fresca: \(.scan.scanned_at)" else "—" end) | \(if .scan then ([.scan.critical[] | "\(.id) (disp. \(.disposition.id))"] | join(", ") | if . == "" then "ninguno" else . end) else "—" end) | \(if .scan then (.scan.high | join(", ") | if . == "" then "ninguno" else . end) else "—" end) |"),
  "",
  "### Limitaciones de cobertura declaradas",
  "",
  (.break_glass.images[] | "- \(.surface): \(.coverage_limitations | gsub("[\r\n]"; " "))")
' "${POLICY_RESULT}")

ISSUE=$(jq -n --arg t "${TITLE}" --arg b "${BODY}" --arg l "${LABEL}" '{title: $t, body: $b, labels: [$l]}' |
  gh api -X POST "repos/${GITHUB_REPOSITORY}/issues" --input -) ||
  fail "no se pudo crear el issue de auditoría: producción no muta sin registro"
jq -e --arg l "${LABEL}" '(.number | type == "number") and any(.labels[]; .name == $l)' <<< "${ISSUE}" > /dev/null ||
  fail "el issue de auditoría no quedó con la etiqueta ${LABEL}"

echo "consumption_status=${STATUS_ID}"
echo "issue_number=$(jq -r '.number' <<< "${ISSUE}")"
echo "issue_url=$(jq -r '.html_url' <<< "${ISSUE}")"
