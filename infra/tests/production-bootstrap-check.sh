#!/usr/bin/env bash
# Instalación nueva y actualizaciones de la cadena de producción: workspace, estado vacío,
# configuración provisional, saved plan revisado en GitHub (revisión saneada, sin valores
# sensibles), una única confirmación del Apply después de la aprobación, confirmaciones
# fuera de GitHub, limpieza del run propio (hcp-state.sh, plan-review.sh), el camino de los
# jobs terraform_plan y production de production-hetzner.yml en cada escenario (instalación
# nueva, solo frontend, solo backend, solo Terraform, plan sin cambios, fallos,
# cancelación) y la matriz de dependencias entre jobs (saltado, fallido, cancelado).
#
# Sin red, sin HCP Terraform y sin GitHub: `curl` es falso (sirve respuestas registradas con
# su código HTTP y anota método, URL y cuerpo de cada llamada). El camino del workflow se
# obtiene evaluando las condiciones `if` reales de sus pasos con un evaluador mínimo del
# subconjunto de expresiones que usan. Nada de esto prueba el comportamiento real de HCP,
# Cloudflare ni de GitHub Actions: eso queda para la verificación formal contra el entorno.
#
# Uso: infra/tests/production-bootstrap-check.sh  (EVIDENCE_DIR=<dir> guarda la matriz de jobs)
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
[ -z "${FAKE_CURL_SLEEP_ON:-}" ] || [[ "${url}" != *"${FAKE_CURL_SLEEP_ON}"* ]] || sleep "${FAKE_CURL_SLEEP:-3}"
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
        permissions: {"can-read-state-versions": true, "can-queue-run": true, "can-queue-apply": true}},
      relationships: {organization: {data: {id: $o, type: "organizations"}}}}}' | jq -c "${1:-.}"
}
NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
OLD_ISO="$(date -u -d '-2 hours' +%Y-%m-%dT%H:%M:%S.000Z)"
# run_json <status> [has-changes] [jq de ajuste]: saved plan propio, creado ahora, que partió
# de sv-Old1 (confirmado ahora si está en un estado de Apply)
run_json() {
  jq -cn --arg s "$1" --argjson hc "${2:-true}" --arg id "${RUN}" --arg ws "${WS}" --arg cv "${CV}" --arg m "${MSG}" \
    --arg plan "${PLAN}" --arg apply "${APPLY}" --arg now "${NOW_ISO}" '
    {data: {id: $id, type: "runs",
      attributes: {status: $s, message: $m, "has-changes": $hc, "auto-apply": false, "plan-only": false, "is-destroy": false,
        "save-plan": true, "created-at": $now,
        "status-timestamps": (if ($s | IN("confirmed", "apply_queued", "applying", "applied")) then {"confirmed-at": $now} else {} end),
        actions: {"is-confirmable": ($s == "planned_and_saved"), "is-discardable": ($s | IN("planned_and_saved", "pending")),
                  "is-cancelable": ($s | IN("pending", "plan_queued", "planning", "applying"))}},
      relationships: {workspace: {data: {id: $ws}}, "configuration-version": {data: {id: $cv}},
        "input-state-version": {data: {id: "sv-Old1", type: "state-versions"}},
        plan: {data: {id: $plan}}, apply: {data: (if $s == "applied" then {id: $apply} else null end)}}}}' |
    jq -c "${3:-.}"
}
# plan_output <acción del proyecto de Pages: create|none> [acción de la asociación del dominio]:
# JSON del plan con valores reales, incluidos canarios que la revisión pública nunca muestra
plan_output() {
  jq -cn --arg a "$1" --arg d "${2:-$1}" '
    def rc($addr; $type; $acts; $before; $after; $bs; $as):
      {address: $addr, type: $type, change: {actions: $acts, before: $before, after: $after,
        after_unknown: (if $acts == ["create"] then {id: true} else {} end), before_sensitive: $bs, after_sensitive: $as}};
    {format_version: "1.2", resource_changes: ([
      rc("module.server.hcloud_server.main"; "hcloud_server"; ["create"]; null;
        {name: "piedrazul-prod", server_type: "cx22", location: "fsn1", image: "ubuntu-24.04", user_data: "CANARY-USERDATA-7f3a"}; false; {user_data: true}),
      rc("module.dns.cloudflare_dns_record.frontend"; "cloudflare_dns_record"; ["create"]; null;
        {name: "piedrazul.org", content: "CANARY-CONTENT-91be", type: "CNAME", proxied: true, ttl: 1}; false; {}),
      rc("module.zone_settings.cloudflare_zone_setting.ssl"; "cloudflare_zone_setting"; ["no-op"]; {value: "strict"}; {value: "strict"}; {}; {})]
      + (if $a == "create" then [rc("module.pages.cloudflare_pages_project.main"; "cloudflare_pages_project"; ["create"]; null; {name: "piedrazul", production_branch: "main"}; false; {})] else [] end)
      + (if $d == "create" then [rc("module.pages.cloudflare_pages_domain.main"; "cloudflare_pages_domain"; ["create"]; null; {name: "piedrazul.org"}; false; {})] else [] end)),
     output_changes: {server_ip: {actions: ["create"], before: null, after: "CANARY-OUTPUT-55d2"}}}'
}
# plan_json_for <JSON del plan>: el plan de HCP con los conteos coherentes con ese JSON
plan_json_for() {
  jq -c --arg id "${PLAN}" '
    [.resource_changes[] | .change.actions] as $a
    | ([$a[] | select(. == ["delete", "create"] or . == ["create", "delete"])] | length) as $r
    | {data: {id: $id, attributes: {status: "finished",
        "resource-additions": (([$a[] | select(. == ["create"])] | length) + $r),
        "resource-changes": ([$a[] | select(. == ["update"])] | length),
        "resource-destructions": (([$a[] | select(. == ["delete"])] | length) + $r),
        "resource-imports": ([.resource_changes[] | select(.change.importing != null)] | length)}}}' <<< "$1"
}
RUNS_URL="${API}/workspaces/${WS}/runs?page%5Bsize%5D=20"
runs_list() { # <run JSON (data)>...
  jq -cn '{data: [$ARGS.positional[] | fromjson | .data]}' --args "$@"
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

# ── 3. Subir la configuración: provisional, sin auto-queue-runs ─────────────
CV_URL="${API}/workspaces/${WS}/configuration-versions"
UPLOAD_URL="https://archivist.test/v1/object/UPLOAD-SECRET-c0ffee"
cv_created() { # [jq de ajuste]
  jq -cn --arg id "${CV}" --arg u "${UPLOAD_URL}" '
    {data: {id: $id, type: "configuration-versions",
      attributes: {status: "pending", provisional: true, speculative: false, "auto-queue-runs": false, "upload-url": $u}}}' |
    jq -c "${1:-.}"
}
cv_state() { # <status> [jq de ajuste]
  jq -cn --arg id "${CV}" --arg s "$1" '{data: {id: $id, attributes: {status: $s, provisional: true, speculative: false}}}' | jq -c "${2:-.}"
}
TFDIR="${TMP}/tfdir"
mkdir -p "${TFDIR}/live/prod" "${TFDIR}/modules/m" "${TFDIR}/live/prod/.terraform/providers"
echo 'terraform {}' > "${TFDIR}/live/prod/main.tf"
echo 'variable "x" {}' > "${TFDIR}/modules/m/variables.tf"
echo 'lock' > "${TFDIR}/live/prod/.terraform.lock.hcl"
echo 'plugin' > "${TFDIR}/live/prod/.terraform/providers/bin"
echo '{"secret":"CANARY-STATE"}' > "${TFDIR}/live/prod/terraform.tfstate"
echo 'x' > "${TFDIR}/live/prod/terraform.tfstate.backup"
upload_setup() { # [respuesta del POST] [respuesta del PUT] [respuestas del GET de la configuración...]
  reset_hcp
  hcp_seq "POST ${CV_URL}" "${1:-$(status 201 "$(cv_created)")}"
  hcp_seq "PUT ${UPLOAD_URL}" "${2:-$(status 200 '')}"
  shift 2 2> /dev/null || shift $#
  if [ "$#" -gt 0 ]; then hcp_seq "GET ${API}/configuration-versions/${CV}" "$@"; else hcp_seq "GET ${API}/configuration-versions/${CV}" "$(cv_state pending)" "$(cv_state uploaded)"; fi
}
upload_cmd() { printf '%q ' bash -c "${HCP_DEF}; hcp_env HCP_UPLOAD_ATTEMPTS=3 '${HCP}' upload '${TFDIR}'"; }
upload_setup
check "upload: configuración provisional subida y procesada → id y sha256 del archivo" \
  bash -c "out=\$(eval \"\$0\" 2> '${TMP}/upload.err') && grep -qx configuration_version_id=${CV} <<< \"\$out\" && grep -qE '^archive_sha256=[0-9a-f]{64}$' <<< \"\$out\" && ! grep -q UPLOAD-SECRET <<< \"\$out\" && ! grep -q UPLOAD-SECRET '${TMP}/upload.err'" "$(upload_cmd)"
check "upload: pide provisional=true, auto-queue-runs=false y no speculative" \
  jq -e '.data.attributes.provisional == true and .data.attributes["auto-queue-runs"] == false and .data.attributes.speculative == false' "$(ls "${FAKE_HCP_POSTS}"/*-configuration-versions | head -1)"
check "upload: el archivo va a la URL de subida sin el token (la URL es la credencial)" \
  bash -c "grep -q '^PUT ${UPLOAD_URL}|$' '${FAKE_HCP_LOG}'"
check "upload: el archivo excluye .terraform/ y *.tfstate* y conserva el lockfile" \
  bash -c "l=\$(tar -tzf \"\$(ls '${FAKE_HCP_POSTS}'/*-UPLOAD-SECRET-c0ffee | head -1)\") && grep -q 'live/prod/main.tf' <<< \"\$l\" && grep -q 'modules/m/variables.tf' <<< \"\$l\" && grep -q '.terraform.lock.hcl' <<< \"\$l\" && ! grep -q 'providers/bin' <<< \"\$l\" && ! grep -q tfstate <<< \"\$l\""
SHA1=$(bash -c "$(upload_cmd)" 2> /dev/null | sed -n 's/^archive_sha256=//p')
upload_setup
touch -d '2001-01-01' "${TFDIR}/live/prod/main.tf"
SHA2=$(bash -c "$(upload_cmd)" 2> /dev/null | sed -n 's/^archive_sha256=//p')
check "upload: archivo reproducible (mismo contenido → mismo sha256, sin fechas)" test -n "${SHA1}" -a "${SHA1}" = "${SHA2}"
upload_fails() { # <descripción> <POST> <PUT> [GET...]
  local d="$1"; shift
  upload_setup "$@"
  if bash -c "$(upload_cmd)" > "${TMP}/up.out" 2> "${TMP}/up.err"; then ko "upload rechaza: ${d} (no falló)"
  elif grep -q UPLOAD-SECRET "${TMP}/up.out" "${TMP}/up.err"; then ko "upload rechaza: ${d} (imprimió la URL de subida)"
  else ok "upload rechaza: ${d}"; fi
}
upload_fails "HCP no registra la configuración como provisional (nunca se sube una no provisional)" "$(status 201 "$(cv_created '.data.attributes.provisional = false')")" "$(status 200 '')"
check "upload: sin provisional confirmado no se sube nada" test "$(grep -c '^PUT ' "${FAKE_HCP_LOG}" || true)" = 0
upload_fails "respuesta sin el atributo provisional" "$(status 201 "$(cv_created 'del(.data.attributes.provisional)')")" "$(status 200 '')"
upload_fails "configuración con auto-queue-runs" "$(status 201 "$(cv_created '.data.attributes["auto-queue-runs"] = true')")" "$(status 200 '')"
upload_fails "configuración speculative" "$(status 201 "$(cv_created '.data.attributes.speculative = true')")" "$(status 200 '')"
upload_fails "HCP rechaza provisional (HTTP 422): sin reintento no provisional" "$(status 422)" "$(status 200 '')"
check "upload: ante el rechazo se crea una sola configuración (sin recurrir a otra)" test "$(posted "/workspaces/${WS}/configuration-versions")" = 1
upload_fails "sin URL de subida" "$(status 201 "$(cv_created 'del(.data.attributes["upload-url"])')")" "$(status 200 '')"
upload_fails "la subida del archivo falla (HTTP 500)" "$(status 201 "$(cv_created)")" "$(status 500)"
reset_hcp
hcp_seq "POST ${CV_URL}" "$(status 201 "$(cv_created)")"
if bash -c "$(upload_cmd)" > "${TMP}/up.out" 2> "${TMP}/up.err"; then ko "upload rechaza: subida sin respuesta (no falló)"
elif grep -q UPLOAD-SECRET "${TMP}/up.out" "${TMP}/up.err"; then ko "upload rechaza: subida sin respuesta (imprimió la URL)"
else ok "upload rechaza: subida sin respuesta (sin imprimir la URL)"; fi
upload_fails "HCP no pudo procesarla (errored)" "$(status 201 "$(cv_created)")" "$(status 200 '')" "$(cv_state errored)"
upload_fails "no termina de procesarse a tiempo" "$(status 201 "$(cv_created)")" "$(status 200 '')" "$(cv_state pending)"
upload_fails "deja de ser provisional al procesarse" "$(status 201 "$(cv_created)")" "$(status 200 '')" "$(cv_state uploaded '.data.attributes.provisional = false')"
upload_fails "estado desconocido de la configuración" "$(status 201 "$(cv_created)")" "$(status 200 '')" "$(cv_state raro)"
# Temporales: el archivo, las respuestas y la URL de subida no quedan en disco (éxito, fallo
# o señal). TMPDIR apunta a un directorio vigilado; el script crea ahí su directorio privado.
UPTMP="${TMP}/upload-tmp"
upload_tmp_case() { # <descripción> <éxito|fallo> [VAR=valor...] -- [args de upload_setup...]
  local d="$1" exp="$2" rc=0 envs=()
  shift 2
  while [ "$#" -gt 0 ] && [ "$1" != -- ]; do envs+=("$1"); shift; done
  [ "${1:-}" != -- ] || shift
  rm -rf "${UPTMP}"; mkdir -p "${UPTMP}"
  upload_setup "$@"
  bash -c "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env TMPDIR='${UPTMP}' HCP_UPLOAD_ATTEMPTS=3 ${envs[*]:-} '${HCP}' upload '${TFDIR}'")" > "${TMP}/upt.out" 2> "${TMP}/upt.err" || rc=$?
  if { [ "${exp}" = éxito ] && [ "${rc}" -ne 0 ]; } || { [ "${exp}" = fallo ] && [ "${rc}" -eq 0 ]; }; then
    ko "upload temporales: ${d} (rc=${rc})"
  elif [ -n "$(find "${UPTMP}" -mindepth 1 -print -quit)" ]; then
    ko "upload temporales: ${d} (quedaron archivos: $(find "${UPTMP}" -mindepth 1 | head -3 | paste -sd' '))"
  elif grep -q UPLOAD-SECRET "${TMP}/upt.out" "${TMP}/upt.err"; then
    ko "upload temporales: ${d} (imprimió la URL de subida)"
  else
    ok "upload temporales: ${d}"
  fi
}
upload_tmp_case "éxito → no queda nada (archivo, respuestas con la URL)" éxito
upload_tmp_case "la subida falla (HTTP 500) → no queda nada" fallo -- "$(status 201 "$(cv_created)")" "$(status 500)"
upload_tmp_case "HCP rechaza provisional (422) → no queda nada" fallo -- "$(status 422)" "$(status 200 '')"
upload_tmp_case "configuración no provisional → no queda nada" fallo -- "$(status 201 "$(cv_created '.data.attributes.provisional = false')")" "$(status 200 '')"
upload_tmp_case "HCP no la procesa (errored) → no queda nada" fallo -- "$(status 201 "$(cv_created)")" "$(status 200 '')" "$(cv_state errored)"
upload_tmp_case "no termina de procesarse a tiempo → no queda nada" fallo -- "$(status 201 "$(cv_created)")" "$(status 200 '')" "$(cv_state pending)"
# Señal (TERM) durante la subida del archivo: el directorio privado se borra igual
rm -rf "${UPTMP}"; mkdir -p "${UPTMP}"
upload_setup
bash -c "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env TMPDIR='${UPTMP}' FAKE_CURL_SLEEP_ON=archivist.test FAKE_CURL_SLEEP=2 '${HCP}' upload '${TFDIR}'")" > /dev/null 2>&1 &
UPPID=$!
for _ in $(seq 1 50); do grep -q '^PUT ' "${FAKE_HCP_LOG}" 2> /dev/null && break; sleep 0.1; done
pkill -TERM -f "hcp-state.sh upload ${TFDIR}" 2> /dev/null || true
wait "${UPPID}" 2> /dev/null || true
sleep 0.2
check "upload temporales: terminado con TERM durante la subida → no queda nada" \
  test -z "$(find "${UPTMP}" -mindepth 1 -print -quit)"

# ── 4. Crear el saved plan: save-plan, auto-apply=false, configuración provisional ──
reset_hcp
CV_OK='{"data":{"id":"cv-Up1","attributes":{"status":"uploaded","speculative":false,"provisional":true}}}'
hcp_seq "GET ${API}/configuration-versions/${CV}" "${CV_OK}"
hcp_seq "POST ${API}/runs" "$(status 201 "$(run_json pending)")"
check "create-run: saved plan propio creado y descrito (id y enlace)" \
  bash -c "out=\$(eval \"\$0\") && grep -qx run_id=${RUN} <<< \"\$out\" && grep -q '^run_link=https://app.terraform.io/app/${ORG}/workspaces/${NAME}/runs/${RUN}$' <<< \"\$out\"" "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' create-run '${CV}'")"
