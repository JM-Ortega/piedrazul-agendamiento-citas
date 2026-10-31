#!/usr/bin/env bash
# Etapas de producción (Run P / Run I), push diferido y rango procesado.
#
# Ejecuta el código real: production-stage.sh, production-range-base.sh y los bloques `run` de
# classify, conclude y del resumen de production-hetzner.yml. Sin red y sin GitHub: `gh` es
# falso y sirve respuestas registradas. Nada de esto prueba el comportamiento real de GitHub
# Actions (run-name, evaluación de `vars`, jobs saltados): eso queda para la verificación en vivo.
#
# Uso: infra/tests/production-stage-check.sh
# Requiere: jq, yq (mikefarah v4), git, python3
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAGE="${ROOT}/.github/scripts/production-stage.sh"
RANGE="${ROOT}/.github/scripts/production-range-base.sh"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
WF_PATH=.github/workflows/production-hetzner.yml

for bin in jq yq git python3; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
fails() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ko "${desc} (no falló)"; else ok "${desc}"; fi; }

# ── 1. Combinaciones de flags por etapa ─────────────────────────────────────
validate() { # <stage> <terraform> <host> <build> <build_pg> <deploy> <frontend> [VAR=valor...]
  local s="$1" t="$2" h="$3" b="$4" p="$5" d="$6" f="$7"
  shift 7
  env IN_STAGE="$s" IN_TERRAFORM="$t" IN_HOST_CONFIG="$h" IN_BUILD="$b" IN_BUILD_POSTGRES="$p" \
    IN_APP_DEPLOY="$d" IN_FRONTEND="$f" IN_IMAGE_TAG= IN_KEYCLOAK_RECOVERY=false IN_BREAK_GLASS= "$@" "${STAGE}" validate
}
check "validate: ordinary no restringe los flags" validate ordinary false false false false true false
check "validate: stage omitido equivale a ordinary" env -u IN_STAGE "${STAGE}" validate
check "validate: provision con la instalación completa" validate provision true true true true true true
check "validate: install con la instalación completa, sin Apply" validate install false true true true true true
fails "validate: etapa desconocida" validate bootstrap true true true true true true
fails "validate: provision sin run_terraform_apply" validate provision false true true true true true
fails "validate: install no puede pedir Apply" validate install true true true true true true
for flag in 3:host_config 4:build 5:build_postgres 6:app_deploy 7:frontend_publish; do
  i="${flag%%:*}" name="${flag#*:}"
  for s in provision install; do
    args=("$s" "$([ "$s" = provision ] && echo true || echo false)" true true true true true)
    args[$((i - 1))]=false
    fails "validate: ${s} sin run_${name}" validate "${args[@]}"
  done
done
for s in provision install; do
  t="$([ "$s" = provision ] && echo true || echo false)"
  fails "validate: ${s} no admite rollback" validate "$s" "$t" true true true true true IN_IMAGE_TAG=abc
  fails "validate: ${s} no admite recuperación de Keycloak" validate "$s" "$t" true true true true true IN_KEYCLOAK_RECOVERY=true
  fails "validate: ${s} no admite break-glass" validate "$s" "$t" true true true true true IN_BREAK_GLASS=BG-2026-001
done
validate provision false true true true true true > "${TMP}/v.out" 2>&1 || true
check "validate: el rechazo es un error visible que nombra el flag" grep -q '::error title=Etapa de producción::.*run_terraform_apply' "${TMP}/v.out"

# ── 2. La etapa decide si admite el plan revisado ───────────────────────────
gate() { "${STAGE}" plan-gate "$@"; } # <stage> <has_changes> <acción> <push_mode>
for s in ordinary provision install; do
  for m in deferred enabled invalid ""; do
    check "plan-gate: ${s} sin cambios pasa (no habrá Apply), push ${m:-sin dato}" gate "$s" false no-op "$m"
    check "plan-gate: ${s} sin cambios no depende de leer el JSON del plan, push ${m:-sin dato}" gate "$s" false unknown "$m"
  done
done
for m in deferred enabled invalid; do
  fails "plan-gate: ordinary no crea el servidor (push ${m})" gate ordinary true create "$m"
  fails "plan-gate: ordinary no reemplaza el servidor (push ${m})" gate ordinary true replace "$m"
  fails "plan-gate: ordinary no destruye el servidor sin reemplazo (push ${m})" gate ordinary true delete "$m"
  fails "plan-gate: provision no destruye sin crear (push ${m})" gate provision true delete "$m"
  check "plan-gate: ordinary conserva los cambios que no tocan el servidor (push ${m})" gate ordinary true no-op "$m"
  check "plan-gate: ordinary conserva la actualización en sitio (push ${m})" gate ordinary true update "$m"
  check "plan-gate: provision recupera un Apply parcial sin crear (push ${m}: sin bloqueo mutuo)" gate provision true no-op "$m"
  check "plan-gate: provision recupera con una actualización en sitio (push ${m})" gate provision true update "$m"
