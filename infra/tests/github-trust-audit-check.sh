#!/usr/bin/env bash
# Auditoría de confianza de GitHub (audit-github-trust.sh): ruleset `Protect main` del
# modelo de un solo mantenedor, environments sensibles y entradas condicionales de SES.
#
# Sin red ni GitHub: `gh` es falso (sirve respuestas registradas por ruta, solo GET) y el
# árbol de workflows es un repositorio git desechable. Los fixtures son sintéticos y parten
# de la configuración aprobada; cada caso altera un control y comprueba el resultado.
#
# Uso: infra/tests/github-trust-audit-check.sh
# Requiere: jq, yq (mikefarah v4), git
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AUDIT="${ROOT}/.github/scripts/audit-github-trust.sh"

for bin in jq yq git; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "${TMP:?}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }

REPO=o/r

# ── gh falso ────────────────────────────────────────────────────────────────
mkdir -p "${TMP}/bin" "${TMP}/gh"
export FAKE_GH_DIR="${TMP}/gh"
export FAKE_GH_LOG="${TMP}/gh.log"
cat > "${TMP}/bin/gh" << 'EOF'
#!/usr/bin/env bash
# Solo `gh api -X GET [-i] [--paginate --slurp] <ruta>`; responde con el archivo de la ruta.
[ "${1:-}" = api ] || { echo "gh falso: $*" >&2; exit 99; }
shift
path="" head=0 slurp=0 query=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) [ "$2" = GET ] || { echo "gh falso: método $2" >&2; exit 99; }; shift 2 ;;
    -i) head=1; shift ;;
    --paginate) shift ;;
    --slurp) slurp=1; shift ;;
    -*) echo "gh falso: opción $1" >&2; exit 99 ;;
    *) path="${1%%\?*}"; [[ "$1" == *\?* ]] && query="${1#*\?}"; shift ;;
  esac
done
f="${FAKE_GH_DIR}/${path//\//__}"
[ $slurp = 0 ] || echo "${path} ${query}" >> "${FAKE_GH_LOG}"
# <ruta>.status: la API responde ese error. <ruta>.fail: --paginate falla en una página posterior
# (la primera sigue respondiendo). <ruta>.raw: cuerpo literal, sin pasar por jq.
if [ -f "$f.status" ]; then [ $head = 1 ] && echo "HTTP/2.0 $(cat "$f.status")"; exit 1; fi
if [ $slurp = 1 ] && [ -f "$f.fail" ]; then exit 1; fi
if [ $slurp = 1 ] && [ -f "$f.pages" ]; then cat "$f.pages"; exit 0; fi
if [ $slurp = 1 ] && [ -f "$f.raw" ]; then cat "$f.raw"; exit 0; fi
if [ ! -f "$f" ]; then [ $head = 1 ] && echo "HTTP/2.0 404 Not Found"; exit 1; fi
[ $head = 1 ] && { echo "HTTP/2.0 200 OK"; echo; }
if [ $slurp = 1 ]; then jq -c '[.]' "$f"; else cat "$f"; fi
EOF
chmod +x "${TMP}/bin/gh"
serve() { printf '%s' "$2" > "${FAKE_GH_DIR}/${1//\//__}"; }
unserve() { rm -f "${FAKE_GH_DIR:?}/${1//\//__}"; }

# ── Árbol de workflows (git real) ───────────────────────────────────────────
G="${TMP}/repo"
git init -q -b main "${G}"
git -C "${G}" config user.email t@example.invalid
git -C "${G}" config user.name t
git -C "${G}" config commit.gpgsign false
mkdir -p "${G}/.github/workflows"
cat > "${G}/.github/workflows/terraform-hetzner-plan.yml" << 'EOF'
on: pull_request
permissions: {}
jobs:
  plan:
    runs-on: ubuntu-latest
    environment: terraform-plan
    steps:
      - run: terraform plan
        env:
          TF_TOKEN_app_terraform_io: ${{ secrets.TF_API_TOKEN }}
