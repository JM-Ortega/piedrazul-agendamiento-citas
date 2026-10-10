#!/usr/bin/env bash
# Auditoría local y de solo lectura de la frontera de confianza externa de GitHub.
#
# Uso: audit-github-trust.sh [--repo OWNER/REPO] [--ref REF] [--reviewers LISTA]
#   --repo       repositorio a auditar (por defecto: el del checkout actual)
#   --ref        árbol de workflows a analizar (por defecto: origin/main; hacer git fetch antes)
#   --reviewers  conjunto exacto de revisores autorizados, separado por comas; cada uno
#                como User:login o Team:slug (un login sin prefijo es User).
#                Por defecto: User:JM-Ortega
#
# Requiere: gh autenticado con el acceso habitual del mantenedor (admin para leer
# environments, secretos y rulesets), jq, yq (mikefarah v4) y git. No usa credenciales
# nuevas y CI no lo ejecuta.
#
# Solo hace GET contra la API y lee el árbol git local. Nunca lee valores de secretos:
# la API solo expone nombres y metadatos.
#
# Todo secreto debe tener un alcance decidido en AUTH. Un nombre sin clasificar (referenciado
# por un workflow o guardado en un environment) es FAIL: el hecho es verificable y contradice
# el contrato; no es UNKNOWN, que queda para lo que no se pudo leer o resolver.
#
# Resultado por control: PASS (verificado), FAIL (el control falta o contradice el
# contrato), UNKNOWN (no se pudo determinar: permisos, API o dato no resoluble) o INFO
# (solo informa). Un UNKNOWN nunca se reporta como PASS ni como FAIL.
# Salida: 0 todo PASS, 1 algún FAIL, 2 sin FAIL pero algún UNKNOWN.
set -uo pipefail

REPO=""
REF="origin/main"
REVIEWERS="User:JM-Ortega"
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --ref) REF="$2"; shift 2 ;;
    --reviewers) REVIEWERS="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "argumento desconocido: $1" >&2; exit 64 ;;
  esac
done

for bin in gh jq yq git; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done
[ -n "$REPO" ] || REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" ||
  { echo "no se pudo determinar el repositorio" >&2; exit 2; }

# ---------------------------------------------------------------- contrato aprobado
# Nombres, nunca valores. Reglas de alcance ya decididas: dónde PUEDE vivir cada secreto
# de producción conocido. El repositorio no es alcance autorizado para ninguno (objetivo:
# 0 copias de repo) y ningún otro environment lo es. No es un inventario obligatorio: que
# un secreto deba existir lo decide un consumidor real en el árbol analizado (--ref), no
# esta tabla. Un secreto nuevo entra aquí cuando se decida su alcance, no antes.
declare -A AUTH=(
  [TF_API_TOKEN]="production-hetzner terraform-plan"
  [ANSIBLE_SSH_KEY]="production-hetzner"
  [CLOUDFLARE_DNS_API_TOKEN]="production-hetzner"
  [KC_BOOTSTRAP_ADMIN_PASSWORD]="production-hetzner"
  [KC_ADMIN_PASSWORD]="production-hetzner"
  [KC_AUTOMATION_CLIENT_SECRET]="production-hetzner"
  [KC_BACKEND_CLIENT_SECRET]="production-hetzner"
  [KC_PLATFORM_ADMIN_INITIAL_PASSWORD]="production-hetzner"
  [IDENTITY_SEED_ADMIN_PASSWORD]="production-hetzner"
  [PIEDRAZUL_BACKEND_MAIL_SEND]="production-hetzner"
  [NOTIFICATION_ENCRYPTION_KEY]="production-hetzner"
  [POSTGRES_PASSWORD]="production-hetzner"
  [APP_DB_PASSWORD]="production-hetzner"
  [MIGRATION_DB_PASSWORD]="production-hetzner"
  [KC_DB_PASSWORD]="production-hetzner"
)
# Mecanismos de GitHub que se leen como secrets.* pero no son secretos del repositorio
# (GitHub no permite crear secretos con prefijo GITHUB_).
BUILTIN_SECRETS=(GITHUB_TOKEN)
PROD_ENV="production-hetzner"
PLAN_ENV="terraform-plan"
SENSITIVE_ENVS=("$PROD_ENV" "$PLAN_ENV")
PROD_REF="main"
PLAN_WORKFLOW=".github/workflows/terraform-hetzner-plan.yml"
# OCI es histórico/inactivo: sus workflows no se mantienen operativos. Al retirar las
# copias de repo dejan de resolver secretos y pasan a referencias inertes.
HISTORICAL_GLOB=".github/workflows/terraform-oci-*.yml"
# Contexto heredado en rojo: no puede ser condición requerida mientras siga así.
FORBIDDEN_REQUIRED_CONTEXTS=("Build and Verify")