done
gate ordinary true delete deferred > /dev/null 2> "${TMP}/gate.err" || true
check "plan-gate: la destrucción se nombra como retiro, no como acción ilegible" grep -q 'retiro es una decisión operativa' "${TMP}/gate.err"
check "plan-gate: provision crea con los push retenidos" gate provision true create deferred
check "plan-gate: provision reemplaza con los push retenidos" gate provision true replace deferred
for m in enabled invalid "" otro; do
  fails "plan-gate: provision no crea sin la retención (push ${m:-sin dato})" gate provision true create "$m"
  fails "plan-gate: provision no reemplaza sin la retención (push ${m:-sin dato})" gate provision true replace "$m"
done
gate provision true replace enabled > /dev/null 2> "${TMP}/gate.err" || true
check "plan-gate: el rechazo dice qué variable corregir y que el saved plan se descarta" \
  bash -c "grep -q 'vars.PRODUCTION_PUSH_DEPLOY=deferred' '${TMP}/gate.err' && grep -q 'saved plan se descarta' '${TMP}/gate.err'"
for a in create replace no-op update delete none unknown; do
  fails "plan-gate: install rechaza un plan con cambios (${a})" gate install true "$a" deferred
done
for a in none ambiguous unknown "" "delete,create"; do
  fails "plan-gate: acción no interpretable con cambios en ordinary (${a:-vacía})" gate ordinary true "$a" deferred
  fails "plan-gate: acción no interpretable con cambios en provision (${a:-vacía})" gate provision true "$a" deferred
done
fails "plan-gate: has_changes inválido" gate ordinary "" no-op deferred
fails "plan-gate: etapa deferred no planifica" gate deferred false no-op deferred
fails "plan-gate: sin el modo de push el contrato no se puede evaluar" gate provision true create

# ── 3. Modo de los push ─────────────────────────────────────────────────────
check "push-mode: sin definir → deferred" bash -c "[ \"\$('${STAGE}' push-mode '')\" = deferred ]"
check "push-mode: deferred" bash -c "[ \"\$('${STAGE}' push-mode deferred)\" = deferred ]"
check "push-mode: enabled" bash -c "[ \"\$('${STAGE}' push-mode enabled)\" = enabled ]"
fails "push-mode: un valor mal escrito no se interpreta" "${STAGE}" push-mode enable
fails "push-mode: las mayúsculas no equivalen a enabled" "${STAGE}" push-mode ENABLED

# ── 4. Rango procesado ──────────────────────────────────────────────────────
REPO="${TMP}/repo"
git init -q -b main "${REPO}"
git -C "${REPO}" config user.email t@t
git -C "${REPO}" config user.name t
git -C "${REPO}" config commit.gpgsign false
mk() { git -C "${REPO}" commit -q --allow-empty -m "$1" && git -C "${REPO}" rev-parse HEAD; }
A=$(mk a); B=$(mk b); C=$(mk c); HEAD_SHA=$(mk d)
git -C "${REPO}" checkout -q -b otra "${A}"
OTHER=$(mk rama)
git -C "${REPO}" checkout -q main