EOF
cat > "${G}/.github/workflows/production-hetzner.yml" << 'EOF'
on: workflow_dispatch
permissions: {}
jobs:
  production:
    runs-on: ubuntu-latest
    environment: production-hetzner
    steps:
      - run: ansible-playbook converge.yml
        env:
          SES_SMTP_USERNAME: ${{ vars.NOTIFICATIONS_SES_ENABLED == 'true' && secrets.SES_SMTP_USERNAME || '' }}
          SES_SMTP_PASSWORD: ${{ vars.NOTIFICATIONS_SES_ENABLED == 'true' && secrets.SES_SMTP_PASSWORD || '' }}
EOF
git -C "${G}" add -A && git -C "${G}" commit -qm init

# ── Configuración aprobada (estado de entrada) ──────────────────────────────
env_json() { # <nombre> <revisores JSON> <deployment_branch_policy JSON>
  jq -cn --arg n "$1" --argjson r "$2" --argjson d "$3" '{name: $n, can_admins_bypass: true,
    deployment_branch_policy: $d,
    protection_rules: [{type: "required_reviewers", prevent_self_review: false, reviewers: $r}]}'
}
ME='[{"type":"User","reviewer":{"login":"JM-Ortega"}}]'
MAIN_ONLY='{"protected_branches":false,"custom_branch_policies":true}'
RULESET='{"id":1,"name":"Protect main","target":"branch","enforcement":"active",
  "conditions":{"ref_name":{"include":["refs/heads/main"],"exclude":[]}},
  "bypass_actors":[],
  "rules":[{"type":"deletion"},{"type":"non_fast_forward"},
    {"type":"pull_request","parameters":{"required_approving_review_count":0,
      "dismiss_stale_reviews_on_push":false,"required_reviewers":[],"require_code_owner_review":false,
      "require_last_push_approval":false,"required_review_thread_resolution":true,
      "require_extra_approval_for_unattributed_changes":true,"allowed_merge_methods":["merge"]}}]}'