PASS=0 FAIL=0 UNKNOWN=0
report() { # report ESTADO control [detalle]
  printf '%-7s %-58s %s\n' "$1" "$2" "${3:-}"
  case "$1" in PASS) PASS=$((PASS+1)) ;; FAIL) FAIL=$((FAIL+1)) ;; UNKNOWN) UNKNOWN=$((UNKNOWN+1)) ;; esac
}
detail() { local l; for l in "$@"; do printf '          - %s\n' "$l"; done; }
# api RUTA → cuerpo JSON en $API_BODY y estado HTTP en $API_STATUS (200, 404, 403, err).
# Siempre GET, con un reintento ante error. No se usa dentro de $(...) para no perder el
# estado en un subshell.
api() {
  local try
  for try in 1 2; do
    API_BODY="" API_STATUS=err
    if API_BODY="$(gh api -X GET "$1" 2>/dev/null)"; then API_STATUS=200; return 0; fi
    API_BODY=""
    case "$(gh api -X GET -i "$1" 2>/dev/null | head -1)" in
      *" 404"*) API_STATUS=404; return 1 ;;
      *" 403"*|*" 401"*) API_STATUS=403; return 1 ;;
    esac
  done
  return 1
}
# api_list RUTA CLAVE → todas las páginas (--paginate) combinadas en $API_BODY con la forma
# {CLAVE: [...]}; CLAVE "-" para endpoints que devuelven un arreglo. Si la API informa
# total_count y no coincide con lo leído, API_STATUS=incompleto y devuelve error.
api_list() {
  local path="$1" key="$2" sep="?" raw try
  [[ "$path" == *\?* ]] && sep="&"
  for try in 1 2; do
    API_BODY="" API_STATUS=err
    if raw="$(gh api -X GET --paginate --slurp "$path${sep}per_page=100" 2>/dev/null)"; then
      if [ "$key" = "-" ]; then
        API_BODY="$(jq -c 'add // []' <<<"$raw")" || return 1
      else
        API_BODY="$(jq -c --arg k "$key" '{($k): (map(.[$k] // []) | add // []), total_count: (.[0].total_count // null)}' <<<"$raw")" || return 1
        if ! jq -e --arg k "$key" '.total_count == null or .total_count == (.[$k] | length)' <<<"$API_BODY" >/dev/null; then
          API_STATUS=incompleto; return 1
        fi
      fi
      API_STATUS=200; return 0
    fi
    case "$(gh api -X GET -i "$path" 2>/dev/null | head -1)" in
      *" 404"*) API_STATUS=404; return 1 ;;
      *" 403"*|*" 401"*) API_STATUS=403; return 1 ;;
    esac
  done
  return 1
}
in_list() { local x="$1"; shift; local y; for y in "$@"; do [ "$x" = "$y" ] && return 0; done; return 1; }
csv() { local IFS=,; echo "$*"; }
is_historical() { [[ "$1" == $HISTORICAL_GLOB ]]; }

echo "Repositorio: $REPO   árbol analizado: $REF   revisores autorizados: $REVIEWERS"
echo

# ---------------------------------------------------------------- acceso
if api "repos/$REPO" && [ "$(jq -r '.permissions.admin // false' <<<"$API_BODY")" = true ]; then
  report PASS "acceso: admin del mantenedor"