mkdir -p "${TMP}/bin" "${TMP}/gh"
cat > "${TMP}/bin/gh" << 'EOF'
#!/usr/bin/env bash
[ "$1" = api ] || exit 2
printf '%s\n' "$2" >> "${FAKE_GH_LOG}"
for f in "${FAKE_GH_DIR}"/*; do
  [ -e "$f" ] || continue
  if [[ "$2" == *"$(basename "$f" | tr '#' '/')"* ]]; then cat "$f"; exit 0; fi
done
echo "gh: HTTP 500" >&2
exit 1
EOF
chmod +x "${TMP}/bin/gh"
export FAKE_GH_DIR="${TMP}/gh" FAKE_GH_LOG="${TMP}/gh.log"
: > "${FAKE_GH_LOG}"

run_row() { # <id> <sha> <conclusión> [evento] [antigüedad en días]
  jq -cn --arg id "$1" --arg sha "$2" --arg c "$3" --arg e "${4:-push}" --arg p "${WF_PATH}" \
    --arg at "$(date -u -d "-${5:-0} days" +%Y-%m-%dT%H:%M:%SZ)" \
    '{id: ($id | tonumber), head_sha: $sha, conclusion: $c, event: $e, path: $p, head_branch: "main", status: "completed", created_at: $at}'
}
runs() { jq -s '{workflow_runs: .}'; }
jobs_file() { # <run_id> <json de jobs>
  jq -c '{total_count: (.jobs | length)} + .' <<< "$2" > "${FAKE_GH_DIR}/runs#$1#jobs"
}
signal() { jobs_file "$1" '{"jobs":[{"name":"Resultado de producción","status":"completed","conclusion":"success"},{"name":"Rango procesado","status":"completed","conclusion":"success"}]}'; }
no_signal() { jobs_file "$1" '{"jobs":[{"name":"Resultado de producción","status":"completed","conclusion":"success"},{"name":"Rango procesado","status":"completed","conclusion":"skipped"}]}'; }
historic() { jobs_file "$1" '{"jobs":[{"name":"Resultado de producción","status":"completed","conclusion":"success"}]}'; }
reset_gh() { rm -f "${FAKE_GH_DIR:?}"/*; : > "${FAKE_GH_LOG}"; }
base() { # stdin: runs.json → base
  (cd "${REPO}" && env PATH="${TMP}/bin:${PATH}" GITHUB_REPOSITORY=o/r "$@" "${RANGE}" "${WF_PATH}" "${HEAD_SHA}" 999)
}
range_is() { # <descripción> <esperado> <runs.json> [VAR=valor...]
  local d="$1" want="$2" json="$3" out rc=0
  shift 3
  out="$(base "$@" <<< "${json}" 2> "${TMP}/range.err")" || rc=$?
  if [ "${rc}" -eq 0 ] && [ "${out}" = "${want}" ]; then ok "rango: ${d}"; else ko "rango: ${d} (rc=${rc}, '${out}', $(tail -1 "${TMP}/range.err"))"; fi
}
range_fails() { # <descripción> <runs.json>
  if base <<< "$2" > "${TMP}/range.out" 2> "${TMP}/range.err"; then ko "rango: $1 (no falló)"
  elif [ -s "${TMP}/range.out" ]; then ko "rango: $1 (imprimió una base)"
  else ok "rango: $1"; fi
}

reset_gh
range_is "sin runs previos → árbol completo" "" "$(printf '' | runs)"
reset_gh; signal 11
range_is "push procesado → su commit es la base" "${B}" "$(run_row 11 "${B}" success | runs)"
check "rango: consulta los jobs del último intento del run" grep -q 'actions/runs/11/jobs?filter=latest' "${FAKE_GH_LOG}"
reset_gh; signal 11; no_signal 12; no_signal 13
range_is "diferidos posteriores no avanzan: la base sigue en el último procesado" "${B}" \
  "$({ run_row 13 "${HEAD_SHA}" success; run_row 12 "${C}" success push 1; run_row 11 "${B}" success push 2; } | runs)"
reset_gh; no_signal 12; no_signal 13
range_is "solo diferidos → árbol completo, nunca 'sin cambios'" "" "$({ run_row 13 "${C}" success; run_row 12 "${B}" success push 1; } | runs)"
reset_gh; historic 12
range_is "run histórico sin el job de señal no cuenta → árbol completo" "" "$(run_row 12 "${B}" success | runs)"
reset_gh; jobs_file 12 '{"jobs":[{"name":"Rango procesado","status":"completed","conclusion":"failure"}]}'
range_is "señal fallida no cuenta" "" "$(run_row 12 "${B}" success | runs)"
reset_gh; jobs_file 12 '{"jobs":[{"name":"Rango procesado","status":"in_progress","conclusion":null}]}'
range_is "señal sin terminar no cuenta" "" "$(run_row 12 "${B}" success | runs)"
reset_gh; jobs_file 12 '{"jobs":[{"name":"Rango procesado","status":"completed","conclusion":"success"},{"name":"Rango procesado","status":"completed","conclusion":"skipped"}]}'
range_is "señal ambigua (repetida) no cuenta" "" "$(run_row 12 "${B}" success | runs)"
reset_gh; signal 12; signal 13; signal 14; signal 15
range_is "fallido, cancelado o dispatch no avanzan aunque traigan el job" "${A}" \
  "$({ run_row 15 "${C}" failure; run_row 14 "${C}" cancelled push 1; run_row 13 "${C}" success workflow_dispatch 2; run_row 12 "${A}" success push 3; } | runs)"
reset_gh; signal 999
range_is "el run actual no se toma a sí mismo como base" "" "$(run_row 999 "${C}" success | runs)"
check "rango: el run actual ni siquiera se consulta" test ! -s "${FAKE_GH_LOG}"
reset_gh; signal 12
range_is "historia reescrita (no ancestro) → árbol completo" "" "$(run_row 12 "${OTHER}" success | runs)"
reset_gh; no_signal 13; no_signal 14; signal 12
range_is "límite de consultas alcanzado → árbol completo (seguro)" "" \
  "$({ run_row 14 "${C}" success; run_row 13 "${C}" success push 1; run_row 12 "${A}" success push 2; } | runs)" RANGE_MAX_LOOKUPS=2
reset_gh
range_fails "API no disponible → falla, no avanza ni se lee como árbol completo" "$(run_row 12 "${B}" success | runs)"
reset_gh; printf 'no es json' > "${FAKE_GH_DIR}/runs#12#jobs"
range_fails "respuesta de jobs ilegible → falla" "$(run_row 12 "${B}" success | runs)"
reset_gh; printf '{"total_count":120,"jobs":[{"name":"Rango procesado","status":"completed","conclusion":"success"}]}' > "${FAKE_GH_DIR}/runs#12#jobs"
range_fails "respuesta de jobs truncada → falla" "$(run_row 12 "${B}" success | runs)"
range_fails "respuesta de runs inválida → falla" '{"x":1}'

# ── 5. Bloque real de classify ──────────────────────────────────────────────
INTENT_RUN="$(yq -r '.jobs.classify.steps[] | select(.id == "intent") | .run' "${WORKFLOW}")"
ROOT_HEAD="$(git -C "${ROOT}" rev-parse HEAD)"
classify() { # <EVENT_NAME> [VAR=valor...] → salida; el directorio queda en ${TMP}/c
  local event="$1"
  shift
  rm -rf "${TMP}/c" && mkdir -p "${TMP}/c"
  (cd "${ROOT}" && env -i PATH="${TMP}/bin:${PATH}" HOME="${TMP}" GIT_CONFIG_GLOBAL=/dev/null FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" \
    GITHUB_REF=refs/heads/main GITHUB_SHA="${ROOT_HEAD}" GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=999 EVENT_NAME="${event}" \
    RUNNER_TEMP="${TMP}/c" GITHUB_OUTPUT="${TMP}/c/out" GITHUB_STEP_SUMMARY="${TMP}/c/summary" WORKFLOW_PATH="${WF_PATH}" \
    PIN_OUTSIDE_ENVIRONMENT=false IN_TERRAFORM=false IN_HOST_CONFIG=false IN_BUILD=false IN_BUILD_POSTGRES=false \
    IN_APP_DEPLOY=false IN_FRONTEND=false IN_IMAGE_TAG= IN_KEYCLOAK_RECOVERY=false IN_BREAK_GLASS= IN_BREAK_GLASS_JUSTIFICATION= \
    "$@" bash -c "${INTENT_RUN}") > "${TMP}/c.log" 2>&1
}
out_is() { grep -qx "$1" "${TMP}/c/out"; }
all_flags=(IN_HOST_CONFIG=true IN_BUILD=true IN_BUILD_POSTGRES=true IN_APP_DEPLOY=true IN_FRONTEND=true)

reset_gh
check "classify: push con la variable sin definir queda diferido" classify push PUSH_DEPLOY_SETTING=
for o in stage=deferred push_mode=deferred intent=deferred range_mode=deferred terraform_required=false deploy_required=false \
  run_build=false run_build_postgres=false run_host_config=false run_app_deploy=false frontend_required=false; do
  check "classify diferido: ${o}" out_is "${o}"
done
check "classify diferido: no depende de la API de GitHub" test ! -s "${FAKE_GH_LOG}"
check "classify diferido: aviso visible que dice que no hay deploy" grep -q '::notice title=Push diferido::Sin deploy' "${TMP}/c.log"
check "classify diferido: el resumen lo distingue" grep -q 'diferido' "${TMP}/c/summary"
check "classify: PRODUCTION_PUSH_DEPLOY=deferred también difiere" bash -c "$(declare -f classify out_is); $(printf 'TMP=%q FAKE_GH_DIR=%q FAKE_GH_LOG=%q ROOT=%q ROOT_HEAD=%q WF_PATH=%q INTENT_RUN=%q' "${TMP}" "${FAKE_GH_DIR}" "${FAKE_GH_LOG}" "${ROOT}" "${ROOT_HEAD}" "${WF_PATH}" "${INTENT_RUN}"); classify push PUSH_DEPLOY_SETTING=deferred && out_is stage=deferred"
fails "classify: un valor inválido falla sin procesar nada" classify push PUSH_DEPLOY_SETTING=on
reset_gh
printf '{"workflow_runs":[]}' > "${FAKE_GH_DIR}/workflows#production-hetzner.yml#runs"
check "classify: push con enabled procesa el árbol completo sin rango previo" classify push PUSH_DEPLOY_SETTING=enabled
check "classify enabled: etapa ordinaria y modo full, con el modo enabled" bash -c "grep -qx stage=ordinary '${TMP}/c/out' && grep -qx push_mode=enabled '${TMP}/c/out' && grep -qx range_mode=full '${TMP}/c/out' && grep -qx terraform_required=true '${TMP}/c/out'"
reset_gh
fails "classify: push con enabled y API caída falla (no se inventa un rango)" classify push PUSH_DEPLOY_SETTING=enabled

check "classify: Run P se acepta" classify workflow_dispatch IN_STAGE=provision IN_TERRAFORM=true "${all_flags[@]}"
for o in stage=provision intent=provision terraform_required=true deploy_required=true run_host_config=true run_app_deploy=true frontend_required=true range_mode=manual; do
  check "classify Run P: ${o} (la intención completa alimenta las comprobaciones previas)" out_is "${o}"
done
check "classify Run P: con push habilitados avisa" bash -c "$(declare -f classify); $(printf 'TMP=%q FAKE_GH_DIR=%q FAKE_GH_LOG=%q ROOT=%q ROOT_HEAD=%q WF_PATH=%q INTENT_RUN=%q' "${TMP}" "${FAKE_GH_DIR}" "${FAKE_GH_LOG}" "${ROOT}" "${ROOT_HEAD}" "${WF_PATH}" "${INTENT_RUN}"); classify workflow_dispatch IN_STAGE=provision IN_TERRAFORM=true ${all_flags[*]} PUSH_DEPLOY_SETTING=enabled && grep -q '::warning title=Etapa' '${TMP}/c.log'"
check "classify Run P: el modo de push viaja como salida" bash -c "$(declare -f classify); $(printf 'TMP=%q FAKE_GH_DIR=%q FAKE_GH_LOG=%q ROOT=%q ROOT_HEAD=%q WF_PATH=%q INTENT_RUN=%q' "${TMP}" "${FAKE_GH_DIR}" "${FAKE_GH_LOG}" "${ROOT}" "${ROOT_HEAD}" "${WF_PATH}" "${INTENT_RUN}"); classify workflow_dispatch IN_STAGE=provision IN_TERRAFORM=true ${all_flags[*]} PUSH_DEPLOY_SETTING=enabled && grep -qx push_mode=enabled '${TMP}/c/out'"
check "classify Run P: sin definir queda retenido" bash -c "$(declare -f classify); $(printf 'TMP=%q FAKE_GH_DIR=%q FAKE_GH_LOG=%q ROOT=%q ROOT_HEAD=%q WF_PATH=%q INTENT_RUN=%q' "${TMP}" "${FAKE_GH_DIR}" "${FAKE_GH_LOG}" "${ROOT}" "${ROOT_HEAD}" "${WF_PATH}" "${INTENT_RUN}"); classify workflow_dispatch IN_STAGE=provision IN_TERRAFORM=true ${all_flags[*]} && grep -qx push_mode=deferred '${TMP}/c/out' && ! grep -q '::warning title=Etapa' '${TMP}/c.log'"
check "classify Run P: un valor inválido no bloquea la recuperación, pero no retiene" bash -c "$(declare -f classify); $(printf 'TMP=%q FAKE_GH_DIR=%q FAKE_GH_LOG=%q ROOT=%q ROOT_HEAD=%q WF_PATH=%q INTENT_RUN=%q' "${TMP}" "${FAKE_GH_DIR}" "${FAKE_GH_LOG}" "${ROOT}" "${ROOT_HEAD}" "${WF_PATH}" "${INTENT_RUN}"); classify workflow_dispatch IN_STAGE=provision IN_TERRAFORM=true ${all_flags[*]} PUSH_DEPLOY_SETTING=on && grep -qx push_mode=invalid '${TMP}/c/out' && grep -q '::warning title=Etapa' '${TMP}/c.log'"
check "classify: un dispatch ordinario no depende de la variable de push" classify workflow_dispatch IN_STAGE=ordinary IN_APP_DEPLOY=true PUSH_DEPLOY_SETTING=on
check "classify: Run I se acepta" classify workflow_dispatch IN_STAGE=install "${all_flags[@]}"
for o in stage=install intent=install terraform_required=true deploy_required=true run_host_config=true range_mode=manual; do
  check "classify Run I: ${o}" out_is "${o}"
done
fails "classify: Run I que pide Apply se rechaza antes de cualquier job" classify workflow_dispatch IN_STAGE=install IN_TERRAFORM=true "${all_flags[@]}"
fails "classify: Run P parcial se rechaza" classify workflow_dispatch IN_STAGE=provision IN_TERRAFORM=true IN_HOST_CONFIG=true
check "classify: dispatch ordinario conserva su intención" classify workflow_dispatch IN_STAGE=ordinary IN_APP_DEPLOY=true
check "classify: dispatch ordinario → deploy_only, etapa ordinary" bash -c "grep -qx stage=ordinary '${TMP}/c/out' && grep -qx intent=deploy_only '${TMP}/c/out'"
check "classify: informa si el pin existe fuera del environment" classify workflow_dispatch IN_STAGE=ordinary IN_APP_DEPLOY=true PIN_OUTSIDE_ENVIRONMENT=true
check "classify: salida pin_outside_environment=true" out_is pin_outside_environment=true

# ── 6. Bloque real de conclude ──────────────────────────────────────────────
CONCLUDE_RUN="$(yq -r '.jobs.conclude.steps[0].run' "${WORKFLOW}")"
conclude() { # [VAR=valor...]: push diferido por defecto
  : > "${TMP}/summary"
  env -i PATH="${PATH}" GITHUB_STEP_SUMMARY="${TMP}/summary" CLASSIFY=success SELECT=success PRODUCTION=skipped TERRAFORM_PLAN=skipped \
    HAS_CHANGES= TERRAFORM_REQUIRED=false DEPLOY_REQUIRED=false FRONTEND_REQUIRED=false FRONTEND_BUILD=skipped PRODUCTION_STAGE=deferred \
    "$@" bash -c "${CONCLUDE_RUN}" > "${TMP}/conclude.log" 2>&1
}
check "conclude: push diferido termina bien con todo lo privilegiado saltado" conclude
check "conclude diferido: el resumen dice que no es un deploy" grep -q 'no es un deploy' "${TMP}/summary"
check "conclude diferido: aviso visible" grep -q '::notice title=Push diferido' "${TMP}/conclude.log"
fails "conclude: diferido con production corrido falla" conclude PRODUCTION=success
fails "conclude: diferido con plan corrido falla" conclude TERRAFORM_PLAN=success
fails "conclude: diferido que requiere trabajo falla" conclude DEPLOY_REQUIRED=true
fails "conclude: classify fallido nunca es un diferido correcto" conclude CLASSIFY=failure
fails "conclude: Run I con plan con cambios falla aunque production figure bien" conclude PRODUCTION_STAGE=install TERRAFORM_REQUIRED=true TERRAFORM_PLAN=success HAS_CHANGES=true PRODUCTION=success DEPLOY_REQUIRED=true
check "conclude: Run I sin cambios y producción bien" conclude PRODUCTION_STAGE=install TERRAFORM_REQUIRED=true TERRAFORM_PLAN=success HAS_CHANGES=false PRODUCTION=success DEPLOY_REQUIRED=true
check "conclude: Run P con producción bien" conclude PRODUCTION_STAGE=provision TERRAFORM_REQUIRED=true TERRAFORM_PLAN=success HAS_CHANGES=true PRODUCTION=success DEPLOY_REQUIRED=true
fails "conclude: Run P con producción fallida falla" conclude PRODUCTION_STAGE=provision TERRAFORM_REQUIRED=true TERRAFORM_PLAN=success HAS_CHANGES=true PRODUCTION=failure DEPLOY_REQUIRED=true

# ── 7. La señal del rango: condición real de range_processed ────────────────
yq -o=json '.jobs.range_processed' "${WORKFLOW}" > "${TMP}/range-job.json"
cat > "${TMP}/range-job.py" << 'EOF'
import itertools, json, re, sys
J = json.load(open(sys.argv[1]))
e = " ".join(J["if"].split())
assert "!cancelled()" in e, "sin función de estado explícita el success() implícito salta el job"
def runs(event, classify, conclude, stage, cancelled):
    x = e.replace("!cancelled()", "(not CANCELLED)").replace("&&", " and ")
    x = re.sub(r"github\.event_name", "EVENT", x)
    x = x.replace("needs.classify.result", "CLASSIFY").replace("needs.conclude.result", "CONCLUDE")
    x = x.replace("needs.classify.outputs.stage", "STAGE")
    return bool(eval(x, {"CANCELLED": cancelled, "EVENT": event, "CLASSIFY": classify, "CONCLUDE": conclude, "STAGE": stage}))
bad = []
for ev, cl, co, st, ca in itertools.product(["push", "workflow_dispatch"], ["success", "failure", "skipped"],
        ["success", "failure", "cancelled", "skipped"], ["ordinary", "deferred", "provision", "install", ""], [False, True]):
    want = ev == "push" and cl == "success" and co == "success" and st == "ordinary" and not ca
    if runs(ev, cl, co, st, ca) != want:
        bad.append((ev, cl, co, st, ca))
assert not bad, bad[:5]
assert J["needs"] == ["classify", "conclude"]
assert J.get("permissions") == {}
assert "environment" not in J and "secrets." not in json.dumps(J)
EOF
check "range_processed: solo un push ordinario verificado por conclude deja la señal (matriz completa)" python3 "${TMP}/range-job.py" "${TMP}/range-job.json"
check "rango: el nombre de la señal coincide entre el workflow y production-range-base.sh" \
  bash -c "n=\$(yq -r '.jobs.range_processed.name' '${WORKFLOW}'); grep -qx \"SIGNAL_JOB=\\\"\${n}\\\"\" '${RANGE}'"

# ── 8. Bloque real del resumen: Run P nunca se informa como instalación ─────
cat > "${TMP}/summary.py" << 'EOF'
import json, os, subprocess, sys
step = json.load(open(sys.argv[1]))
status, over = sys.argv[3], json.loads(sys.argv[4])
env = {k: "" for k in step["env"]}
env.update({"PATH": os.environ["PATH"], "GITHUB_STEP_SUMMARY": sys.argv[2], "GITHUB_SHA": "a" * 40, "GITHUB_EVENT_NAME": "workflow_dispatch",
            "POLICY_RESULT": "/nonexistent", "TERRAFORM_REQUIRED": "true", "TF_APPLY_REQUIRED": "true", "DEPLOY_REQUIRED": "false",
            "FRONTEND_REQUIRED": "true", "O_CREDENTIALS": "success", "O_HCP": "success/success/success/success/success"})
env.update(over)
r = subprocess.run(["bash", "-c", step["run"].replace("${{ job.status }}", status)], env=env, capture_output=True, text=True)
print(r.stdout + r.stderr)
sys.exit(r.returncode)
EOF
yq -o=json '.jobs.production.steps[] | select(.name == "Resumen de producción")' "${WORKFLOW}" > "${TMP}/summary-step.json"
SUM="${TMP}/prod-summary.md"
summary() { : > "${SUM}"; python3 "${TMP}/summary.py" "${TMP}/summary-step.json" "${SUM}" "$1" "$2" > "${TMP}/summary.log" 2>&1; }
P='"PRODUCTION_STAGE":"provision","SERVER_ID":"12345","SERVER_IP":"203.0.113.7","STATE_USED":"sv-New1"'
NOT_CLAIMED='PROVISIONED\|TRUST PENDING\|NOT INSTALLED\|INFRASTRUCTURE U'
outcome() { # <descripción> <status> <JSON extra> <título esperado>
  summary "$2" "{${P}${3:+,$3}}"
  check "resumen Run P: $1 → $4" grep -qF "**$4**" "${SUM}"
  check "resumen Run P: $1 → aviso con ese mismo título" grep -qF "::notice title=$4::" "${TMP}/summary.log"
  check "resumen Run P: $1 → un solo titular" test "$(grep -c "${NOT_CLAIMED}" "${SUM}")" = 1
  check "resumen Run P: $1 → expone la identidad del estado exacto" grep -qF 'instancia `12345`, dirección `203.0.113.7` (state version `sv-New1`)' "${SUM}"
  check "resumen Run P: $1 → no afirma último éxito ni publicación" bash -c "! grep -q 'último éxito registrado' '${SUM}' && grep -q 'Run P nunca publica' '${SUM}'"
}
no_claim() { # <descripción> <status> <JSON extra>
  summary "$2" "{${P}${3:+,$3}}"
  check "resumen Run P: $1 → sin ningún resultado de éxito" bash -c "! grep -q '${NOT_CLAIMED}' '${SUM}' '${TMP}/summary.log'"
}
PROV='PROVISIONED / TRUST PENDING / NOT INSTALLED'
UNCHANGED='INFRASTRUCTURE UNCHANGED / TRUST AND INSTALLATION NOT EVALUATED'
UPDATED='INFRASTRUCTURE UPDATED / TRUST AND INSTALLATION NOT EVALUATED'
outcome "Apply que creó el servidor" success '"WATCH_SERVER":"create"' "${PROV}"
check "resumen Run P: con el servidor creado indica el paso fuera de banda" grep -q 'autenticar la clave del host por la consola' "${SUM}"
outcome "Apply que reemplazó el servidor" success '"WATCH_SERVER":"replace"' "${PROV}"
outcome "plan sin cambios" success '"TF_APPLY_REQUIRED":"false","WATCH_SERVER":"no-op"' "${UNCHANGED}"
check "resumen Run P sin cambios: dice que no aplicó ni sabe el estado del servidor" grep -q 'no aplicó nada ni sabe si ese servidor tiene pin' "${SUM}"
outcome "plan sin cambios con JSON ilegible" success '"TF_APPLY_REQUIRED":"false","WATCH_SERVER":"unknown"' "${UNCHANGED}"
outcome "plan sin cambios aunque la acción diga create (no hubo Apply)" success '"TF_APPLY_REQUIRED":"false","WATCH_SERVER":"create"' "${UNCHANGED}"
outcome "recuperación que aplicó sin tocar el servidor" success '"WATCH_SERVER":"no-op"' "${UPDATED}"
outcome "actualización en sitio" success '"WATCH_SERVER":"update"' "${UPDATED}"
outcome "acción ausente con Apply: nunca se presume creación" success '' "${UPDATED}"
no_claim "cancelado tras crear" cancelled '"WATCH_SERVER":"create"'
no_claim "cancelado sin cambios" cancelled '"TF_APPLY_REQUIRED":"false"'
no_claim "fallido sin cambios" failure '"TF_APPLY_REQUIRED":"false"'
no_claim "Apply fallido" failure '"WATCH_SERVER":"create","O_HCP_APPLY":"failure"'
check "resumen Run P con Apply fallido: FALLÓ" grep -q 'FALLÓ' "${SUM}"
no_claim "resultado del Apply fallido" failure '"WATCH_SERVER":"create","O_HCP":"success/success/success/failure/skipped"'
no_claim "preflight de Pages fallido tras el Apply" failure '"WATCH_SERVER":"create","O_PAGES_PREFLIGHT":"failure"'
no_claim "sin outputs del estado" success '"WATCH_SERVER":"create","O_HCP":"success/success/success/success/skipped"'
check "resumen Run I: identidad rechazada" summary failure '{"PRODUCTION_STAGE":"install","TF_APPLY_REQUIRED":"false","O_HOST_TRUST":"success/failure/skipped"}'
check "resumen Run I: categoría propia para la identidad y sin PROVISIONED" bash -c "grep -q 'Identidad SSH del host' '${SUM}' && ! grep -q PROVISIONED '${SUM}'"
check "resumen Run I completo: último éxito registrado" summary success '{"PRODUCTION_STAGE":"install","TF_APPLY_REQUIRED":"false","O_RECORD":"success","O_PAGES_PUBLISH":"success","BACKEND_IMAGE_REF":"b"}'
check "resumen Run I completo: sin PROVISIONED" bash -c "grep -q 'último éxito registrado' '${SUM}' && ! grep -q PROVISIONED '${SUM}'"

# ── 9. Contratos del workflow que no deben relajarse ────────────────────────
wf() { yq -r "$1" "${WORKFLOW}"; }
check "workflow: solo terraform_plan y production usan un environment" \
  test "$(wf '.jobs | to_entries | map(select(.value.environment != null) | .key) | sort | join(",")')" = "production,terraform_plan"
check "workflow: el stage es una elección cerrada con ordinary por defecto" \
  test "$(wf '.on.workflow_dispatch.inputs.stage | .type + "/" + .default + "/" + (.options | join(","))')" = "choice/ordinary/ordinary,provision,install"
check "workflow: no se agregó statuses: write fuera de production" \
  test "$(wf '.jobs | to_entries | map(select(.value.permissions.statuses != null) | .key) | join(",")')" = production
check "workflow: el push diferido no agrega comprobaciones de estado (ningún POST a statuses nuevo)" \
  bash -c "! grep -q 'repos/.*/statuses' '${WORKFLOW}'"