check "create-run: pide save-plan=true, auto-apply=false, no plan-only, no destroy, con esta configuración y el mensaje propio" \
  jq -e --arg m "${MSG}" --arg ws "${WS}" --arg cv "${CV}" '.data.attributes["auto-apply"] == false and .data.attributes["plan-only"] == false and .data.attributes["is-destroy"] == false and .data.attributes["save-plan"] == true and .data.attributes.message == $m and .data.relationships.workspace.data.id == $ws and .data.relationships["configuration-version"].data.id == $cv' "$(ls "${FAKE_HCP_POSTS}"/*-runs | head -1)"
create_fails() { # <descripción> <respuesta del POST> [respuesta de la configuración]
  local cv="${CV_OK}"
  [ "$#" -lt 3 ] || cv="$3"
  hcp_seq "GET ${API}/configuration-versions/${CV}" "${cv}"
  hcp_seq "POST ${API}/runs" "$2"
  if run_hcp "${HCP}" create-run "${CV}" > /dev/null 2>&1; then ko "create-run rechaza: $1 (no falló)"; else ok "create-run rechaza: $1"; fi
}
create_fails "HCP registró el run con auto-apply=true" "$(status 201 "$(run_json pending true '.data.attributes["auto-apply"] = true')")"
create_fails "HCP no lo registró como saved plan" "$(status 201 "$(run_json pending true '.data.attributes["save-plan"] = false')")"
create_fails "respuesta sin save-plan" "$(status 201 "$(run_json pending true 'del(.data.attributes["save-plan"])')")"
create_fails "run de otro workspace" "$(status 201 "$(run_json pending true '.data.relationships.workspace.data.id = "ws-Otro"')")"
create_fails "run con otra configuración" "$(status 201 "$(run_json pending true '.data.relationships["configuration-version"].data.id = "cv-Otra"')")"
create_fails "run con otro mensaje (no es el propio)" "$(status 201 "$(run_json pending true '.data.attributes.message = "otro"')")"
create_fails "HCP rechaza la creación (HTTP 422)" "$(status 422)"
create_fails "configuración no subida" "$(status 201 "$(run_json pending)")" '{"data":{"attributes":{"status":"pending","speculative":false,"provisional":true}}}'
create_fails "configuración speculative" "$(status 201 "$(run_json pending)")" '{"data":{"attributes":{"status":"uploaded","speculative":true,"provisional":true}}}'
create_fails "configuración no provisional" "$(status 201 "$(run_json pending)")" '{"data":{"attributes":{"status":"uploaded","speculative":false,"provisional":false}}}'
reset_hcp
hcp_seq "GET ${API}/configuration-versions/${CV}" '{"data":{"attributes":{"status":"uploaded","speculative":false,"provisional":false}}}'
hcp_seq "POST ${API}/runs" "$(status 201 "$(run_json pending)")"
run_hcp "${HCP}" create-run "${CV}" > /dev/null 2>&1 || true
check "create-run: con una configuración no provisional no se crea ningún run" test "$(posted /runs)" = 0

# ── 5. Plan: saved plan con cambios, sin cambios, revisión saneada ──────────
plan_setup() { # <respuestas del run>...
  reset_hcp
  hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
  hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" '{"data":{"id":"sv-Old1"}}'
  hcp_seq "GET ${API}/runs/${RUN}" "$@"
  local out
  out="$(plan_output create)"
  hcp_seq "GET ${API}/plans/${PLAN}" "$(plan_json_for "${out}")"
  hcp_seq "GET ${API}/plans/${PLAN}/json-output" "${out}"
}
plan_with() { # <JSON del plan>: JSON del plan y conteos coherentes
  hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$1"
  hcp_seq "GET ${API}/plans/${PLAN}" "$(plan_json_for "$1")"
}
WATCH='pages_project=module.pages.cloudflare_pages_project.main pages_domain=module.pages.cloudflare_pages_domain.main server=module.server.hcloud_server.main'
plan_cmd() { # [FRESHNESS_CMD] [EXPECTED_STATE]
  printf '%q ' bash -c "${HCP_DEF}; hcp_env PLAN_WATCH='${WATCH}' FRESHNESS_CMD='${1:-}' EXPECTED_STATE='${2-sv-Old1}' PLAN_REVIEW_FILE='${TMP}/review.md' '${HCP}' plan '${RUN}'"
}
PLAN_CMD="$(plan_cmd)"
plan_setup "$(run_json planning)" "$(run_json planned)" "$(run_json planned_and_saved)"
check "plan: saved plan con cambios → has_changes=true con identidad (plan, configuración, estado de entrada) y conteos" \
  bash -c "out=\$(eval \"\$0\") && grep -qx has_changes=true <<< \"\$out\" && grep -qx plan_id=${PLAN} <<< \"\$out\" && grep -qx configuration_version_id=${CV} <<< \"\$out\" && grep -qx plan_state_version=sv-Old1 <<< \"\$out\" && grep -qx state_binding=relationship <<< \"\$out\" && grep -qx additions=4 <<< \"\$out\" && grep -qx review_creates=4 <<< \"\$out\" && grep -qx watch_pages_project=create <<< \"\$out\" && grep -qx watch_pages_domain=create <<< \"\$out\" && grep -qx watch_server=create <<< \"\$out\"" "${PLAN_CMD}"
check "plan: 'planned' de un saved plan no se confunde con la espera de confirmación (sigue esperando)" \
  test "$(grep -c "^GET ${API}/runs/${RUN}|" "${FAKE_HCP_LOG}")" = 3
check "plan: la revisión lista los cambios materiales (sin no-op) con valores clasificados" \
  bash -c "grep -q 'module.server.hcloud_server.main' '${TMP}/review.md' && grep -q '\`server_type\` = \`cx22\`' '${TMP}/review.md' && ! grep -q 'zone_setting.ssl' '${TMP}/review.md'"
check "plan: nunca confirma, descarta ni cancela (solo lecturas)" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
# Confidencialidad: el JSON del plan trae valores reales; nada de eso sale
plan_setup "$(run_json planned_and_saved)"
bash -c "${PLAN_CMD}" > "${TMP}/plan.out" 2> "${TMP}/plan.err" || true
check "confidencialidad: ningún canario (user_data sensible, content, output) en la revisión, la salida ni el log" \
  bash -c "! grep -q CANARY '${TMP}/review.md' '${TMP}/plan.out' '${TMP}/plan.err'"
check "confidencialidad: el atributo sensible y el no clasificado aparecen solo como ruta" \
  bash -c "grep -q '\`user_data\` (sensible)' '${TMP}/review.md' && grep -q '\`content\`' '${TMP}/review.md' && grep -q '\`server_ip\` (create)' '${TMP}/review.md'"
# Rutas sensibles con claves dinámicas: ni la clave ni el valor se publican (cambios,
# reemplazos, creaciones, desconocidos, objeto entero); la estructura no sensible sí
REVIEW="${ROOT}/.github/scripts/plan-review.sh"
jq -cn '
  def rc($addr; $type; $acts; $c): {address: $addr, type: $type, change: ({actions: $acts, after_unknown: {}} + $c)};
  {resource_changes: [
    rc("module.pages.cloudflare_pages_project.main"; "cloudflare_pages_project"; ["update"];
      {before: {config: {"SECRET_CANARY_123456": "CANARY-VAL-OLD"}, labels: {env: "a"}},
       after: {config: {"SECRET_CANARY_123456": "CANARY-VAL-NEW"}, labels: {env: "b"}},
       before_sensitive: {config: {"SECRET_CANARY_123456": true}}, after_sensitive: {config: {"SECRET_CANARY_123456": true}}}),
    rc("module.a.cloudflare_pages_project.env"; "cloudflare_pages_project"; ["update"];
      {before: {env: {vars: {"KEYCANARY_DYN": {value: "CANARY-V1"}}}}, after: {env: {vars: {"KEYCANARY_DYN": {value: "CANARY-V2"}}}},
       before_sensitive: {env: {vars: {"KEYCANARY_DYN": {value: true}}}}, after_sensitive: {env: {vars: {"KEYCANARY_DYN": {value: true}}}}}),
    rc("module.a.cloudflare_pages_project.whole"; "cloudflare_pages_project"; ["update"];
      {before: {settings: {"CANARY_WHOLE_KEY": "x"}}, after: {settings: {"CANARY_WHOLE_KEY": "y"}},
       before_sensitive: {settings: true}, after_sensitive: {settings: true}}),
    rc("module.a.cloudflare_pages_project.rep"; "cloudflare_pages_project"; ["delete", "create"];
      {before: {secrets: {"CANARY_RP_KEY_9": "CANARY-RP-OLD"}, name: "piedrazul"}, after: {secrets: {"CANARY_RP_KEY_9": "CANARY-RP-NEW"}, name: "piedrazul"},
       before_sensitive: {secrets: {"CANARY_RP_KEY_9": true}}, after_sensitive: {secrets: {"CANARY_RP_KEY_9": true}}})
      + {action_reason: "replace_because_cannot_update", replace_paths: [["secrets", "CANARY_RP_KEY_9"]]},
    rc("module.a.hcloud_server.new"; "hcloud_server"; ["create"];
      {before: null, after: {name: "piedrazul-prod", tags: {"CANARY_CREATE_KEY": "CANARY-CREATE-VAL"}, user_data: "CANARY-UD"},
       before_sensitive: false, after_sensitive: {tags: {"CANARY_CREATE_KEY": true}, user_data: true}}),
    rc("module.a.cloudflare_pages_project.unk"; "cloudflare_pages_project"; ["update"];
      {before: {conf: {}}, after: {conf: {}}, after_unknown: {conf: {"CANARY_UNK_KEY": true}},
       before_sensitive: {}, after_sensitive: {conf: {"CANARY_UNK_KEY": true}}}),
    rc("module.a.cloudflare_pages_project.root"; "cloudflare_pages_project"; ["update"];
      {before: {"CANARY_ROOT_KEY": "a"}, after: {"CANARY_ROOT_KEY": "b"}, before_sensitive: true, after_sensitive: true}),
    rc("module.a.hcloud_server.gone"; "hcloud_server"; ["delete"];
      {before: {name: "canary-sensitive-name"}, after: null, before_sensitive: {name: true}, after_sensitive: false})
  ]}' > "${TMP}/sens-plan.json"
"${REVIEW}" "${TMP}/sens-plan.json" "${TMP}/sens-review.md" > "${TMP}/sens.out" 2> "${TMP}/sens.err" || true
check "confidencialidad: claves dinámicas sensibles (cambio, descendiente, objeto entero, reemplazo, creación, desconocido, raíz) y un valor clasificado pero sensible (destrucción) nunca aparecen" \
  bash -c "[ -s '${TMP}/sens-review.md' ] && ! grep -qi CANARY '${TMP}/sens-review.md' '${TMP}/sens.out' '${TMP}/sens.err'"
check "confidencialidad: una ruta sensible se reduce a su atributo de primer nivel (también en las rutas que fuerzan el reemplazo)" \
  bash -c "r='${TMP}/sens-review.md'; grep -q '\`config\` (sensible)' \$r && grep -q '\`env\` (sensible)' \$r && grep -q '\`settings\` (sensible)' \$r && grep -q 'fuerzan el reemplazo: \`secrets\` (sensible)' \$r && grep -q '\`tags\`' \$r && grep -q '\`user_data\` (sensible)' \$r && grep -q '\`conf\` (sensible)' \$r && grep -q '(atributos sensibles)' \$r"
check "confidencialidad: la estructura no sensible se conserva (ruta anidada y valor clasificado)" \
  bash -c "grep -q '\`labels.env\`' '${TMP}/sens-review.md' && grep -q '\`name\` = \`piedrazul-prod\`' '${TMP}/sens-review.md'"
# Diagnósticos: un error nunca cita valores del plan
jq -cn '{resource_changes: [{address: {"x": "CANARY-ADDR-OBJ"}, type: "t", change: {actions: ["create"]}}]}' > "${TMP}/bad1.json"
jq -cn '{resource_changes: [], resource_drift: [{address: "a.b", change: {actions: [{"k": "CANARY-DRIFT-ERR"}]}}]}' > "${TMP}/bad2.json"
jq -cn '{resource_changes: [{address: "a.b", type: "t", change: {actions: ["create", "CANARY-ACTION"]}}]}' > "${TMP}/bad3.json"
for b in bad1 bad2 bad3; do
  if "${REVIEW}" "${TMP}/${b}.json" "${TMP}/${b}.md" > "${TMP}/${b}.out" 2> "${TMP}/${b}.err"; then ko "diagnóstico ${b}: el plan inesperado no falló"
  elif grep -q CANARY "${TMP}/${b}.out" "${TMP}/${b}.err" "${TMP}/${b}.md" 2> /dev/null; then ko "diagnóstico ${b}: el error cita un valor del plan"
  else ok "diagnóstico ${b}: falla sin citar valores del plan ($(head -c 90 "${TMP}/${b}.err"))"; fi
done
plan_setup "$(run_json planned_and_saved)"
plan_with "$(cat "${TMP}/sens-plan.json")"
bash -c "${PLAN_CMD}" > "${TMP}/sensplan.out" 2> "${TMP}/sensplan.err" || true
check "confidencialidad: por hcp-state.sh plan (revisión, salida y log) tampoco aparecen claves ni valores sensibles" \
  bash -c "grep -qx has_changes=true '${TMP}/sensplan.out' && ! grep -q CANARY '${TMP}/review.md' '${TMP}/sensplan.out' '${TMP}/sensplan.err'"
plan_with "$(cat "${TMP}/bad2.json")"
bash -c "${PLAN_CMD}" > "${TMP}/badplan.out" 2> "${TMP}/badplan.err" || true
check "diagnóstico: por hcp-state.sh plan, un error interno de la revisión tampoco cita valores" \
  bash -c "grep -q 'revisión saneada' '${TMP}/badplan.err' && ! grep -q CANARY '${TMP}/badplan.out' '${TMP}/badplan.err'"
mkdir -p "${TMP}/plan-tmp"
plan_setup "$(run_json planned_and_saved)"
bash -c "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env TMPDIR='${TMP}/plan-tmp' EXPECTED_STATE=sv-Old1 PLAN_REVIEW_FILE='${TMP}/review.md' '${HCP}' plan '${RUN}'")" > /dev/null 2>&1 || true
check "confidencialidad: el JSON del plan (con valores sensibles) no queda en disco al terminar" \
  bash -c "! grep -rlq CANARY '${TMP}/plan-tmp'"
plan_setup "$(run_json planned_and_saved)"
plan_with "$(plan_output none)"
check "plan: un plan que no crea el proyecto de Pages lo informa (watch_pages_project=none)" \
  bash -c "eval \"\$0\" | grep -qx watch_pages_project=none" "${PLAN_CMD}"
plan_with "$(plan_output none create)"
check "plan: proyecto existente y dominio por crear → watch_pages_project=none, watch_pages_domain=create" \
  bash -c "out=\$(eval \"\$0\") && grep -qx watch_pages_project=none <<< \"\$out\" && grep -qx watch_pages_domain=create <<< \"\$out\"" "${PLAN_CMD}"
plan_with "$(plan_output create | jq -c '.resource_changes += [{address: "module.pages.cloudflare_pages_project.main", type: "cloudflare_pages_project", change: {actions: ["delete"], before: {name: "x"}, after: null}}]')"
check "plan: dirección vigilada repetida → ambiguous" \
  bash -c "eval \"\$0\" | grep -qx watch_pages_project=ambiguous" "${PLAN_CMD}"
server_plan() { # <acciones del servidor como JSON | none | dup>: el plan base con esa acción sobre el servidor
  case "$1" in
    none) plan_output create | jq -c '.resource_changes |= map(select(.address != "module.server.hcloud_server.main"))' ;;
    dup) plan_output create | jq -c '.resource_changes += [.resource_changes[0]]' ;;
    *) plan_output create | jq -c --argjson a "$1" '.resource_changes |= map(if .address == "module.server.hcloud_server.main" then .change.actions = $a | (if $a != ["create"] then .change.before = .change.after else . end) else . end)' ;;
  esac
}
# El estado previo (sv-Old1) existe: la creación se decide por la acción del plan, no por
# la ausencia de state version (un state version puede no tener recursos)
plan_setup "$(run_json planned_and_saved)"
check "plan: estado previo existente (sin recursos) y el plan crea el servidor → watch_server=create" \
  bash -c "out=\$(eval \"\$0\") && grep -qx plan_state_version=sv-Old1 <<< \"\$out\" && grep -qx watch_server=create <<< \"\$out\"" "${PLAN_CMD}"
for c in 'replace|["delete","create"]' 'replace|["create","delete"]' 'no-op|["no-op"]' 'update|["update"]' 'none|none' 'ambiguous|dup'; do
  want="${c%%|*}" acts="${c#*|}"
  plan_setup "$(run_json planned_and_saved)"
  plan_with "$(server_plan "${acts}")"
  check "plan: servidor con acción ${acts} → watch_server=${want}" \
    bash -c "eval \"\$0\" | grep -qx watch_server=${want}" "${PLAN_CMD}"
