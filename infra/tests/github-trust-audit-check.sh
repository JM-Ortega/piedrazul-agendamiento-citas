#!/usr/bin/env bash
# Auditoría de confianza de GitHub (audit-github-trust.sh): ruleset `Protect main` del
# modelo de un solo mantenedor y environments sensibles.
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
cat > "${TMP}/bin/gh" << 'EOF'
#!/usr/bin/env bash
# Solo `gh api -X GET [-i] [--paginate --slurp] <ruta>`; responde con el archivo de la ruta.
[ "${1:-}" = api ] || { echo "gh falso: $*" >&2; exit 99; }
shift
path="" head=0 slurp=0
while [ $# -gt 0 ]; do
  case "$1" in
    -X) [ "$2" = GET ] || { echo "gh falso: método $2" >&2; exit 99; }; shift 2 ;;
    -i) head=1; shift ;;
    --paginate) shift ;;
    --slurp) slurp=1; shift ;;
    -*) echo "gh falso: opción $1" >&2; exit 99 ;;
    *) path="${1%%\?*}"; shift ;;
  esac
done
f="${FAKE_GH_DIR}/${path//\//__}"
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
}
ruleset() { serve "repos/${REPO}/rulesets/1" "$(jq -c "$1" <<<"${RULESET}")"; }
environments() { # <filtro jq sobre la lista de environments>
  local f="${FAKE_GH_DIR}/repos__o__r__environments"
  jq -c "$1 | .total_count = (.environments | length)" "$f" > "$f.new" && mv "$f.new" "$f"
}

# audit [opciones…]: deja la salida en OUT y el código en RC.
audit() {
  RC=0
  OUT="$(cd "${G}" && env -i PATH="${TMP}/bin:${PATH}" HOME="${TMP}" FAKE_GH_DIR="${FAKE_GH_DIR}" "${AUDIT}" --repo "${REPO}" --ref HEAD "$@" 2>&1)" || RC=$?
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

echo
[ "${FAILS}" -eq 0 ] && { echo "OK"; exit 0; }
echo "${FAILS} fallos"; exit 1