check "workflow: Run P no deja correr nada del servidor (Ansible y SSH solo con los flags efectivos)" \
  bash -c "[ \"\$(yq -r '.jobs.production.env.DEPLOY_REQUIRED' '${WORKFLOW}')\" = \"\\\${{ needs.classify.outputs.stage != 'provision' && needs.classify.outputs.deploy_required == 'true' }}\" ]"
check "workflow: credenciales y contraseña de Keycloak leen la intención, no el flag efectivo" \
  bash -c "for s in credentials kc_admin_preflight; do [ \"\$(yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .env.RUN_HOST_CONFIG\" '${WORKFLOW}')\" = '\${{ needs.classify.outputs.run_host_config }}' ] || exit 1; done"
check "workflow: las evaluaciones previas de Run P siguen con la intención completa (política y build de frontend)" \
  bash -c "yq -r '.jobs.image_policy.if' '${WORKFLOW}' | grep -q \"needs.classify.outputs.deploy_required == 'true'\" && yq -r '.jobs.terraform_plan.if' '${WORKFLOW}' | grep -q \"needs.classify.outputs.deploy_required != 'true'\" && yq -r '.jobs.frontend_build.if' '${WORKFLOW}' | grep -q frontend_required"
check "workflow: la política autoritativa corre en cada run que despliega, también Run I" \
  test "$(yq -r '.jobs.production.steps[] | select(.id == "policy") | .if' "${WORKFLOW}")" = "env.DEPLOY_REQUIRED == 'true'"