done
plan_setup "$(run_json planned_and_saved)"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(status 403)"
fails "plan con cambios: JSON del plan ilegible para el token → falla (sin revisión no hay Apply)" bash -c "${PLAN_CMD}"
hcp_clear "GET ${API}/plans/${PLAN}/json-output"
fails "plan con cambios: sin JSON del plan (sin respuesta) → falla" bash -c "${PLAN_CMD}"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" '{"resource_changes":[{"address":"x","change":{}}]}'
fails "plan con cambios: JSON del plan malformado → falla" bash -c "${PLAN_CMD}"
plan_setup "$(run_json planned_and_saved)"
hcp_seq "GET ${API}/plans/${PLAN}" '{"data":{"attributes":{"status":"finished","resource-additions":9,"resource-changes":0,"resource-destructions":0,"resource-imports":0}}}'
fails "plan rechaza: los cambios del JSON no coinciden con los conteos del plan de HCP" bash -c "${PLAN_CMD}"
plan_setup "$(run_json planned_and_saved)"
plan_with '{"resource_changes":[{"address":"a.b","type":"t","change":{"actions":["create","forget"],"before":null,"after":{}}}]}'
fails "plan rechaza: acción de Terraform desconocida (la revisión no puede describirla)" bash -c "${PLAN_CMD}"
plan_setup "$(run_json planned_and_saved)"
plan_with "$(jq -cn '{resource_changes: [range(0; 151) | {address: "null_resource.r[\(.)]", type: "null_resource", change: {actions: ["create"], before: null, after: {}}}]}')"
fails "plan rechaza: revisión demasiado grande para leerse con seguridad" bash -c "${PLAN_CMD}"
plan_setup "$(run_json planned_and_saved)"
plan_with '{"resource_changes":[],"deferred_changes":[{"reason":"x"}]}'
fails "plan rechaza: cambios diferidos" bash -c "${PLAN_CMD}"

# ── 5b. Ligadura del saved plan al estado (input-state-version opcional) ────
# Evidencia real (2026-10-28): un saved plan planned_and_saved sin la relación
# input-state-version. El plan se liga al estado capturado antes de crear el run si sigue
# siendo el actual después del plan; una relación informada distinta es una contradicción.
no_rel() { echo 'del(.data.relationships["input-state-version"])'; }
set_rel() { echo ".data.relationships[\"input-state-version\"] = $1"; }
state_now() { # <sv-…|none|lista no vacía|lista 403|500>: estado actual que lee el plan
  case "$1" in
    none) hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"; hcp_seq "GET ${STATE_LIST_URL}" '{"data":[]}' ;;
    listed) hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"; hcp_seq "GET ${STATE_LIST_URL}" '{"data":[{"id":"sv-X"}]}' ;;
    list403) hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"; hcp_seq "GET ${STATE_LIST_URL}" "$(status 403)" ;;
    500) hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 500)" ;;
    *) hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "{\"data\":{\"id\":\"$1\"}}" ;;
  esac
}
bind_case() { # <descripción> <pasa|falla> <EXPECTED_STATE> <estado actual> <ajuste jq del run> [salidas esperadas...]
  local d="$1" exp="$2" es="$3" now="$4" adj="$5" out rc=0 line
  shift 5
  plan_setup "$(run_json planning true "${adj}")" "$(run_json planned_and_saved true "${adj}")"
  state_now "${now}"
  out=$(bash -c "$(plan_cmd '' "${es}")" 2> "${TMP}/bind.err") || rc=$?
  if [ "${exp}" = pasa ]; then
    [ "${rc}" -eq 0 ] || { ko "ligadura: ${d} (rc=${rc}: $(tail -1 "${TMP}/bind.err"))"; return; }
    for line in "$@"; do grep -qx -- "${line}" <<< "${out}" || { ko "ligadura: ${d} (falta ${line})"; return; }; done
  else
    [ "${rc}" -ne 0 ] || { ko "ligadura: ${d} (no falló)"; return; }
    ! grep -q '^plan_state_version=' <<< "${out}" || { ko "ligadura: ${d} (publicó plan_state_version)"; return; }
  fi
  [ "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0 ] || { ko "ligadura: ${d} (hizo un POST)"; return; }
  ok "ligadura: ${d}"
}
# La respuesta real observada: planned_and_saved, save-plan, has-changes, sin la relación
LIVE_ADJ='del(.data.relationships["input-state-version"]) | .data.attributes["has-changes"] = true'
bind_case "evidencia real: planned_and_saved sin input-state-version y estado capturado sin cambios → revisión completa (bracketed)" pasa sv-Old1 sv-Old1 "${LIVE_ADJ}" \
  has_changes=true run_status=planned_and_saved plan_state_version=sv-Old1 state_binding=bracketed plan_id="${PLAN}" configuration_version_id="${CV}" review_creates=4
check "ligadura: sin relación, la revisión saneada se genera igual (efectos listados)" grep -q 'module.server.hcloud_server.main' "${TMP}/review.md"
bind_case "relación presente sin clave data → no informada (bracketed), nunca none" pasa sv-Old1 sv-Old1 "$(set_rel '{links: {related: "/x"}}')" plan_state_version=sv-Old1 state_binding=bracketed
bind_case "relación presente e igual al estado capturado → relationship" pasa sv-Old1 sv-Old1 . plan_state_version=sv-Old1 state_binding=relationship
bind_case "relación presente y CONTRADICTORIA (sv-Otro) con el estado actual igual al capturado → falla (sin fallback)" falla sv-Old1 sv-Old1 "$(set_rel '{data: {id: "sv-Otro", type: "state-versions"}}')"
bind_case "relación nula (sin estado) pero se capturó sv-Old1 → contradicción, falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: null}')"
bind_case "relación sv-Old1 pero se capturó none → contradicción, falla" falla none none .
bind_case "estado cambió durante el plan (sin relación): actual sv-New9 → falla" falla sv-Old1 sv-New9 "$(no_rel)"
bind_case "estado cambió durante el plan (relación igual a la captura): actual sv-New9 → falla" falla sv-Old1 sv-New9 .
bind_case "no se puede leer el estado actual (HTTP 500) → falla" falla sv-Old1 500 "$(no_rel)"
bind_case "relación malformada (data no es objeto) → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: "sv-Old1"}')"
bind_case "relación malformada (id inválido) → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: {id: "nope", type: "state-versions"}}')"
bind_case "relación con tipo incorrecto (runs) aunque el id coincida → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: {id: "sv-Old1", type: "runs"}}')"
bind_case "relación sin tipo aunque el id coincida → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: {id: "sv-Old1"}}')"
bind_case "relación con tipo no textual aunque el id coincida → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: {id: "sv-Old1", type: null}}')"
bind_case "relación sin id (solo tipo) → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: {type: "state-versions"}}')"
bind_case "relación presente con valor null → falla (no es ausencia)" falla sv-Old1 sv-Old1 "$(set_rel 'null')"
bind_case "relationships null → falla (no es ausencia)" falla sv-Old1 sv-Old1 '.data.relationships = null'
# Parser aislado: ausencia real frente a estructura inválida (sin depender de otras validaciones)
parse_input_state() { # <run JSON (data)> → salida de input_state, o FAIL
  bash -c 'fail() { echo FAIL; exit 1; }; '"$(sed -n '/^input_state() {/,/^}/p' "${HCP}")"'; input_state "$1"' _ "$1" 2> /dev/null || true
}
parse_case() { # <JSON> <esperado: unreported|none|sv-…|FAIL>
  local got
  got=$(parse_input_state "$1")
  if [ "${got}" = "$2" ]; then ok "input_state: $1 → $2"; else ko "input_state: $1 → '${got}', esperado $2"; fi
}
parse_case '{"id":"run-X","relationships":{}}' unreported
parse_case '{"id":"run-X","relationships":{"input-state-version":{}}}' unreported
parse_case '{"id":"run-X","relationships":{"input-state-version":{"links":{"related":"/x"}}}}' unreported
parse_case '{"id":"run-X","relationships":{"input-state-version":{"data":null}}}' none
parse_case '{"id":"run-X","relationships":{"input-state-version":{"data":{"id":"sv-A1","type":"state-versions"}}}}' sv-A1
parse_case '{"id":"run-X","relationships":{"input-state-version":null}}' FAIL
parse_case '{"id":"run-X","relationships":null}' FAIL
parse_case '{"id":"run-X"}' FAIL
parse_case '{"id":"run-X","relationships":[]}' FAIL
parse_case '{"id":"run-X","relationships":"x"}' FAIL
parse_case '{"id":"run-X","relationships":{"input-state-version":[]}}' FAIL
parse_case '{"id":"run-X","relationships":{"input-state-version":"sv-A1"}}' FAIL
parse_case '{"id":"run-X","relationships":{"input-state-version":{"data":{"id":"sv-A1","type":"runs"}}}}' FAIL
parse_case '{"id":"run-X","relationships":{"input-state-version":{"data":{"id":"sv-A1"}}}}' FAIL
bind_case "relación malformada (data es una lista) → falla" falla sv-Old1 sv-Old1 "$(set_rel '{data: [{id: "sv-Old1"}]}')"
bind_case "relación malformada (no es objeto) → falla" falla sv-Old1 sv-Old1 "$(set_rel '"sv-Old1"')"
bind_case "EXPECTED_STATE ausente → falla, nunca none" falla "" sv-Old1 "$(no_rel)"
bind_case "EXPECTED_STATE malformado ('None') → falla" falla None none "$(no_rel)"
bind_case "EXPECTED_STATE malformado ('sv-') → falla" falla sv- sv-Old1 "$(no_rel)"
bind_case "EXPECTED_STATE con caracteres extra → falla" falla 'sv-Old1;x' sv-Old1 "$(no_rel)"
check "ligadura: EXPECTED_STATE inválido falla antes de leer el run" bash -c "! grep -q '^GET ${API}/runs/' '${FAKE_HCP_LOG}'"
# Instalación nueva (none) frente a un estado existente vacío (sv-…, cero recursos)
bind_case "instalación nueva: relación nula y none confirmado por dos lecturas → none (relationship)" pasa none none "$(set_rel '{data: null}')" plan_state_version=none state_binding=relationship
bind_case "instalación nueva sin relación: none confirmado por dos lecturas → none (bracketed)" pasa none none "$(no_rel)" plan_state_version=none state_binding=bracketed
bind_case "none capturado pero la lista de state versions no está vacía → falla" falla none listed "$(no_rel)"
bind_case "none capturado y la lista de state versions responde 403 → falla (nunca 'vacío')" falla none list403 "$(no_rel)"
bind_case "none capturado pero ahora hay un state version (vacío o no) → falla" falla none sv-Empty0 "$(no_rel)"
bind_case "estado existente vacío (sv-Empty0, cero recursos) sin cambios → sv-Empty0, no none" pasa sv-Empty0 sv-Empty0 "$(no_rel)" plan_state_version=sv-Empty0 state_binding=bracketed
bind_case "estado existente vacío capturado pero el actual dice none → falla" falla sv-Empty0 none "$(no_rel)"
# Sin cambios: terminal igual que antes, también sin relación
plan_setup "$(run_json planned_and_finished false "$(no_rel)")"
plan_with '{"resource_changes":[]}'
check "ligadura: plan sin cambios sin relación y estado sin cambios → has_changes=false, terminal, sin POST" \
  bash -c "out=\$(eval \"\$0\") && grep -qx has_changes=false <<< \"\$out\" && grep -qx run_status=planned_and_finished <<< \"\$out\" && grep -qx state_binding=bracketed <<< \"\$out\" && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "${PLAN_CMD}"
plan_setup "$(run_json planned_and_finished false "$(no_rel)")"
plan_with '{"resource_changes":[]}'
state_now sv-New9
fails "ligadura: plan sin cambios y el estado cambió → falla (la cadena no sigue con otro estado)" bash -c "${PLAN_CMD}"
# Estados terminales o inesperados sin relación: nunca llegan a la ligadura ni al Apply
for st in errored discarded canceled force_canceled policy_soft_failed weird_state; do
  plan_setup "$(run_json "${st}" true "$(no_rel)")"
  if bash -c "${PLAN_CMD}" > "${TMP}/bind.out" 2> /dev/null; then ko "ligadura: run ${st} sin relación → falla (no falló)"
  elif grep -q '^plan_state_version=' "${TMP}/bind.out"; then ko "ligadura: run ${st} sin relación publicó plan_state_version"
  else ok "ligadura: run ${st} sin relación → falla sin publicar el plan"; fi
done
plan_setup "$(run_json apply_queued true "$(no_rel)")" "$(run_json applied true "$(no_rel)")"
if bash -c "${PLAN_CMD}" > "${TMP}/bind.out" 2> /dev/null; then ko "ligadura: confirmado fuera de GitHub sin relación → falla (no falló)"; else ok "ligadura: confirmado fuera de GitHub sin relación → falla"; fi
check "ligadura: confirmación ajena sin relación se informa out_of_band, sin POST" \
  bash -c "grep -qx apply_request=out_of_band '${TMP}/bind.out' && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]"
PLAN_CMD="$(plan_cmd)"

# Sin cambios: planned_and_finished es terminal; no se descarta nada
plan_setup "$(run_json planning false)" "$(run_json planned_and_finished false)"
plan_with '{"resource_changes":[{"address":"module.zone_settings.cloudflare_zone_setting.ssl","type":"cloudflare_zone_setting","change":{"actions":["no-op"],"before":{"value":"strict"},"after":{"value":"strict"}}}]}'
check "plan sin cambios: planned_and_finished → has_changes=false, terminal" \
  bash -c "out=\$(eval \"\$0\") && grep -qx has_changes=false <<< \"\$out\" && grep -qx run_status=planned_and_finished <<< \"\$out\" && grep -q 'Sin cambios de recursos' '${TMP}/review.md'" "${PLAN_CMD}"
check "plan sin cambios: no intenta descartar ni cancelar" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
plan_setup "$(run_json planned_and_finished false)"
hcp_seq "GET ${API}/plans/${PLAN}/json-output" "$(status 403)"
hcp_seq "GET ${API}/plans/${PLAN}" '{"data":{"attributes":{"status":"finished","resource-additions":0,"resource-changes":0,"resource-destructions":0,"resource-imports":0}}}'
check "plan sin cambios: JSON ilegible no bloquea (no hay nada que revisar ni aplicar)" \
  bash -c "eval \"\$0\" | grep -qx has_changes=false" "${PLAN_CMD}"
plan_fails() { # <descripción> <respuestas del run>...
  local d="$1"; shift
  plan_setup "$@"
  if bash -c "${PLAN_CMD}" > /dev/null 2>&1; then ko "plan rechaza: ${d} (no falló)"; else ok "plan rechaza: ${d}"; fi
}
plan_fails "saved plan sin cambios en planned_and_saved (resultado no admitido)" "$(run_json planned_and_saved false)"
plan_fails "saved plan con cambios no confirmable" "$(run_json planned_and_saved true '.data.attributes.actions["is-confirmable"] = false')"
plan_fails "planned_and_finished que informa cambios (contradicción)" "$(run_json planned_and_finished true)"
plan_fails "plan fallido (errored)" "$(run_json planning)" "$(run_json errored)"
plan_fails "run descartado antes de terminar la revisión" "$(run_json discarded)"
plan_fails "run cancelado" "$(run_json canceled)"
plan_fails "override de políticas pendiente" "$(run_json policy_override)"
plan_fails "decisión de run tasks pendiente" "$(run_json post_plan_awaiting_decision)"
plan_fails "estado desconocido" "$(run_json weird_state)"
plan_fails "no termina dentro del plazo" "$(run_json planning)"
plan_fails "run ajeno (otro mensaje)" "$(run_json planned_and_saved true '.data.attributes.message = "otro"')"
plan_fails "run con auto-apply=true" "$(run_json planned_and_saved true '.data.attributes["auto-apply"] = true')"
plan_fails "run que no es saved plan" "$(run_json planned_and_saved true '.data.attributes["save-plan"] = false')"
plan_fails "run de otro workspace" "$(run_json planned_and_saved true '.data.relationships.workspace.data.id = "ws-Otro"')"
plan_setup "$(run_json planned_and_saved)"
hcp_seq "GET ${API}/plans/${PLAN}" '{"data":{"attributes":{"status":"running"}}}'
fails "plan rechaza: el plan del run no terminó" bash -c "${PLAN_CMD}"

# Confirmado fuera de GitHub antes de terminar la revisión: se observa, no se toca, falla
plan_setup "$(run_json apply_queued)" "$(run_json applying)" "$(run_json applied)"
if bash -c "${PLAN_CMD}" > "${TMP}/oob.out" 2> "${TMP}/oob.err"; then ko "plan: confirmado fuera de GitHub → falla (no falló)"; else ok "plan: confirmado fuera de GitHub → falla (nunca se atribuye a GitHub)"; fi
check "plan: confirmación fuera de GitHub observada hasta que termina, informada, sin cancelar ni descartar" \
  bash -c "grep -qx apply_request=out_of_band '${TMP}/oob.out' && grep -qx run_status=applied '${TMP}/oob.out' && grep -q '::error title=Terraform::.*fuera de la autorización de GitHub' '${TMP}/oob.err' && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]"

# Frescura: un commit viejo no sigue; su saved plan se descarta
fresh_plan_setup() { plan_setup "$@"; hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 202 '')"; }
fresh_plan_setup "$(run_json planned_and_saved)"
fails "plan + frescura: main avanzó con cambios → falla" bash -c "$(plan_cmd "${TMP}/bin/fresh-no")"
check "plan + frescura: ese saved plan se descarta (sin Apply)" test "$(posted "/runs/${RUN}/actions/discard")" = 1
fresh_plan_setup "$(run_json planned_and_finished false)"
fails "plan + frescura: main avanzó sin cambios → la cadena no sigue con un commit viejo" bash -c "$(plan_cmd "${TMP}/bin/fresh-no")"
check "plan + frescura: sin cambios → nada que descartar" test "$(posted "/runs/${RUN}/actions/discard")" = 0
fresh_plan_setup "$(run_json planned_and_saved)"
check "plan + frescura: commit vigente → sin descarte" \
  bash -c "eval \"\$0\" > /dev/null && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(plan_cmd "${TMP}/bin/fresh-yes")"

# ── 6. Apply: una sola confirmación, después de revalidar ────────────────────
apply_setup() { # <respuestas GET del run>...
  reset_hcp
  hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
  hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" '{"data":{"id":"sv-Old1"}}'
  hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json planned_and_saved)" "$(run_json applied true '.data.id = "run-Prev1"')")"
  hcp_seq "GET ${API}/runs/${RUN}" "$@"
  hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 202 '')"
}
apply_cmd() { # [VAR=valor...]
  printf '%q ' bash -c "${HCP_DEF}; hcp_env EXPECTED_PLAN_ID='${PLAN}' EXPECTED_CONFIGURATION_VERSION='${CV}' EXPECTED_STATE=sv-Old1 FRESHNESS_CMD='${TMP}/bin/fresh-yes' HCP_ACK_ATTEMPTS=3 APPLY_COMMENT='Aprobado en GitHub: run 77/1' $* '${HCP}' apply '${RUN}'"
}
applies() { posted "/runs/${RUN}/actions/apply"; }
SAVED="$(run_json planned_and_saved)"
apply_setup "${SAVED}" "${SAVED}" "$(run_json apply_queued)" "$(run_json applying)"
check "apply: saved plan revisado y vigente → una sola solicitud, aceptada cuando HCP lo muestra confirmado (aceptar no es aplicar: sin estado ni resultado final)" \
  bash -c "out=\$(eval \"\$0\") && grep -qx apply_request=accepted <<< \"\$out\" && grep -qx run_status=apply_queued <<< \"\$out\" && ! grep -q state_version_id <<< \"\$out\"" "$(apply_cmd)"