baseline() {
  rm -f "${FAKE_GH_DIR}"/*
  : > "${FAKE_GH_LOG}"
  serve "repos/${REPO}" '{"permissions":{"admin":true}}'
  serve "repos/${REPO}/actions/secrets" '{"total_count":0,"secrets":[]}'
  serve "repos/${REPO}/environments" "$(jq -cn --argjson p "$(env_json production-hetzner "$ME" "$MAIN_ONLY")" \
    --argjson t "$(env_json terraform-plan "$ME" null)" '{total_count: 2, environments: [$p, $t]}')"
  serve "repos/${REPO}/environments/production-hetzner/secrets" '{"total_count":0,"secrets":[]}'
  serve "repos/${REPO}/environments/terraform-plan/secrets" '{"total_count":1,"secrets":[{"name":"TF_API_TOKEN"}]}'
  serve "repos/${REPO}/environments/production-hetzner/deployment-branch-policies" \
    '{"total_count":1,"branch_policies":[{"name":"main","type":"branch"}]}'
  serve "repos/${REPO}/actions/permissions" '{"sha_pinning_required":true}'
  serve "repos/${REPO}/actions/permissions/workflow" '{"default_workflow_permissions":"read","can_approve_pull_request_reviews":false}'
  serve "repos/${REPO}/rulesets" '[{"id":1,"name":"Protect main"}]'
  serve "repos/${REPO}/rulesets/1" "${RULESET}"
  serve "repos/${REPO}/actions/variables" '{"total_count":1,"variables":[{"name":"PRODUCTION_PUSH_DEPLOY","value":"deferred"}]}'
  serve "repos/${REPO}/actions/organization-variables" '{"total_count":0,"variables":[]}'
  serve "repos/${REPO}/environments/production-hetzner/variables" '{"total_count":0,"variables":[]}'
  serve "repos/${REPO}/environments/terraform-plan/variables" '{"total_count":0,"variables":[]}'
}
variables() { # <ruta bajo repos/o/r> <nombre=valor>…
  local path="repos/${REPO}/$1" kv; shift
  local list='[]'
  for kv in "$@"; do list="$(jq -c --arg n "${kv%%=*}" --arg v "${kv#*=}" '. + [{name: $n, value: $v}]' <<<"${list}")"; done
  serve "${path}" "$(jq -c '{total_count: length, variables: .}' <<<"${list}")"
}
secrets_of() { # <environment> <nombre>…
  local path="repos/${REPO}/environments/$1/secrets"; shift
  serve "${path}" "$(jq -cn '$ARGS.positional | {total_count: length, secrets: map({name: .})}' --args "$@")"
}
ruleset() { serve "repos/${REPO}/rulesets/1" "$(jq -c "$1" <<<"${RULESET}")"; }
environments() { # <filtro jq sobre la lista de environments>
  local f="${FAKE_GH_DIR}/repos__o__r__environments"
  jq -c "$1 | .total_count = (.environments | length)" "$f" > "$f.new" && mv "$f.new" "$f"
}

# audit [opciones…]: deja la salida en OUT y el código en RC.
audit() {
  RC=0
  OUT="$(cd "${G}" && env -i PATH="${TMP}/bin:${PATH}" HOME="${TMP}" FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" "${AUDIT}" --repo "${REPO}" --ref HEAD "$@" 2>&1)" || RC=$?
}
# expect <descripción> <código esperado> <ESTADO> <control>…: cada control con ese estado.
expect() {
  local desc="$1" rc="$2" st="$3" c good=1; shift 3
  [ "${RC}" = "${rc}" ] || good=0
  for c in "$@"; do grep -qE "^${st} +${c}" <<<"${OUT}" || good=0; done
  if [ $good = 1 ]; then ok "${desc}"; else ko "${desc} (código ${RC})"; printf '%s\n' "${OUT}" | sed 's/^/      | /'; fi
}
absent() { # absent <descripción> <regex>: ninguna línea coincide
  if grep -qE "$2" <<<"${OUT}"; then ko "$1"; grep -E "$2" <<<"${OUT}" | sed 's/^/      | /'; else ok "$1"; fi
}

# ── 1. Configuración aprobada ───────────────────────────────────────────────
baseline; audit
expect "aprobada: sin FAIL ni UNKNOWN (salida 0)" 0 PASS \
  "Protect main: activo sobre main" "Protect main: regla pull_request" "Protect main: regla deletion" \
  "Protect main: regla non_fast_forward" "Protect main: aprobaciones requeridas" \
  "Protect main: resolución de hilos de revisión" "Protect main: sin actores de bypass" \
  "production-hetzner: revisores requeridos" "terraform-plan: revisores requeridos" \
  "production-hetzner: restricción de ref"
absent "aprobada: ningún FAIL" '^FAIL '
expect "aprobada: checks requeridos ausentes son PENDING, no PASS" 0 PENDING "Protect main: checks de estado requeridos"
absent "aprobada: el contexto heredado no se da por verificado sin checks" '^PASS +Protect main: sin contexto heredado'
grep -q 'PENDING=1$' <<<"${OUT}" && ok "aprobada: resumen cuenta el pendiente" || ko "aprobada: resumen cuenta el pendiente"

# ── 2. Aprobaciones humanas: desviación del modelo de un mantenedor ─────────
for n in 1 2; do
  baseline; ruleset "(.rules[]|select(.type==\"pull_request\")|.parameters.required_approving_review_count) = $n"; audit
  expect "aprobaciones=$n: FAIL" 1 FAIL "Protect main: aprobaciones requeridas"
done
baseline; ruleset '(.rules[]|select(.type=="pull_request")|.parameters) |= del(.required_approving_review_count)'; audit
expect "aprobaciones ilegibles: UNKNOWN, no PASS" 2 UNKNOWN "Protect main: aprobaciones requeridas"
for v in null '"0"' '"abc"' -1 1.5; do
  baseline; ruleset "(.rules[]|select(.type==\"pull_request\")|.parameters.required_approving_review_count) = $v"; audit
  expect "aprobaciones=$v: UNKNOWN, no PASS" 2 UNKNOWN "Protect main: aprobaciones requeridas"
done

# ── 3. PR obligatorio y aplicación del ruleset ──────────────────────────────
baseline; ruleset 'del(.rules[]|select(.type=="pull_request"))'; audit
expect "sin regla pull_request: FAIL" 1 FAIL "Protect main: regla pull_request"
absent "sin regla pull_request: aprobaciones no pasan por omisión" '^PASS +Protect main: aprobaciones'
for e in disabled evaluate; do
  baseline; ruleset ".enforcement = \"$e\""; audit
  expect "enforcement=$e: FAIL" 1 FAIL "Protect main: activo sobre main"
done
baseline; ruleset '.conditions.ref_name.include = ["refs/heads/develop"]'; audit
expect "ruleset fuera de main: FAIL" 1 FAIL "Protect main: activo sobre main"
baseline; serve "repos/${REPO}/rulesets" '[]'; audit
expect "sin ruleset: FAIL" 1 FAIL "ruleset 'Protect main'"
baseline; ruleset '(.rules[]|select(.type=="pull_request")|.parameters.required_review_thread_resolution) = false'; audit
expect "hilos de revisión sin resolver permitidos: FAIL" 1 FAIL "Protect main: resolución de hilos de revisión"

# ── 4. Integridad de la rama ────────────────────────────────────────────────
for r in deletion non_fast_forward; do
  baseline; ruleset "del(.rules[]|select(.type==\"$r\"))"; audit
  expect "sin regla $r: FAIL" 1 FAIL "Protect main: regla $r"
done

# ── 5. Bypass ───────────────────────────────────────────────────────────────
baseline; ruleset '.bypass_actors = [{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"}]'; audit
expect "bypass de rol admin: FAIL" 1 FAIL "Protect main: sin actores de bypass"
baseline; ruleset '.bypass_actors = [{"actor_id":9,"actor_type":"Integration","bypass_mode":"pull_request"}]'; audit
expect "bypass de app en PR: FAIL" 1 FAIL "Protect main: sin actores de bypass"
baseline; ruleset 'del(.bypass_actors)'; audit
expect "bypass no expuesto: UNKNOWN, no PASS" 2 UNKNOWN "Protect main: sin actores de bypass"
for v in null '{}' '""' '"x"' 0; do
  baseline; ruleset ".bypass_actors = $v"; audit
  expect "bypass_actors=$v: UNKNOWN, no PASS" 2 UNKNOWN "Protect main: sin actores de bypass"
  absent "bypass_actors=$v: sin error de jq" '^jq: '
done
baseline; ruleset '.bypass_actors = ["x"]'; audit
expect "bypass_actors con elemento no objeto: FAIL" 1 FAIL "Protect main: sin actores de bypass"
absent "bypass_actors con elemento no objeto: sin error de jq" '^jq: '

# ── 6. Checks requeridos (activación futura) ────────────────────────────────
baseline; ruleset '.rules += [{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"synthetic-check"}]}}]'; audit
expect "checks configurados: sin PENDING y contexto heredado verificado" 0 PASS "Protect main: sin contexto heredado en rojo"
absent "checks configurados: no quedan PENDING" '^PENDING '
baseline; ruleset '.rules += [{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"Build and Verify"}]}}]'; audit
expect "contexto heredado requerido: FAIL" 1 FAIL "Protect main: sin contexto heredado en rojo"

# ── 7. Environments sensibles ───────────────────────────────────────────────
baseline; environments '(.environments[]|select(.name=="production-hetzner")|.protection_rules[0].reviewers) += [{"type":"User","reviewer":{"login":"intruso"}}]'; audit
expect "revisor adicional en producción: FAIL" 1 FAIL "production-hetzner: revisores requeridos"
baseline; environments '(.environments[]|select(.name=="terraform-plan")|.protection_rules) = []'; audit
expect "terraform-plan sin revisores: FAIL" 1 FAIL "terraform-plan: revisores requeridos"
baseline; environments '(.environments[]|select(.name=="production-hetzner")|.deployment_branch_policy) = null'; audit
expect "producción desde cualquier ref: FAIL" 1 FAIL "production-hetzner: restricción de ref"
baseline; serve "repos/${REPO}/environments/production-hetzner/deployment-branch-policies" \
  '{"total_count":2,"branch_policies":[{"name":"main","type":"branch"},{"name":"*","type":"branch"}]}'; audit
expect "producción con ref adicional: FAIL" 1 FAIL "production-hetzner: restricción de ref"
baseline; environments 'del(.environments[]|select(.name=="production-hetzner"))'; audit
expect "sin environment de producción: FAIL" 1 FAIL "production-hetzner: existe"

# Revisor transicional: el valor por defecto sigue siendo JM-Ortega y --reviewers lo sustituye.
baseline; environments '(.environments[].protection_rules[0].reviewers) = [{"type":"Team","reviewer":{"slug":"ti"}}]'
audit --reviewers Team:ti
expect "--reviewers con revisor institucional: PASS" 0 PASS "production-hetzner: revisores requeridos" "terraform-plan: revisores requeridos"
audit
expect "revisor por defecto ante otro revisor: FAIL" 1 FAIL "production-hetzner: revisores requeridos"

# ── 8. Entradas condicionales de SES ────────────────────────────────────────
PV=environments/production-hetzner/variables
HOST=email-smtp.us-east-1.amazonaws.com
baseline; audit
expect "SES ausente, sin secretos: deshabilitado, sin FAIL" 0 PASS "production-hetzner: decisión de correo SES" \
  "production-hetzner: entradas de SES" "SES: variables solo en production-hetzner"
expect "SES ausente: los secretos faltantes se informan, no fallan" 0 INFO "consumidores: secretos de SES ausentes"
absent "SES ausente: no cuenta como consumidor sin fuente" '^FAIL +consumidores: env autorizado sin ninguna fuente'
grep -q 'no el .env del host, el backend ni la entrega' <<<"${OUT}" && ok "la salida separa confianza de runtime y entrega" ||
  ko "la salida separa confianza de runtime y entrega"
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=false; audit
expect "SES=false, sin secretos: PASS" 0 PASS "production-hetzner: entradas de SES"
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=false; secrets_of production-hetzner SES_SMTP_USERNAME SES_SMTP_PASSWORD; audit
expect "SES=false con secretos autorizados guardados: sin FAIL" 0 PASS "production-hetzner: sin secretos fuera de su alcance"
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}"; secrets_of production-hetzner SES_SMTP_USERNAME SES_SMTP_PASSWORD; audit
expect "SES=true completo: PASS" 0 PASS "production-hetzner: entradas de SES"
absent "SES=true completo: no afirma que los valores sean válidos sin decirlo" '^PASS +production-hetzner: entradas de SES +presentes$'
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}"; audit
expect "SES=true sin secretos: FAIL de entradas" 1 FAIL "production-hetzner: entradas de SES"
expect "SES=true sin secretos: FAIL de consumidores sin fuente" 1 FAIL "consumidores: env autorizado sin ninguna fuente"
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}"; secrets_of production-hetzner SES_SMTP_USERNAME; audit
expect "SES=true sin contraseña: FAIL" 1 FAIL "production-hetzner: entradas de SES"
grep -q 'secreto SES_SMTP_PASSWORD' <<<"${OUT}" && ok "SES=true sin contraseña: la nombra" || ko "SES=true sin contraseña: la nombra"
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=true; secrets_of production-hetzner SES_SMTP_USERNAME SES_SMTP_PASSWORD; audit
expect "SES=true sin endpoint: FAIL" 1 FAIL "production-hetzner: entradas de SES"
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=true SES_SMTP_HOST=smtp.example.org; secrets_of production-hetzner SES_SMTP_USERNAME SES_SMTP_PASSWORD; audit
expect "SES=true con otro servidor: FAIL" 1 FAIL "production-hetzner: entradas de SES"
for v in TRUE yes 1 ' true'; do
  baseline; variables "${PV}" "NOTIFICATIONS_SES_ENABLED=$v"; audit
  expect "SES='$v': decisión inválida, FAIL" 1 FAIL "production-hetzner: decisión de correo SES"
done
baseline; unserve "repos/${REPO}/${PV}"; audit
expect "variables del environment ilegibles: UNKNOWN, no PASS" 2 UNKNOWN "production-hetzner: decisión de correo SES"
expect "decisión ilegible: los secretos faltantes no se dan por aceptados" 2 UNKNOWN "consumidores: fuente no determinable"
absent "decisión ilegible: sin PASS de entradas" '^PASS +production-hetzner: entradas de SES'
baseline; variables "${PV}" NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}"; unserve "repos/${REPO}/environments/production-hetzner/secrets"; audit
expect "SES=true y secretos ilegibles: UNKNOWN, no PASS" 2 UNKNOWN "production-hetzner: entradas de SES"

for v in NOTIFICATIONS_SES_ENABLED=false NOTIFICATIONS_SES_ENABLED=true "SES_SMTP_HOST=${HOST}"; do
  baseline; variables actions/variables "$v"; audit
  expect "copia de repositorio ($v): FAIL" 1 FAIL "SES: variables solo en production-hetzner"
done
baseline; variables actions/organization-variables NOTIFICATIONS_SES_ENABLED=false; audit
expect "copia de organización: FAIL" 1 FAIL "SES: variables solo en production-hetzner"
baseline; variables environments/terraform-plan/variables "SES_SMTP_HOST=${HOST}"; audit
expect "copia en otro environment: FAIL" 1 FAIL "SES: variables solo en production-hetzner"
baseline; unserve "repos/${REPO}/actions/variables"; audit
expect "variables de repositorio ilegibles: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
baseline; unserve "repos/${REPO}/actions/organization-variables"; audit
expect "variables de organización ilegibles: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
baseline; unserve "repos/${REPO}/actions/organization-variables"; serve "repos/${REPO}" '{"permissions":{"admin":true},"owner":{"type":"User"}}'; audit
expect "dueño usuario sin variables de organización: PASS" 0 PASS "SES: variables solo en production-hetzner"

baseline; serve "repos/${REPO}/actions/secrets" '{"total_count":1,"secrets":[{"name":"SES_SMTP_PASSWORD"}]}'; audit
expect "secreto SES en el repositorio: FAIL" 1 FAIL "repositorio: 0 secretos de Actions"
baseline; secrets_of terraform-plan TF_API_TOKEN SES_SMTP_USERNAME; audit
expect "secreto SES en otro environment: FAIL" 1 FAIL "terraform-plan: sin secretos fuera de su alcance"
baseline; secrets_of production-hetzner PIEDRAZUL_BACKEND_MAIL_SEND; audit
expect "secreto retirado de SendGrid guardado: sin clasificar, FAIL" 1 FAIL "production-hetzner: sin secretos sin clasificar"

# ── 9. Evidencia de SES ilegible: nunca una ausencia ────────────────────────
PV_FILE="${FAKE_GH_DIR}/repos__${REPO//\//__}__environments__production-hetzner__variables"
SCOPES=(actions/variables actions/organization-variables environments/terraform-plan/variables)
malformed_case() { # <descripción> <cuerpo JSON de una página con respuesta 200>
  baseline; serve "repos/${REPO}/${PV}" "$2"; audit
  expect "decisión con respuesta $1: UNKNOWN, no PASS" 2 UNKNOWN "production-hetzner: decisión de correo SES"
  absent "decisión con respuesta $1: no se da por deshabilitado" '^PASS +production-hetzner: (decisión de correo SES|entradas de SES)'
  absent "decisión con respuesta $1: sin error de jq" '^jq: '
  local scope
  for scope in "${SCOPES[@]}"; do
    baseline; serve "repos/${REPO}/${scope}" "$2"; audit
    expect "${scope} con respuesta $1: UNKNOWN, no PASS" 2 UNKNOWN "SES: variables solo en production-hetzner"
  done
}
malformed_case "sin variables" '{"total_count":0}'
malformed_case "objeto vacío" '{}'
malformed_case "nula" 'null'
malformed_case "arreglo" '[]'
malformed_case "texto" '"x"'
malformed_case "variables no arreglo" '{"total_count":0,"variables":{}}'
malformed_case "sin total_count" '{"variables":[]}'
malformed_case "total_count texto" '{"total_count":"0","variables":[]}'
malformed_case "lista truncada" '{"total_count":1,"variables":[]}'
malformed_case "elemento no objeto" '{"total_count":1,"variables":["x"]}'
malformed_case "elemento sin nombre" '{"total_count":1,"variables":[{"value":"true"}]}'
malformed_case "nombre nulo" '{"total_count":1,"variables":[{"name":null,"value":"true"}]}'
malformed_case "elemento sin valor" '{"total_count":1,"variables":[{"name":"NOTIFICATIONS_SES_ENABLED"}]}'
malformed_case "nombres repetidos" '{"total_count":2,"variables":[{"name":"A","value":"1"},{"name":"a","value":"2"}]}'
baseline; printf '<html>error</html>' > "${PV_FILE}.raw"; audit
expect "decisión con respuesta no JSON: UNKNOWN" 2 UNKNOWN "production-hetzner: decisión de correo SES"
absent "respuesta no JSON: sin decisión afirmada" '^PASS +production-hetzner: decisión de correo SES'
for s in 403\ Forbidden 404\ Not\ Found 500\ Error; do
  baseline; printf '%s' "$s" > "${PV_FILE}.status"; audit
  expect "variables del environment con HTTP ${s%% *}: UNKNOWN" 2 UNKNOWN "production-hetzner: decisión de correo SES"
  absent "variables con HTTP ${s%% *}: sin ausencia afirmada" '^PASS +production-hetzner: entradas de SES'
done
baseline; serve "repos/${REPO}/${PV}" '{}'; audit
expect "decisión ilegible con consumidor: secretos no aceptados como ausentes" 2 UNKNOWN "consumidores: fuente no determinable"
absent "decisión ilegible con consumidor: sin ausencia aceptada" 'secretos de SES ausentes'
for scope in repos/${REPO}/actions/secrets repos/${REPO}/environments/production-hetzner/secrets repos/${REPO}/environments; do
  baseline; serve "$scope" '{}'; audit
  [ "${RC}" = 2 ] && ok "lista sin su arreglo (${scope##*/}): UNKNOWN, no lista vacía" || { ko "lista sin su arreglo (${scope##*/}) (código ${RC})"; printf '%s\n' "${OUT}" | grep -E '^(FAIL|UNKNOWN)' | sed 's/^/      | /'; }
done
baseline; serve "repos/${REPO}/actions/secrets" '{}'; audit
absent "secretos de repositorio ilegibles: no pasan por vacíos" '^PASS +repositorio: 0 secretos'

# ── 10. Copias por nombre, cualquiera que sea su valor ──────────────────────
for scope in "${SCOPES[@]}"; do
  for v in 'NOTIFICATIONS_SES_ENABLED=' NOTIFICATIONS_SES_ENABLED=false NOTIFICATIONS_SES_ENABLED=true 'SES_SMTP_HOST=' "SES_SMTP_HOST=${HOST}" 'notifications_ses_enabled=' 'ses_smtp_host=x'; do
    baseline; variables "${scope}" "$v"; audit
    expect "copia en ${scope} ('$v'): FAIL" 1 FAIL "SES: variables solo en production-hetzner"
  done
done
baseline; variables "${PV}" 'NOTIFICATIONS_SES_ENABLED='; audit
expect "decisión vacía en el environment: inválida, FAIL" 1 FAIL "production-hetzner: decisión de correo SES"
baseline; variables "${PV}" notifications_ses_enabled=true "SES_SMTP_HOST=${HOST}"; secrets_of production-hetzner SES_SMTP_USERNAME SES_SMTP_PASSWORD; audit
expect "nombre en minúsculas en el environment: se reconoce" 0 PASS "production-hetzner: entradas de SES"

# ── 11. Paginación ──────────────────────────────────────────────────────────
var_pages() { # <ruta> <total> <n.º de la primera página> <nombre extra de la segunda>
  local path="repos/${REPO}/$1" total="$2" first="$3" extra="$4"
  jq -cn --argjson t "${total}" --argjson n "${first}" --arg x "${extra}" \
    '[{total_count: $t, variables: [range($n) | {name: "VAR_\(.)", value: "v"}]},
      {total_count: $t, variables: [{name: $x, value: "false"}]}]' > "${FAKE_GH_DIR}/${path//\//__}.pages"
}
baseline; : > "${FAKE_GH_LOG}"; audit
for scope in actions/variables actions/organization-variables "${PV}" environments/terraform-plan/variables; do
  grep -qx "repos/${REPO}/${scope} per_page=30" "${FAKE_GH_LOG}" && ok "paginación: ${scope} pide el máximo documentado (30)" || ko "paginación: ${scope} pide el máximo documentado (30)"
done
grep -q 'secrets per_page=100' "${FAKE_GH_LOG}" && ok "paginación: las listas de secretos conservan su tamaño" || ko "paginación: las listas de secretos conservan su tamaño"
baseline; var_pages actions/variables 31 30 NOTIFICATIONS_SES_ENABLED; audit
expect "copia en la segunda página: FAIL" 1 FAIL "SES: variables solo en production-hetzner"
baseline; var_pages "${PV}" 31 30 NOTIFICATIONS_SES_ENABLED; audit
expect "decisión en la segunda página: se lee" 0 PASS "production-hetzner: decisión de correo SES"
grep -qE '^PASS +production-hetzner: decisión de correo SES +deshabilitado$' <<<"${OUT}" && ok "decisión en la segunda página: no se informa ausente" || ko "decisión en la segunda página: no se informa ausente"
baseline; var_pages actions/variables 32 30 VAR_EXTRA; audit
expect "total mayor que lo leído: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
baseline; var_pages "${PV}" 32 30 VAR_EXTRA; audit
expect "total mayor que lo leído en el environment: UNKNOWN" 2 UNKNOWN "production-hetzner: decisión de correo SES"
baseline; printf '[{"total_count":2,"variables":[{"name":"A","value":"1"}]},{"total_count":3,"variables":[{"name":"B","value":"2"}]}]' > "${FAKE_GH_DIR}/repos__o__r__actions__variables.pages"; audit
expect "total_count que cambia entre páginas: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
baseline; printf '[]' > "${FAKE_GH_DIR}/repos__o__r__actions__variables.pages"; audit
expect "paginación sin ninguna página: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
baseline; : > "${PV_FILE}.fail"; audit
expect "fallo en una página posterior: UNKNOWN" 2 UNKNOWN "production-hetzner: decisión de correo SES"
absent "fallo en una página posterior: sin decisión afirmada" '^PASS +production-hetzner: decisión de correo SES'
baseline; : > "${FAKE_GH_DIR}/repos__o__r__actions__variables.fail"; audit
expect "fallo en una página posterior del repositorio: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
absent "fallo en una página posterior: no hay copias negadas" '^PASS +SES: variables solo en'

# Organización: solo un dueño usuario queda exento de la lectura
baseline; serve "repos/${REPO}" '{"permissions":{"admin":true},"owner":{"type":"Organization"}}'; serve "repos/${REPO}/actions/organization-variables" '{}'; audit
expect "organización con respuesta inesperada: UNKNOWN" 2 UNKNOWN "SES: variables solo en production-hetzner"
baseline; serve "repos/${REPO}" '{"permissions":{"admin":true},"owner":{"type":"User"}}'; : > "${FAKE_GH_LOG}"; audit
grep -q 'organization-variables' "${FAKE_GH_LOG}" && ko "dueño usuario: no consulta variables de organización" || ok "dueño usuario: no consulta variables de organización"

echo
[ "${FAILS}" -eq 0 ] && { echo "OK"; exit 0; }
echo "${FAILS} fallos"; exit 1