check "workflow: hcp_apply rechaza stage=install aunque su condición fallara" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"hcp_apply\") | .run' '${WORKFLOW}' | grep -q 'PRODUCTION_STAGE:-}\" = \"install\"'"
check "workflow: la retención se decide con la salida de classify, no con vars dentro del environment" \
  bash -c "[ \"\$(yq -r '.jobs.classify.outputs.push_mode' '${WORKFLOW}')\" = '\${{ steps.intent.outputs.push_mode }}' ] && [ \"\$(yq -r '.jobs.terraform_plan.steps[] | select(.id == \"stage_gate\") | .env.PUSH_MODE' '${WORKFLOW}')\" = '\${{ needs.classify.outputs.push_mode }}' ] && [ \"\$(yq -r '.jobs.classify.environment // \"none\"' '${WORKFLOW}')\" = none ] && ! yq -r '.jobs.terraform_plan' '${WORKFLOW}' | grep -q 'vars.PRODUCTION_PUSH_DEPLOY'"
check "workflow: la variable de push se lee solo para el título, classify y la última barrera antes del Apply" \
  bash -c "jobs=''; for j in \$(yq -r '.jobs | keys | .[]' '${WORKFLOW}'); do yq -r \".jobs.\$j\" '${WORKFLOW}' | grep -qF '\${{ vars.PRODUCTION_PUSH_DEPLOY' && jobs=\"\$jobs\$j,\"; done; [ \"\$jobs\" = classify,production, ] && [ \"\$(yq -r '.jobs.production.steps[] | select(.env.PUSH_DEPLOY_NOW != null) | .id' '${WORKFLOW}' | paste -sd,)\" = kc_admin_preflight ]"