check "apply: exactamente un POST de Apply, con el comentario de la aprobación de GitHub, sin descartes" \
  bash -c "[ \"\$(grep -c '^POST ${API}/runs/${RUN}/actions/apply|' '${FAKE_HCP_LOG}')\" = 1 ] && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}')\" = 1 ] && jq -e '.comment | test(\"Aprobado en GitHub\")' \"\$(ls '${FAKE_HCP_POSTS}'/*-apply | head -1)\""
apply_fails() { # <descripción> <POSTs de Apply esperados> <cmd> <respuestas GET del run>...
  local d="$1" n="$2" cmd="$3"
  shift 3
  apply_setup "$@"
  if bash -c "${cmd}" > "${TMP}/apply.out" 2> "${TMP}/apply.err"; then
    ko "apply rechaza: ${d} (no falló)"
  elif [ "$(applies)" != "${n}" ]; then
    ko "apply rechaza: ${d} (POST de Apply: $(applies), esperado ${n})"
  elif grep -q '^POST .*/actions/\(discard\|cancel\)' "${FAKE_HCP_LOG}"; then
    ko "apply rechaza: ${d} (descartó o canceló)"
  else
    ok "apply rechaza: ${d}"
  fi
}
A="$(apply_cmd)"
# Revalidación antes de la solicitud: nada se confirma
apply_fails "otro plan que el revisado" 0 "$(apply_cmd EXPECTED_PLAN_ID=plan-Otro)" "${SAVED}"
apply_fails "otra configuración que la revisada" 0 "$(apply_cmd EXPECTED_CONFIGURATION_VERSION=cv-Otra)" "${SAVED}"
apply_fails "el plan partió de otro estado" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships["input-state-version"].data.id = "sv-Otro"')"
apply_fails "el estado del workspace cambió desde el plan" 0 "$(apply_cmd EXPECTED_STATE=sv-Prev0)" "$(run_json planned_and_saved true '.data.relationships["input-state-version"].data.id = "sv-Prev0"')"
NOREL_SAVED="$(run_json planned_and_saved true 'del(.data.relationships["input-state-version"])')"
apply_fails "sin relación de entrada y el estado cambió desde el plan (aprobación tardía)" 0 "$(apply_cmd EXPECTED_STATE=sv-Prev0)" "${NOREL_SAVED}"
apply_fails "relación nula (sin estado) pero el plan revisado es sv-Old1 → contradicción" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships["input-state-version"].data = null')"
apply_fails "relación malformada" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships["input-state-version"].data = {id: "nope", type: "state-versions"}')"
apply_fails "relación con tipo incorrecto (runs) aunque el id coincida" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships["input-state-version"].data.type = "runs"')"
apply_fails "relación presente con valor null (no es ausencia)" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships["input-state-version"] = null')"
apply_fails "relationships null (no es ausencia)" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships = null')"
apply_fails "relación sin tipo aunque el id coincida" 0 "${A}" "$(run_json planned_and_saved true 'del(.data.relationships["input-state-version"].data.type)')"
apply_fails "EXPECTED_STATE ausente" 0 "$(apply_cmd EXPECTED_STATE=)" "${NOREL_SAVED}"
apply_fails "EXPECTED_STATE malformado" 0 "$(apply_cmd EXPECTED_STATE=None)" "${NOREL_SAVED}"
apply_fails "plan revisado none pero el workspace ahora tiene estado" 0 "$(apply_cmd EXPECTED_STATE=none)" "${NOREL_SAVED}"
apply_setup "${NOREL_SAVED}" "${NOREL_SAVED}" "$(run_json apply_queued true 'del(.data.relationships["input-state-version"])')"
check "apply: sin relación de entrada (evidencia real) y estado sin cambios → una sola solicitud, aceptada" \
  bash -c "out=\$(eval \"\$0\") && grep -qx apply_request=accepted <<< \"\$out\" && [ \"\$(grep -c '^POST ${API}/runs/${RUN}/actions/apply|' '${FAKE_HCP_LOG}')\" = 1 ]" "${A}"
apply_setup "${NOREL_SAVED}" "${NOREL_SAVED}" "$(run_json apply_queued true 'del(.data.relationships["input-state-version"])')"
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"
hcp_seq "GET ${STATE_LIST_URL}" '{"data":[]}'
check "apply: instalación nueva (none confirmado por dos lecturas, sin relación) → una sola solicitud" \
  bash -c "eval \"\$0\" | grep -qx apply_request=accepted && [ \"\$(grep -c '^POST ${API}/runs/${RUN}/actions/apply|' '${FAKE_HCP_LOG}')\" = 1 ]" "$(apply_cmd EXPECTED_STATE=none)"
apply_setup "${NOREL_SAVED}"
hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" "$(status 404)"
hcp_seq "GET ${STATE_LIST_URL}" "$(status 403)"
fails "apply rechaza: none esperado pero la ausencia de estado no se puede confirmar (lista 403)" bash -c "$(apply_cmd EXPECTED_STATE=none)"
check "apply: ausencia no confirmada → ninguna solicitud" test "$(applies)" = 0
# Después de la última lectura del estado: HCP descarta el saved plan y la última lectura del run lo ve
apply_fails "el estado cambia tras la revalidación: HCP descarta el saved plan antes de la solicitud" 0 "${A}" "${NOREL_SAVED}" "$(run_json discarded true 'del(.data.relationships["input-state-version"])')"
apply_order() { # líneas de fresh, bind_state y la última lectura del run en cmd_apply, en orden
  local f b l
  f=$(awk '/^cmd_apply\(\) \{/,/^}/' "${HCP}" | grep -n '^  fresh || fail' | cut -d: -f1)
  b=$(awk '/^cmd_apply\(\) \{/,/^}/' "${HCP}" | grep -n '^  bind_state ' | cut -d: -f1)
  l=$(awk '/^cmd_apply\(\) \{/,/^}/' "${HCP}" | grep -n '^  # Última lectura antes de la solicitud' | cut -d: -f1)
  [[ "${f}" =~ ^[0-9]+$ && "${b}" =~ ^[0-9]+$ && "${l}" =~ ^[0-9]+$ ]] && [ "${f}" -lt "${b}" ] && [ "${b}" -lt "${l}" ]
}
check "apply: la revalidación del estado es posterior a la frescura y anterior a la última lectura del run" apply_order
apply_fails "plan más viejo que el máximo (aprobación vencida)" 0 "${A}" "$(run_json planned_and_saved true ".data.attributes[\"created-at\"] = \"${OLD_ISO}\"")"
apply_fails "fecha de creación del run en el futuro" 0 "${A}" "$(run_json planned_and_saved true '.data.attributes["created-at"] = "2099-01-01T00:00:00Z"')"
apply_fails "fecha de creación ilegible" 0 "${A}" "$(run_json planned_and_saved true '.data.attributes["created-at"] = "ayer"')"
apply_fails "main avanzó" 0 "$(apply_cmd FRESHNESS_CMD="${TMP}/bin/fresh-no")" "${SAVED}"
apply_fails "saved plan descartado por HCP (estado cambiado)" 0 "${A}" "$(run_json discarded)"
apply_fails "run sin cambios" 0 "${A}" "$(run_json planned_and_saved false)"
apply_fails "run no confirmable" 0 "${A}" "$(run_json planned_and_saved true '.data.attributes.actions["is-confirmable"] = false')"
apply_fails "run que no es saved plan (no se confirma un run aplicable común)" 0 "${A}" "$(run_json planned true '.data.attributes["save-plan"] = false')"
apply_fails "run ajeno (otro mensaje)" 0 "${A}" "$(run_json planned_and_saved true '.data.attributes.message = "otro"')"
apply_fails "run de otro workspace" 0 "${A}" "$(run_json planned_and_saved true '.data.relationships.workspace.data.id = "ws-Otro"')"
apply_fails "run_id malformado" 0 "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env EXPECTED_PLAN_ID='${PLAN}' EXPECTED_CONFIGURATION_VERSION='${CV}' EXPECTED_STATE=sv-Old1 '${HCP}' apply 'run-;x'")" "${SAVED}"
apply_fails "sin plan esperado" 0 "$(apply_cmd EXPECTED_PLAN_ID=)" "${SAVED}"
apply_setup "${SAVED}"
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json '.data.attributes["auto-apply"] = true')"
fails "apply rechaza: el workspace tiene auto-apply habilitado (leído antes de confirmar)" bash -c "${A}"
hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json '.data.attributes.permissions["can-queue-apply"] = false')"
fails "apply rechaza: el token no puede confirmar runs (can-queue-apply)" bash -c "${A}"
check "apply: workspace no apto → ninguna solicitud" test "$(applies)" = 0
apply_setup "${SAVED}"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "${SAVED}" "$(run_json applying true '.data.id = "run-Other9" | .data.attributes.message = "otro"')")"
fails "apply rechaza: otro run ocupa el workspace (no se confirma detrás de él)" bash -c "${A}"
check "apply: workspace ocupado → ninguna solicitud" test "$(applies)" = 0
apply_setup "${SAVED}"
hcp_seq "GET ${API}/runs/${RUN}" "${SAVED}" "${SAVED}" "$(run_json applying)"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "${SAVED}" "$(run_json planned_and_saved true '.data.id = "run-Old7" | .data.attributes.message = "otro"')")"
check "apply: un saved plan ajeno sin confirmar no ocupa el workspace" bash -c "eval \"\$0\" | grep -qx apply_request=accepted" "${A}"
# Confirmado fuera de GitHub antes de la solicitud: se observa y falla, sin solicitud propia
apply_fails "ya confirmado fuera de GitHub al leerlo (observado, sin solicitud propia)" 0 "${A}" "$(run_json applying)" "$(run_json applied)"
check "apply: la confirmación ajena se informa como out_of_band, con su resultado observado" \
  bash -c "grep -qx apply_request=out_of_band '${TMP}/apply.out' && grep -qx run_status=applied '${TMP}/apply.out' && grep -q 'fuera de la autorización de GitHub' '${TMP}/apply.err'"
apply_fails "confirmado fuera de GitHub entre la revalidación y la solicitud" 0 "${A}" "${SAVED}" "$(run_json applying)" "$(run_json applied)"
# Respuestas de la solicitud: nunca se repite
apply_fails "202 pero el run termina sin aplicar (errored)" 1 "${A}" "${SAVED}" "${SAVED}" "$(run_json errored)"
apply_fails "202 pero el Apply no empieza a tiempo (sin reintento)" 1 "${A}" "${SAVED}" "${SAVED}" "${SAVED}"
apply_setup "${SAVED}" "${SAVED}" "$(run_json applying)" "$(run_json applied)"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 409)"
if bash -c "${A}" > "${TMP}/apply.out" 2> /dev/null; then ko "apply: 409 y el run aparece confirmado → confirmación ajena, falla (no falló)"; else ok "apply: 409 y el run aparece confirmado → confirmación ajena, falla"; fi
check "apply: tras el 409 no hay segunda solicitud y se informa out_of_band" bash -c "[ \"\$(grep -c '^POST ${API}/runs/${RUN}/actions/apply|' '${FAKE_HCP_LOG}')\" = 1 ] && grep -qx apply_request=out_of_band '${TMP}/apply.out'"
apply_setup "${SAVED}" "${SAVED}" "$(run_json discarded)"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 409)"
fails "apply: 409 con el saved plan descartado (estado cambiado) → falla sin Apply" bash -c "${A}"
apply_setup "${SAVED}"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 409)"
fails "apply: 409 y el run sigue sin confirmar → falla sin reintento" bash -c "${A}"
check "apply: 409 → una sola solicitud" test "$(applies)" = 1
apply_setup "${SAVED}" "${SAVED}" "${SAVED}" "$(run_json applying true '.data.attributes["status-timestamps"]["confirmed-at"] = (now + 60 | todate)')"
hcp_clear "POST ${API}/runs/${RUN}/actions/apply"
check "apply: sin respuesta y HCP lo muestra confirmado después de la solicitud → accepted_after_ambiguous (sin reintento)" \
  bash -c "out=\$(eval \"\$0\" 2> /dev/null) && grep -qx apply_request=accepted_after_ambiguous <<< \"\$out\" && [ \"\$(grep -c '^POST ${API}/runs/${RUN}/actions/apply|' '${FAKE_HCP_LOG}')\" = 1 ]" "${A}"
apply_setup "${SAVED}" "${SAVED}" "$(run_json applying true ".data.attributes[\"status-timestamps\"][\"confirmed-at\"] = \"${OLD_ISO}\"")"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 500)"
fails "apply: 500 y el run confirmado ANTES de la solicitud → confirmación ajena, falla" bash -c "${A}"
apply_setup "${SAVED}" "${SAVED}" "$(run_json applying true '.data.attributes["status-timestamps"] = {}')"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 503)"
fails "apply: 503 y el run confirmado sin fecha de confirmación (no atribuible) → falla" bash -c "${A}"
apply_setup "${SAVED}"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 500)"
fails "apply: 500 y el run sigue sin confirmar → falla sin reintento" bash -c "${A}"
check "apply: respuesta incierta → una sola solicitud" test "$(applies)" = 1
apply_setup "${SAVED}" "${SAVED}" "$(run_json discarded)"
hcp_seq "POST ${API}/runs/${RUN}/actions/apply" "$(status 502)"
fails "apply: respuesta incierta y el run termina descartado → falla" bash -c "${A}"
check "hcp-state.sh: un único POST de Apply en todo el script (subcomando apply)" \
  test "$(grep -c 'hcp POST .*actions/apply' "${HCP}")" = 1

# ── 7. Limpieza del run propio (fallo, cancelación o vencimiento del job) ──
run_setup() { reset_hcp; hcp_seq "GET ${API}/runs/${RUN}" "$@"; hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 202 '')"; }
cleanup_cmd() { printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' cleanup '${RUN}'"; }
cleanup_case() { # <descripción> <salida esperada> <descartes esperados> <cancelaciones esperadas> <respuestas del run>...
  local d="$1" exp="$2" nd="$3" nc="$4" out rc=0
  shift 4
  run_setup "$@"
  hcp_seq "POST ${API}/runs/${RUN}/actions/cancel" "$(status 202 '')"
  out=$(bash -c "$(cleanup_cmd)" 2> /dev/null) || rc=$?
  if [ "${rc}" -eq 0 ] && grep -qx "cleanup=${exp}" <<< "${out}" &&
     [ "$(posted "/runs/${RUN}/actions/discard")" = "${nd}" ] && [ "$(posted "/runs/${RUN}/actions/cancel")" = "${nc}" ]; then
    ok "cleanup: ${d}"
  else
    ko "cleanup: ${d} (rc=${rc}; ${out//$'\n'/ })"
  fi
}
cleanup_case "saved plan sin confirmar → lo descarta" discarded 1 0 "$(run_json planned_and_saved)"
cleanup_case "planificando → cancela el plan" canceled 0 1 "$(run_json planning)"
cleanup_case "confirmado/aplicando → no lo toca (apply_accepted): cancelar el job no deshace el Apply" apply_accepted 0 0 "$(run_json applying)"
cleanup_case "aplicado → apply_accepted, sin acciones" apply_accepted 0 0 "$(run_json applied)"
cleanup_case "sin cambios (planned_and_finished) → none, sin intentar descartarlo" none 0 0 "$(run_json planned_and_finished false)"
cleanup_case "ya descartado → none" none 0 0 "$(run_json discarded)"
# Carrera: alguien confirma entre la lectura y el descarte
run_setup "$(run_json planned_and_saved)" "$(run_json planned_and_saved)" "$(run_json applying)"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 409)"
check "cleanup: confirmado durante el descarte (409) → apply_accepted, sin fingir descarte" \
  bash -c "eval \"\$0\" | grep -qx cleanup=apply_accepted" "$(cleanup_cmd)"
