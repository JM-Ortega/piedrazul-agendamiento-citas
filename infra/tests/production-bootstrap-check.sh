#!/usr/bin/env bash
# Instalación nueva y actualizaciones de la cadena de producción: workspace, estado vacío,
# plan revisado y confirmado por una persona en HCP, limpieza del run propio
# (hcp-state.sh) y el camino que recorre el job `production` de production-hetzner.yml en
# cada escenario (instalación nueva, solo frontend, solo backend, solo Terraform, plan sin
# cambios, fallos, cancelación).
#
# Sin red, sin HCP Terraform y sin GitHub: `curl` es falso (sirve respuestas registradas con
# su código HTTP y anota método, URL y cuerpo de cada llamada). El camino del workflow se
# obtiene evaluando las condiciones `if` reales de sus pasos con un evaluador mínimo del
# subconjunto de expresiones que usan. Nada de esto prueba el comportamiento real de HCP,
# Cloudflare ni de GitHub Actions: eso queda para la verificación formal contra el entorno.
#
# Uso: infra/tests/production-bootstrap-check.sh
# Requiere: jq, yq (mikefarah v4), python3
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HCP="${ROOT}/.github/scripts/hcp-state.sh"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"

for bin in jq yq python3 sha256sum; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap '[ -n "${KEEP_TMP:-}" ] || rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
fails() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ko "${desc} (no falló)"; else ok "${desc}"; fi; }
key() { printf '%s' "$1" | sha256sum | cut -c1-32; }

# ── curl falso de HCP ───────────────────────────────────────────────────────
# Respuestas por "<MÉTODO> <URL>": archivos <clave>.1, .2, … (la última se repite), con una
# primera línea opcional "@status NNN" (si no, 200). Anota "<MÉTODO> <URL>" y guarda el
# cuerpo de cada POST. Sin respuesta registrada: con -w imprime 000 y sale 7; sin -w sale 22.
mkdir -p "${TMP}/bin" "${TMP}/hcp" "${TMP}/posts"
export FAKE_HCP_DIR="${TMP}/hcp" FAKE_HCP_LOG="${TMP}/hcp.log" FAKE_HCP_POSTS="${TMP}/posts"
cat > "${TMP}/bin/curl" << 'EOF'
#!/usr/bin/env bash
url="" out="" fmt="" failflag="" method=GET data="" auth=""
while [ $# -gt 0 ]; do
  case "$1" in
    -H) [[ "$2" == @* ]] && auth=$(cat "${2#@}"); shift 2 ;;
    -o) out="$2"; shift 2 ;;
    -w) fmt="$2"; shift 2 ;;
    -X) method="$2"; shift 2 ;;
    --data-binary) data="${2#@}"; shift 2 ;;
    --max-time) shift 2 ;;
    -sSf | -sSfL | -f | --fail) failflag=1; shift ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s %s|%s\n' "${method}" "${url}" "${auth}" >> "${FAKE_HCP_LOG}"