check "workflow: la retención solo lee: ningún paso escribe variables ni usa un secreto nuevo para ella" \
  bash -c "! yq -r '.jobs[].steps[].run // \"\"' '${WORKFLOW}' | grep -Eq 'gh variable|actions/variables' && [ \"\$(yq -r '.jobs.terraform_plan' '${WORKFLOW}' | grep -o 'secrets\.[A-Z_]*' | sort -u | paste -sd,)\" = secrets.TF_API_TOKEN ] && [ \"\$(yq -r '.jobs.classify' '${WORKFLOW}' | grep -c 'secrets\.' || true)\" = 0 ]"
check "workflow: el pin solo se lee en classify (existencia) y en los dos pasos de production" \
  bash -c "[ \"\$(grep -c 'vars.PRODUCTION_SSH_HOST_PIN' '${WORKFLOW}')\" = 3 ] && [ \"\$(yq -r '.jobs.production.steps[] | select(.env.EXPECTED_HOST_PIN != null) | .id' '${WORKFLOW}' | paste -sd,)\" = pin_preflight,host_trust ] && [ \"\$(yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .env.PIN_OUTSIDE_ENVIRONMENT' '${WORKFLOW}')\" = \"\\\${{ vars.PRODUCTION_SSH_HOST_PIN != '' }}\" ]"