else
  report UNKNOWN "acceso: admin del mantenedor" "sin admin varios controles quedarán UNKNOWN"
fi

# ---------------------------------------------------------------- estado vivo de secretos
# REPO_SECRETS / ENV_SECRETS[env]: nombres presentes. *_OK=0 si no se pudo leer.
REPO_SECRETS="" REPO_SECRETS_OK=0
if api_list "repos/$REPO/actions/secrets" secrets; then
  REPO_SECRETS="$(jq -r '[.secrets[].name]|join(" ")' <<<"$API_BODY")" REPO_SECRETS_OK=1
fi
REPO_SECRETS_STATUS=$API_STATUS

declare -A ENV_EXISTS ENV_SECRETS ENV_SECRETS_OK ENV_SECRETS_STATUS
ENVS_OK=0 ENVS=""
if api_list "repos/$REPO/environments" environments; then
  ENVS_OK=1 ENVS="$API_BODY"
  for e in $(jq -r '.environments[].name' <<<"$ENVS"); do
    ENV_EXISTS[$e]=1
    if api_list "repos/$REPO/environments/$e/secrets" secrets; then
      ENV_SECRETS[$e]="$(jq -r '[.secrets[].name]|join(" ")' <<<"$API_BODY")" ENV_SECRETS_OK[$e]=1
    else
      ENV_SECRETS_OK[$e]=0 ENV_SECRETS_STATUS[$e]=$API_STATUS
    fi
  done
else
  ENVS_STATUS=$API_STATUS
fi
env_json() { jq -c --arg n "$1" '.environments[]? | select(.name==$n)' <<<"$ENVS"; }