run_setup "$(run_json planned_and_saved true '.data.attributes.message = "otro"')"
fails "cleanup: run ajeno → falla sin tocarlo" bash -c "$(cleanup_cmd)"
check "cleanup: run ajeno nunca se descarta ni cancela" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
run_setup "$(run_json planning true '.data.attributes.message = "otro"')"
hcp_seq "POST ${API}/runs/${RUN}/actions/cancel" "$(status 202 '')"
fails "cleanup: run ajeno planificando → falla sin cancelarlo" bash -c "$(cleanup_cmd)"
check "cleanup: un plan ajeno en curso nunca se cancela" test "$(grep -c '^POST' "${FAKE_HCP_LOG}" || true)" = 0
run_setup "$(run_json planned_and_saved)"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 500)"
fails "cleanup: HCP no acepta el descarte y el saved plan sigue confirmable → falla (visible)" bash -c "$(cleanup_cmd)"

# Sin run_id: GitHub no registró el run (la creación falló o se cortó tras el POST)
cleanup_noid() { printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' cleanup"; }
FOREIGN="$(run_json planned_and_saved true '.data.id = "run-Other9" | .data.attributes.message = "otro"')"
run_setup "$(run_json planned_and_saved)"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "${FOREIGN}" "$(run_json planned_and_saved)")"
check "cleanup sin run_id: encuentra el run propio por su mensaje y lo descarta; el ajeno no se toca" \
  bash -c "out=\$(eval \"\$0\") && grep -qx cleanup=discarded <<< \"\$out\" && grep -qx run_ids=${RUN} <<< \"\$out\" && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}')\" = 1 ] && grep -q '^POST ${API}/runs/${RUN}/actions/discard|' '${FAKE_HCP_LOG}'" "$(cleanup_noid)"
reset_hcp
hcp_seq "GET ${RUNS_URL}" "$(runs_list "${FOREIGN}")"
check "cleanup sin run_id: ningún run con el mensaje de este intento (HCP no lo creó) → none, sin acciones" \
  bash -c "eval \"\$0\" | grep -qx cleanup=none && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(cleanup_noid)"
hcp_seq "GET ${RUNS_URL}" "$(status 500)"
fails "cleanup sin run_id: la lista de runs falla → falla visible (procedimiento manual)" bash -c "$(cleanup_noid)"
run_setup "$(run_json applying)"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json applying)")"
check "cleanup sin run_id: run propio ya confirmado → apply_accepted, sin acciones" \
  bash -c "eval \"\$0\" | grep -qx cleanup=apply_accepted && [ \"\$(grep -c '^POST' '${FAKE_HCP_LOG}' || true)\" = 0 ]" "$(cleanup_noid)"
# Creación cortada después del POST: HCP creó el run pero create-run falló al verificarlo
reset_hcp
hcp_seq "GET ${API}/configuration-versions/${CV}" "${CV_OK}"
hcp_seq "POST ${API}/runs" "$(status 201 "$(run_json pending true '.data.attributes["auto-apply"] = true')")"
hcp_seq "GET ${API}/runs/${RUN}" "$(run_json pending true '.data.attributes["auto-apply"] = true')"
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json pending true '.data.attributes["auto-apply"] = true')")"
hcp_seq "POST ${API}/runs/${RUN}/actions/discard" "$(status 202 '')"
if run_hcp "${HCP}" create-run "${CV}" > "${TMP}/cr.out" 2> /dev/null; then
  ko "creación cortada tras el POST: create-run falla sin publicar run_id (no falló)"
else
  ok "creación cortada tras el POST: create-run falla sin publicar run_id"
fi
check "creación cortada tras el POST: la limpieza sin run_id encuentra ese run y lo descarta" \
  bash -c "! grep -q '^run_id=' '${TMP}/cr.out' && eval \"\$0\" | grep -qx cleanup=discarded && grep -q '^POST ${API}/runs/${RUN}/actions/discard|' '${FAKE_HCP_LOG}'" "$(cleanup_noid)"

# ── 8. Estado consumido y workspace libre ───────────────────────────────────
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
  bash -c "out=\$(eval \"\$0\") && grep -qx state_version_id=sv-New1 <<< \"\$out\" && grep -qx state_source=run <<< \"\$out\" && grep -qx run_status=applied <<< \"\$out\"" "$(state_cmd none)"
state_setup "$(run_json applying)" sv-New1
fails "run-state: Apply aceptado pero sin terminar dentro del plazo → falla (aceptar no es aplicar)" bash -c "$(state_cmd none)"
state_setup "$(run_json errored)" none
fails "run-state: Apply fallido (errored) → falla" bash -c "$(state_cmd none)"
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
tf_outputs_json() { # <server_id> [JSON del valor de server_ip]
  jq -cn --argjson id "$1" --argjson ip "${2:-\"203.0.113.7\"}" '{data: [
    {attributes: {name: "server_id", value: $id}}, {attributes: {name: "server_ip", value: $ip}},
    {attributes: {name: "api_fqdn", value: "api.piedrazul.org"}}, {attributes: {name: "auth_fqdn", value: "auth.piedrazul.org"}}]}'
}
outputs_setup() { # <respuesta de outputs>
  reset_hcp
  hcp_seq "GET ${API}/workspaces/${WS}" "$(workspace_json)"
  hcp_seq "GET ${API}/workspaces/${WS}/current-state-version" '{"data":{"id":"sv-Out1"}}'
  hcp_seq "GET ${API}/state-versions/sv-Out1/outputs" "$1"
}
outputs_cmd="$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' outputs sv-Out1")"
outputs_setup "$(tf_outputs_json '"12345678"')"
check "outputs: expone la instancia (server_id) junto con la dirección del state version exacto" \
  bash -c "out=\$(eval \"\$0\") && grep -qx server_id=12345678 <<< \"\$out\" && grep -qx server_ip=203.0.113.7 <<< \"\$out\" && grep -qx api_fqdn=api.piedrazul.org <<< \"\$out\"" "${outputs_cmd}"
outputs_setup "$(tf_outputs_json 12345678)"
check "outputs: server_id numérico en el estado se lee igual" \
  bash -c "out=\$(eval \"\$0\") && grep -qx server_id=12345678 <<< \"\$out\"" "${outputs_cmd}"
outputs_setup "$(jq -c 'del(.data[0])' <<< "$(tf_outputs_json '"1"')")"
fails "outputs: sin server_id → falla (no hay instancia que conciliar con el pin)" bash -c "${outputs_cmd}"
outputs_setup "$(tf_outputs_json '"abc"')"
fails "outputs: server_id malformado → falla" bash -c "${outputs_cmd}"
fails "outputs: sin estado (none) no hay outputs que consumir → falla" bash -c "$(printf '%q ' bash -c "${HCP_DEF}; hcp_env '${HCP}' outputs none")"
reset_hcp
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json planned true '.data.attributes["save-plan"] = false')")"
fails "wait-idle: un run anterior esperando confirmación bloquea (no se compite con él)" run_hcp "${HCP}" wait-idle
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json applied)" "$(run_json applying true '.data.id = "run-Older1"')")"
fails "wait-idle: un Apply en curso detrás del último run también bloquea" run_hcp "${HCP}" wait-idle
hcp_seq "GET ${RUNS_URL}" "$(runs_list "$(run_json planned_and_saved)" "$(run_json errored true '.data.id = "run-Older1"')")"
check "wait-idle: saved plan sin confirmar y run fallido → libre (no ocupan el workspace)" run_hcp "${HCP}" wait-idle
hcp_seq "GET ${RUNS_URL}" '{"data":[]}'
check "wait-idle: workspace nuevo sin runs → libre" run_hcp "${HCP}" wait-idle
hcp_seq "GET ${RUNS_URL}" '{"data":"x"}'
fails "wait-idle: lista de runs ilegible → falla" run_hcp "${HCP}" wait-idle

# ── 9. Workflow: estructura del plan y de la confirmación ───────────────────
PROD_STEPS="$(yq -r '.jobs.production.steps[].id // ""' "${WORKFLOW}")"
PLAN_STEPS="$(yq -r '.jobs.terraform_plan.steps[].id // ""' "${WORKFLOW}")"
idx() { grep -nx "$1" <<< "${PROD_STEPS}" | cut -d: -f1; }
pidx() { grep -nx "$1" <<< "${PLAN_STEPS}" | cut -d: -f1; }
step() { yq -r ".jobs.production.steps[] | select(.id == \"$1\") | $2" "${WORKFLOW}"; }
pstep() { yq -r ".jobs.terraform_plan.steps[] | select(.id == \"$1\") | $2" "${WORKFLOW}"; }
SDEF="$(declare -f step pstep); WORKFLOW='${WORKFLOW}'"
in_order() { # <lista de pasos> <ids del job>
  local p=0 i
  for s in $1; do i=$(grep -nx "$s" <<< "$2" | cut -d: -f1); [ -n "$i" ] && [ "$i" -gt "$p" ] || return 1; p=$i; done
}
check "workflow: ninguna acción de HCP de terceros (subida, run y Apply por hcp-state.sh)" \
  bash -c "! grep -q 'tfc-workflows-github/actions/' '${WORKFLOW}' && ! grep -q 'actions/apply' '${WORKFLOW}'"
check "workflow: solo el paso hcp_apply de production confirma (hcp-state.sh apply), y solo si hay que aplicar" \
  bash -c "${SDEF}; [ \"\$(yq -r '.jobs[].steps[].run // \"\"' '${WORKFLOW}' | grep -c 'hcp-state.sh apply')\" = 1 ] && step hcp_apply .run | grep -q 'hcp-state.sh apply \"\${HCP_RUN_ID}\"' && [ \"\$(step hcp_apply .if)\" = \"env.TF_APPLY_REQUIRED == 'true'\" ]"
check "terraform_plan: environment production-hetzner, misma concurrencia, solo contents: read" \
  test "$(yq -r '.jobs.terraform_plan | .environment + "/" + .concurrency.group + "/" + (.concurrency."cancel-in-progress" | tostring) + "/" + .concurrency.queue + "/" + (.permissions | to_entries | map(.key + "=" + .value) | join(","))' "${WORKFLOW}")" = "production-hetzner/piedrazul-production-hetzner/false/max/contents=read"
check "terraform_plan: orden credenciales → workspace → libre → estado previo → frescura → subida → saved plan → revisión → limpieza" \
  in_order "credentials hcp_workspace hcp_idle hcp_before fresh_plan hcp_upload hcp_run hcp_plan hcp_cleanup" "${PLAN_STEPS}"
check "terraform_plan: workspace en modo apply (sin auto-apply y con permiso de confirmar)" \
  bash -c "${SDEF}; pstep hcp_workspace .run | grep -q 'hcp-state.sh workspace apply'"
check "terraform_plan: configuración provisional con hcp-state.sh upload y saved plan con esa configuración" \
  bash -c "${SDEF}; pstep hcp_upload .run | grep -q 'hcp-state.sh upload infra/terraform' && pstep hcp_run .run | grep -q 'hcp-state.sh create-run' && [ \"\$(pstep hcp_run .env.CONFIGURATION_VERSION)\" = '\${{ steps.hcp_upload.outputs.configuration_version_id }}' ]"
check "terraform_plan: el plan comprueba frescura, vigila Pages y se liga al estado previo y a la configuración subida" \
  bash -c "${SDEF}; [ \"\$(pstep hcp_plan .env.FRESHNESS_CMD)\" = .github/scripts/verify-main-tip.sh ] && pstep hcp_plan .env.PLAN_WATCH | grep -q 'pages_domain=module.pages.cloudflare_pages_domain.main' && pstep hcp_plan .run | grep -q 'get plan_state_version)\" != \"\${STATE_BEFORE}\"' && [ \"\$(pstep hcp_plan .env.EXPECTED_STATE)\" = '\${{ steps.hcp_before.outputs.state_version_id }}' ] && [ \"\$(yq -r '.jobs.terraform_plan.outputs.plan_state_version' '${WORKFLOW}')\" = '\${{ steps.hcp_plan.outputs.plan_state_version }}' ] && pstep hcp_plan .run | grep -q 'get configuration_version_id)\" != \"\${CONFIGURATION_VERSION}\"'"
check "terraform_plan: el mensaje del run identifica el intento (sha, run_id y run_attempt) y viaja como salida" \
  bash -c "${SDEF}; yq -r '.jobs.terraform_plan.env.HCP_RUN_MESSAGE' '${WORKFLOW}' | grep -qF '\${{ github.sha }} — run \${{ github.run_id }}/\${{ github.run_attempt }}' && for s in hcp_run hcp_plan hcp_cleanup; do [ \"\$(pstep \$s .env.RUN_MESSAGE)\" = '\${{ env.HCP_RUN_MESSAGE }}' ] || exit 1; done && [ \"\$(yq -r '.jobs.terraform_plan.outputs.run_message' '${WORKFLOW}')\" = '\${{ steps.hcp_run.outputs.run_message }}' ]"
check "terraform_plan: la limpieza solo corre si el job falló o se canceló tras intentar crear el run (con éxito, el saved plan queda para production)" \
  bash -c "${SDEF}; c=\$(pstep hcp_cleanup .if); grep -q 'always() && (failure() || cancelled())' <<< \"\$c\" && grep -q \"steps.hcp_run.outcome == 'failure'\" <<< \"\$c\" && grep -q \"steps.hcp_run.outcome == 'cancelled'\" <<< \"\$c\" && pstep hcp_cleanup .run | grep -qF 'hcp-state.sh cleanup \${RUN_ID:+\"\${RUN_ID}\"}'"
check "production: usa el run, el mensaje y el estado de terraform_plan (no los recalcula)" \
  bash -c "${SDEF}; [ \"\$(yq -r '.jobs.production.env.HCP_RUN_MESSAGE' '${WORKFLOW}')\" = '\${{ needs.terraform_plan.outputs.run_message }}' ] && [ \"\$(yq -r '.jobs.production.env.HCP_RUN_ID' '${WORKFLOW}')\" = '\${{ needs.terraform_plan.outputs.run_id }}' ] && for s in hcp_apply hcp_cleanup; do [ \"\$(step \$s .env.RUN_MESSAGE)\" = '\${{ env.HCP_RUN_MESSAGE }}' ] || exit 1; done && [ \"\$(step hcp_state .env.RUN_ID)\" = '\${{ env.HCP_RUN_ID }}' ]"
check "production: la confirmación exige el plan, la configuración y el estado revisados, frescura y antigüedad máxima" \
  bash -c "${SDEF}; [ \"\$(step hcp_apply .env.EXPECTED_PLAN_ID)\" = '\${{ needs.terraform_plan.outputs.plan_id }}' ] && [ \"\$(step hcp_apply .env.EXPECTED_CONFIGURATION_VERSION)\" = '\${{ needs.terraform_plan.outputs.configuration_version_id }}' ] && [ \"\$(step hcp_apply .env.EXPECTED_STATE)\" = '\${{ needs.terraform_plan.outputs.plan_state_version }}' ] && [ \"\$(step hcp_apply .env.FRESHNESS_CMD)\" = .github/scripts/verify-main-tip.sh ] && [ \"\$(step hcp_apply .env.PLAN_MAX_AGE_SECONDS)\" -le 3600 ]"
check "production: TF_APPLY_REQUIRED solo con Terraform requerido, has_changes exactamente true y nunca en stage=install" \
  test "$(yq -r '.jobs.production.env.TF_APPLY_REQUIRED' "${WORKFLOW}")" = "\${{ needs.classify.outputs.stage != 'install' && needs.classify.outputs.terraform_required == 'true' && needs.terraform_plan.outputs.has_changes == 'true' }}"
check "production: orden workspace → libre → estado → evidencia de Pages → contraseña inicial de Keycloak → frescura → confirmación → resultado del Apply → outputs" \
  in_order "hcp_workspace hcp_idle hcp_before pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_state tf_outputs" "${PROD_STEPS}"
check "production: la contraseña inicial de Keycloak se verifica solo si hay que aplicar, con la acción sobre el servidor del plan revisado" \
  bash -c "${SDEF}; [ \"\$(step kc_admin_preflight .if)\" = \"env.TF_APPLY_REQUIRED == 'true'\" ] && [ \"\$(step kc_admin_preflight .env.WATCH_SERVER)\" = '\${{ needs.terraform_plan.outputs.watch_server }}' ] && [ \"\$(yq -r '.jobs.terraform_plan.outputs.watch_server' '${WORKFLOW}')\" = '\${{ steps.hcp_plan.outputs.watch_server }}' ] && [ \"\$(step kc_admin_preflight '.\"continue-on-error\" // false')\" = false ]"
check "production: la verificación temprana usa las reglas del converge (keycloak_credentials.yml), sin copia" \
  bash -c "${SDEF}; step kc_admin_preflight .run | grep -q 'ansible-playbook -i infra/ansible/inventories/prod infra/ansible/playbooks/keycloak-admin-preflight.yml' && [ \"\$(yq -r '.[0].tasks[0].\"ansible.builtin.include_role\".tasks_from' '${ROOT}/infra/ansible/playbooks/keycloak-admin-preflight.yml')\" = keycloak_credentials.yml ] && [ \"\$(yq -r '.[0].hosts + \"/\" + .[0].connection + \"/\" + (.[0].become | tostring)' '${ROOT}/infra/ansible/playbooks/keycloak-admin-preflight.yml')\" = localhost/local/false ] && ! step kc_admin_preflight .run | grep -q 'hcp-state.sh'"
check "terraform_plan: la dirección vigilada del servidor es la del recurso real (módulo server, sin count ni for_each)" \
  bash -c "${SDEF}; pstep hcp_plan .env.PLAN_WATCH | grep -q 'server=module.server.hcloud_server.main' && grep -q '^module \"server\" {' '${ROOT}/infra/terraform/live/prod/main.tf' && [ \"\$(grep -c '^resource \"hcloud_server\"' '${ROOT}'/infra/terraform/modules/hetzner/server/*.tf | awk -F: '{s+=\$2} END {print s}')\" = 1 ] && grep -q '^resource \"hcloud_server\" \"main\" {' '${ROOT}/infra/terraform/modules/hetzner/server/main.tf' && ! grep -Eq '^\s*(count|for_each)\s*=' '${ROOT}/infra/terraform/modules/hetzner/server/main.tf' && ! sed -n '/^module \"server\"/,/^}/p' '${ROOT}/infra/terraform/live/prod/main.tf' | grep -Eq '^\s*(count|for_each)\s*='"