if [ -n "${data}" ]; then
  n=$(ls "${FAKE_HCP_POSTS}" | wc -l)
  cp "${data}" "${FAKE_HCP_POSTS}/$((n + 1))-$(printf '%s' "${url##*/}")"
fi
k="${FAKE_HCP_DIR:?}/$(printf '%s' "${method} ${url}" | sha256sum | cut -c1-32)"
f=$(ls "${k}".* 2> /dev/null | sort -V | head -1)
if [ -z "${f}" ]; then
  [ -z "${fmt}" ] || { printf '000'; exit 7; }
  exit 22
fi
code=200 body="${f}"
if head -1 "${f}" | grep -qE '^@status [0-9]{3}$'; then
  code=$(head -1 "${f}" | cut -d' ' -f2)
  body=$(mktemp); tail -n +2 "${f}" > "${body}"
fi
if [ -n "${out}" ]; then cat "${body}" > "${out}"; else cat "${body}"; fi
[ "$(ls "${k}".* | wc -l)" -le 1 ] || rm -f -- "${f:?}"
[ -z "${fmt}" ] || printf '%s' "${code}"
if [ -n "${failflag}" ] && [ "${code}" -ge 400 ]; then exit 22; fi
exit 0
EOF
chmod +x "${TMP}/bin/curl"

API=https://hcp.test/api/v2
WS=ws-AbC123
ORG=Piedrazul
NAME=piedrazul-hetzner
RUN=run-Own1
CV="cv-Up1"
PLAN=plan-P1
APPLY=apply-A1
MSG="Producción $(printf 'a%.0s' {1..40}) — run 77/1"
STATE_LIST_URL="${API}/state-versions?filter%5Bworkspace%5D%5Bname%5D=${NAME}&filter%5Borganization%5D%5Bname%5D=${ORG}&page%5Bsize%5D=1"

hcp_clear() { find "${FAKE_HCP_DIR:?}" -maxdepth 1 -name "$(key "$1").*" -delete; }
# hcp_seq "<MÉTODO> <URL>" <respuesta>...
hcp_seq() {
  local k="$1" n=1
  shift
  hcp_clear "${k}"
  for r in "$@"; do printf '%s' "${r}" > "${FAKE_HCP_DIR:?}/$(key "${k}").${n}"; n=$((n + 1)); done
}
reset_hcp() { rm -f "${FAKE_HCP_DIR:?}"/* "${FAKE_HCP_POSTS:?}"/*; : > "${FAKE_HCP_LOG}"; }
status() { # <código> [cuerpo]
  local body
  if [ "$#" -ge 2 ]; then body="$2"; else body="{\"errors\":[{\"status\":\"$1\",\"title\":\"x\"}]}"; fi
  printf '@status %s\n%s' "$1" "${body}"
}

# workspace_json [jq de ajuste]
workspace_json() {
  jq -cn --arg id "${WS}" --arg n "${NAME}" --arg o "${ORG}" '
    {data: {id: $id, type: "workspaces",
      attributes: {name: $n, "execution-mode": "remote", "auto-apply": false, "auto-apply-run-trigger": false,
        permissions: {"can-read-state-versions": true, "can-queue-run": true}},
      relationships: {organization: {data: {id: $o, type: "organizations"}}}}}' | jq -c "${1:-.}"
}
# run_json <status> [has-changes] [jq de ajuste]
run_json() {
  jq -cn --arg s "$1" --argjson hc "${2:-true}" --arg id "${RUN}" --arg ws "${WS}" --arg cv "${CV}" --arg m "${MSG}" \
    --arg plan "${PLAN}" --arg apply "${APPLY}" '
    def confirmable: $s | IN("planned", "cost_estimated", "policy_checked", "post_plan_completed");
    {data: {id: $id, type: "runs",
      attributes: {status: $s, message: $m, "has-changes": $hc, "auto-apply": false, "plan-only": false, "is-destroy": false,
        "status-timestamps": {},
        actions: {"is-confirmable": confirmable, "is-discardable": (confirmable or $s == "pending"),
                  "is-cancelable": ($s | IN("pending", "plan_queued", "planning", "applying"))}},
      relationships: {workspace: {data: {id: $ws}}, "configuration-version": {data: {id: $cv}},
        plan: {data: {id: $plan}}, apply: {data: (if $s == "applied" then {id: $apply} else null end)}}}}' |
    jq -c "${3:-.}"
}
plan_json() { # [resource-additions]
  jq -cn --arg id "${PLAN}" --argjson add "${1:-9}" '{data: {id: $id, attributes: {status: "finished", "resource-additions": $add, "resource-changes": 0, "resource-destructions": 0, "resource-imports": 0}}}'
}
plan_output() { # <acción del proyecto de Pages: create|none> [acción de la asociación del dominio: create|none]
  jq -cn --arg a "$1" --arg d "${2:-$1}" '{resource_changes: ([
      {address: "module.server.hcloud_server.main", change: {actions: ["create"]}},
      {address: "module.dns.cloudflare_dns_record.frontend", change: {actions: ["create"]}},
      {address: "module.zone_settings.cloudflare_zone_setting.ssl", change: {actions: ["no-op"]}}]
    + (if $a == "create" then [{address: "module.pages.cloudflare_pages_project.main", change: {actions: ["create"]}}] else [] end)
    + (if $d == "create" then [{address: "module.pages.cloudflare_pages_domain.main", change: {actions: ["create"]}}] else [] end))}'
}
# Frescura simulada: siempre fresca / siempre vieja
cat > "${TMP}/bin/fresh-yes" << 'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "${TMP}/bin/fresh-no" << 'EOF'
#!/usr/bin/env bash
echo "main avanzó (simulado)" >&2
exit 1
EOF
chmod +x "${TMP}/bin/fresh-yes" "${TMP}/bin/fresh-no"

hcp_env() {
  env PATH="${TMP}/bin:${PATH}" TF_API_TOKEN=tok-hcp-SECRET HCP_WORKSPACE_ID="${WS}" HCP_API_URL="${API}" \
    TF_CLOUD_ORGANIZATION="${ORG}" TF_WORKSPACE="${NAME}" RUN_MESSAGE="${MSG}" \
    HCP_POLL_INTERVAL=0 HCP_POLL_ATTEMPTS=3 HCP_CONFIRM_ATTEMPTS=3 HCP_OUTPUTS_INTERVAL=0 "$@"
}
HCP_DEF="$(declare -f hcp_env); TMP='${TMP}' WS='${WS}' API='${API}' ORG='${ORG}' NAME='${NAME}' MSG='${MSG}'"
run_hcp() { bash -c "${HCP_DEF}; hcp_env $(printf '%q ' "$@")"; }
posted() { grep -c "^POST ${API}$1|" "${FAKE_HCP_LOG}" || true; }

# ── 1. Workspace: identidad, permisos y auto-apply ──────────────────────────
reset_hcp
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
check "workspace apply: workspace declarado, remoto, sin auto-apply y con permiso de encolar runs" \
  bash -c "$(declare -f run_hcp); HCP_DEF=\$1; out=\$(run_hcp '${HCP}' workspace apply) && grep -qx workspace_name=${NAME} <<< \"\$out\" && grep -qx auto_apply=false <<< \"\$out\"" _ "${HCP_DEF}"
check "workspace: el token va en una cabecera, nunca en la línea de comandos" \
  bash -c "grep -q '|Authorization: Bearer tok-hcp-SECRET$' '${FAKE_HCP_LOG}' && ! cut -d'|' -f1 '${FAKE_HCP_LOG}' | grep -q SECRET"
ws_fails() { # <descripción> <modo> <respuesta>
  hcp_seq "GET ${API}/workspaces/${WS}" "$3"
  if run_hcp "${HCP}" workspace "$2" > /dev/null 2>&1; then ko "workspace rechaza: $1 (no falló)"; else ok "workspace rechaza: $1"; fi
}
ws_fails "auto-apply habilitado (un plan se aplicaría sin revisión)" apply "$(workspace_json '.data.attributes["auto-apply"] = true')"
ws_fails "auto-apply-run-trigger habilitado" apply "$(workspace_json '.data.attributes["auto-apply-run-trigger"] = true')"
ws_fails "token sin permiso de encolar runs" apply "$(workspace_json '.data.attributes.permissions["can-queue-run"] = false')"
ws_fails "otro workspace con ese id (nombre distinto)" read "$(workspace_json '.data.attributes.name = "otro"')"
ws_fails "otra organización" read "$(workspace_json '.data.relationships.organization.data.id = "Otra"')"
ws_fails "ejecución local (sin runs remotos)" read "$(workspace_json '.data.attributes["execution-mode"] = "local"')"
ws_fails "token sin permiso de leer el estado" read "$(workspace_json '.data.attributes.permissions["can-read-state-versions"] = false')"
ws_fails "token inválido (HTTP 401)" read "$(status 401)"
ws_fails "workspace inexistente o sin acceso (HTTP 404)" read "$(status 404)"
ws_fails "respuesta ilegible" read '<html>'
hcp_clear "GET ${API}/workspaces/${WS}"
fails "workspace rechaza: sin respuesta de HCP" run_hcp "${HCP}" workspace read
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json '.data.attributes["auto-apply"] = true')"
check "workspace en modo lectura (deploy sin Apply) no exige auto-apply deshabilitado" \
  run_hcp "${HCP}" workspace read

# ── 2. Estado previo: vacío solo si HCP lo confirma ─────────────────────────
reset_hcp
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" '{"data":{"id":"sv-Old1","type":"state-versions"}}'
check "current: workspace con estado → su state version" \
  bash -c "[ \"\$(eval \"\$0\")\" = sv-Old1 ]" "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' current")"
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"
hcp_seq "GET ${STATE_LIST_URL}" '{"data":[]}'
check "current: 404 y lista de state versions vacía → none (instalación nueva)" \
  bash -c "[ \"\$(eval \"\$0\")\" = none ]" "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' current")"
hcp_seq "GET ${STATE_LIST_URL}" '{"data":[{"id":"sv-X"}]}'
fails "current: 404 pero el workspace tiene state versions → falla (contradicción)" run_hcp "${HCP}" current
hcp_seq "GET ${STATE_LIST_URL}" "$(status 403)"
fails "current: 404 y la lista responde 403 → falla, nunca 'vacío'" run_hcp "${HCP}" current
hcp_seq "GET ${STATE_LIST_URL}" '{"data":"x"}'
fails "current: 404 y lista malformada → falla" run_hcp "${HCP}" current
hcp_seq "GET ${STATE_LIST_URL}" '{"data":[]}'
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 500)"
fails "current: error de HCP (HTTP 500) → falla" run_hcp "${HCP}" current
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 401)"
fails "current: token inválido al leer el estado → falla" run_hcp "${HCP}" current
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"
hcp_seq "GET ${API}/workspaces/${WS}" "$(status 404)"
fails "current: workspace inaccesible (404) aunque el estado también dé 404 → falla" run_hcp "${HCP}" current
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json '.data.attributes.permissions["can-read-state-versions"] = false')"
fails "current: sin permiso de leer el estado, un 404 no se lee como vacío" run_hcp "${HCP}" current
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" '{"data":{"id":"no-es-sv"}}'
fails "current: id de state version malformado → falla" run_hcp "${HCP}" current

# ── 3. Crear el run: aplicable y con auto-apply=false explícito ─────────────
reset_hcp
hcp_seq "GET ${API}/configuration-versions/${CV}" '{"data":{"id":"cv-Up1","attributes":{"status":"uploaded","speculative":false}}}'
hcp_seq "POST ${API}/runs" "$(status 201 "$(run_json pending)")"
check "create-run: run propio creado y descrito (id y enlace)" \
  bash -c "out=\$(eval \"\$0\") && grep -qx run_id=${RUN} <<< \"\$out\" && grep -q '^run_link=https://app.terraform.io/app/${ORG}/workspaces/${NAME}/runs/${RUN}$' <<< \"\$out\"" "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' create-run '${CV}'")"
check "create-run: pide auto-apply=false, no speculative, no destroy, no saved plan, con esta configuración y el mensaje propio" \
  jq -e --arg m "${MSG}" --arg ws "${WS}" --arg cv "${CV}" '.data.attributes["auto-apply"] == false and .data.attributes["plan-only"] == false and .data.attributes["is-destroy"] == false and .data.attributes["save-plan"] == false and .data.attributes.message == $m and .data.relationships.workspace.data.id == $ws and .data.relationships["configuration-version"].data.id == $cv' "$(ls "${FAKE_HCP_POSTS}"/*-runs | head -1)"
create_fails() { # <descripción> <respuesta del POST> [respuesta de la configuración]
  local cv='{"data":{"attributes":{"status":"uploaded","speculative":false}}}'
  [ "$#" -lt 3 ] || cv="$3"
  hcp_seq "GET ${API}/configuration-versions/${CV}" "${cv}"
  hcp_seq "POST ${API}/runs" "$2"
  if run_hcp "${HCP}" create-run "${CV}" > /dev/null 2>&1; then ko "create-run rechaza: $1 (no falló)"; else ok "create-run rechaza: $1"; fi
}
create_fails "HCP registró el run con auto-apply=true" "$(status 201 "$(run_json pending true '.data.attributes["auto-apply"] = true')")"
create_fails "run de otro workspace" "$(status 201 "$(run_json pending true '.data.relationships.workspace.data.id = "ws-Otro"')")"
create_fails "run con otra configuración" "$(status 201 "$(run_json pending true '.data.relationships["configuration-version"].data.id = "cv-Otra"')")"
create_fails "run con otro mensaje (no es el propio)" "$(status 201 "$(run_json pending true '.data.attributes.message = "otro"')")"
create_fails "HCP rechaza la creación (HTTP 422)" "$(status 422)"
create_fails "configuración no subida" "$(status 201 "$(run_json pending)")" '{"data":{"attributes":{"status":"pending","speculative":false}}}'
create_fails "configuración speculative" "$(status 201 "$(run_json pending)")" '{"data":{"attributes":{"status":"uploaded","speculative":true}}}'

# ── 4. Plan: con cambios espera confirmación; sin cambios, nada que confirmar ──
plan_setup() { # <respuestas del run>...
  reset_hcp
  hcp_seq "GET ${API}/runs/${RUN}" "$@"
  hcp_seq "GET ${API}/plans/${PLAN}" "$(plan_json)"
  hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(plan_output create)"
}
WATCH='pages_project=module.pages.cloudflare_pages_project.main pages_domain=module.pages.cloudflare_pages_domain.main'
plan_cmd() { # [FRESHNESS_CMD]
  printf '%q ' bash -c "${HCP_DEF}; hcp_env PLAN_WATCH='${WATCH}' FRESHNESS_CMD='${1:-}' PLAN_CHANGES_FILE='${TMP}/changes.txt' '${HCP}' plan '${RUN}'"
}
PLAN_CMD="$(plan_cmd)"
plan_setup "$(run_json planning)" "$(run_json planned)"
check "plan: con cambios → needs_confirmation=true, sin confirmar, con conteos, enlace y acción sobre Pages" \
  bash -c "out=\$(eval \"\$0\") && grep -qx needs_confirmation=true <<< \"\$out\" && grep -qx already_confirmed=false <<< \"\$out\" && grep -qx additions=9 <<< \"\$out\" && grep -qx destructions=0 <<< \"\$out\" && grep -qx watch_pages_project=create <<< \"\$out\" && grep -qx watch_pages_domain=create <<< \"\$out\" && grep -qx plan_id=${PLAN} <<< \"\$out\"" "${PLAN_CMD}"
check "plan: lista los cambios para la revisión (sin no-op)" \
  bash -c "grep -qx 'create module.server.hcloud_server.main' '${TMP}/changes.txt' && ! grep -q zone_setting '${TMP}/changes.txt'"
check "plan: nunca confirma ni descarta (solo lecturas)" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
plan_setup "$(run_json planning)" "$(run_json planned)"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(plan_output none)"
check "plan: un plan que no crea el proyecto de Pages lo informa (watch_pages_project=none)" \
  bash -c "eval \"\$0\" | grep -qx watch_pages_project=none" "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(plan_output none create)"
check "plan: proyecto existente y dominio por crear (Apply parcial anterior) → watch_pages_project=none, watch_pages_domain=create" \
  bash -c "out=\$(eval \"\$0\") && grep -qx watch_pages_project=none <<< \"\$out\" && grep -qx watch_pages_domain=create <<< \"\$out\"" "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(status 403)"
check "plan: JSON del plan ilegible para el token → unknown en cada recurso vigilado (nunca una creación inferida)" \
  bash -c "out=\$(eval \"\$0\") && grep -qx watch_pages_project=unknown <<< \"\$out\" && grep -qx watch_pages_domain=unknown <<< \"\$out\" && grep -q 'revisar el plan en HCP' '${TMP}/changes.txt'" "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" '{"resource_changes":[{"address":"module.pages.cloudflare_pages_project.main","change":{}}]}'
check "plan: JSON del plan malformado (sin acciones) → unknown" \
  bash -c "eval \"\$0\" | grep -qx watch_pages_project=unknown" "${PLAN_CMD}"
hcp_clear "GET ${API}/plans/${PLAN}/json-output"
check "plan: sin JSON del plan (sin respuesta) → unknown" \
  bash -c "eval \"\$0\" | grep -qx watch_pages_project=unknown" "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(plan_output create | jq -c '.resource_changes += [{address: "module.pages.cloudflare_pages_project.main", change: {actions: ["delete"]}}]')"
check "plan: dirección vigilada repetida → ambiguous" \
  bash -c "eval \"\$0\" | grep -qx watch_pages_project=ambiguous" "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(plan_output create)"
hcp_seq "GET ${API}/plans/${PLAN}" "$(plan_json 0)"
fails "plan rechaza: el JSON muestra creaciones pero el plan informa 0 recursos a crear (contradicción)" bash -c "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}" "$(plan_json)"
plan_setup "$(run_json planned)"
fails "plan: PLAN_WATCH malformado → falla" run_hcp env PLAN_WATCH=sin-igual "${HCP}" plan "${RUN}"
plan_setup "$(run_json planned)"
check "plan: PLAN_WATCH bien formado con los mismos datos → pasa (control del caso anterior)" \
  run_hcp env PLAN_WATCH=pages_project=module.pages.cloudflare_pages_project.main "${HCP}" plan "${RUN}"

# Frescura en el plan: mientras nada mutó, un commit viejo no sigue; un Apply ya confirmado sí
fresh_plan_setup() { plan_setup "$@"; hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 202 '')"; }
fresh_plan_setup "$(run_json planned)"
fails "plan + frescura: main avanzó y el plan espera confirmación → falla" bash -c "$(plan_cmd "${TMP}/bin/fresh-no")"
check "plan + frescura: ese run se descarta (sin Apply)" test "$(posted "/runs/${RUN}/actions/discard")" = 1
fresh_plan_setup "$(run_json planned_and_finished false)"
fails "plan + frescura: main avanzó y el plan no tiene cambios → la cadena no sigue con un commit viejo" bash -c "$(plan_cmd "${TMP}/bin/fresh-no")"
check "plan + frescura: plan sin cambios → nada que descartar" test "$(posted "/runs/${RUN}/actions/discard")" = 0
fresh_plan_setup "$(run_json apply_queued)"
check "plan + frescura: ya confirmado en HCP y main avanzó → sigue (el Apply empezó) con confirmed_while_stale=true" \
  bash -c "out=\$(eval \"\$0\") && grep -qx already_confirmed=true <<< \"\$out\" && grep -qx confirmed_while_stale=true <<< \"\$out\"" "$(plan_cmd "${TMP}/bin/fresh-no")"
check "plan + frescura: un Apply ya confirmado nunca se descarta ni cancela" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
fresh_plan_setup "$(run_json planned)"
check "plan + frescura: commit vigente → confirmed_while_stale=false, sin descarte" \
  bash -c "out=\$(eval \"\$0\") && grep -qx confirmed_while_stale=false <<< \"\$out\" && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(plan_cmd "${TMP}/bin/fresh-yes")"
plan_setup "$(run_json planning false)" "$(run_json planned_and_finished false)"
check "plan: sin cambios (planned_and_finished) → needs_confirmation=false" \
  bash -c "eval \"\$0\" | grep -qx needs_confirmation=false" "${PLAN_CMD}"
plan_setup "$(run_json cost_estimated)"
check "plan: confirmable tras estimar costos → needs_confirmation=true" bash -c "eval \"\$0\" | grep -qx needs_confirmation=true" "${PLAN_CMD}"
plan_setup "$(run_json apply_queued)"
check "plan: ya confirmado en HCP antes de leerlo (auto-apply=false) → already_confirmed=true" \
  bash -c "eval \"\$0\" | grep -qx already_confirmed=true" "${PLAN_CMD}"
plan_fails() { # <descripción> <respuestas del run>...
  local d="$1"; shift
  plan_setup "$@"
  if bash -c "${PLAN_CMD}" > /dev/null 2>&1; then ko "plan rechaza: ${d} (no falló)"; else ok "plan rechaza: ${d}"; fi
}
plan_fails "plan fallido (errored)" "$(run_json planning)" "$(run_json errored)"
plan_fails "run descartado antes de terminar el plan" "$(run_json discarded)"
plan_fails "run cancelado" "$(run_json canceled)"
plan_fails "override de políticas pendiente" "$(run_json policy_override)"
plan_fails "decisión de run tasks pendiente" "$(run_json post_plan_awaiting_decision)"
plan_fails "saved plan" "$(run_json planned_and_saved)"
plan_fails "estado desconocido" "$(run_json weird_state)"
plan_fails "no termina dentro del plazo" "$(run_json planning)"
plan_fails "sin cambios pero informa cambios (contradicción)" "$(run_json planned_and_finished true)"
plan_fails "espera confirmación sin cambios (contradicción)" "$(run_json planned false)"
plan_fails "run ajeno (otro mensaje)" "$(run_json planned true '.data.attributes.message = "otro"')"
plan_fails "run con auto-apply=true" "$(run_json planned true '.data.attributes["auto-apply"] = true')"
plan_fails "run de otro workspace" "$(run_json planned true '.data.relationships.workspace.data.id = "ws-Otro"')"
plan_setup "$(run_json planned)"
hcp_seq "GET ${API}/plans/${PLAN}" '{"data":{"attributes":{"status":"running"}}}'
fails "plan rechaza: el plan del run no terminó" bash -c "${PLAN_CMD}"

# ── 5. Confirmación humana: espera, rechazo, plazo, frescura ────────────────
COUNTER="${TMP}/fresh-count"
cat > "${TMP}/bin/fresh-ok" << 'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "${TMP}/bin/fresh-advances" << EOF
#!/usr/bin/env bash
# main avanza en la segunda comprobación
n=\$(( \$(cat '${COUNTER}' 2> /dev/null || echo 0) + 1 )); echo "\$n" > '${COUNTER}'
[ "\$n" -lt 2 ]
EOF
chmod +x "${TMP}/bin/fresh-ok" "${TMP}/bin/fresh-advances"
confirm_cmd() { printf '%q ' bash -c "${HCP_DEF}; hcp_env FRESHNESS_CMD='$1' '${HCP}' await-confirm '${RUN}'"; }
confirm_setup() { reset_hcp; hcp_seq "GET ${API}/runs/${RUN}" "$@"; hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 202 '')"; }

confirm_setup "$(run_json planned)" "$(run_json planned)" "$(run_json applying)"
check "await-confirm: espera mientras está planned y termina cuando una persona confirma (applying)" \
  bash -c "eval \"\$0\" | grep -qx confirmed=true" "$(confirm_cmd "${TMP}/bin/fresh-ok")"
check "await-confirm: confirmado no descarta nada" test "$(posted "/runs/${RUN}/actions/discard")" = 0
confirm_setup "$(run_json planned)" "$(run_json applied)"
check "await-confirm: confirmado y ya aplicado → confirmed=true" bash -c "eval \"\$0\" | grep -qx confirmed=true" "$(confirm_cmd "${TMP}/bin/fresh-ok")"
confirm_setup "$(run_json planned)" "$(run_json applying)"
rm -f "${COUNTER}"
check "await-confirm: confirmado en HCP en la ventana en que main avanzó → confirmed=true y confirmed_while_stale=true, sin descarte" \
  bash -c "out=\$(eval \"\$0\") && grep -qx confirmed=true <<< \"\$out\" && grep -qx confirmed_while_stale=true <<< \"\$out\" && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(confirm_cmd "${TMP}/bin/fresh-advances")"
confirm_setup "$(run_json planned)" "$(run_json applying)"
check "await-confirm: confirmado con main vigente → confirmed_while_stale=false" \
  bash -c "eval \"\$0\" | grep -qx confirmed_while_stale=false" "$(confirm_cmd "${TMP}/bin/fresh-ok")"
confirm_setup "$(run_json planned)" "$(run_json discarded)"
fails "await-confirm: plan rechazado (descartado) en HCP → falla sin Apply" bash -c "$(confirm_cmd "${TMP}/bin/fresh-ok")"
confirm_setup "$(run_json planned)" "$(run_json errored)"
fails "await-confirm: run fallido mientras espera → falla" bash -c "$(confirm_cmd "${TMP}/bin/fresh-ok")"
confirm_setup "$(run_json planned)" "$(run_json planning)"
fails "await-confirm: transición inesperada (vuelve a planning) → falla" bash -c "$(confirm_cmd "${TMP}/bin/fresh-ok")"
confirm_setup "$(run_json planned)"
fails "await-confirm: sin confirmación dentro del plazo → falla" bash -c "$(confirm_cmd "${TMP}/bin/fresh-ok")"
check "await-confirm: al vencer el plazo descarta el run propio (una vez)" test "$(posted "/runs/${RUN}/actions/discard")" = 1
check "await-confirm: el descarte lleva un comentario" bash -c "jq -e '.comment | test(\"plazo\")' \"\$(ls '${FAKE_HCP_POSTS}'/*-discard | tail -1)\""
confirm_setup "$(run_json planned)"
rm -f "${COUNTER}"
# Plazo amplio: el descarte debe venir de la frescura (2.ª comprobación), no del vencimiento
if bash -c "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env HCP_CONFIRM_ATTEMPTS=50 FRESHNESS_CMD='${TMP}/bin/fresh-advances' '${HCP}' await-confirm '${RUN}'")" > /dev/null 2> "${TMP}/fresh.err"; then
  ko "await-confirm: main avanza mientras espera → falla (no falló)"
else
  ok "await-confirm: main avanza mientras espera → falla"
fi
check "await-confirm: main avanzó → descarta el run propio en esa comprobación (no por vencimiento), sin Apply" \
  bash -c "[ \"\$(grep -c '^POST ${API}/runs/${RUN}/actions/discard|' '${FAKE_HCP_LOG}')\" = 1 ] && grep -q 'main avanzó' '${TMP}/fresh.err' && [ \"\$(grep -c '^GET ${API}/runs/${RUN}|' '${FAKE_HCP_LOG}')\" -le 4 ] && jq -e '.comment | test(\"main avanzó\")' \"\$(ls '${FAKE_HCP_POSTS}'/*-discard | tail -1)\""
confirm_setup "$(run_json planned true '.data.attributes.message = "otro"')"
fails "await-confirm: run ajeno → falla" bash -c "$(confirm_cmd "${TMP}/bin/fresh-ok")"
check "await-confirm: un run ajeno nunca se descarta" test "$(posted "/runs/${RUN}/actions/discard")" = 0
confirm_setup "$(run_json planned)"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 409)"
fails "await-confirm: plazo vencido y HCP rechaza el descarte → falla igual (y lo informa)" bash -c "$(confirm_cmd "${TMP}/bin/fresh-ok")"

# ── 6. Limpieza del run propio (fallo, cancelación o vencimiento del job) ──
cleanup_cmd() { printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' cleanup '${RUN}'"; }
cleanup_case() { # <descripción> <salida esperada> <descartes esperados> <cancelaciones esperadas> <respuestas del run>...
  local d="$1" exp="$2" nd="$3" nc="$4" out rc=0
  shift 4
  confirm_setup "$@"
  hcp_seq "POST ${API}/runs/${RUN}/actions/cancel" "$(status 202 '')"
  out=$(bash -c "$(cleanup_cmd)" 2> /dev/null) || rc=$?
  if [ "${rc}" -eq 0 ] && grep -qx "cleanup=${exp}" <<< "${out}" &&
     [ "$(posted "/runs/${RUN}/actions/discard")" = "${nd}" ] && [ "$(posted "/runs/${RUN}/actions/cancel")" = "${nc}" ]; then
    ok "cleanup: ${d}"
  else
    ko "cleanup: ${d} (rc=${rc}; ${out//$'\n'/ })"
  fi
}
cleanup_case "esperando confirmación → descarta" discarded 1 0 "$(run_json planned)"
cleanup_case "planificando → cancela el plan" canceled 0 1 "$(run_json planning)"
cleanup_case "confirmado/aplicando → no lo toca (apply_accepted): cancelar el job no deshace el Apply" apply_accepted 0 0 "$(run_json applying)"
cleanup_case "aplicado → apply_accepted, sin acciones" apply_accepted 0 0 "$(run_json applied)"
cleanup_case "sin cambios (planned_and_finished) → none" none 0 0 "$(run_json planned_and_finished false)"
cleanup_case "ya descartado → none" none 0 0 "$(run_json discarded)"
# Carrera: alguien confirma entre la lectura y el descarte
confirm_setup "$(run_json planned)" "$(run_json planned)" "$(run_json applying)"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 409)"
check "cleanup: confirmado durante el descarte (409) → apply_accepted, sin fingir descarte" \
  bash -c "eval \"\$0\" | grep -qx cleanup=apply_accepted" "$(cleanup_cmd)"
confirm_setup "$(run_json planned true '.data.attributes.message = "otro"')"
fails "cleanup: run ajeno → falla sin tocarlo" bash -c "$(cleanup_cmd)"
check "cleanup: run ajeno nunca se descarta ni cancela" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
confirm_setup "$(run_json planning true '.data.attributes.message = "otro"')"
hcp_seq "POST ${API}/runs/${RUN}/actions/cancel" "$(status 202 '')"
fails "cleanup: run ajeno planificando → falla sin cancelarlo" bash -c "$(cleanup_cmd)"
check "cleanup: un plan ajeno en curso nunca se cancela" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
confirm_setup "$(run_json planned)"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 500)"
fails "cleanup: HCP no acepta el descarte y el run sigue esperando → falla (visible)" bash -c "$(cleanup_cmd)"

# Sin run_id: GitHub no registró el run (la creación falló o se cortó tras el POST)
RUNS_URL="${API}/workspaces/${WS}/runs?page%5Bsize%5D=20"
runs_list() { # <run JSON (data)>...
  jq -cn '{data: [$ARGS.positional[] | fromjson | .data]}' --args "$@"
}
cleanup_noid() { printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' cleanup"; }
FOREIGN="$(run_json planned true '.data.id = "run-Other9" | .data.attributes.message = "otro"')"
confirm_setup "$(run_json planned)"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "${FOREIGN}" "$(run_json planned)")"
check "cleanup sin run_id: encuentra el run propio por su mensaje y lo descarta; el ajeno no se toca" \
  bash -c "out=\$(eval \"\$0\") && grep -qx cleanup=discarded <<< \"\$out\" && grep -qx run_ids=${RUN} <<< \"\$out\" && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}')\" = 1 ] && grep -q '^POST ${API}/runs/${RUN}/actions/discard|' '${FAKE_HCP_LOG}'" "$(cleanup_noid)"
reset_hcp
hcp_seq "GET ${RUNS_URL}" "$(runs_list "${FOREIGN}")"
check "cleanup sin run_id: ningún run con el mensaje de este intento (HCP no lo creó) → none, sin acciones" \
  bash -c "eval \"\$0\" | grep -qx cleanup=none && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(cleanup_noid)"
hcp_seq "GET ${RUNS_URL}" "$(status 500)"
fails "cleanup sin run_id: la lista de runs falla → falla visible (procedimiento manual)" bash -c "$(cleanup_noid)"
confirm_setup "$(run_json applying)"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json applying)")"
check "cleanup sin run_id: run propio ya confirmado → apply_accepted, sin acciones" \
  bash -c "eval \"\$0\" | grep -qx cleanup=apply_accepted && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(cleanup_noid)"
# Creación cortada después del POST: HCP creó el run pero create-run falló al verificarlo
reset_hcp
hcp_seq "GET ${API}/configuration-versions/${CV}" '{"data":{"attributes":{"status":"uploaded","speculative":false}}}'
hcp_seq "POST ${API}/runs" "$(status 201 "$(run_json pending true '.data.attributes["auto-apply"] = true')")"
hcp_seq "GET ${API}/runs/${RUN}" "$(run_json pending true '.data.attributes["auto-apply"] = true')"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json pending true '.data.attributes["auto-apply"] = true')")"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 202 '')"
if run_hcp "${HCP}" create-run "${CV}" > "${TMP}/cr.out" 2> /dev/null; then
  ko "creación cortada tras el POST: create-run falla sin publicar run_id (no falló)"
else
  ok "creación cortada tras el POST: create-run falla sin publicar run_id"
fi
check "creación cortada tras el POST: la limpieza sin run_id encuentra ese run (aunque aplicaría solo) y lo descarta" \
  bash -c "! grep -q '^run_id=' '${TMP}/cr.out' && eval \"\$0\" | grep -qx cleanup=discarded && grep -q '^POST ${API}/runs/${RUN}/actions/discard|' '${FAKE_HCP_LOG}'" "$(cleanup_noid)"

# ── 7. Estado consumido: instalación nueva, reintento y concurrencia ────────
state_setup() { # <respuesta del run> <state actual: sv-…|none>
  reset_hcp
  hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
  hcp_seq "GET ${API}/runs/${RUN}" "$1"
  hcp_seq "GET ${API}/applies/${APPLY}" '{"data":{"relationships":{"state-versions":{"data":[{"id":"sv-New1"}]}}}}'
  hcp_seq "GET ${API}/state-versions/sv-New1" "{\"data\":{\"relationships\":{\"run\":{\"data\":{\"id\":\"${RUN}\"}}}}}"
  if [ "$2" = none ]; then
    hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"
    hcp_seq "GET ${STATE_LIST_URL}" '{"data":[]}'
  else
    hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "{\"data\":{\"id\":\"$2\"}}"
  fi
}
state_cmd() { printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' run-state '${RUN}' '$1'"; }
state_setup "$(run_json applied)" sv-New1
check "run-state: instalación nueva (antes none) aplicada → el state version de este run" \
  bash -c "out=\$(eval \"\$0\") && grep -qx state_version_id=sv-New1 <<< \"\$out\" && grep -qx state_source=run <<< \"\$out\"" "$(state_cmd none)"
state_setup "$(run_json planned_and_finished false)" none
check "run-state: sin estado y plan sin cambios → sigue none (unchanged)" \
  bash -c "eval \"\$0\" | grep -qx state_version_id=none" "$(state_cmd none)"
state_setup "$(run_json planned_and_finished false)" sv-Other
fails "run-state: sin estado antes, sin Apply propio y aparece un estado ajeno → falla" bash -c "$(state_cmd none)"
state_setup "$(run_json planned_and_finished false)" sv-New1
check "reintento tras éxito parcial (Apply hecho, deploy fallido): el plan nuevo no tiene cambios y se consume el mismo estado" \
  bash -c "eval \"\$0\" | grep -qx state_version_id=sv-New1" "$(state_cmd sv-New1)"
state_setup "$(run_json applied)" sv-Other
fails "run-state: otro run reemplazó el estado tras el Apply (concurrencia) → falla" bash -c "$(state_cmd none)"
fails "run-state: state version previo malformado → falla" bash -c "$(state_cmd sv)"
state_setup "$(run_json applied)" none
fails "outputs: sin estado (none) no hay outputs que consumir → falla" bash -c "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' outputs none")"
reset_hcp
hcp_seq "GET ${API}/workspaces/${WS}/runs?page%5Bsize%5D=1" '{"data":[{"attributes":{"status":"planned"}}]}'
fails "wait-idle: un run anterior esperando confirmación bloquea (no se compite con él)" run_hcp "${HCP}" wait-idle
hcp_seq "GET ${API}/workspaces/${WS}/runs?page%5Bsize%5D=1" '{"data":[]}'
check "wait-idle: workspace nuevo sin runs → libre" run_hcp "${HCP}" wait-idle

# ── 8. Workflow: Terraform sin confirmación automática ──────────────────────
PROD_STEPS="$(yq -r '.jobs.production.steps[].id // ""' "${WORKFLOW}")"
idx() { grep -nx "$1" <<< "${PROD_STEPS}" | cut -d: -f1; }
step() { yq -r ".jobs.production.steps[] | select(.id == \"$1\") | $2" "${WORKFLOW}"; }
check "workflow: ninguna acción confirma o aplica runs (sin apply-run ni create-run; nada usa is-confirmable)" \
  bash -c "! grep -qE 'tfc-workflows-github/actions/(apply-run|create-run)' '${WORKFLOW}' && ! grep -q 'is-confirmable' '${WORKFLOW}' && ! grep -qE 'actions/(apply|confirm)' '${HCP}'"
check "workflow: solo se sube la configuración con la acción (sin auto-queue-runs)" \
  test "$(grep -c 'tfc-workflows-github/actions/' "${WORKFLOW}")" = 1
check "workflow: el run lo crea hcp-state.sh create-run con la configuración subida en este run" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; [ \"\$(step hcp_run .run | grep -c 'hcp-state.sh create-run')\" = 1 ] && step hcp_run .env.CONFIGURATION_VERSION | grep -qx '\${{ steps.hcp_upload.outputs.configuration_version_id }}'" 2> /dev/null
check "workflow: el workspace se exige sin auto-apply cuando el run aplica" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; step hcp_workspace .run | grep -qF \"workspace \\\${{ env.TERRAFORM_REQUIRED == 'true' && 'apply' || 'read' }}\""
check "workflow: el mensaje del run identifica el intento (sha, run_id y run_attempt) y es el mismo en crear, plan, confirmar y limpiar" \
  bash -c "yq -r '.jobs.production.env.HCP_RUN_MESSAGE' '${WORKFLOW}' | grep -qF '\${{ github.sha }} — run \${{ github.run_id }}/\${{ github.run_attempt }}' && for s in hcp_run hcp_plan hcp_confirm hcp_cleanup; do [ \"\$(yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .env.RUN_MESSAGE\" '${WORKFLOW}')\" = '\${{ env.HCP_RUN_MESSAGE }}' ] || exit 1; done"
check "workflow: la confirmación solo se espera con cambios y sin confirmación previa" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; c=\$(step hcp_confirm .if); grep -q \"steps.hcp_plan.outputs.needs_confirmation == 'true'\" <<< \"\$c\" && grep -q \"steps.hcp_plan.outputs.already_confirmed != 'true'\" <<< \"\$c\""
check "workflow: mientras espera, la frescura de main se comprueba (verify-main-tip.sh) y el paso tiene plazo" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; [ \"\$(step hcp_confirm .env.FRESHNESS_CMD)\" = .github/scripts/verify-main-tip.sh ] && [ \"\$(step hcp_confirm '.\"timeout-minutes\"')\" -le 40 ] && [ \"\$(step hcp_confirm .env.HCP_CONFIRM_ATTEMPTS)\" -ge 1 ]"
check "workflow: la limpieza corre siempre que este intento intentó crear un run (también si la creación falló o se canceló, sin run_id)" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; c=\$(step hcp_cleanup .if); grep -q 'always()' <<< \"\$c\" && grep -q \"steps.hcp_run.outcome == 'failure'\" <<< \"\$c\" && grep -q \"steps.hcp_run.outcome == 'cancelled'\" <<< \"\$c\" && grep -q \"steps.hcp_run.outcome == 'success'\" <<< \"\$c\" && step hcp_cleanup .run | grep -qF 'hcp-state.sh cleanup \${RUN_ID:+\"\${RUN_ID}\"}'"
check "workflow: el plan comprueba la frescura (verify-main-tip.sh) y vigila proyecto y dominio de Pages" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; [ \"\$(step hcp_plan .env.FRESHNESS_CMD)\" = .github/scripts/verify-main-tip.sh ] && step hcp_plan .env.GH_TOKEN | grep -q github.token && step hcp_plan .env.PLAN_WATCH | grep -q 'pages_domain=module.pages.cloudflare_pages_domain.main'"
check "workflow: cloud-init (host_init) se espera antes de converge, después de SSH, solo con configuración del servidor" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; [ $(idx host_init) -gt $(idx readiness) ] && [ $(idx host_init) -lt $(idx converge) ] && [ \"\$(step host_init .if)\" = \"env.RUN_HOST_CONFIG == 'true'\" ] && step host_init .run | grep -q 'host-ready.sh' && [ \"\$(step host_init '.\"continue-on-error\" // false')\" = false ]"
check "workflow: orden workspace → idle → estado previo → frescura → subida → run → plan → confirmación → estado → outputs" \
  bash -c "p=0; for s in hcp_workspace hcp_idle hcp_before fresh_apply hcp_upload hcp_run hcp_plan hcp_confirm hcp_state tf_outputs; do i=\$(grep -nx \"\$s\" <<< \"\$1\" | cut -d: -f1); [ -n \"\$i\" ] && [ \"\$i\" -gt \"\$p\" ] || exit 1; p=\$i; done" _ "${PROD_STEPS}"
check "workflow: la limpieza va después de todos los pasos que pueden fallar y antes del resumen" \
  bash -c "[ $(idx hcp_cleanup) -gt $(idx pages_publish) ] && [ \"\$(yq -r '.jobs.production.steps[-1].name' '${WORKFLOW}')\" = 'Resumen de producción' ]"
check "workflow: sin estado no hay deploy (outputs exige un state version)" \
  bash -c "$(declare -f step); WORKFLOW='${WORKFLOW}'; step tf_outputs .run | grep -q 'EXPECTED_STATE}\" = none'"
check "workflow: environment, concurrencia y queue sin cambios" \
  test "$(yq -r '.jobs.production | .environment + "/" + .concurrency.group + "/" + (.concurrency."cancel-in-progress" | tostring) + "/" + .concurrency.queue' "${WORKFLOW}")" = "production-hetzner/piedrazul-production-hetzner/false/max"
check "workflow: el token de HCP solo en el job production" \
  test "$(yq -r '.jobs | to_entries[] | select(.key != "production") | .value' "${WORKFLOW}" | grep -c 'secrets.TF_API_TOKEN' || true)" = 0
check "workflow: el plazo del job cubre la espera de confirmación" \
  test "$(yq -r '.jobs.production."timeout-minutes"' "${WORKFLOW}")" -ge 100
check "workflow: el dispatch completo sigue disponible sin diff (6 flags) y sin preset nuevo" \
  bash -c "[ \"\$(yq -r '.on.workflow_dispatch.inputs | keys | sort | join(\",\")' '${WORKFLOW}')\" = 'break_glass_authorization,break_glass_justification,image_tag,keycloak_recovery,run_app_deploy,run_build,run_build_postgres,run_frontend_publish,run_host_config,run_terraform_apply' ]"

# ── 9. Camino del job production por escenario ──────────────────────────────
# Evalúa las condiciones `if` reales de los pasos (subconjunto de expresiones de GitHub
# Actions que usa el workflow). Un paso sin función de estado solo corre si ninguno anterior
# falló (success() implícito); always() corre siempre. Las salidas simuladas de cada paso
# son las del escenario.
yq -o=json '.jobs.production.steps' "${WORKFLOW}" > "${TMP}/steps.json"
cat > "${TMP}/flow.py" << 'EOF'
import json, re, sys
steps = json.load(open(sys.argv[1]))
sc = json.loads(sys.argv[2])
env = sc["env"]
outs = sc.get("outputs", {})
needs = {"image_scan": {"result": sc.get("image_scan", "success")}}
fail_at = sc.get("fail_at")
cancel_at = sc.get("cancel_at")
state = {}
failed = cancelled = False

def translate(expr):
    e = " ".join(expr.replace("${{", "").replace("}}", "").split())
    e = re.sub(r"\benv\.([A-Z_]+)", r"E('\1')", e)
    e = re.sub(r"\bsteps\.([a-z_]+)\.outputs\.([a-z_]+)", r"O('\1','\2')", e)
    e = re.sub(r"\bsteps\.([a-z_]+)\.outcome", r"OC('\1')", e)
    e = re.sub(r"\bneeds\.([a-z_]+)\.result", r"N('\1')", e)
    e = e.replace("!=", " NE ").replace("&&", " and ").replace("||", " or ")
    e = re.sub(r"!\s*\(", " not (", e).replace(" NE ", " != ")
    e = e.replace("always()", "True").replace("cancelled()", "CANCELLED").replace("failure()", "FAILED").replace("success()", "(not FAILED)")
    return e

for st in steps:
    sid = st.get("id") or st.get("name")
    cond = str(st.get("if", "")).strip()
    has_status = any(f in cond for f in ("always()", "failure()", "cancelled()", "success()"))
    run = True
    if not has_status and (failed or cancelled):
        run = False
    elif cond:
        ctx = {"E": lambda k: env.get(k, ""), "O": lambda s, k: (outs.get(s, {}).get(k, "") if state.get(s) == "success" else ""),
               "OC": lambda s: state.get(s, "skipped"), "N": lambda n: needs[n]["result"],
               "CANCELLED": cancelled, "FAILED": failed}
        run = bool(eval(translate(cond), {}, ctx))
    if not run:
        state[sid] = "skipped"
        continue
    if sid == fail_at:
        state[sid] = "failure"; failed = True
    elif sid == cancel_at:
        state[sid] = "cancelled"; cancelled = True
    else:
        state[sid] = "success"
print(" ".join(k for k, v in state.items() if v != "skipped"))
EOF
flow() { python3 "${TMP}/flow.py" "${TMP}/steps.json" "$1"; }
# runs/skips <descripción> <escenario JSON> <pasos que deben correr> <pasos que no deben correr>
flow_case() {
  local d="$1" sc="$2" ran missing="" extra=""
  if ! ran=" $(flow "${sc}" 2> "${TMP}/flow.err") "; then ko "camino: ${d} ($(tail -1 "${TMP}/flow.err"))"; return; fi
  for s in $3; do [[ "${ran}" == *" $s "* ]] || missing="${missing} $s"; done
  for s in $4; do [[ "${ran}" != *" $s "* ]] || extra="${extra} $s"; done
  if [ -z "${missing}${extra}" ]; then ok "camino: ${d}"; else ko "camino: ${d} (faltan:${missing:- —}; sobran:${extra:- —})"; fi
}
AUTH='"policy":{"decision":"authorized"},"scan_evidence":{"scan_backend":"false","scan_postgres":"false"}'
FULL_ENV='{"TERRAFORM_REQUIRED":"true","DEPLOY_REQUIRED":"true","RUN_HOST_CONFIG":"true","RUN_APP_DEPLOY":"true","FRONTEND_REQUIRED":"true"}'
FRESH="{\"env\":${FULL_ENV},\"outputs\":{\"pages_probe\":{\"project_state\":\"absent\"},\"hcp_run\":{\"run_id\":\"run-1\"},\"hcp_plan\":{\"needs_confirmation\":\"true\",\"already_confirmed\":\"false\"},${AUTH}}}"
flow_case "instalación nueva (dispatch completo, Pages ausente, plan con cambios)" "${FRESH}" \
  "credentials pages_target pages_artifact pages_tooling pages_probe hcp_workspace hcp_idle hcp_before fresh_apply hcp_upload hcp_run hcp_plan hcp_confirm hcp_state tf_outputs pages_preflight access readiness host_init host_state select scan_evidence policy converge converge_identity deploy running_identity container_health public_health record pages_publish hcp_cleanup" \
  "fresh_deploy fresh_frontend break_glass authorization scan_backend scan_postgres"
FRONT='{"env":{"TERRAFORM_REQUIRED":"false","DEPLOY_REQUIRED":"false","RUN_HOST_CONFIG":"false","RUN_APP_DEPLOY":"false","FRONTEND_REQUIRED":"true"},"outputs":{"pages_probe":{"project_state":"present"}},"image_scan":"skipped"}'
flow_case "solo frontend: sin Terraform ni SSH; sondeo y preflight estrictos antes de publicar" "${FRONT}" \
  "credentials pages_probe pages_preflight fresh_frontend pages_publish" \
  "hcp_workspace hcp_idle hcp_before hcp_upload hcp_run hcp_plan hcp_confirm hcp_state tf_outputs access readiness converge deploy record hcp_cleanup"
BACK="{\"env\":{\"TERRAFORM_REQUIRED\":\"false\",\"DEPLOY_REQUIRED\":\"true\",\"RUN_HOST_CONFIG\":\"false\",\"RUN_APP_DEPLOY\":\"true\",\"FRONTEND_REQUIRED\":\"false\"},\"outputs\":{${AUTH}}}"
flow_case "solo backend: lee el estado sin plan ni Apply, sin Pages" "${BACK}" \
  "hcp_workspace hcp_idle hcp_before tf_outputs access select policy fresh_deploy deploy running_identity record" \
  "host_init authorization fresh_apply hcp_upload hcp_run hcp_plan hcp_confirm hcp_state converge pages_probe pages_preflight pages_publish hcp_cleanup"
TFONLY='{"env":{"TERRAFORM_REQUIRED":"true","DEPLOY_REQUIRED":"false","RUN_HOST_CONFIG":"false","RUN_APP_DEPLOY":"false","FRONTEND_REQUIRED":"false"},"outputs":{"hcp_run":{"run_id":"run-1"},"hcp_plan":{"needs_confirmation":"true","already_confirmed":"false"}},"image_scan":"skipped"}'
flow_case "solo Terraform con cambios: plan, confirmación humana y estado; sin servidor ni Pages" "${TFONLY}" \
  "hcp_workspace fresh_apply hcp_upload hcp_run hcp_plan hcp_confirm hcp_state hcp_cleanup" \
  "tf_outputs access converge deploy pages_probe pages_preflight pages_publish"
NOCHANGE="${TFONLY/\"needs_confirmation\":\"true\"/\"needs_confirmation\":\"false\"}"
flow_case "plan sin cambios: no espera confirmación" "${NOCHANGE}" "hcp_plan hcp_state hcp_cleanup" "hcp_confirm"
PRECONF="${TFONLY/\"already_confirmed\":\"false\"/\"already_confirmed\":\"true\"}"
flow_case "plan ya confirmado en HCP: no espera, sigue al estado" "${PRECONF}" "hcp_plan hcp_state" "hcp_confirm"
flow_case "confirmación vencida o rechazada: ni estado, ni servidor, ni Pages; limpieza y resumen sí" \
  "${FRESH%\}},\"fail_at\":\"hcp_confirm\"}" \
  "hcp_confirm hcp_cleanup" "hcp_state tf_outputs pages_preflight access converge deploy record pages_publish"
flow_case "cancelación mientras espera la confirmación: la limpieza corre igual" \
  "${FRESH%\}},\"cancel_at\":\"hcp_confirm\"}" \
  "hcp_confirm hcp_cleanup" "hcp_state converge pages_publish"
flow_case "Pages no existe tras el Apply (preflight falla): el servidor no se toca y no se publica" \
  "${FRESH%\}},\"fail_at\":\"pages_preflight\"}" \
  "hcp_confirm hcp_state tf_outputs pages_preflight hcp_cleanup" "access converge deploy record pages_publish"
flow_case "sondeo de Pages falla (Git, token, cuenta): ninguna mutación (ni plan)" \
  "${FRESH%\}},\"fail_at\":\"pages_probe\"}" \
  "pages_probe" "hcp_workspace fresh_apply hcp_upload hcp_run hcp_plan hcp_confirm converge deploy pages_publish hcp_cleanup"
flow_case "plan revisado: Pages ausente y el plan no lo crea → falla antes de confirmar" \
  "${FRESH%\}},\"fail_at\":\"hcp_plan\"}" \
  "hcp_run hcp_plan hcp_cleanup" "hcp_confirm hcp_state converge pages_publish"
flow_case "deploy falla tras el Apply: no se registra ni se publica; la limpieza informa el Apply aceptado" \
  "${FRESH%\}},\"fail_at\":\"deploy\"}" \
  "hcp_state deploy hcp_cleanup" "record pages_publish"
BLOCKED="${FRESH/\"decision\":\"authorized\"/\"decision\":\"blocked\"}"
flow_case "política de vulnerabilidades bloquea en la instalación nueva: el gate falla y nada del servidor ni de Pages muta (no hay bypass por bootstrap)" \
  "${BLOCKED%\}},\"fail_at\":\"authorization\"}" \
  "policy authorization hcp_cleanup" "converge deploy record pages_publish"
INCOMPLETE_RUN="${FRESH/\"project_state\":\"absent\"/\"project_state\":\"incomplete\"}"
flow_case "reintento tras Apply parcial (Pages incompleto): mismo camino que la instalación nueva, preflight estricto antes de publicar" \
  "${INCOMPLETE_RUN}" "pages_probe hcp_plan hcp_confirm hcp_state pages_preflight host_init converge deploy record pages_publish" ""
flow_case "creación del run falla (o se corta tras el POST): la limpieza corre igual, sin run_id" \
  "${FRESH%\}},\"fail_at\":\"hcp_run\"}" "hcp_run hcp_cleanup" "hcp_plan hcp_confirm hcp_state converge pages_publish"
flow_case "cancelación durante la creación del run: la limpieza corre igual" \
  "${FRESH%\}},\"cancel_at\":\"hcp_run\"}" "hcp_run hcp_cleanup" "hcp_plan converge"
flow_case "cloud-init falló o no terminó: ni converge, ni deploy, ni publicación" \
  "${FRESH%\}},\"fail_at\":\"host_init\"}" "host_init hcp_cleanup" "host_state converge deploy record pages_publish"
flow_case "publicación falla tras el registro del servidor (release parcial visible)" \
  "${FRESH%\}},\"fail_at\":\"pages_publish\"}" \
  "record pages_publish hcp_cleanup" ""

# ── 10. Decisión del paso hcp_plan sobre Pages (bloque run real del workflow) ──
# Ejecuta el `run` del paso con un hcp-state.sh falso que devuelve las acciones observadas
PLAN_RUN="$(yq -r '.jobs.production.steps[] | select(.id == "hcp_plan") | .run' "${WORKFLOW}")"
PD="${TMP}/plan-step"
mkdir -p "${PD}/.github/scripts"
cat > "${PD}/.github/scripts/hcp-state.sh" << 'EOF'
#!/usr/bin/env bash
printf '%b\n' "${FAKE_PLAN_OUT}"
EOF
chmod +x "${PD}/.github/scripts/hcp-state.sh"
plan_step() { # <estado del sondeo> <watch proyecto> <watch dominio> [confirmed_while_stale] [already_confirmed] [needs_confirmation]
  : > "${PD}/out"; : > "${PD}/summary"; echo x > "${PD}/plan-changes.txt"
  (cd "${PD}" && env -i PATH="${PATH}" RUNNER_TEMP="${PD}" GITHUB_OUTPUT="${PD}/out" GITHUB_STEP_SUMMARY="${PD}/summary" \
    TF_CLOUD_ORGANIZATION="${ORG}" TF_WORKSPACE="${NAME}" HCP_WORKSPACE_ID="${WS}" GITHUB_SHA=abc RUN_ID="${RUN}" \
    PAGES_PROJECT_STATE="$1" \
    FAKE_PLAN_OUT="run_status=planned\nneeds_confirmation=${6:-true}\nalready_confirmed=${5:-false}\nplan_id=${PLAN}\nadditions=9\nchanges=0\ndestructions=0\nimports=0\nwatch_pages_project=$2\nwatch_pages_domain=$3\nconfirmed_while_stale=${4:-false}\nrun_link=https://x" \
    bash -c "${PLAN_RUN}") > "${PD}/log" 2>&1
}
plan_step_case() { # <descripción> <pasa|falla> <args de plan_step...>
  local d="$1" exp="$2" rc=0
  shift 2
  plan_step "$@" || rc=$?
  if { [ "${exp}" = pasa ] && [ "${rc}" -eq 0 ]; } || { [ "${exp}" = falla ] && [ "${rc}" -ne 0 ]; }; then
    ok "hcp_plan: ${d}"
  else
    ko "hcp_plan: ${d} (rc=${rc}: $(tail -1 "${PD}/log"))"
  fi
}
plan_step_case "Pages ausente y el plan crea proyecto y dominio → sigue a la confirmación" pasa absent create create
plan_step_case "Pages ausente y el JSON del plan no se pudo leer (unknown) → falla" falla absent unknown unknown
plan_step_case "Pages ausente, proyecto observado pero dominio unknown → falla" falla absent create unknown
plan_step_case "Pages ausente y el plan no crea el proyecto → falla" falla absent none create
plan_step_case "Pages ausente y el proyecto aparece repetido (ambiguous) → falla" falla absent ambiguous create
plan_step_case "Pages ausente y el plan lo reemplaza en vez de crearlo → falla" falla absent replace create
plan_step_case "Pages incompleto y el plan crea la asociación del dominio → sigue (reintento tras Apply parcial)" pasa incomplete none create
plan_step_case "Pages incompleto y el JSON del plan no se pudo leer → falla" falla incomplete unknown unknown
plan_step_case "Pages incompleto y el plan no crea el dominio → falla" falla incomplete none none
plan_step_case "Pages presente y completo: no exige evidencia del plan (unknown no bloquea)" pasa present unknown unknown
plan_step_case "sin frontend en el run: no exige evidencia del plan" pasa "" unknown unknown
plan_step "" none none true
check "hcp_plan: confirmado cuando main ya avanzó → anotación visible (::warning) y sigue" \
  bash -c "grep -q '^::warning title=Terraform::' '${PD}/log'"
# Confirmación temprana (antes de que termine este paso): HCP no permite impedirla
plan_step absent create create false false || true
check "hcp_plan: revisión completa → aviso 'Plan revisado por GitHub' con run, plan y commit (única señal para confirmar)" \
  bash -c "grep -q '^::notice title=Terraform::Plan revisado por GitHub: .*${RUN} (plan ${PLAN}, commit abc)' '${PD}/log' && grep -q 'solo cuando este paso termine en éxito' '${PD}/summary'"
plan_step absent unknown unknown false false || true
check "hcp_plan: evidencia de Pages insuficiente sin confirmar → error 'el run se descarta' y sin aviso de revisado" \
  bash -c "grep -q 'no se aplica: el run se descarta' '${PD}/log' && ! grep -q '^::notice title=Terraform::Plan revisado' '${PD}/log'"
plan_step_case "confirmado antes de terminar la revisión y evidencia de Pages insuficiente → falla (la cadena se detiene)" falla absent unknown unknown false true
check "hcp_plan: ese error no finge un descarte: dice que el Apply ya fue aceptado y no se deshace" \
  bash -c "grep -q 'ya se confirmó en HCP: el Apply fue aceptado y no se deshace' '${PD}/log' && ! grep -q 'el run se descarta' '${PD}/log' && ! grep -q '^::notice title=Terraform::Plan revisado' '${PD}/log'"
plan_step_case "confirmado antes de terminar la revisión con evidencia correcta → sigue (no se interrumpe un Apply aceptado)" pasa absent create create false true
check "hcp_plan: la confirmación temprana se anota como fuera del procedimiento y no emite el aviso de revisado" \
  bash -c "grep -q '^::warning title=Terraform::.*antes de que GitHub terminara de revisar' '${PD}/log' && ! grep -q '^::notice title=Terraform::Plan revisado' '${PD}/log' && grep -q 'confirmado en HCP antes de que GitHub terminara' '${PD}/summary'"
plan_step present unknown unknown false false false || true
check "hcp_plan: plan sin cambios → sin aviso de confirmación" bash -c "! grep -q '^::notice title=Terraform::Plan revisado' '${PD}/log' && grep -q 'sin cambios: nada que aplicar' '${PD}/summary'"
check "workflow: el aviso de revisado va después de todas las comprobaciones del paso (frescura en hcp-state.sh plan y evidencia de Pages)" \
  bash -c "r=\$(yq -r '.jobs.production.steps[] | select(.id == \"hcp_plan\") | .run' '${WORKFLOW}'); a=\$(grep -n 'hcp-state.sh plan' <<< \"\$r\" | cut -d: -f1); b=\$(grep -n 'for w in' <<< \"\$r\" | cut -d: -f1); c=\$(grep -n 'Plan revisado por GitHub: ya se puede' <<< \"\$r\" | cut -d: -f1); [ \"\$a\" -lt \"\$b\" ] && [ \"\$b\" -lt \"\$c\" ]"
check "hcp_plan: el resumen describe workspace, commit, run, plan y conteos" \
  bash -c "grep -q '${NAME}' '${PD}/summary' && grep -q 'abc' '${PD}/summary' && grep -q '${RUN}' '${PD}/summary' && grep -q '9 / 0 / 0 / 0' '${PD}/summary'"

# ── 11. Barrera de cloud-init (host-ready.sh) ───────────────────────────────
READY="${ROOT}/.github/scripts/host-ready.sh"
cat > "${TMP}/bin/fake-ssh" << 'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${FAKE_SSH_LOG}"
[ -z "${FAKE_SSH_SLEEP:-}" ] || sleep "${FAKE_SSH_SLEEP}"
[ -z "${FAKE_SSH_FAIL:-}" ] || { echo "ssh: connect to host: Connection refused" >&2; exit 255; }
printf '%s' "${FAKE_CI_JSON:-}"
exit "${FAKE_SSH_RC:-0}"
EOF
chmod +x "${TMP}/bin/fake-ssh"
export FAKE_SSH_LOG="${TMP}/ssh.log"
ready() { # <JSON> [variables extra...]
  local json="$1"
  shift
  env SSH_BIN="${TMP}/bin/fake-ssh" SSH_KEY_FILE=/dev/null FAKE_CI_JSON="${json}" "$@" "${READY}" 203.0.113.7
}
ready_case() { # <descripción> <pasa|falla> <JSON> [esperado en la salida] [variables extra...]
  local d="$1" exp="$2" json="$3" want="${4:-}" rc=0
  shift 3; shift || true
  ready "${json}" "$@" > "${TMP}/ready.out" 2> "${TMP}/ready.err" || rc=$?
  if { [ "${exp}" = pasa ] && [ "${rc}" -eq 0 ] && { [ -z "${want}" ] || grep -qx "${want}" "${TMP}/ready.out"; }; } ||
     { [ "${exp}" = falla ] && [ "${rc}" -ne 0 ]; }; then
    ok "host-ready: ${d}"
  else
    ko "host-ready: ${d} (rc=${rc}; $(tail -1 "${TMP}/ready.err"))"
  fi
}
: > "${FAKE_SSH_LOG}"
ready_case "cloud-init done sin errores → listo" pasa '{"status":"done","extended_status":"done","errors":[],"recoverable_errors":{}}' cloud_init_status=done
check "host-ready: espera (status --wait) y lee el estado final en JSON, como ansible, por SSH sin contraseña" \
  bash -c "grep -q 'BatchMode=yes' '${FAKE_SSH_LOG}' && grep -q 'ansible@203.0.113.7' '${FAKE_SSH_LOG}' && grep -q 'cloud-init status --wait > /dev/null 2>&1; cloud-init status --format json' '${FAKE_SSH_LOG}'"
ready_case "cloud-init antiguo sin extended_status, done → listo" pasa '{"status":"done","errors":[]}' cloud_init_status=done
ready_case "degraded done (errores recuperables, sin errores) → falla cerrado" falla '{"status":"done","extended_status":"degraded done","errors":[],"recoverable_errors":{"DEPRECATED":["x"],"WARNING":["y"]}}'
check "host-ready: degradado se diagnostica como tal, con la cantidad y el contenido de los avisos" \
  bash -c "grep -q '\[degradado\]' '${TMP}/ready.err' && grep -q '2 error(es) recuperable(s)' '${TMP}/ready.err' && grep -q 'WARNING' '${TMP}/ready.err' && [ ! -s '${TMP}/ready.out' ]"
ready_case "cloud-init antiguo sin extended_status pero con errores recuperables → falla cerrado" falla '{"status":"done","errors":[],"recoverable_errors":{"WARNING":["apt: x"]}}'
ready_case "done con recoverable_errors vacío → listo" pasa '{"status":"done","extended_status":"done","errors":[],"recoverable_errors":{"WARNING":[]}}' cloud_init_status=done
check "host-ready: éxito sin salida de degradado" bash -c "! grep -q 'cloud_init_status=degraded' '${TMP}/ready.out' && grep -q '\[éxito\]' '${TMP}/ready.err'"
ready_case "status error → falla" falla '{"status":"error","extended_status":"error - done","errors":["module x failed"]}'
check "host-ready: el fallo se diagnostica como tal, con los errores" bash -c "grep -q '\[fallo\]' '${TMP}/ready.err' && grep -q 'module x failed' '${TMP}/ready.err'"
ready_case "done pero con errores → falla" falla '{"status":"done","extended_status":"done","errors":["boom"]}'
ready_case "todavía corriendo (running) → falla (indeterminado)" falla '{"status":"running","errors":[]}'
ready_case "cloud-init deshabilitado → falla (indeterminado)" falla '{"status":"disabled","errors":[]}'
ready_case "no iniciado → falla" falla '{"status":"not started","errors":[]}'
ready_case "estado extendido desconocido → falla" falla '{"status":"done","extended_status":"raro","errors":[]}'
ready_case "errors con forma inesperada → falla" falla '{"status":"done","errors":"x"}'
ready_case "salida ilegible (sin cloud-init o texto) → falla" falla 'status: done'
check "host-ready: la salida ilegible se diagnostica como indeterminada" grep -q '\[indeterminado\]' "${TMP}/ready.err"
ready_case "salida vacía → falla" falla ''
ready_case "SSH falla (rc 255) → falla" falla '' "" FAKE_SSH_FAIL=1
ready_case "sin terminar dentro del plazo → falla" falla '{"status":"done","errors":[]}' "" FAKE_SSH_SLEEP=3 HOST_READY_TIMEOUT=1
check "host-ready: el plazo vencido se diagnostica como tal" bash -c "grep -q '\[plazo vencido\]' '${TMP}/ready.err' && grep -q 'no terminó en 1s' '${TMP}/ready.err'"
# Forma del JSON y código de salida (fail-open reproducidos en la revisión)
OKJSON='{"status":"done","extended_status":"done","errors":[],"recoverable_errors":{}}'
ready_case "recoverable_errors con forma inválida (texto) → falla (nunca '0 avisos')" falla '{"status":"done","extended_status":"done","errors":[],"recoverable_errors":"bad-shape"}'
check "host-ready: forma inválida se diagnostica como indeterminada" grep -q '\[indeterminado\].*forma inesperada' "${TMP}/ready.err"
ready_case "recoverable_errors con un valor que no es lista → falla" falla '{"status":"done","extended_status":"done","errors":[],"recoverable_errors":{"WARNING":"x"}}'
ready_case "recoverable_errors como lista → falla" falla '{"status":"done","extended_status":"done","errors":[],"recoverable_errors":["x"]}'
ready_case "errors como objeto → falla" falla '{"status":"done","extended_status":"done","errors":{},"recoverable_errors":{}}'
ready_case "extended_status no textual → falla" falla '{"status":"done","extended_status":1,"errors":[]}'
ready_case "JSON no objeto (lista) → falla" falla '[{"status":"done"}]'
ready_case "campos opcionales en null (versiones antiguas) → listo" pasa '{"status":"done","extended_status":null,"errors":null,"recoverable_errors":null}' cloud_init_status=done
ready_case "solo status done (versiones antiguas) → listo" pasa '{"status":"done"}' cloud_init_status=done
ready_case "JSON de éxito pero ssh/comando remoto con código 23 → falla" falla "${OKJSON}" "" FAKE_SSH_RC=23
check "host-ready: código distinto de 0 con JSON de éxito se diagnostica como indeterminado, con el código" \
  bash -c "grep -q '\[indeterminado\]' '${TMP}/ready.err' && grep -q 'código 23' '${TMP}/ready.err' && [ ! -s '${TMP}/ready.out' ]"
ready_case "JSON de éxito con código 1 → falla" falla "${OKJSON}" "" FAKE_SSH_RC=1
ready_case "degradado con código 2 (lo que devuelve cloud-init) → falla como degradado" falla '{"status":"done","extended_status":"degraded done","errors":[],"recoverable_errors":{"WARNING":["x"]}}' "" FAKE_SSH_RC=2
check "host-ready: con código distinto de 0 el diagnóstico del JSON tiene prioridad (degradado)" grep -q '\[degradado\]' "${TMP}/ready.err"
ready_case "error con código 1 → falla como fallo" falla '{"status":"error","extended_status":"error - done","errors":["boom"]}' "" FAKE_SSH_RC=1
check "host-ready: error con código 1 se diagnostica como fallo" grep -q '\[fallo\]' "${TMP}/ready.err"
ready_case "éxito con código 0 → listo (control)" pasa "${OKJSON}" cloud_init_status=done
fails "host-ready: IP inválida → falla sin conectar" env SSH_BIN="${TMP}/bin/fake-ssh" "${READY}" 'x;rm'

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "production-bootstrap-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "production-bootstrap-check: todo PASS"