check "workflow: el pin no tiene alternativa (sin || en su lectura) y classify no tiene environment" \
  bash -c "! grep -E 'PRODUCTION_SSH_HOST_PIN *\\|\\||\\|\\| *vars\\.PRODUCTION_SSH_HOST_PIN|PRODUCTION_SSH_HOST_PIN_' '${WORKFLOW}' && [ \"\$(yq -r '.jobs.classify.environment // \"none\"' '${WORKFLOW}')\" = none ]"
check "workflow: ningún workflow ni script escribe variables de GitHub" \
  bash -c "! grep -rEq 'gh variable|actions/variables|/variables/' '${ROOT}/.github/workflows' '${ROOT}/.github/scripts/host-trust.sh' '${ROOT}/.github/scripts/production-stage.sh' '${ROOT}/.github/scripts/production-range-base.sh'"
check "workflow: un push ordinario no expone el input de etapa (solo dispatch lo lee)" \
  bash -c "yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .run' '${WORKFLOW}' | grep -q 'STAGE=\"\${IN_STAGE:-ordinary}\"' && yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .run' '${WORKFLOW}' | grep -qx ' *echo \"stage=ordinary\"'"

echo
if [ "${FAILS}" -ne 0 ]; then
  echo "${FAILS} fallo(s)"
  exit 1
fi
echo "Todo pasó"