check "production: workspace en modo apply solo si se aplica" \
  bash -c "${SDEF}; step hcp_workspace .run | grep -qF \"workspace \\\${{ env.TF_APPLY_REQUIRED == 'true' && 'apply' || 'read' }}\""
check "production: el resultado del Apply (run-state) se exige con Terraform (con o sin cambios), contra el estado del plan" \
  bash -c "${SDEF}; [ \"\$(step hcp_state .if)\" = \"env.TERRAFORM_REQUIRED == 'true'\" ] && [ \"\$(step hcp_state .env.STATE_BEFORE)\" = '\${{ env.HCP_STATE_BEFORE_PLAN }}' ]"
check "production: la limpieza corre siempre que había que aplicar, después de todo lo que puede fallar y antes del resumen" \
  bash -c "${SDEF}; [ \"\$(step hcp_cleanup .if)\" = \"always() && env.TF_APPLY_REQUIRED == 'true'\" ] && [ $(idx hcp_cleanup) -gt $(idx pages_publish) ] && [ \"\$(yq -r '.jobs.production.steps[-1].name' '${WORKFLOW}')\" = 'Resumen de producción' ]"
check "production: cloud-init (host_init) se espera antes de converge, después de SSH, solo con configuración del servidor" \
  bash -c "${SDEF}; [ $(idx host_init) -gt $(idx readiness) ] && [ $(idx host_init) -lt $(idx converge) ] && [ \"\$(step host_init .if)\" = \"env.RUN_HOST_CONFIG == 'true'\" ] && step host_init .run | grep -q 'host-ready.sh' && [ \"\$(step host_init '.\"continue-on-error\" // false')\" = false ]"
check "production: sin estado no hay deploy (outputs exige un state version)" \
  bash -c "${SDEF}; step tf_outputs .run | grep -q 'EXPECTED_STATE}\" = none'"
check "production: environment, concurrencia y queue sin cambios" \
  test "$(yq -r '.jobs.production | .environment + "/" + .concurrency.group + "/" + (.concurrency."cancel-in-progress" | tostring) + "/" + .concurrency.queue' "${WORKFLOW}")" = "production-hetzner/piedrazul-production-hetzner/false/max"
check "workflow: el token de HCP solo en terraform_plan y production" \
  test "$(yq -r '.jobs | to_entries[] | select(.key != "production" and .key != "terraform_plan") | .value' "${WORKFLOW}" | grep -c 'secrets.TF_API_TOKEN' || true)" = 0
check "terraform_plan: ningún otro secreto de producción (solo TF_API_TOKEN)" \
  test "$(yq -r '.jobs.terraform_plan' "${WORKFLOW}" | grep -o 'secrets\.[A-Z_]*' | sort -u | paste -sd,)" = "secrets.TF_API_TOKEN"
check "workflow: el plazo del job production cubre el Apply y el deploy" \
  test "$(yq -r '.jobs.production."timeout-minutes"' "${WORKFLOW}")" -ge 100
check "workflow: el dispatch completo sigue disponible sin diff (6 flags); stage es el único input nuevo" \
  bash -c "[ \"\$(yq -r '.on.workflow_dispatch.inputs | keys | sort | join(\",\")' '${WORKFLOW}')\" = 'break_glass_authorization,break_glass_justification,image_tag,keycloak_recovery,run_app_deploy,run_build,run_build_postgres,run_frontend_publish,run_host_config,run_terraform_apply,stage' ]"
PR_WORKFLOW="${ROOT}/.github/workflows/terraform-hetzner-plan.yml"
check "PR: el plan de PR sigue siendo speculative, en su propio environment, sin saved plan, Apply ni production" \
  bash -c "[ \"\$(yq -r '.jobs[].steps[] | select(.with.speculative != null) | .with.speculative' '${PR_WORKFLOW}')\" = true ] && [ \"\$(yq -r '.jobs[].environment' '${PR_WORKFLOW}')\" = terraform-plan ] && ! grep -qE 'save-plan|actions/apply|apply-run|production-hetzner|hcp-state.sh' '${PR_WORKFLOW}'"
check "PR: el workflow de producción no lee planes de PR (solo push a main y dispatch)" \
  bash -c "[ \"\$(yq -r '.on | keys | sort | join(\",\")' '${WORKFLOW}')\" = 'push,workflow_dispatch' ] && ! grep -q 'speculative' <<< \"\$(yq -r '.jobs.terraform_plan' '${WORKFLOW}')\""

# ── 10. Camino de los pasos por escenario ───────────────────────────────────
# Evalúa las condiciones `if` reales de los pasos (subconjunto de expresiones de GitHub
# Actions que usa el workflow). Un paso sin función de estado solo corre si ninguno anterior
# falló (success() implícito); always() corre siempre. Las salidas simuladas de cada paso
# son las del escenario.
yq -o=json '.jobs.production.steps' "${WORKFLOW}" > "${TMP}/steps.json"
yq -o=json '.jobs.terraform_plan.steps' "${WORKFLOW}" > "${TMP}/plan-steps.json"
yq -o=json '.jobs.production.env' "${WORKFLOW}" > "${TMP}/production-env.json"
cat > "${TMP}/flow.py" << 'EOF'
import json, re, sys
steps = json.load(open(sys.argv[1]))
sc = json.loads(sys.argv[2])
env = sc.get("env", {})
outs = sc.get("outputs", {})
# Con "classify", el entorno sale de las expresiones REALES de env de production
if "classify" in sc:
    job_outs = {"classify": sc["classify"], "terraform_plan": sc.get("terraform_plan", {})}
    for k, v in json.load(open(sys.argv[3])).items():
        m = re.fullmatch(r"\s*\$\{\{(.*)\}\}\s*", str(v))
        if not m or "needs." not in v or "image_targets" in v:
            continue
        e = re.sub(r"\bneeds\.([a-z_]+)\.outputs\.([a-z_]+)", r"NO('\1','\2')", m.group(1))
        r = eval(e.replace("&&", " and ").replace("||", " or "), {"NO": lambda n, o: job_outs[n].get(o, "")})
        env[k] = ("true" if r else "false") if isinstance(r, bool) else r
env.setdefault("FRONTEND_PUBLISH", env.get("FRONTEND_REQUIRED", ""))
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
# flow_case <descripción> <escenario JSON> <pasos que deben correr> <pasos que no deben correr> [pasos del job]
flow_case() {
  local d="$1" sc="$2" ran missing="" extra=""
  if ! ran=" $(python3 "${TMP}/flow.py" "${5:-${TMP}/steps.json}" "${sc}" "${TMP}/production-env.json" 2> "${TMP}/flow.err") "; then ko "camino: ${d} ($(tail -1 "${TMP}/flow.err"))"; return; fi
  for s in $3; do [[ "${ran}" == *" $s "* ]] || missing="${missing} $s"; done
  for s in $4; do [[ "${ran}" != *" $s "* ]] || extra="${extra} $s"; done
  if [ -z "${missing}${extra}" ]; then ok "camino: ${d}"; else ko "camino: ${d} (faltan:${missing:- —}; sobran:${extra:- —})"; fi
}
PS="${TMP}/plan-steps.json"
flow_case "terraform_plan: éxito → subida provisional, saved plan y revisión; el saved plan queda (sin limpieza)" '{}' \
  "credentials hcp_workspace hcp_idle hcp_before fresh_plan hcp_upload hcp_run hcp_plan" "hcp_cleanup" "${PS}"
flow_case "terraform_plan: la revisión falla (identidad, revisión, frescura) → limpieza del run propio" '{"fail_at":"hcp_plan"}' \
  "hcp_plan hcp_cleanup" "" "${PS}"
flow_case "terraform_plan: la creación del run falla (o se corta tras el POST) → limpieza sin run_id" '{"fail_at":"hcp_run"}' \
  "hcp_run hcp_cleanup" "hcp_plan" "${PS}"
flow_case "terraform_plan: cancelación durante el plan → limpieza" '{"cancel_at":"hcp_plan"}' "hcp_plan hcp_cleanup" "" "${PS}"
flow_case "terraform_plan: la subida falla → sin run que limpiar" '{"fail_at":"hcp_upload"}' "hcp_upload" "hcp_run hcp_plan hcp_cleanup" "${PS}"
flow_case "terraform_plan: frescura falla → nada se sube ni se crea" '{"fail_at":"fresh_plan"}' "fresh_plan" "hcp_upload hcp_run hcp_plan hcp_cleanup" "${PS}"

AUTH='"policy":{"decision":"authorized"},"scan_evidence":{"scan_backend":"false","scan_postgres":"false"}'
FULL_ENV='{"TERRAFORM_REQUIRED":"true","TF_APPLY_REQUIRED":"true","DEPLOY_REQUIRED":"true","RUN_HOST_CONFIG":"true","RUN_APP_DEPLOY":"true","FRONTEND_REQUIRED":"true"}'
FRESH="{\"env\":${FULL_ENV},\"outputs\":{\"pages_probe\":{\"project_state\":\"absent\"},\"hcp_apply\":{\"apply_request\":\"accepted\"},${AUTH}}}"
flow_case "instalación nueva (dispatch completo, Pages ausente, plan con cambios)" "${FRESH}" \
  "credentials pages_target pages_artifact pages_tooling pages_probe hcp_workspace hcp_idle hcp_before pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_state tf_outputs pages_preflight access readiness host_init host_state select scan_evidence policy converge converge_identity deploy running_identity container_health public_health record pages_publish hcp_cleanup" \
  "fresh_deploy fresh_frontend break_glass authorization scan_backend scan_postgres"
NOOP_DEPLOY="${FRESH/\"TF_APPLY_REQUIRED\":\"true\"/\"TF_APPLY_REQUIRED\":\"false\"}"
NOOP_DEPLOY="${NOOP_DEPLOY/\"project_state\":\"absent\"/\"project_state\":\"present\"}"
flow_case "Terraform sin cambios + deploy: sin Apply ni limpieza; estado del plan verificado y frescura antes del servidor" "${NOOP_DEPLOY}" \
  "hcp_workspace hcp_idle hcp_before hcp_state tf_outputs pages_preflight fresh_deploy converge deploy record pages_publish" \
  "pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_cleanup"
FRONT='{"env":{"TERRAFORM_REQUIRED":"false","TF_APPLY_REQUIRED":"false","DEPLOY_REQUIRED":"false","RUN_HOST_CONFIG":"false","RUN_APP_DEPLOY":"false","FRONTEND_REQUIRED":"true"},"outputs":{"pages_probe":{"project_state":"present"}},"image_scan":"skipped"}'
flow_case "solo frontend: sin Terraform ni SSH; sondeo y preflight estrictos antes de publicar" "${FRONT}" \
  "credentials pages_probe pages_preflight fresh_frontend pages_publish" \
  "hcp_workspace hcp_idle hcp_before pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_state tf_outputs access readiness converge deploy record hcp_cleanup"
BACK="{\"env\":{\"TERRAFORM_REQUIRED\":\"false\",\"TF_APPLY_REQUIRED\":\"false\",\"DEPLOY_REQUIRED\":\"true\",\"RUN_HOST_CONFIG\":\"false\",\"RUN_APP_DEPLOY\":\"true\",\"FRONTEND_REQUIRED\":\"false\"},\"outputs\":{${AUTH}}}"
flow_case "solo backend: lee el estado sin plan ni Apply, sin Pages" "${BACK}" \
  "hcp_workspace hcp_idle hcp_before tf_outputs access select policy fresh_deploy deploy running_identity record" \
  "host_init authorization pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_state converge pages_probe pages_preflight pages_publish hcp_cleanup"
TFONLY='{"env":{"TERRAFORM_REQUIRED":"true","TF_APPLY_REQUIRED":"true","DEPLOY_REQUIRED":"false","RUN_HOST_CONFIG":"false","RUN_APP_DEPLOY":"false","FRONTEND_REQUIRED":"false"},"outputs":{"hcp_apply":{"apply_request":"accepted"}},"image_scan":"skipped"}'
flow_case "solo Terraform con cambios: verificación de la acción sobre el servidor, confirmación y resultado del Apply; sin servidor ni Pages" "${TFONLY}" \
  "hcp_workspace kc_admin_preflight fresh_apply hcp_apply hcp_state hcp_cleanup" \
  "pages_plan tf_outputs access converge deploy pages_probe pages_preflight pages_publish"
flow_case "revalidación o solicitud de Apply falla (también confirmación ajena): ni estado, ni servidor, ni Pages; limpieza y resumen sí" \
  "${FRESH%\}},\"fail_at\":\"hcp_apply\"}" \
  "hcp_apply hcp_cleanup" "hcp_state tf_outputs pages_preflight access converge deploy record pages_publish"
flow_case "cancelación durante la confirmación: la limpieza corre igual" \
  "${FRESH%\}},\"cancel_at\":\"hcp_apply\"}" "hcp_apply hcp_cleanup" "hcp_state converge pages_publish"
flow_case "el Apply termina mal (run-state falla): ni outputs, ni servidor, ni Pages" \
  "${FRESH%\}},\"fail_at\":\"hcp_state\"}" "hcp_apply hcp_state hcp_cleanup" "tf_outputs pages_preflight access converge deploy record pages_publish"
flow_case "frescura falla antes del Apply: no se confirma; el saved plan se descarta" \
  "${FRESH%\}},\"fail_at\":\"fresh_apply\"}" "fresh_apply hcp_cleanup" "hcp_apply hcp_state converge"
flow_case "el plan no crea lo que falta de Pages: no se confirma; el saved plan se descarta" \
  "${FRESH%\}},\"fail_at\":\"pages_plan\"}" "pages_plan hcp_cleanup" "fresh_apply hcp_apply hcp_state converge pages_publish"
flow_case "la contraseña inicial de Keycloak falta o es inválida (servidor nuevo): no se confirma; el saved plan se descarta; nada se registra" \
  "${FRESH%\}},\"fail_at\":\"kc_admin_preflight\"}" "pages_plan kc_admin_preflight hcp_cleanup" "fresh_apply hcp_apply hcp_state tf_outputs pages_preflight access converge deploy record pages_publish"
flow_case "cancelación durante la verificación temprana: no se confirma; la limpieza corre igual" \
  "${FRESH%\}},\"cancel_at\":\"kc_admin_preflight\"}" "kc_admin_preflight hcp_cleanup" "fresh_apply hcp_apply hcp_state converge record"
flow_case "Pages no existe tras el Apply (preflight falla): el servidor no se toca y no se publica" \
  "${FRESH%\}},\"fail_at\":\"pages_preflight\"}" \
  "hcp_apply hcp_state tf_outputs pages_preflight hcp_cleanup" "access converge deploy record pages_publish"
flow_case "sondeo de Pages falla (Git, token, cuenta): ninguna mutación; el saved plan se descarta" \
  "${FRESH%\}},\"fail_at\":\"pages_probe\"}" \
  "pages_probe hcp_cleanup" "hcp_workspace fresh_apply hcp_apply converge deploy pages_publish"
flow_case "deploy falla tras el Apply: no se registra ni se publica; la limpieza informa el Apply aceptado" \
  "${FRESH%\}},\"fail_at\":\"deploy\"}" \
  "hcp_state deploy hcp_cleanup" "record pages_publish"
BLOCKED="${FRESH/\"decision\":\"authorized\"/\"decision\":\"blocked\"}"
flow_case "política de vulnerabilidades bloquea en la instalación nueva: el gate falla y nada del servidor ni de Pages muta (no hay bypass por bootstrap)" \
  "${BLOCKED%\}},\"fail_at\":\"authorization\"}" \
  "policy authorization hcp_cleanup" "converge deploy record pages_publish"
INCOMPLETE_RUN="${FRESH/\"project_state\":\"absent\"/\"project_state\":\"incomplete\"}"
flow_case "reintento tras Apply parcial (Pages incompleto): mismo camino que la instalación nueva, preflight estricto antes de publicar" \
  "${INCOMPLETE_RUN}" "pages_probe pages_plan hcp_apply hcp_state pages_preflight host_init converge deploy record pages_publish" ""
flow_case "cloud-init falló o no terminó: ni converge, ni deploy, ni publicación" \
  "${FRESH%\}},\"fail_at\":\"host_init\"}" "host_init hcp_cleanup" "host_state converge deploy record pages_publish"
flow_case "publicación falla tras el registro del servidor (release parcial visible)" \
  "${FRESH%\}},\"fail_at\":\"pages_publish\"}" \
  "record pages_publish hcp_cleanup" ""

# Etapas: el entorno del job sale de sus expresiones reales, no del escenario
flow_case "terraform_plan: la etapa no admite el plan → el saved plan se descarta antes de la segunda aprobación" '{"fail_at":"stage_gate"}' \
  "hcp_plan stage_gate hcp_cleanup" "" "${PS}"