# ---------------------------------------------------------------- environments sensibles
# Conjunto exacto de revisores: faltante → FAIL, adicional (usuario o equipo) → FAIL.
EXPECTED_REVIEWERS=()
for r in ${REVIEWERS//,/ }; do [[ "$r" == *:* ]] || r="User:$r"; EXPECTED_REVIEWERS+=("$r"); done

audit_reviewers() { # audit_reviewers ENV JSON
  local rules have=() missing=() extra=() r
  if ! rules="$(jq -c '[.protection_rules[]? | select(.type=="required_reviewers")]' <<<"$2" 2>/dev/null)"; then
    report UNKNOWN "$1: revisores requeridos (conjunto exacto)" "respuesta no interpretable"; return
  fi
  if [ "$(jq 'length' <<<"$rules")" -eq 0 ]; then
    report FAIL "$1: revisores requeridos (conjunto exacto)" "sin regla required_reviewers"; return
  fi
  mapfile -t have < <(jq -r '.[].reviewers[]? | "\(.type):\(.reviewer.login // .reviewer.slug // "?")"' <<<"$rules" | sort -u)
  for r in "${EXPECTED_REVIEWERS[@]}"; do in_list "$r" "${have[@]}" || missing+=("$r"); done
  for r in "${have[@]}"; do in_list "$r" "${EXPECTED_REVIEWERS[@]}" || extra+=("$r"); done
  if [ ${#missing[@]} -eq 0 ] && [ ${#extra[@]} -eq 0 ]; then
    report PASS "$1: revisores requeridos (conjunto exacto)" "$(csv "${have[@]}")"
  else
    report FAIL "$1: revisores requeridos (conjunto exacto)" \
      "faltan: '$(csv "${missing[@]}")' adicionales: '$(csv "${extra[@]}")'"
  fi
  report INFO "$1: prevent_self_review / bypass admins" \
    "$(jq -r '[.[].prevent_self_review][0]' <<<"$rules") / $(jq -r '.can_admins_bypass' <<<"$2") (aceptados)"
}

if [ "$ENVS_OK" != 1 ]; then
  report UNKNOWN "environments" "HTTP $ENVS_STATUS"
else
  for e in "${SENSITIVE_ENVS[@]}"; do
    j="$(env_json "$e")"
    if [ -z "$j" ]; then report FAIL "$e: existe" "no existe"; continue; fi
    report PASS "$e: existe"
    audit_reviewers "$e" "$j"
  done

  j="$(env_json "$PROD_ENV")"
  if [ -n "$j" ]; then
    if [ "$(jq -r '.deployment_branch_policy.custom_branch_policies // false' <<<"$j")" != true ]; then
      report FAIL "$PROD_ENV: restricción de ref" "$(jq -c '.deployment_branch_policy' <<<"$j") (cualquier ref puede usarlo)"
    elif ! api_list "repos/$REPO/environments/$PROD_ENV/deployment-branch-policies" branch_policies; then
      report UNKNOWN "$PROD_ENV: restricción de ref" "HTTP $API_STATUS"
    else
      refs="$(jq -r '[.branch_policies[] | "\(.type // "branch"):\(.name)"] | join(",")' <<<"$API_BODY")"
      [ "$refs" = "branch:$PROD_REF" ] && report PASS "$PROD_ENV: restricción de ref" "$refs" ||
        report FAIL "$PROD_ENV: restricción de ref" "esperado branch:$PROD_REF, actual '${refs:-ninguna}'"
    fi
  fi

  # Un secreto protegido en un environment que no es alcance autorizado para él.
  for e in "${!ENV_EXISTS[@]}"; do
    if [ "${ENV_SECRETS_OK[$e]}" != 1 ]; then
      report UNKNOWN "$e: sin secretos fuera de su alcance" "no se pudieron leer (HTTP ${ENV_SECRETS_STATUS[$e]})"
      report UNKNOWN "$e: sin secretos sin clasificar" "no se pudieron leer (HTTP ${ENV_SECRETS_STATUS[$e]})"
      continue
    fi
    bad=() unclassified=()
    for s in ${ENV_SECRETS[$e]}; do
      if [ -z "${AUTH[$s]+x}" ]; then unclassified+=("$s")
      elif ! in_list "$e" ${AUTH[$s]}; then bad+=("$s"); fi
    done
    [ ${#bad[@]} -eq 0 ] && report PASS "$e: sin secretos fuera de su alcance" ||
      report FAIL "$e: sin secretos fuera de su alcance" "no autorizado para: $(csv "${bad[@]}")"
    [ ${#unclassified[@]} -eq 0 ] && report PASS "$e: sin secretos sin clasificar" ||
      report FAIL "$e: sin secretos sin clasificar" "sin alcance decidido: $(csv "${unclassified[@]}")"
  done
fi

# ---------------------------------------------------------------- copias de repositorio
if [ "$REPO_SECRETS_OK" != 1 ]; then
  report UNKNOWN "repositorio: 0 secretos de Actions" "HTTP $REPO_SECRETS_STATUS"
else
  # Objetivo final: ningún secreto de Actions con alcance de repositorio, conocido o no.
  known=() other=()
  for s in $REPO_SECRETS; do [ -n "${AUTH[$s]+x}" ] && known+=("$s") || other+=("$s"); done
  if [ ${#known[@]} -eq 0 ] && [ ${#other[@]} -eq 0 ]; then
    report PASS "repositorio: 0 secretos de Actions"
  else
    report FAIL "repositorio: 0 secretos de Actions" "$(( ${#known[@]} + ${#other[@]} )) presentes"
    [ ${#known[@]} -eq 0 ] || detail "de producción: $(csv "${known[@]}")"
    [ ${#other[@]} -eq 0 ] || detail "sin clasificar: $(csv "${other[@]}")"
  fi
fi

# ---------------------------------------------------------------- Actions
if ! api "repos/$REPO/actions/permissions"; then report UNKNOWN "actions: sha_pinning_required" "HTTP $API_STATUS"
else
  v="$(jq -r 'if has("sha_pinning_required") then .sha_pinning_required|tostring else "ausente" end' <<<"$API_BODY")"
  case "$v" in
    true) report PASS "actions: sha_pinning_required" "true" ;;
    ausente) report UNKNOWN "actions: sha_pinning_required" "la API no lo expone" ;;
    *) report FAIL "actions: sha_pinning_required" "$v" ;;
  esac
fi
if ! api "repos/$REPO/actions/permissions/workflow"; then report UNKNOWN "actions: permisos del GITHUB_TOKEN" "HTTP $API_STATUS"
else
  d="$(jq -r '.default_workflow_permissions' <<<"$API_BODY")"; a="$(jq -r '.can_approve_pull_request_reviews' <<<"$API_BODY")"
  [ "$d" = read ] && [ "$a" = false ] && report PASS "actions: permisos del GITHUB_TOKEN" "default=read, aprobar PRs=false" ||
    report FAIL "actions: permisos del GITHUB_TOKEN" "default=$d, aprobar PRs=$a"
fi

# ---------------------------------------------------------------- Protect main
if ! api_list "repos/$REPO/rulesets" -; then report UNKNOWN "ruleset 'Protect main'" "HTTP $API_STATUS"
else
  RS_ID="$(jq -r '.[] | select(.name=="Protect main") | .id' <<<"$API_BODY" | head -1)"
  if [ -z "$RS_ID" ]; then report FAIL "ruleset 'Protect main'" "no existe"
  elif ! api "repos/$REPO/rulesets/$RS_ID"; then report UNKNOWN "ruleset 'Protect main'" "HTTP $API_STATUS"
  else
    RS="$API_BODY"
    enf="$(jq -r '.enforcement' <<<"$RS")"; inc="$(jq -r '.conditions.ref_name.include|join(",")' <<<"$RS")"
    { [ "$enf" = active ] && in_list "refs/heads/main" ${inc//,/ }; } &&
      report PASS "Protect main: activo sobre main" "enforcement=$enf include=$inc" ||
      report FAIL "Protect main: activo sobre main" "enforcement=$enf include=$inc"
    for r in deletion non_fast_forward pull_request; do
      jq -e --arg t "$r" 'any(.rules[]; .type==$t)' <<<"$RS" >/dev/null &&
        report PASS "Protect main: regla $r" || report FAIL "Protect main: regla $r" "ausente"
    done
    n="$(jq -r '[.rules[]|select(.type=="pull_request")|.parameters.required_approving_review_count][0] // 0' <<<"$RS")"
    [ "$n" -ge 1 ] && report PASS "Protect main: aprobaciones requeridas" "$n" ||
      report FAIL "Protect main: aprobaciones requeridas" "$n"
    ctx="$(jq -r '[.rules[]|select(.type=="required_status_checks")|.parameters.required_status_checks[].context]|join(",")' <<<"$RS")"
    bad=(); for c in "${FORBIDDEN_REQUIRED_CONTEXTS[@]}"; do [[ ",$ctx," == *",$c,"* ]] && bad+=("$c"); done
    [ ${#bad[@]} -eq 0 ] && report PASS "Protect main: sin contexto heredado en rojo" "requeridos: '${ctx:-ninguno}'" ||
      report FAIL "Protect main: sin contexto heredado en rojo" "requerido: $(csv "${bad[@]}")"
    report INFO "Protect main: bypass" "$(jq -c '[.bypass_actors[]?|"\(.actor_type):\(.bypass_mode)"]' <<<"$RS")"
  fi
fi

# ---------------------------------------------------------------- árbol de workflows ($REF)
# Para cada job que referencia un secreto protegido se resuelve de dónde lo obtendría si
# corriera hoy con el estado vivo: copia del environment declarado si existe ahí; si no,
# copia del repositorio; si no, ninguna fuente. El resultado se compara con los alcances
# autorizados de ese secreto.
if ! git rev-parse --verify -q "$REF^{commit}" >/dev/null; then
  report UNKNOWN "workflows@$REF" "ref no disponible localmente (git fetch)"
else
  echo; echo "Árbol $REF = commit $(git rev-parse "$REF^{commit}") tree $(git rev-parse "$REF^{tree}")"
  WF_DIR="$(mktemp -d)"; trap 'rm -rf "$WF_DIR"' EXIT
  unpinned=() parse_fail=()
  mapfile -t FILES < <(git ls-tree -r --name-only "$REF" -- .github/workflows .github/actions | grep -E '\.ya?ml$')
  for f in "${FILES[@]}"; do
    body="$(git show "$REF:$f")"
    while IFS= read -r u; do
      [ -z "$u" ] && continue
      case "$u" in ./*|docker://*) continue ;; esac
      [[ "$u" =~ @[0-9a-f]{40}$ ]] || unpinned+=("$f:$u")
    done < <(grep -E '^[[:space:]]*(-[[:space:]]*)?uses:' <<<"$body" | sed -E 's/^[^:]*uses:[[:space:]]*//; s/[[:space:]]+#.*$//; s/["'\'']//g')
    if [[ "$f" == .github/workflows/* ]]; then
      out="$WF_DIR/wf-$(basename "$f").json"
      yq -o=json '.' <<<"$body" >"$out" 2>/dev/null || { parse_fail+=("$f"); rm -f "$out"; }
    fi
  done
  [ ${#unpinned[@]} -eq 0 ] && report PASS "workflows@$REF: acciones externas por SHA" ||
    { report FAIL "workflows@$REF: acciones externas por SHA" "${#unpinned[@]} referencias no fijadas"; detail "${unpinned[@]}"; }
  [ ${#parse_fail[@]} -eq 0 ] || { report UNKNOWN "workflows@$REF: parseo YAML" "${#parse_fail[@]} archivos"; detail "${parse_fail[@]}"; }

  # Llamadas a workflows reutilizables: callee<TAB>input<TAB>valor.
  CALLS="$(for j in "$WF_DIR"/wf-*.json; do
    jq -r '.jobs // {} | to_entries[] | select(.value.uses // "" | startswith("./"))
           | .value.uses as $u | (.value.with // {} | to_entries[] | [($u|ltrimstr("./")), .key, (.value|tostring)] | @tsv)' "$j"
  done)"

  # job<TAB>archivo<TAB>environment declarado<TAB>secretos protegidos referenciados
  CONSUMERS=() unclassified_refs=() dynamic_refs=()
  for f in "${FILES[@]}"; do
    [[ "$f" == .github/workflows/* ]] || continue
    j="$WF_DIR/wf-$(basename "$f").json"; [ -f "$j" ] || continue
    triggers="$(jq -r '.on | if type=="object" then keys|join(",") elif type=="array" then join(",") else . end' "$j")"
    while IFS=$'\t' read -r job envexpr secs; do
      [ -z "$job" ] && continue
      prot=()
      for s in $secs; do
        if [ -n "${AUTH[$s]+x}" ]; then prot+=("$s")
        elif ! in_list "$s" "${BUILTIN_SECRETS[@]}"; then unclassified_refs+=("$f#$job: $s"); fi
      done
      [ ${#prot[@]} -eq 0 ] && continue
      # Environment declarado: literal, "-" (ninguno), o expresión ${{ inputs.X }} resuelta
      # por los llamadores cuando el workflow solo es workflow_call y todos pasan literales.
      envs="$envexpr"
      if [[ "$envexpr" == *'${{'* ]]; then
        envs="?"
        if [[ "$envexpr" =~ ^\$\{\{[[:space:]]*inputs\.([A-Za-z0-9_-]+)[[:space:]]*\}\}$ ]] && [ "$triggers" = workflow_call ]; then
          input="${BASH_REMATCH[1]}"
          vals="$(awk -F'\t' -v c="$f" -v i="$input" '$1==c && $2==i {print $3}' <<<"$CALLS" | sort -u)"
          if [ -n "$vals" ] && ! grep -q '\${{' <<<"$vals"; then envs="$(tr '\n' ' ' <<<"$vals")"; fi
        fi
      fi
      CONSUMERS+=("$f#$job"$'\t'"$envs"$'\t'"${prot[*]}")
    done < <(jq -r '
      (.env // {} | [..|strings|scan("secrets\\.([A-Za-z0-9_]+)")[0]|ascii_upcase]) as $wf
      | .jobs // {} | to_entries[]
      | [ .key,
          ((.value.environment | if type=="object" then .name else . end) // "-"),
          (($wf + [.value|..|strings|scan("secrets\\.([A-Za-z0-9_]+)")[0]|ascii_upcase]) | unique | join(" ")) ]
      | @tsv' "$j")
    # Acceso a secretos por expresión (secrets[...] o toJSON(secrets)): nombre no resoluble.
    jq -e '[..|strings|select(test("secrets\\s*\\[|toJSON\\(\\s*secrets\\s*\\)"))]|length>0' "$j" >/dev/null &&
      dynamic_refs+=("$f")
  done

  ok=() repo_in_auth=() repo_outside=() hist_live=() env_unauth=() auth_nosrc=() inert=() unresolved=() missing_env=()
  plan_envs=""
  for c in "${CONSUMERS[@]}"; do
    IFS=$'\t' read -r where envs secs <<<"$c"
    file="${where%%#*}"
    [ "$file" = "$PLAN_WORKFLOW" ] && plan_envs="$plan_envs $envs"
    for e in $envs; do
      if [ "$e" != "-" ] && [ "$e" != "?" ] && [ "$ENVS_OK" = 1 ] && [ -z "${ENV_EXISTS[$e]+x}" ]; then
        in_list "$where ($e)" "${missing_env[@]}" || missing_env+=("$where ($e)")
      fi
      for s in $secs; do
        tag="$where [env=$e] $s"
        if [ "$e" = "?" ]; then unresolved+=("$tag: environment dinámico no resoluble"); continue; fi
        # Fuente efectiva.
        src=""
        if [ "$e" != "-" ] && [ -n "${ENV_EXISTS[$e]+x}" ]; then
          if [ "${ENV_SECRETS_OK[$e]}" != 1 ]; then unresolved+=("$tag: secretos de $e ilegibles"); continue; fi
          in_list "$s" ${ENV_SECRETS[$e]} && src="env"
        elif [ "$e" != "-" ] && [ "$ENVS_OK" != 1 ]; then
          unresolved+=("$tag: environments ilegibles"); continue
        fi
        if [ -z "$src" ]; then
          if [ "$REPO_SECRETS_OK" != 1 ]; then unresolved+=("$tag: secretos de repo ilegibles"); continue; fi
          in_list "$s" $REPO_SECRETS && src="repo" || src="none"
        fi
        authorized=0; [ "$e" != "-" ] && in_list "$e" ${AUTH[$s]} && authorized=1
        case "$src" in
          env) [ $authorized = 1 ] && ok+=("$tag") || env_unauth+=("$tag") ;;
          repo)
            if is_historical "$file"; then hist_live+=("$tag ← copia de repo")
            elif [ $authorized = 1 ]; then repo_in_auth+=("$tag ← copia de repo")
            else repo_outside+=("$tag ← copia de repo"); fi ;;
          none)
            if [ $authorized = 1 ]; then auth_nosrc+=("$tag")
            else inert+=("$tag$(is_historical "$file" && echo ' (histórico OCI)')"); fi ;;
        esac
      done
    done
  done

  pr() { # pr ESTADO_SI_HAY control explicación lista...
    local st="$1" name="$2" why="$3"; shift 3
    if [ $# -eq 0 ] && [ ${#unresolved[@]} -gt 0 ]; then
      report UNKNOWN "$name" "ninguno entre los resueltos; ${#unresolved[@]} sin resolver"
    elif [ $# -eq 0 ]; then report PASS "$name" "ninguno"
    else report "$st" "$name" "$# — $why"; detail "$@"; fi
  }
  if [ ${#ok[@]} -gt 0 ]; then report INFO "consumidores: vía environment autorizado" "${#ok[@]}"; detail "${ok[@]}"; fi
  pr FAIL "consumidores: env autorizado pero resuelven copia de repo" \
    "falta la copia en el environment; la copia de repo es alcanzable fuera de él" "${repo_in_auth[@]}"
  pr FAIL "consumidores: copia de repo sin environment autorizado" \
    "ruta privilegiada fuera de la frontera" "${repo_outside[@]}"
  pr FAIL "consumidores históricos (OCI): ruta viva" \
    "OCI está inactivo; se cierra al retirar la copia de repo (no se mantiene OCI operativo)" "${hist_live[@]}"
  pr FAIL "consumidores: secreto en environment no autorizado" \
    "el environment no es alcance aprobado para ese secreto" "${env_unauth[@]}"
  pr FAIL "consumidores: env autorizado sin ninguna fuente" \
    "el árbol referencia el secreto y no hay fuente: cargarlo si tiene consumidor real o quitar la referencia" "${auth_nosrc[@]}"
  pr FAIL "workflows: environments declarados inexistentes" \
    "GitHub crearía el environment sin protección al ejecutar el job" "${missing_env[@]}"
  if [ ${#unresolved[@]} -gt 0 ]; then report UNKNOWN "consumidores: fuente no determinable" "${#unresolved[@]}"; detail "${unresolved[@]}"; fi
  if [ ${#inert[@]} -gt 0 ]; then report INFO "consumidores: referencias inertes (sin fuente viva)" "${#inert[@]}"; detail "${inert[@]}"; fi
  [ ${#unclassified_refs[@]} -eq 0 ] && report PASS "workflows@$REF: secretos referenciados clasificados" ||
    { report FAIL "workflows@$REF: secretos referenciados clasificados" "${#unclassified_refs[@]} sin alcance decidido"; detail "${unclassified_refs[@]}"; }
  [ ${#dynamic_refs[@]} -eq 0 ] && report PASS "workflows@$REF: sin acceso dinámico a secretos" ||
    { report UNKNOWN "workflows@$REF: sin acceso dinámico a secretos" "${#dynamic_refs[@]} — nombre no resoluble"; detail "${dynamic_refs[@]}"; }

  # Presencia sin consumidor: no se exige ni se prohíbe; se informa para revisar secretos
  # muertos o transicionales que no conviene arrastrar al environment.
  for e in "${SENSITIVE_ENVS[@]}"; do
    [ -n "${ENV_EXISTS[$e]+x}" ] && [ "${ENV_SECRETS_OK[$e]}" = 1 ] || continue
    idle=()
    for s in ${ENV_SECRETS[$e]}; do
      [ -n "${AUTH[$s]+x}" ] || continue
      used=0
      for c in "${CONSUMERS[@]}"; do
        IFS=$'\t' read -r _ cenvs csecs <<<"$c"
        in_list "$e" $cenvs && in_list "$s" $csecs && { used=1; break; }
      done
      [ $used = 1 ] || idle+=("$s")
    done
    [ ${#idle[@]} -eq 0 ] || { report INFO "$e: secretos sin consumidor en $REF" "${#idle[@]}"; detail "${idle[@]}"; }
  done

  read -r -a pe <<<"$plan_envs"
  if [ ${#pe[@]} -eq 0 ]; then report UNKNOWN "workflows@$REF: plan Hetzner usa $PLAN_ENV" "consumidor no encontrado"
  else
    bad=(); for e in "${pe[@]}"; do [ "$e" = "$PLAN_ENV" ] || bad+=("$e"); done
    [ ${#bad[@]} -eq 0 ] && report PASS "workflows@$REF: plan Hetzner usa $PLAN_ENV" ||
      report FAIL "workflows@$REF: plan Hetzner usa $PLAN_ENV" "environment declarado: $(csv "${bad[@]}")"
  fi
  prt=(); for f in "${FILES[@]}"; do
    [[ "$f" == .github/workflows/* ]] && git show "$REF:$f" | grep -q 'pull_request_target' && prt+=("$f")
  done
  [ ${#prt[@]} -eq 0 ] && report PASS "workflows@$REF: sin pull_request_target" ||
    { report FAIL "workflows@$REF: sin pull_request_target" "${#prt[@]} — corre con secretos sobre código de PR"; detail "${prt[@]}"; }
fi

echo
echo "PASS=$PASS FAIL=$FAIL UNKNOWN=$UNKNOWN"
[ "$FAIL" -gt 0 ] && exit 1
[ "$UNKNOWN" -gt 0 ] && exit 2
exit 0