CLASS_ALL='"terraform_required":"true","deploy_required":"true","run_host_config":"true","run_app_deploy":"true","frontend_required":"true"'
HOST_STEPS="pin_preflight host_trust access readiness host_init sshd_check host_state select scan_evidence policy fresh_deploy converge converge_identity deploy running_identity container_health public_health record"
RUN_P="{\"classify\":{\"stage\":\"provision\",${CLASS_ALL}},\"terraform_plan\":{\"has_changes\":\"true\"},\"outputs\":{\"pages_probe\":{\"project_state\":\"absent\"},\"hcp_apply\":{\"apply_request\":\"accepted\"}}}"
flow_case "Run P: comprobaciones previas, Apply y estado, y termina antes de cualquier SSH, deploy o publicación" "${RUN_P}" \
  "credentials pages_target pages_artifact pages_tooling pages_probe hcp_workspace hcp_idle hcp_before pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_state tf_outputs pages_preflight hcp_cleanup" \
  "${HOST_STEPS} fresh_frontend pages_publish"
RUN_P_DONE="${RUN_P/\"has_changes\":\"true\"/\"has_changes\":\"false\"}"
RUN_P_DONE="${RUN_P_DONE/\"project_state\":\"absent\"/\"project_state\":\"present\"}"
flow_case "Run P tras un Apply ya completado (plan sin cambios): sin Apply, informa la identidad del estado y termina sin fallar" "${RUN_P_DONE}" \
  "hcp_state tf_outputs pages_preflight" "pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_cleanup ${HOST_STEPS} pages_publish"
RUN_I="{\"classify\":{\"stage\":\"install\",${CLASS_ALL}},\"terraform_plan\":{\"has_changes\":\"false\"},\"outputs\":{\"pages_probe\":{\"project_state\":\"present\"},${AUTH}}}"
flow_case "Run I: sin Apply; identidad antes del primer SSH, sshd antes del converge, todas las mutaciones y publicación" "${RUN_I}" \
  "pin_preflight hcp_state tf_outputs pages_preflight host_trust access readiness host_init sshd_check host_state select policy fresh_deploy converge deploy running_identity container_health public_health record pages_publish" \
  "pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_cleanup"
RUN_I_CHANGED="${RUN_I/\"has_changes\":\"false\"/\"has_changes\":\"true\"}"
flow_case "Run I aunque el plan informara cambios: ningún paso de Apply corre (TF_APPLY_REQUIRED falso)" "${RUN_I_CHANGED}" "tf_outputs" "pages_plan kc_admin_preflight fresh_apply hcp_apply hcp_cleanup"
flow_case "pin ausente o mal formado: falla antes de Pages, de HCP y del servidor" "${RUN_I%\}},\"fail_at\":\"pin_preflight\"}" "pin_preflight" "hcp_workspace tf_outputs host_trust access readiness converge deploy record pages_publish"
flow_case "identidad rechazada: la clave SSH no se escribe, nada lee ni muta el servidor y no se publica" "${RUN_I%\}},\"fail_at\":\"host_trust\"}" "tf_outputs host_trust" "access readiness host_init sshd_check host_state select policy converge deploy record pages_publish"
flow_case "handshake rechazado tras verificar: sin lecturas del host ni mutaciones" "${RUN_I%\}},\"fail_at\":\"readiness\"}" "host_trust access readiness" "host_init sshd_check host_state select converge deploy record pages_publish"
flow_case "sshd efectivo inseguro: los secretos del converge no se entregan" "${RUN_I%\}},\"fail_at\":\"sshd_check\"}" "host_init sshd_check" "host_state select policy converge deploy record pages_publish"
flow_case "converge falla en Run I: no se registra ni se publica (el reintento no requiere otra autenticación fuera de banda)" "${RUN_I%\}},\"fail_at\":\"converge\"}" "host_trust converge" "deploy record pages_publish"
ORD="{\"classify\":{\"stage\":\"ordinary\",\"terraform_required\":\"false\",\"deploy_required\":\"true\",\"run_host_config\":\"false\",\"run_app_deploy\":\"true\",\"frontend_required\":\"false\"},\"outputs\":{${AUTH}}}"
flow_case "deploy ordinario contra un host de confianza: un solo run, con identidad verificada y sin Terraform" "${ORD}" \
  "pin_preflight tf_outputs host_trust access readiness host_state select policy fresh_deploy deploy record" "hcp_apply hcp_state host_init sshd_check converge pages_publish"
check "production: orden pin → Apply → outputs → identidad → clave SSH → espera → cloud-init → sshd → lectura del host → selección → política → converge" \
  in_order "pin_preflight hcp_apply tf_outputs pages_preflight host_trust access readiness host_init sshd_check host_state select policy converge" "${PROD_STEPS}"
check "terraform_plan: la etapa se decide después de la revisión y antes de la limpieza" \
  in_order "hcp_plan stage_gate hcp_cleanup" "${PLAN_STEPS}"

# ── 11. Dependencias entre jobs: saltado, fallido y cancelado nunca son éxito ──
# Evalúa las condiciones `if` REALES de terraform_plan y production (con !cancelled(),
# needs.<job>.result y needs.<job>.outputs) y ejecuta el script REAL de conclude, sobre la
# matriz completa de intención × resultado de terraform_plan × has_changes × resultado de
# production × cancelación del workflow. El oráculo es independiente del workflow.
yq -o=json '{"plan": .jobs.terraform_plan.if, "prod": .jobs.production.if, "conclude": .jobs.conclude.steps[0].run}' "${WORKFLOW}" > "${TMP}/jobs.json"
cat > "${TMP}/jobs.py" << 'EOF'
import itertools, json, os, re, subprocess, sys
J = json.load(open(sys.argv[1]))

def translate(expr):
    e = " ".join(expr.replace("${{", "").replace("}}", "").split())
    e = re.sub(r"\bneeds\.([a-z_]+)\.outputs\.([a-z_]+)", r"NO('\1','\2')", e)
    e = re.sub(r"\bneeds\.([a-z_]+)\.result", r"N('\1')", e)
    e = e.replace("!=", " NE ").replace("&&", " and ").replace("||", " or ")
    e = e.replace("!cancelled()", " not CANCELLED ")
    e = re.sub(r"!\s*\(", " not (", e).replace(" NE ", " != ")
    e = e.replace("always()", "True").replace("cancelled()", "CANCELLED")
    if "!" in e.replace("!=", ""):
        raise SystemExit("expresión no soportada: " + expr)
    return e

def job_runs(expr, results, outputs, cancelled):
    if "!cancelled()" not in expr and "always()" not in expr:
        raise SystemExit("la condición del job no usa una función de estado explícita")
    ctx = {"N": lambda n: results[n], "NO": lambda n, k: outputs.get(n, {}).get(k, ""), "CANCELLED": cancelled}
    return bool(eval(translate(expr), {}, ctx))

def conclude(results, outputs, cls):
    env = {"PATH": os.environ["PATH"], "CLASSIFY": results["classify"], "SELECT": results["select_images"],
           "PRODUCTION": results["production"], "TERRAFORM_PLAN": results["terraform_plan"],
           "HAS_CHANGES": outputs.get("terraform_plan", {}).get("has_changes", ""),
           "TERRAFORM_REQUIRED": cls["terraform_required"], "DEPLOY_REQUIRED": cls["deploy_required"],
           "FRONTEND_REQUIRED": cls["frontend_required"], "FRONTEND_BUILD": results["frontend_build"],
           "PRODUCTION_STAGE": cls["stage"]}
    return subprocess.run(["bash", "-c", J["conclude"]], env=env, capture_output=True).returncode == 0

failures, cases, counts = [], 0, {"prod_runs": 0, "conclude_ok": 0}
B = ["true", "false"]
for stage, tf, dep, fe, plan_res, hc, prod_res, cancelled, fb in itertools.product(
        ["ordinary", "provision", "install"], B, B, B, ["success", "failure", "cancelled", "forced_skip"],
        ["true", "false", "", "garbage"], ["success", "failure", "cancelled"], [False, True], ["success", "failure"]):
    cls = {"stage": stage, "terraform_required": tf, "deploy_required": dep, "frontend_required": fe}
    results = {"classify": "success", "select_images": "success", "image_targets": "success",
               "image_scan": "success", "image_policy": "success",
               "frontend_build": fb if fe == "true" else "skipped"}
    outputs = {"classify": cls, "image_targets": {"targets": "[\"x\"]"}}
    # terraform_plan: corre según su condición real; si corre, termina en plan_res.
    # forced_skip modela un terraform_plan saltado aunque su condición lo pediría (p. ej. un
    # cambio futuro de esa condición): production nunca debe leerlo como "sin Terraform".
    plan_would_run = job_runs(J["plan"], results, outputs, cancelled)
    if plan_would_run and plan_res == "forced_skip":
        results["terraform_plan"] = "skipped"
    elif plan_would_run:
        results["terraform_plan"] = plan_res
        outputs["terraform_plan"] = {"has_changes": hc}   # también si falló (salidas parciales)
    else:
        results["terraform_plan"] = "skipped"
    prod = job_runs(J["prod"], results, outputs, cancelled)
    results["production"] = prod_res if prod else "skipped"
    ok = conclude(results, outputs, cls)
    cases += 1
    counts["prod_runs"] += prod
    counts["conclude_ok"] += ok
    # Oráculo independiente
    plan_ok = results["terraform_plan"] == "success" and outputs.get("terraform_plan", {}).get("has_changes") in ("true", "false")
    gates_ok = fe != "true" or fb == "success"
    if tf == "true":
        expect_plan_run = (not cancelled) and gates_ok
        prod_allowed = plan_ok and (outputs["terraform_plan"]["has_changes"] == "true" or dep == "true" or fe == "true")
    else:
        expect_plan_run = False
        prod_allowed = dep == "true" or fe == "true"
    # Run I solo sigue con un plan terminado sin cambios, nunca con uno que aplicar
    if stage == "install":
        prod_allowed = prod_allowed and outputs.get("terraform_plan", {}).get("has_changes") == "false"
    expect_prod_run = (not cancelled) and gates_ok and prod_allowed
    prod_required = (tf == "true" and outputs.get("terraform_plan", {}).get("has_changes") == "true") or dep == "true" or fe == "true"
    expect_ok = (gates_ok or fe != "true") and (fe != "true" or fb == "success") and \
        (plan_ok if tf == "true" else results["terraform_plan"] == "skipped") and \
        (results["production"] == "success" if prod_required else results["production"] == "skipped") and \
        not (stage == "install" and tf == "true" and outputs.get("terraform_plan", {}).get("has_changes") == "true")
    label = f"stage={stage} tf={tf} deploy={dep} frontend={fe} frontend_build={fb} plan={results['terraform_plan']} has_changes={hc!r} production={results['production']} cancelled={cancelled}"
    if plan_would_run != expect_plan_run:
        failures.append("terraform_plan " + ("corrió" if results["terraform_plan"] != "skipped" else "no corrió") + ": " + label)
    if prod != expect_prod_run:
        failures.append("production " + ("corrió" if prod else "no corrió") + ": " + label)
    if ok != expect_ok:
        failures.append("conclude " + ("success" if ok else "failure") + ": " + label)
    # Invariantes de seguridad, explícitas
    if prod and tf == "true" and not plan_ok:
        failures.append("production corrió con un plan no exitoso o inválido: " + label)
    if prod and tf != "true" and results["terraform_plan"] != "skipped":
        failures.append("production corrió sin Terraform con terraform_plan no saltado: " + label)
    if prod and stage == "install" and outputs.get("terraform_plan", {}).get("has_changes") != "false":
        failures.append("production corrió en Run I sin un plan terminado sin cambios: " + label)
    if ok and "failure" in (results["terraform_plan"], results["production"]) and (tf == "true" or prod_required):
        failures.append("conclude success con un job requerido fallido: " + label)
    if ok and "cancelled" in (results["terraform_plan"], results["production"]):
        failures.append("conclude success con un job cancelado: " + label)
print(json.dumps({"cases": cases, **counts, "failures": failures[:20], "n_failures": len(failures)}))
sys.exit(1 if failures else 0)
EOF
if python3 "${TMP}/jobs.py" "${TMP}/jobs.json" > "${TMP}/jobs.out" 2>&1; then
  ok "jobs: matriz completa ($(jq -r .cases "${TMP}/jobs.out") casos) — saltado/fallido/cancelado nunca habilitan production ni un conclude exitoso"
else
  ko "jobs: matriz ($(jq -r '.n_failures // "?"' "${TMP}/jobs.out" 2> /dev/null) fallos): $(jq -r '.failures[0:3] | join(" | ")' "${TMP}/jobs.out" 2> /dev/null || cat "${TMP}/jobs.out")"
fi
[ -z "${EVIDENCE_DIR:-}" ] || cp "${TMP}/jobs.out" "${EVIDENCE_DIR}/job-matrix.json"
# Casos con nombre (los mismos que la matriz, explícitos)
job_case() { # <descripción> <tf> <deploy> <frontend> <plan_result|-> <has_changes> <esperado: production corre (si|no)> <esperado: conclude (ok|fail)> [production result]
  python3 - "${TMP}/jobs.json" "$@" << 'EOF'
import json, os, re, subprocess, sys
J = json.load(open(sys.argv[1]))
_, _, d, tf, dep, fe, plan_res, hc, exp_prod, exp_ok, *rest = sys.argv
prod_res = rest[0] if rest else "success"
def tr(e):
    e = " ".join(e.split())
    e = re.sub(r"\bneeds\.([a-z_]+)\.outputs\.([a-z_]+)", r"NO('\1','\2')", e)
    e = re.sub(r"\bneeds\.([a-z_]+)\.result", r"N('\1')", e)
    e = e.replace("!=", " NE ").replace("&&", " and ").replace("||", " or ").replace("!cancelled()", " not CANCELLED ")
    return re.sub(r"!\s*\(", " not (", e).replace(" NE ", " != ")
cls = {"terraform_required": tf, "deploy_required": dep, "frontend_required": fe}
res = {"classify": "success", "select_images": "success", "image_targets": "success", "image_scan": "success",
       "image_policy": "success", "frontend_build": "success" if fe == "true" else "skipped"}
outs = {"classify": cls, "image_targets": {"targets": "[\"x\"]"}}
ctx = lambda: {"N": lambda n: res[n], "NO": lambda n, k: outs.get(n, {}).get(k, ""), "CANCELLED": False}
res["terraform_plan"] = plan_res if eval(tr(J["plan"]), {}, ctx()) else "skipped"
if res["terraform_plan"] != "skipped":
    outs["terraform_plan"] = {"has_changes": hc}
prod = eval(tr(J["prod"]), {}, ctx())
res["production"] = prod_res if prod else "skipped"
env = {"PATH": os.environ["PATH"], "CLASSIFY": "success", "SELECT": "success", "PRODUCTION": res["production"],
       "TERRAFORM_PLAN": res["terraform_plan"], "HAS_CHANGES": outs.get("terraform_plan", {}).get("has_changes", ""),
       "TERRAFORM_REQUIRED": tf, "DEPLOY_REQUIRED": dep, "FRONTEND_REQUIRED": fe, "FRONTEND_BUILD": res["frontend_build"]}
ok = subprocess.run(["bash", "-c", J["conclude"]], env=env, capture_output=True).returncode == 0
sys.exit(0 if (("si" if prod else "no") == exp_prod and ("ok" if ok else "fail") == exp_ok) else 1)
EOF
}
jc() { local d="$1"; shift; if job_case "$d" "$@" > /dev/null 2>&1; then ok "jobs: ${d}"; else ko "jobs: ${d}"; fi; }
jc "solo deploy: terraform_plan saltado (intencional) → production corre, success" false true false - "" si ok
jc "solo frontend: terraform_plan saltado → production corre, success" false false true - "" si ok
jc "Terraform con cambios: plan success → production corre (segunda aprobación), success" true false false success true si ok
jc "Terraform sin cambios + deploy: production corre sin Apply, success" true true false success false si ok
jc "Terraform sin cambios y nada más: production saltado, no-op limpio" true false false success false no ok
jc "plan fallido (aunque haya publicado has_changes=true): production no corre, conclude falla" true true true failure true no fail
jc "plan cancelado: production no corre, conclude falla" true true false cancelled true no fail
jc "plan con has_changes vacío: production no corre, conclude falla" true true false success "" no fail
jc "plan con has_changes inválido: production no corre, conclude falla" true false false success garbage no fail
jc "Terraform con cambios y production fallido: conclude falla" true false false success true si fail failure
jc "Terraform con cambios y production cancelado: conclude falla" true false false success true si fail cancelled
jc "solo deploy y production fallido: conclude falla" false true false - "" si fail failure

# ── 12. Bloques run reales de los pasos de revisión, Pages y confirmación ───
# Ejecutan el `run` del paso con un hcp-state.sh falso que devuelve lo observado
SD="${TMP}/step"
mkdir -p "${SD}/.github/scripts"
cat > "${SD}/.github/scripts/hcp-state.sh" << 'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${FAKE_CALLS:-/dev/null}"
[ -z "${FAKE_REVIEW:-}" ] || [ -z "${PLAN_REVIEW_FILE:-}" ] || printf '%b\n' "${FAKE_REVIEW}" > "${PLAN_REVIEW_FILE}"
printf '%b\n' "${FAKE_OUT}"
exit "${FAKE_RC:-0}"
EOF
chmod +x "${SD}/.github/scripts/hcp-state.sh"
run_step() { # <job> <id> [VAR=valor...]
  local job="$1" id="$2" body
  shift 2
  body="$(yq -r ".jobs.${job}.steps[] | select(.id == \"${id}\") | .run" "${WORKFLOW}")"
  : > "${SD}/out"; : > "${SD}/summary"; : > "${SD}/calls"
  (cd "${SD}" && env -i PATH="${PATH}" RUNNER_TEMP="${SD}" GITHUB_OUTPUT="${SD}/out" GITHUB_STEP_SUMMARY="${SD}/summary" \
    FAKE_CALLS="${SD}/calls" TF_CLOUD_ORGANIZATION="${ORG}" TF_WORKSPACE="${NAME}" HCP_WORKSPACE_ID="${WS}" \
    GITHUB_SHA=abc GITHUB_RUN_ID=77 GITHUB_RUN_ATTEMPT=1 "$@" bash -c "${body}") > "${SD}/log" 2>&1
}
step_case() { # <descripción> <pasa|falla> <job> <id> [VAR=valor...]
  local d="$1" exp="$2" rc=0
  shift 2
  run_step "$@" || rc=$?
  if { [ "${exp}" = pasa ] && [ "${rc}" -eq 0 ]; } || { [ "${exp}" = falla ] && [ "${rc}" -ne 0 ]; }; then ok "$1/$2: ${d}"; else ko "$1/$2: ${d} (rc=${rc}: $(tail -1 "${SD}/log"))"; fi
}
PLAN_OUT="run_status=planned_and_saved\nhas_changes=true\nplan_id=${PLAN}\nconfiguration_version_id=${CV}\nplan_state_version=sv-Old1\nstate_binding=relationship\nadditions=4\nchanges=0\ndestructions=1\nimports=0\nreview_creates=3\nreview_replaces=1\nreview_deletes=0\nwatch_pages_project=create\nwatch_pages_domain=create\nrun_link=https://x"
PLAN_ENV=(RUN_ID="${RUN}" RUN_LINK=https://x CONFIGURATION_VERSION="${CV}" ARCHIVE_SHA256=deadbeef STATE_BEFORE=sv-Old1 FAKE_REVIEW='#### Reemplazar (1)\n- `module.x`')
step_case "plan revisado con cambios → salidas, resumen con identidad y efectos, aviso de revisión" pasa terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT}"
check "terraform_plan/hcp_plan: el resumen muestra workspace, commit, run, plan, configuración provisional, estado de entrada y los efectos" \
  bash -c "s='${SD}/summary'; grep -q '${NAME}' \$s && grep -q 'abc' \$s && grep -q '${RUN}' \$s && grep -q '${PLAN}' \$s && grep -q '${CV}' \$s && grep -q 'deadbeef' \$s && grep -q 'sv-Old1' \$s && grep -q 'Reemplazar (1)' \$s && grep -q 'aplica \*\*exactamente\*\* este saved plan' \$s"
check "terraform_plan/hcp_plan: aviso para revisar y aprobar, y advertencia de plan destructivo" \
  bash -c "grep -q '^::notice title=Terraform::Plan listo para revisión: run ${RUN}, plan ${PLAN}, commit abc' '${SD}/log' && grep -q '^::warning title=Terraform::Plan destructivo: 0 destrucción(es) y 1 reemplazo(s)' '${SD}/log' && grep -qx has_changes=true '${SD}/out'"
step_case "plan sin cambios → resumen 'Sin cambios', sin aviso de revisión" pasa terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/has_changes=true/has_changes=false}"
check "terraform_plan/hcp_plan: sin cambios no pide revisar ni aprobar un Apply" \
  bash -c "grep -q 'Sin cambios:' '${SD}/summary' && ! grep -q '::notice' '${SD}/log'"
step_case "el plan quedó ligado a otro estado que el capturado → falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/plan_state_version=sv-Old1/plan_state_version=sv-Otro}"
check "terraform_plan/hcp_plan: con identidad inconsistente no publica salidas (production no puede usarlas)" bash -c "! grep -q has_changes '${SD}/out'"
step_case "sin plan_state_version → falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/plan_state_version=sv-Old1/}"
step_case "state_binding inválido → falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/state_binding=relationship/state_binding=fallback}"
step_case "sin state_binding → falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/state_binding=relationship/}"
step_case "ligado por captura y relectura (bracketed) → sigue, con la ligadura en el resumen" pasa terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/state_binding=relationship/state_binding=bracketed}"
check "terraform_plan/hcp_plan: el resumen muestra el state version del plan y cómo se ligó" grep -qF "state version del plan | \`sv-Old1\` (bracketed)" "${SD}/summary"
step_case "el run usa otra configuración que la subida → falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/configuration_version_id=${CV}/configuration_version_id=cv-Otra}"
step_case "has_changes inválido → falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="${PLAN_OUT/has_changes=true/has_changes=quizas}"
step_case "hcp-state.sh plan falla → el paso falla" falla terraform_plan hcp_plan "${PLAN_ENV[@]}" FAKE_OUT="" FAKE_RC=1

pages_case() { # <descripción> <pasa|falla> <estado del sondeo> <watch proyecto> <watch dominio>
  step_case "$1" "$2" production pages_plan PAGES_PROJECT_STATE="$3" WATCH_PAGES_PROJECT="$4" WATCH_PAGES_DOMAIN="$5"
}
pages_case "Pages ausente y el plan crea proyecto y dominio → sigue a la confirmación" pasa absent create create
pages_case "Pages ausente y el JSON del plan no se pudo leer (unknown) → falla" falla absent unknown unknown
pages_case "Pages ausente, proyecto observado pero dominio unknown → falla" falla absent create unknown
pages_case "Pages ausente y el plan no crea el proyecto → falla" falla absent none create
pages_case "Pages ausente y el proyecto aparece repetido (ambiguous) → falla" falla absent ambiguous create
pages_case "Pages ausente y el plan lo reemplaza en vez de crearlo → falla" falla absent replace create
pages_case "Pages ausente y sin dato del plan → falla" falla absent "" ""
pages_case "Pages incompleto y el plan crea la asociación del dominio → sigue (reintento tras Apply parcial)" pasa incomplete none create
pages_case "Pages incompleto y el plan no crea el dominio → falla" falla incomplete none none
pages_case "Pages presente y completo: no exige evidencia del plan" pasa present unknown unknown
check "production/pages_plan: la falta de evidencia dice que el saved plan se descarta (no hubo Apply)" \
  bash -c "pages_out=\$(cd '${SD}' && env -i PATH='${PATH}' PAGES_PROJECT_STATE=absent WATCH_PAGES_PROJECT=none WATCH_PAGES_DOMAIN=create bash -c \"\$(yq -r '.jobs.production.steps[] | select(.id == \"pages_plan\") | .run' '${WORKFLOW}')\" 2>&1); grep -q 'el saved plan se descarta' <<< \"\$pages_out\""

APPLY_ENV=(HCP_RUN_ID="${RUN}" EXPECTED_STATE=sv-Old1 HCP_STATE_BEFORE_PLAN=sv-Old1)
step_case "confirmación aceptada → salidas del Apply publicadas" pasa production hcp_apply "${APPLY_ENV[@]}" FAKE_OUT="apply_request=accepted\nrun_status=apply_queued"
check "production/hcp_apply: llama a hcp-state.sh apply una sola vez con el run de terraform_plan" \
  bash -c "[ \"\$(cat '${SD}/calls')\" = 'apply ${RUN}' ] && grep -qx apply_request=accepted '${SD}/out'"
step_case "confirmación fuera de GitHub → falla, con lo observado publicado para el resumen" falla production hcp_apply "${APPLY_ENV[@]}" FAKE_OUT="apply_request=out_of_band\nrun_status=applied" FAKE_RC=1
check "production/hcp_apply: una confirmación ajena queda registrada como out_of_band (nunca como aprobada)" \
  grep -qx apply_request=out_of_band "${SD}/out"
step_case "el estado de entrada del plan no es el capturado por terraform_plan → falla sin llamar a HCP" falla production hcp_apply HCP_RUN_ID="${RUN}" EXPECTED_STATE=sv-Old1 HCP_STATE_BEFORE_PLAN=sv-Otro FAKE_OUT=x
check "production/hcp_apply: con identidad inconsistente no hay solicitud" test ! -s "${SD}/calls"

# ── 12b. Contraseña inicial de Keycloak antes del Apply (kc_admin_preflight) ──
# Corre el `run` real del paso en el repositorio, con el ansible-playbook real (reglas de
# keycloak_credentials.yml) y valores sintéticos. Nunca toca HCP ni el servidor.
KC_BODY="$(yq -r '.jobs.production.steps[] | select(.id == "kc_admin_preflight") | .run' "${WORKFLOW}")"
KC_BACKEND="synthetic-kcb-$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
KC_AUTO="synthetic-kca-$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
KC_VALID="CANARY-pw-$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
EXAMPLE_PW="$(grep -E '^[A-Z_]*(SECRET|PASSWORD|KEY)=.' "${ROOT}/.env.example" | head -1 | cut -d= -f2-)"
kc_run() { # <acción del servidor> <RUN_HOST_CONFIG> [contraseña: omitida si no se pasa]
  local args=(PATH="${PATH}" HOME="${HOME}" ANSIBLE_CONFIG="${ROOT}/infra/ansible/ansible.cfg" ANSIBLE_LOCAL_TEMP="${TMP}/ansible-tmp"
    PRODUCTION_STAGE="${KC_STAGE:-provision}" PUSH_MODE="${KC_PUSH-deferred}" PUSH_DEPLOY_NOW="${KC_PUSH_NOW-}" WATCH_SERVER="$1" RUN_HOST_CONFIG="$2"
    KC_BACKEND_CLIENT_SECRET="${KC_BACKEND}" KC_AUTOMATION_CLIENT_SECRET="${KC_AUTO}")
  [ "$#" -lt 3 ] || args+=(KC_PLATFORM_ADMIN_INITIAL_PASSWORD="$3")
  (cd "${ROOT}" && env -i "${args[@]}" bash -c "${KC_BODY}") < /dev/null > "${TMP}/kc.log" 2>&1
}
kc_case() { # <descripción> <pasa|falla> <acción> <RUN_HOST_CONFIG> [contraseña] — y nunca el valor en el log
  local d="$1" exp="$2" rc=0
  shift 2
  kc_run "$@" || rc=$?
  if grep -qE 'CANARY|synthetic-' "${TMP}/kc.log" || { [ "$#" -ge 3 ] && [ -n "$3" ] && [[ "$3" != *$'\n'* ]] && grep -qF -- "$3" "${TMP}/kc.log"; }; then
    ko "production/kc_admin_preflight: ${d} (el log contiene un valor secreto)"
  elif { [ "${exp}" = pasa ] && [ "${rc}" -eq 0 ]; } || { [ "${exp}" = falla ] && [ "${rc}" -ne 0 ]; }; then
    ok "production/kc_admin_preflight: ${d}"
  else
    ko "production/kc_admin_preflight: ${d} (rc=${rc}: $(grep -E '::error|msg:|ERROR' "${TMP}/kc.log" | tail -1))"
  fi
}
if command -v ansible-playbook > /dev/null; then
  kc_case "servidor creado y contraseña ausente → falla antes del Apply" falla create true
  check "production/kc_admin_preflight: el fallo nombra la regla (vacía), dice que no se solicitó el Apply y no muestra el valor" \
    bash -c "grep -q 'vacío' '${TMP}/kc.log' && grep -q '::error title=Keycloak::KC_PLATFORM_ADMIN_INITIAL_PASSWORD falta o no cumple' '${TMP}/kc.log' && grep -q 'el saved plan se descarta' '${TMP}/kc.log' && grep -q 'valor no mostrado' '${TMP}/kc.log'"
  kc_case "servidor creado y contraseña válida → sigue a la confirmación" pasa create true "${KC_VALID}"
  check "production/kc_admin_preflight: el paso no llama a HCP ni solicita el Apply" bash -c "! grep -qiE 'hcp-state|actions/apply' '${TMP}/kc.log'"
  kc_case "servidor creado, contraseña corta → falla" falla create true "CANARY-short"
  grep -q 'menos de 16 caracteres' "${TMP}/kc.log" && ok "production/kc_admin_preflight: la regla incumplida se nombra (longitud)" || ko "production/kc_admin_preflight: la regla incumplida se nombra (longitud)"
  kc_case "servidor creado, contraseña con espacio final → falla" falla create true "${KC_VALID} "
  kc_case "servidor creado, contraseña con salto de línea en medio → falla" falla create true "${KC_VALID}"$'\n'"x"
  check "production/kc_admin_preflight: la regla de saltos de línea se nombra" grep -q 'con saltos de línea' "${TMP}/kc.log"
  kc_case "servidor creado, contraseña con retorno de carro en medio → falla" falla create true "${KC_VALID}"$'\r'"x"
  kc_case "servidor creado, contraseña que contiene el nombre de la cuenta → falla" falla create true "CANARY-piedrazul-platform-admin-1"
  kc_case "servidor creado, contraseña igual a una credencial técnica → falla" falla create true "${KC_BACKEND}"
  check "production/kc_admin_preflight: la regla de credencial técnica se nombra" grep -q 'coincide con una credencial técnica' "${TMP}/kc.log"
  kc_case "servidor creado, contraseña igual a un valor de .env.example → falla" falla create true "${EXAMPLE_PW}"
  check "production/kc_admin_preflight: la regla de .env.example se nombra" grep -q 'valor de referencia de .env.example' "${TMP}/kc.log"
  kc_case "servidor reemplazado y contraseña ausente → falla (misma protección que la creación)" falla replace true
  kc_case "servidor reemplazado y contraseña inválida → falla" falla replace true "CANARY-short"
  kc_case "servidor reemplazado y contraseña válida → sigue" pasa replace true "${KC_VALID}"
  kc_case "servidor sin cambios (no-op) y contraseña ausente → no se exige" pasa no-op true
  check "production/kc_admin_preflight: sin creación no corre la validación (ni Ansible)" \
    bash -c "grep -q 'no se exige KC_PLATFORM_ADMIN_INITIAL_PASSWORD' '${TMP}/kc.log' && ! grep -q 'PLAY' '${TMP}/kc.log'"
  kc_case "servidor actualizado en sitio y contraseña ausente → no se exige" pasa update true
  kc_case "servidor destruido sin crear otro → falla antes del Apply, sin contraseña que evaluar" falla delete true
  check "production/kc_admin_preflight: la destrucción se nombra como retiro" grep -q 'retiro es una decisión operativa' "${TMP}/kc.log"
  KC_STAGE=ordinary kc_case "servidor destruido en un run ordinario → falla antes del Apply" falla delete true "${KC_VALID}"
  kc_case "cambio de infraestructura sin el servidor y sin configuración del servidor (solo Terraform) → no se exige" pasa no-op false
  kc_case "servidor creado pero el run no configura el servidor (run_host_config=false) → falla aunque la contraseña sea válida" falla create false "${KC_VALID}"
  check "production/kc_admin_preflight: el fallo por falta de configuración del servidor lo explica" grep -q 'run_host_config=false' "${TMP}/kc.log"
  KC_STAGE=ordinary kc_case "servidor creado fuera de Run P → falla aunque la contraseña sea válida (segunda barrera antes de confirmar)" falla create true "${KC_VALID}"
  check "production/kc_admin_preflight: el fallo fuera de Run P nombra stage=provision" grep -q 'stage=provision' "${TMP}/kc.log"
  KC_STAGE=ordinary kc_case "servidor reemplazado fuera de Run P → falla" falla replace true "${KC_VALID}"
  KC_PUSH=enabled kc_case "Run P crea con los push habilitados en classify → falla aunque la contraseña sea válida" falla create true "${KC_VALID}"
  check "production/kc_admin_preflight: el fallo nombra la variable de la retención" grep -q 'PRODUCTION_PUSH_DEPLOY=deferred' "${TMP}/kc.log"
  KC_PUSH= kc_case "Run P reemplaza sin el modo de push de classify → falla cerrado" falla replace true "${KC_VALID}"
  KC_PUSH_NOW=enabled kc_case "la retención se levantó durante la espera de la aprobación → falla antes del Apply" falla replace true "${KC_VALID}"
  KC_PUSH_NOW=enable kc_case "un valor inválido leído antes del Apply no retiene → falla" falla create true "${KC_VALID}"
  KC_PUSH_NOW=deferred kc_case "Run P con la retención explícita en ambas lecturas → sigue" pasa create true "${KC_VALID}"
  KC_PUSH=enabled KC_PUSH_NOW=enabled kc_case "recuperación que no crea ni reemplaza con push habilitados → no hay bloqueo mutuo" pasa no-op true
  KC_PUSH=enabled KC_PUSH_NOW=enabled KC_STAGE=ordinary kc_case "cambio ordinario sin tocar el servidor con push habilitados → sigue" pasa update true
  for a in none ambiguous unknown "" forget read "create,delete,update"; do
    kc_case "acción no interpretable sobre el servidor ('${a}') → falla aunque la contraseña sea válida" falla "${a}" true "${KC_VALID}"
  done
  check "production/kc_admin_preflight: la acción no interpretable dice que el saved plan se descarta" grep -q 'el saved plan se descarta' "${TMP}/kc.log"
else
  ko "production/kc_admin_preflight: falta ansible-playbook para ejecutar el paso"
fi

# ── 13. Barrera de cloud-init (host-ready.sh) ───────────────────────────────
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
echo "Host *" > "${TMP}/verified_ssh_config"
ready() { # <JSON> [variables extra...]
  local json="$1"
  shift
  env SSH_BIN="${TMP}/bin/fake-ssh" SSH_CONFIG_FILE="${TMP}/verified_ssh_config" FAKE_CI_JSON="${json}" "$@" "${READY}" 203.0.113.7
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
check "host-ready: espera (status --wait) y lee el estado final en JSON, como ansible, con el ssh_config verificado de este run" \
  bash -c "grep -qF -- '-F ${TMP}/verified_ssh_config' '${FAKE_SSH_LOG}' && ! grep -q 'StrictHostKeyChecking' '${FAKE_SSH_LOG}' && grep -q 'ansible@203.0.113.7' '${FAKE_SSH_LOG}' && grep -q 'cloud-init status --wait > /dev/null 2>&1; cloud-init status --format json' '${FAKE_SSH_LOG}'"
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
