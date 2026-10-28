#!/usr/bin/env bash
# Workspace, configuración, saved plan, Apply y estado de HCP Terraform para la cadena
# Plan → revisión en GitHub → Apply → Deploy.
#
# Uso:
#   hcp-state.sh workspace [apply]
#   hcp-state.sh wait-idle
#   hcp-state.sh current
#   hcp-state.sh upload <directorio>
#   hcp-state.sh create-run <configuration_version_id>
#   hcp-state.sh plan <run_id>
#   hcp-state.sh apply <run_id>
#   hcp-state.sh cleanup [run_id]
#   hcp-state.sh run-state <run_id> <state_version_antes|none>
#   hcp-state.sh outputs <state_version_id>
#
# Entorno: TF_API_TOKEN, HCP_WORKSPACE_ID, HCP_ORGANIZATION (o TF_CLOUD_ORGANIZATION),
#   HCP_WORKSPACE_NAME (o TF_WORKSPACE); HCP_API_URL (por defecto app.terraform.io/api/v2),
#   HCP_APP_URL (enlaces; por defecto https://app.terraform.io). create-run, plan, apply y
#   cleanup además: RUN_MESSAGE (el mensaje exacto del run de esta ejecución: identifica al
#   run propio).
#
# Contrato:
#   - workspace: el id es el workspace declarado (nombre y organización), el token lo lee y
#     puede leer su estado, y la ejecución es remota (remote/agent). Con `apply`, además: el
#     token puede encolar runs y confirmarlos (can-queue-run y can-queue-apply) y el
#     workspace NO aplica solo (auto-apply y auto-apply-run-trigger en false). Un
#     401/403/404 es credencial o workspace incorrectos, nunca "workspace vacío".
#   - wait-idle: espera a que ningún run reciente del workspace (los 20 más recientes) esté
#     activo o a la espera de una resolución. Terminados (applied, planned_and_finished,
#     discarded, canceled, force_canceled, errored, policy_soft_failed) y saved plans sin
#     confirmar (planned_and_saved: HCP los deja fuera de la cola y los descarta si el estado
#     cambia) no ocupan el workspace; cualquier otro estado sí.
#   - current: id del state version actual, o `none` si el workspace (verificado como en
#     `workspace`) no tiene ninguno: solo cuando current-state-version responde 404 Y la
#     lista de state versions del workspace está vacía. Cualquier otro código, cuerpo
#     ilegible o contradicción falla: un error nunca se lee como instalación limpia.
#   - upload: sube <directorio> como configuration version PROVISIONAL (no pasa a ser la
#     configuración actual del workspace salvo que su run se aplique), sin auto-queue-runs y
#     no speculative, y espera a que HCP la procese (uploaded). La respuesta de HCP debe
#     confirmar los tres atributos; si no (o si la subida falla, vence o HCP la marca
#     errored), falla: nunca se recurre a una configuración no provisional. El archivo es un
#     tar.gz reproducible (sin .terraform/, .git/ ni *.tfstate*). La URL de subida es una
#     credencial temporal: nunca se imprime. Salida: configuration_version_id y
#     archive_sha256 (sha256 del archivo subido, no un hash del plan).
#   - create-run: crea, con esa configuración (uploaded, provisional, no speculative), un
#     saved plan (save-plan=true, no plan-only, no destroy) con auto-apply=false explícito, y
#     verifica en la respuesta que HCP lo registró así (workspace, configuración, mensaje).
#     HCP nunca aplica solo un saved plan: solo se aplica confirmándolo (subcomando apply).
#   - plan: (EXPECTED_STATE: el state version capturado por `current` antes de crear el run,
#     sv-… o none; obligatorio) espera a que el saved plan termine y lo clasifica:
#       * planned_and_saved, confirmable, con cambios → has_changes=true (espera el Apply);
#       * planned_and_finished sin cambios → has_changes=false (terminal: nada que aplicar
#         ni que descartar);
#       * planned_and_saved sin cambios, una contradicción, fallo, descarte, cancelación,
#         override de políticas o run tasks → falla;
#       * confirmado (Apply aceptado) antes de la revisión de GitHub → confirmación fuera de
#         GitHub: se observa hasta que termina (sin cancelarla) y falla.
#     Con cambios, el JSON del plan es obligatorio: se lee una vez a un archivo privado
#     temporal (nunca se imprime ni se sube), plan-review.sh lo convierte en la revisión
#     saneada (PLAN_REVIEW_FILE) y sus conteos deben coincidir con los del plan de HCP.
#     PLAN_WATCH (opcional): "<nombre>=<dirección> …"; por cada uno informa la acción
#     OBSERVADA en el JSON del plan, watch_<nombre>=create|delete|replace|update|no-op|none,
#     o unknown (JSON ilegible) o ambiguous (la dirección aparece más de una vez).
#     Estado (ver bind_state): si HCP informa input-state-version, debe ser EXPECTED_STATE
#     (otra cosa es una contradicción y falla); la relación es opcional (HCP la omitió en un
#     saved plan real), y en todos los casos el estado actual, leído después del plan, debe
#     seguir siendo EXPECTED_STATE. Salida: plan_state_version y state_binding
#     (relationship|bracketed).
#     FRESHNESS_CMD (opcional; p. ej. verify-main-tip.sh): si el commit dejó de ser la punta
#     de main, el run propio se descarta (si espera el Apply) y falla.
#   - apply: confirma el saved plan propio, una sola vez, después de revalidar en ese momento:
#     workspace (apply), run propio (mensaje, save-plan, auto-apply=false, no destroy),
#     planned_and_saved y confirmable con cambios, plan (EXPECTED_PLAN_ID), configuración
#     (EXPECTED_CONFIGURATION_VERSION), antigüedad del run (PLAN_MAX_AGE_SECONDS, por
#     defecto 3600), ningún otro run activo, frescura (FRESHNESS_CMD) y, al final, estado
#     (EXPECTED_STATE, sv-… o none: el de entrada del run si HCP lo informa y el actual del
#     workspace, como en plan). Envía UN solo
#     POST /runs/<id>/actions/apply y nunca lo repite:
#       * 202 → apply_request=accepted cuando HCP muestra el run confirmado;
#       * 409 → se relee: descartado (estado cambiado) falla; confirmado por otro es una
#         confirmación fuera de GitHub;
#       * sin respuesta, 5xx u otro código → se relee con plazo: confirmado después de la
#         solicitud (confirmed-at) → apply_request=accepted_after_ambiguous; si no se puede
#         atribuir, es una confirmación fuera de GitHub; si sigue sin confirmar, falla sin
#         reintentar (cleanup lo descarta).
#     Una confirmación fuera de GitHub nunca se atribuye a la aprobación de GitHub: se
#     observa hasta que termina (sin cancelarla), se informa (apply_request=out_of_band) y
#     falla, así la cadena no sigue mutando el servidor. Aceptar la solicitud no es aplicar:
#     el resultado lo determina run-state.
#   - cleanup: solo sobre runs propios (este workspace y exactamente RUN_MESSAGE; uno ajeno
#     no se toca y falla). Sin run_id (GitHub no llegó a registrarlo: la creación falló o se
#     cortó después del POST), busca el run por RUN_MESSAGE entre los 20 más recientes del
#     workspace. Descartable (is-discardable) → lo descarta. Planificando → lo cancela.
#     Confirmado o aplicando → no lo toca: un Apply que HCP ya aceptó no se deshace cancelando
#     el job (lo informa). Terminado (también planned_and_finished) → nada. Salida:
#     cleanup=discarded|canceled|apply_accepted|none (separados por coma si hay varios).
#   - run-state: espera a que <run_id> (creado por esta misma cadena) termine y determina el
#     state version exacto que el Deploy debe consumir:
#       * si el apply del run tiene state versions enlazados, el actual del workspace
#         debe ser uno de ellos (si no, otro run lo reemplazó: falla);
#       * si no tiene, el run debe ser planned_and_finished y el actual debe seguir
#         siendo <state_version_antes> (`none` si el workspace no tenía estado).
#     Cualquier otro estado final, un run de otro workspace o una contradicción falla.
#   - outputs: lee los outputs de ese state version exacto, y solo si sigue siendo el
#     actual antes y después de leerlos.
#
# Salida en formato GITHUB_OUTPUT. El token nunca va en la línea de comandos.
#
# Archivos temporales: todos (respuestas de HCP, la URL de subida, el archivo de la
# configuración, el JSON del plan) van a un directorio privado propio (0700), que se borra
# al salir, con éxito, con fallo o por INT/TERM. Su contenido nunca se imprime.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

WORK_DIR="$(umask 077 && mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export TMPDIR="${WORK_DIR}"

: "${TF_API_TOKEN:?TF_API_TOKEN no definido}"
: "${HCP_WORKSPACE_ID:?HCP_WORKSPACE_ID no definido}"
API="${HCP_API_URL:-https://app.terraform.io/api/v2}"
APP="${HCP_APP_URL:-https://app.terraform.io}"
ORGANIZATION="${HCP_ORGANIZATION:-${TF_CLOUD_ORGANIZATION:-}}"
WORKSPACE_NAME="${HCP_WORKSPACE_NAME:-${TF_WORKSPACE:-}}"
POLL_INTERVAL="${HCP_POLL_INTERVAL:-15}"
POLL_ATTEMPTS="${HCP_POLL_ATTEMPTS:-60}"
ACK_ATTEMPTS="${HCP_ACK_ATTEMPTS:-8}"
UPLOAD_ATTEMPTS="${HCP_UPLOAD_ATTEMPTS:-20}"
OUTPUTS_INTERVAL="${HCP_OUTPUTS_INTERVAL:-5}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

[[ "${HCP_WORKSPACE_ID}" =~ ^ws-[A-Za-z0-9]+$ ]] || fail "HCP_WORKSPACE_ID inválido"

# Estados de un run (API de HCP Terraform)
PLANNING_STATES=" pending fetching fetching_completed pre_plan_running pre_plan_completed queuing plan_queued planning planned cost_estimating cost_estimated policy_checking policy_checked post_plan_running post_plan_completed "
# Confirmado: HCP ya aceptó el Apply
APPLY_STATES=" confirmed apply_queued queuing_apply pre_apply_running pre_apply_completed applying applied "
# No ocupan el workspace: terminados y saved plans sin confirmar
IDLE_STATES=" applied planned_and_finished discarded canceled force_canceled errored policy_soft_failed planned_and_saved "
# Terminados sin Apply en curso (para observar un Apply ajeno sin cancelarlo)
FINAL_STATES=" applied planned_and_finished discarded canceled force_canceled errored policy_soft_failed "
in_set() { [[ "$2" == *" $1 "* ]]; }

api() {
  curl -sSf \
    -H @<(printf 'Authorization: Bearer %s\n' "${TF_API_TOKEN}") \
    -H "Content-Type: application/vnd.api+json" \
    "${API}$1"
}

# hcp <método> <ruta> <archivo de cuerpo> [archivo de datos]: código HTTP ("000" sin respuesta)
hcp() {
  local code data=()
  [ -z "${4:-}" ] || data=(--data-binary "@$4")
  code=$(curl -sS -X "$1" -o "$3" -w '%{http_code}' --max-time 60 \
    -H @<(printf 'Authorization: Bearer %s\n' "${TF_API_TOKEN}") \
    -H "Content-Type: application/vnd.api+json" \
    "${data[@]}" "${API}$2") || code="000"
  echo "${code}"
}

require_identity() {
  [[ "${ORGANIZATION}" =~ ^[A-Za-z0-9_-]+$ ]] || fail "organización de HCP no definida o inválida (HCP_ORGANIZATION/TF_CLOUD_ORGANIZATION)"
  [[ "${WORKSPACE_NAME}" =~ ^[A-Za-z0-9_-]+$ ]] || fail "nombre de workspace no definido o inválido (HCP_WORKSPACE_NAME/TF_WORKSPACE)"
}

run_link() { echo "${APP}/app/${ORGANIZATION}/workspaces/${WORKSPACE_NAME}/runs/$1"; }

# workspace_json: el workspace verificado (identidad, permisos de lectura, ejecución remota)
workspace_json() {
  local body code ws
  require_identity
  body=$(mktemp)
  code=$(hcp GET "/workspaces/${HCP_WORKSPACE_ID}" "${body}")
  case "${code}" in
    200) ;;
    401) fail "token de HCP Terraform inválido o vencido (HTTP 401)" ;;
    403 | 404) fail "el workspace ${HCP_WORKSPACE_ID} no existe o el token no tiene acceso (HTTP ${code})" ;;
    000) fail "sin respuesta de HCP Terraform" ;;
    *) fail "respuesta inesperada al leer el workspace (HTTP ${code})" ;;
  esac
  ws=$(jq -c '.data // empty' "${body}" 2> /dev/null) || fail "respuesta ilegible al leer el workspace"
  [ -n "${ws}" ] || fail "respuesta ilegible al leer el workspace"
  jq -e --arg id "${HCP_WORKSPACE_ID}" --arg n "${WORKSPACE_NAME}" --arg o "${ORGANIZATION}" '
      .id == $id and .attributes.name == $n and .relationships.organization.data.id == $o' <<< "${ws}" > /dev/null ||
    fail "${HCP_WORKSPACE_ID} no es el workspace ${ORGANIZATION}/${WORKSPACE_NAME} (es $(jq -r '"\(.relationships.organization.data.id // "?")/\(.attributes.name // "?")"' <<< "${ws}"))"
  jq -e '.attributes.permissions["can-read-state-versions"] == true' <<< "${ws}" > /dev/null ||
    fail "el token no puede leer el estado de ${WORKSPACE_NAME} (can-read-state-versions)"
  jq -e '.attributes["execution-mode"] | IN("remote", "agent")' <<< "${ws}" > /dev/null ||
    fail "${WORKSPACE_NAME} no ejecuta en HCP (execution-mode: $(jq -r '.attributes["execution-mode"] // "?"' <<< "${ws}")); la cadena necesita runs remotos"
  echo "${ws}"
}

cmd_workspace() {
  local mode="${1:-read}" ws
  ws=$(workspace_json)
  if [ "${mode}" = apply ]; then
    jq -e '.attributes.permissions["can-queue-run"] == true' <<< "${ws}" > /dev/null ||
      fail "el token no puede crear runs en ${WORKSPACE_NAME} (can-queue-run)"
    jq -e '.attributes.permissions["can-queue-apply"] == true' <<< "${ws}" > /dev/null ||
      fail "el token no puede confirmar runs en ${WORKSPACE_NAME} (can-queue-apply)"
    jq -e '.attributes["auto-apply"] == false' <<< "${ws}" > /dev/null ||
      fail "${WORKSPACE_NAME} tiene auto-apply habilitado: un plan se aplicaría sin revisión. Deshabilitarlo en HCP (Settings → General → Apply method: Manual)"
    jq -e '(.attributes["auto-apply-run-trigger"] // false) == false' <<< "${ws}" > /dev/null ||
      fail "${WORKSPACE_NAME} aplica solo los runs de run triggers (auto-apply-run-trigger): un plan se aplicaría sin revisión"
  fi
  echo "workspace_name=${WORKSPACE_NAME}"
  echo "organization=${ORGANIZATION}"
  echo "execution_mode=$(jq -r '.attributes["execution-mode"]' <<< "${ws}")"
  echo "auto_apply=$(jq -r '.attributes["auto-apply"]' <<< "${ws}")"
}

# current_state: sv-… o none, con la ausencia comprobada por dos lecturas
current_state() {
  local body code sv list
  workspace_json > /dev/null
  body=$(mktemp)
  code=$(hcp GET "/workspaces/${HCP_WORKSPACE_ID}/current-state-version" "${body}")
  case "${code}" in
    200)
      sv="$(jq -r '.data.id // ""' "${body}" 2> /dev/null)" || fail "respuesta ilegible del state version actual"
      [[ "${sv}" =~ ^sv-[A-Za-z0-9]+$ ]] || fail "state version actual inválido: '${sv}'"
      echo "${sv}"
      ;;
    404)
      # 404 también puede ser falta de permiso: el workspace ya se leyó con
      # can-read-state-versions y la lista de state versions debe responder y estar vacía
      list=$(mktemp)
      code=$(hcp GET "/state-versions?filter%5Bworkspace%5D%5Bname%5D=${WORKSPACE_NAME}&filter%5Borganization%5D%5Bname%5D=${ORGANIZATION}&page%5Bsize%5D=1" "${list}")
      [ "${code}" = 200 ] || fail "current-state-version respondió 404 y la lista de state versions respondió HTTP ${code}: no se puede afirmar que el workspace esté vacío"
      jq -e '.data | type == "array"' "${list}" > /dev/null 2>&1 || fail "lista de state versions ilegible"
      jq -e '.data | length == 0' "${list}" > /dev/null ||
        fail "current-state-version respondió 404 pero el workspace tiene state versions: respuesta contradictoria"
      echo "none"
      ;;
    000) fail "sin respuesta de HCP Terraform al leer el state version actual" ;;
    *) fail "no se pudo leer el state version actual (HTTP ${code})" ;;
  esac
}

# run_json <run_id>: el run, que debe ser de este workspace
run_json() {
  local body code
  body=$(mktemp)
  code=$(hcp GET "/runs/$1" "${body}")
  [ "${code}" = 200 ] || fail "no se pudo leer el run $1 (HTTP ${code})"
  jq -e --arg ws "${HCP_WORKSPACE_ID}" '.data.relationships.workspace.data.id == $ws' "${body}" > /dev/null 2>&1 ||
    fail "el run $1 no pertenece a ${HCP_WORKSPACE_ID}"
  jq -c '.data' "${body}"
}

# require_mine <run JSON>: el run es el de esta ejecución (mismo mensaje exacto)
require_mine() {
  : "${RUN_MESSAGE:?RUN_MESSAGE no definido}"
  jq -e --arg m "${RUN_MESSAGE}" '.attributes.message == $m' <<< "$1" > /dev/null ||
    fail "el run $(jq -r .id <<< "$1") no es el de esta ejecución (mensaje distinto): no se toca"
}

# require_owned <run JSON>: propio, saved plan, y nunca aplica solo
require_owned() {
  require_mine "$1"
  jq -e '.attributes["auto-apply"] == false and .attributes["plan-only"] == false and .attributes["is-destroy"] == false and .attributes["save-plan"] == true' <<< "$1" > /dev/null ||
    fail "el run $(jq -r .id <<< "$1") no es un saved plan aplicable con auto-apply=false"
}

status_of() { jq -r '.attributes.status // ""' <<< "$1"; }

# discard_owned <run_id> <comentario>: descarta el run propio si todavía se puede
discard_owned() {
  local run body data code
  run=$(run_json "$1")
  require_mine "${run}"
  if ! jq -e '.attributes.actions["is-discardable"] == true' <<< "${run}" > /dev/null; then
    echo "El run $1 ya no se puede descartar (status: $(status_of "${run}"))" >&2
    return 1
  fi
  body=$(mktemp) data=$(mktemp)
  jq -n --arg c "$2" '{comment: $c}' > "${data}"
  code=$(hcp POST "/runs/$1/actions/discard" "${body}" "${data}")
  [ "${code}" = 202 ] || { echo "HCP no aceptó el descarte del run $1 (HTTP ${code})" >&2; return 1; }
  echo "Run $1 descartado: $2" >&2
}

# fresh: 0 si el commit sigue siendo la punta de main (o no hay FRESHNESS_CMD)
fresh() { [ -z "${FRESHNESS_CMD:-}" ] || bash -c "${FRESHNESS_CMD}" >&2; }

# observe_out_of_band <run_id>: un Apply confirmado fuera de GitHub se observa (nunca se
# cancela) hasta que termina o vence el plazo; escribe lo observado y falla
observe_out_of_band() {
  local run_id="$1" run status confirmed
  run=$(run_json "${run_id}")
  confirmed=$(jq -r '.attributes["status-timestamps"]["confirmed-at"] // "?"' <<< "${run}")
  echo "::error title=Terraform::El run ${run_id} se confirmó fuera de la autorización de GitHub (confirmado: ${confirmed}). No se cancela un Apply aceptado; la cadena no sigue mutando el servidor ni Pages: $(run_link "${run_id}")" >&2
  status=$(status_of "${run}")
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    in_set "${status}" "${FINAL_STATES}" && break
    echo "Apply fuera de GitHub en '${status}' — observando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
    sleep "${POLL_INTERVAL}"
    status=$(status_of "$(run_json "${run_id}")")
  done
  echo "apply_request=out_of_band"
  echo "run_status=${status}"
  echo "confirmed_at=${confirmed}"
  fail "Apply del run ${run_id} confirmado fuera de GitHub (estado observado: ${status}); revisar el resultado y volver a correr la cadena completa"
}

# idle_runs: 0 si ningún run reciente distinto de <excluir> ocupa el workspace
busy_runs() { # [run_id a excluir] → ids y estados de los runs que ocupan el workspace
  local body code
  body=$(mktemp)
  code=$(hcp GET "/workspaces/${HCP_WORKSPACE_ID}/runs?page%5Bsize%5D=20" "${body}")
  [ "${code}" = 200 ] || fail "no se pudieron listar los runs del workspace (HTTP ${code})"
  jq -r --arg x "${1:-}" --arg idle "${IDLE_STATES}" '
      .data | if type == "array" then .[] else error("x") end
      | select(.id != $x)
      | (.attributes.status // "?") as $s
      | select(($idle | contains(" " + $s + " ")) | not)
      | "\(.id):\($s)"' "${body}" 2> /dev/null || fail "lista de runs ilegible"
}

cmd_wait_idle() {
  local busy
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    busy=$(busy_runs)
    if [ -z "${busy}" ]; then
      echo "Terraform libre: ningún run activo ni a la espera de una resolución" >&2
      return 0
    fi
    # Activo o esperando una resolución (pending, planning, planned, policy_override,
    # apply_queued, applying, ...): no se compite con él
    echo "Terraform no está libre ($(paste -sd' ' <<< "${busy}")) — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
    sleep "${POLL_INTERVAL}"
  done
  fail "Terraform no liberó el workspace a tiempo"
}

cmd_upload() {
  local dir="$1" archive body data code cv cv_id url sha status
  require_identity
  [ -d "${dir}" ] || fail "directorio de configuración inexistente: ${dir}"
  # Archivo, respuestas y URL de subida solo en el directorio temporal privado (ver arriba);
  # además se borran apenas dejan de hacer falta
  archive=$(mktemp)
  # Reproducible: orden, fechas y dueños fijos; sin metadatos de gzip
  tar -C "${dir}" --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    --exclude='.terraform' --exclude='.git' --exclude='*.tfstate' --exclude='*.tfstate.*' \
    -cf - . | gzip -n > "${archive}" || fail "no se pudo empaquetar ${dir}"
  sha=$(sha256sum "${archive}" | cut -d' ' -f1)

  body=$(mktemp) data=$(mktemp)
  jq -n '{data: {type: "configuration-versions",
            attributes: {"auto-queue-runs": false, speculative: false, provisional: true}}}' > "${data}"
  code=$(hcp POST "/workspaces/${HCP_WORKSPACE_ID}/configuration-versions" "${body}" "${data}")
  [ "${code}" = 201 ] || fail "HCP no creó la configuración (HTTP ${code}): $(jq -r '[.errors[]?.detail // .errors[]?.title] | join("; ")' "${body}" 2> /dev/null || true)"
  cv=$(jq -c '.data' "${body}" 2> /dev/null) || fail "respuesta ilegible al crear la configuración"
  cv_id=$(jq -r '.id // ""' <<< "${cv}")
  [[ "${cv_id}" =~ ^cv-[A-Za-z0-9]+$ ]] || fail "id de configuración inválido en la respuesta: '${cv_id}'"
  # Sin provisional confirmado por HCP no se sube nada: nunca una configuración no provisional
  jq -e '.attributes.provisional == true and .attributes.speculative == false and .attributes["auto-queue-runs"] == false' <<< "${cv}" > /dev/null ||
    fail "HCP no registró la configuración ${cv_id} como provisional, no speculative y sin auto-queue-runs: no se sube"
  url=$(jq -r '.attributes["upload-url"] // ""' <<< "${cv}")
  [[ "${url}" =~ ^https://[^[:space:]]+$ ]] || fail "la configuración ${cv_id} no trae una URL de subida válida"

  # La URL es una credencial temporal: ni el token ni la URL se imprimen
  code=$(curl -sS -X PUT -o /dev/null -w '%{http_code}' --max-time 120 \
    -H "Content-Type: application/octet-stream" --data-binary "@${archive}" "${url}" 2> /dev/null) || code="000"
  rm -f "${archive}" "${body}" "${data}"
  url="" cv=""
  [ "${code}" = 200 ] || fail "HCP no aceptó el archivo de la configuración ${cv_id} (HTTP ${code})"

  status=""
  for i in $(seq 1 "${UPLOAD_ATTEMPTS}"); do
    code=$(hcp GET "/configuration-versions/${cv_id}" "${body}")
    [ "${code}" = 200 ] || fail "no se pudo leer la configuración ${cv_id} (HTTP ${code})"
    jq -e '.data.attributes.provisional == true and .data.attributes.speculative == false' "${body}" > /dev/null 2>&1 ||
      fail "la configuración ${cv_id} dejó de ser provisional o es speculative"
    status=$(jq -r '.data.attributes.status // ""' "${body}")
    case "${status}" in
      uploaded) break ;;
      errored) fail "HCP no pudo procesar la configuración ${cv_id} (errored)" ;;
      pending | fetching) ;;
      *) fail "estado inesperado de la configuración ${cv_id}: '${status}'" ;;
    esac
    echo "Configuración ${cv_id} en '${status}' — esperando ${POLL_INTERVAL}s ($i/${UPLOAD_ATTEMPTS})" >&2
    status=""
    sleep "${POLL_INTERVAL}"
  done
  [ "${status}" = uploaded ] || fail "la configuración ${cv_id} no terminó de procesarse a tiempo"
  echo "configuration_version_id=${cv_id}"
  echo "archive_sha256=${sha}"
}

cmd_create_run() {
  local cv="$1" body data code run run_id
  : "${RUN_MESSAGE:?RUN_MESSAGE no definido}"
  require_identity
  [[ "${cv}" =~ ^cv-[A-Za-z0-9]+$ ]] || fail "configuration version inválida: '${cv}'"
  body=$(mktemp)
  code=$(hcp GET "/configuration-versions/${cv}" "${body}")
  [ "${code}" = 200 ] || fail "no se pudo leer la configuración ${cv} (HTTP ${code})"
  jq -e '.data.attributes.status == "uploaded" and .data.attributes.speculative == false' "${body}" > /dev/null 2>&1 ||
    fail "la configuración ${cv} no está subida o es speculative"
  jq -e '.data.attributes.provisional == true' "${body}" > /dev/null 2>&1 ||
    fail "la configuración ${cv} no es provisional: un saved plan sin aplicar la dejaría como configuración actual"

  data=$(mktemp)
  jq -n --arg m "${RUN_MESSAGE}" --arg ws "${HCP_WORKSPACE_ID}" --arg cv "${cv}" '
    {data: {type: "runs",
      attributes: {message: $m, "auto-apply": false, "plan-only": false, "is-destroy": false,
                   "save-plan": true, refresh: true},
      relationships: {workspace: {data: {type: "workspaces", id: $ws}},
                      "configuration-version": {data: {type: "configuration-versions", id: $cv}}}}}' > "${data}"
  code=$(hcp POST "/runs" "${body}" "${data}")
  [ "${code}" = 201 ] || fail "HCP no creó el run (HTTP ${code}): $(jq -r '[.errors[]?.detail // .errors[]?.title] | join("; ")' "${body}" 2> /dev/null || true)"
  run=$(jq -c '.data' "${body}" 2> /dev/null) || fail "respuesta ilegible al crear el run"
  run_id=$(jq -r '.id // ""' <<< "${run}")
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "id de run inválido en la respuesta: '${run_id}'"
  jq -e --arg ws "${HCP_WORKSPACE_ID}" --arg cv "${cv}" '
      .relationships.workspace.data.id == $ws and .relationships["configuration-version"].data.id == $cv' <<< "${run}" > /dev/null ||
    fail "el run ${run_id} no quedó ligado a este workspace y a la configuración ${cv}"
  require_owned "${run}"
  echo "run_id=${run_id}"
  echo "run_link=$(run_link "${run_id}")"
}

# input_state <run JSON>: lo que HCP informa como state version de entrada del run:
#   sv-…        relación presente con data {id: sv-…, type: "state-versions"};
#   none        relación presente con data: null (el run partió sin estado);
#   unreported  relación ausente (clave inexistente en relationships), o presente como
#               objeto sin clave data (HCP no la informa: observado en saved plans
#               planned_and_saved). Nunca se lee como none.
# Cualquier otra forma (relationships que no es objeto, relación null o que no es objeto,
# data que no es objeto ni null, tipo distinto de state-versions o ausente, id malformado)
# falla.
input_state() {
  local sv
  sv=$(jq -r '
      if (.relationships | type) != "object" then "!"
      elif (.relationships | has("input-state-version") | not) then "unreported"
      else .relationships["input-state-version"] as $r
      | if ($r | type) != "object" then "!"
        elif ($r | has("data") | not) then "unreported"
        elif $r.data == null then "none"
        elif ($r.data | type) == "object" and $r.data.type == "state-versions" and ($r.data.id | type) == "string" then $r.data.id
        else "!" end end' <<< "$1" 2> /dev/null) || sv="!"
  [[ "${sv}" =~ ^sv-[A-Za-z0-9]+$ ]] || [ "${sv}" = none ] || [ "${sv}" = unreported ] ||
    fail "el run $(jq -r .id <<< "$1") informa un state version de entrada malformado"
  echo "${sv}"
}

# bind_state <run JSON> <state esperado>: liga el saved plan al state version esperado
# (el capturado antes de crear el run: sv-… o none) y escribe cómo:
#   relationship  HCP informa el state version de entrada y es el esperado;
#   bracketed     HCP no lo informa: el esperado se capturó antes de crear el run y sigue
#                 siendo el actual ahora (después del plan). Un state version nuevo siempre
#                 tiene un id nuevo (también un rollback), así que el actual no pudo cambiar y
#                 volver; HCP además descarta un saved plan si el estado cambia.
# En ambos casos el estado actual debe seguir siendo el esperado (none solo si current lo
# confirma por dos lecturas). Una relación informada distinta del esperado es una
# contradicción y falla: nunca se recurre al estado actual para taparla.
bind_state() {
  local run="$1" expected="$2" in_sv current id
  id=$(jq -r .id <<< "${run}")
  [[ "${expected}" =~ ^sv-[A-Za-z0-9]+$ ]] || [ "${expected}" = none ] ||
    fail "state version esperado ausente o inválido: '${expected}'"
  in_sv=$(input_state "${run}")
  if [ "${in_sv}" != unreported ] && [ "${in_sv}" != "${expected}" ]; then
    fail "HCP informa que el plan del run ${id} partió de ${in_sv}, no del estado capturado (${expected}): contradicción"
  fi
  current=$(current_state)
  [ "${current}" = "${expected}" ] ||
    fail "el estado del workspace cambió desde la captura previa al plan (${expected} → ${current}): el saved plan del run ${id} ya no corresponde"
  if [ "${in_sv}" = unreported ]; then echo bracketed; else echo relationship; fi
}

# watch_actions <plan JSON|vacío>: watch_<nombre>=<acción observada> por cada entrada de
# PLAN_WATCH. Solo acciones observadas: sin JSON legible todas son unknown.
watch_actions() {
  local json="$1" name addr
  for entry in ${PLAN_WATCH:-}; do
    name="${entry%%=*}" addr="${entry#*=}"
    [[ "${name}" =~ ^[a-z_]+$ ]] && [ -n "${addr}" ] && [ "${addr}" != "${entry}" ] || fail "PLAN_WATCH inválido: '${entry}'"
    if [ -z "${json}" ]; then
      echo "watch_${name}=unknown"
      continue
    fi
    echo "watch_${name}=$(jq -r --arg a "${addr}" '
      [.resource_changes[] | select(.address == $a) | .change.actions] |
      if length == 0 then "none"
      elif length > 1 then "ambiguous"
      elif .[0] == ["create"] then "create"
      elif .[0] == ["delete"] then "delete"
      elif (.[0] | index("create")) and (.[0] | index("delete")) then "replace"
      else .[0] | join(",") end' "${json}")"
  done
}

cmd_plan() {
  local run_id="$1" run status plan_id body code has json review counts binding cv
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
  [[ "${EXPECTED_STATE:-}" =~ ^sv-[A-Za-z0-9]+$ ]] || [ "${EXPECTED_STATE:-}" = none ] || fail "EXPECTED_STATE inválido"
  require_identity
  status=""
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    run=$(run_json "${run_id}")
    require_owned "${run}"
    status=$(status_of "${run}")
    case "${status}" in
      planned_and_saved)
        jq -e '.attributes["has-changes"] == true' <<< "${run}" > /dev/null ||
          fail "el saved plan del run ${run_id} quedó en planned_and_saved sin cambios: resultado no admitido (se esperaba planned_and_finished)"
        jq -e '.attributes.actions["is-confirmable"] == true' <<< "${run}" > /dev/null ||
          fail "el saved plan del run ${run_id} tiene cambios pero HCP no lo admite como confirmable"
        has=true
        break
        ;;
      planned_and_finished)
        jq -e '.attributes["has-changes"] == false' <<< "${run}" > /dev/null ||
          fail "el run ${run_id} terminó sin aplicar (planned_and_finished) pero informa cambios: contradicción"
        has=false
        break
        ;;
      errored | canceled | force_canceled) fail "el plan del run ${run_id} terminó en '${status}' ($(run_link "${run_id}"))" ;;
      discarded) fail "el run ${run_id} fue descartado antes de terminar la revisión" ;;
      policy_override | policy_soft_failed | post_plan_awaiting_decision)
        fail "el run ${run_id} requiere una decisión de políticas o run tasks (${status}) que la cadena no admite ($(run_link "${run_id}"))" ;;
    esac
    # Un saved plan solo se aplica confirmándolo: si ya está aquí, alguien lo confirmó
    # fuera de GitHub antes de que terminara la revisión
    in_set "${status}" "${APPLY_STATES}" && observe_out_of_band "${run_id}"
    in_set "${status}" "${PLANNING_STATES}" || fail "el run ${run_id} está en un estado inesperado: '${status}'"
    echo "Run ${run_id} en '${status}' — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
    status=""
    sleep "${POLL_INTERVAL}"
  done
  [ -n "${status}" ] || fail "el plan del run ${run_id} no terminó a tiempo"

  plan_id=$(jq -r '.relationships.plan.data.id // ""' <<< "${run}")
  [[ "${plan_id}" =~ ^plan-[A-Za-z0-9]+$ ]] || fail "el run ${run_id} no tiene un plan válido"
  cv=$(jq -r '.relationships["configuration-version"].data.id // ""' <<< "${run}")
  [[ "${cv}" =~ ^cv-[A-Za-z0-9]+$ ]] || fail "el run ${run_id} no informa su configuración"
  body=$(mktemp)
  code=$(hcp GET "/plans/${plan_id}" "${body}")
  [ "${code}" = 200 ] || fail "no se pudo leer el plan ${plan_id} (HTTP ${code})"
  jq -e '.data.attributes.status == "finished"' "${body}" > /dev/null 2>&1 ||
    fail "el plan ${plan_id} no terminó (status: $(jq -r '.data.attributes.status // "?"' "${body}" 2> /dev/null))"

  # JSON del plan: contiene valores sensibles. Archivo privado (directorio temporal propio),
  # nunca impreso ni subido, borrado al salir. Con cambios es obligatorio: sin él no hay
  # revisión posible.
  json=$(mktemp)
  if ! curl -sSfL -o "${json}" --max-time 120 \
       -H @<(printf 'Authorization: Bearer %s\n' "${TF_API_TOKEN}") \
       "${API}/plans/${plan_id}/json-output" 2> /dev/null ||
     ! jq -e '(.resource_changes // [] | type == "array") and all(.resource_changes[]?; (.address | type == "string") and (.change.actions | type == "array"))' "${json}" > /dev/null 2>&1; then
    [ "${has}" = false ] ||
      fail "el JSON del plan ${plan_id} no se pudo leer o interpretar con este token: sin revisión del plan no hay Apply"
    : > "${json}"
  fi

  review="${PLAN_REVIEW_FILE:-/dev/null}"
  if [ -s "${json}" ]; then
    counts=$("${SCRIPT_DIR}/plan-review.sh" "${json}" "${review}") ||
      fail "la revisión saneada del plan ${plan_id} no se pudo generar (acción desconocida, JSON inesperado o revisión demasiado grande)"
    # Los conteos de la revisión deben ser los del plan de HCP
    jq -e --argjson c "$(sed 's/^\([a-z_]*\)=\(.*\)$/"\1":\2/' <<< "${counts}" | paste -sd, | sed 's/^/{/; s/$/}/')" '
        .data.attributes as $a
        | ($a["resource-additions"] // 0) == ($c.review_creates + $c.review_replaces)
        and ($a["resource-changes"] // 0) == $c.review_updates
        and ($a["resource-destructions"] // 0) == ($c.review_deletes + $c.review_replaces)
        and ($a["resource-imports"] // 0) == $c.review_imports' "${body}" > /dev/null ||
      fail "los cambios del JSON del plan ${plan_id} no coinciden con los conteos del plan de HCP: contradicción"
  else
    counts=""
    printf '_Plan sin cambios: el JSON del plan no se pudo leer; no hay cambios que revisar._\n' > "${review}"
  fi

  # Estado: el plan terminó y el estado capturado antes de crear el run sigue siendo el
  # actual (y coincide con el de entrada si HCP lo informa). Si no, falla: HCP descarta el
  # saved plan por el cambio de estado y la limpieza del job también.
  binding=$(bind_state "${run}" "${EXPECTED_STATE}")

  # Frescura: un commit viejo no sigue; el saved plan propio se descarta
  if ! fresh; then
    if [ "${has}" = true ]; then
      discard_owned "${run_id}" "main avanzó antes de la revisión; este saved plan ya no puede aplicarse" || true
      fail "main avanzó antes de terminar la revisión del run ${run_id}: saved plan descartado, sin Apply"
    fi
    fail "main avanzó: el plan del run ${run_id} no tiene cambios y la cadena no sigue con un commit viejo (nada mutó)"
  fi

  echo "run_status=${status}"
  echo "has_changes=${has}"
  echo "plan_id=${plan_id}"
  echo "configuration_version_id=${cv}"
  echo "plan_state_version=${EXPECTED_STATE}"
  echo "state_binding=${binding}"
  echo "created_at=$(jq -r '.attributes["created-at"] // ""' <<< "${run}")"
  jq -r '.data.attributes | "additions=\(.["resource-additions"] // 0)\nchanges=\(.["resource-changes"] // 0)\ndestructions=\(.["resource-destructions"] // 0)\nimports=\(.["resource-imports"] // 0)"' "${body}"
  [ -z "${counts}" ] || echo "${counts}"
  if [ -s "${json}" ]; then watch_actions "${json}"; else watch_actions ""; fi
  echo "run_link=$(run_link "${run_id}")"
}

# confirmed_after <run JSON> <epoch>: el run se confirmó en <epoch> o después
confirmed_after() {
  local at epoch
  at=$(jq -r '.attributes["status-timestamps"]["confirmed-at"] // ""' <<< "$1")
  [ -n "${at}" ] || return 1
  epoch=$(date -d "${at}" +%s 2> /dev/null) || return 1
  [ "${epoch}" -ge "$(($2 - 5))" ]
}

cmd_apply() {
  local run_id="$1" run status created age busy body data code requested
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
  [[ "${EXPECTED_PLAN_ID:-}" =~ ^plan-[A-Za-z0-9]+$ ]] || fail "EXPECTED_PLAN_ID inválido"
  [[ "${EXPECTED_CONFIGURATION_VERSION:-}" =~ ^cv-[A-Za-z0-9]+$ ]] || fail "EXPECTED_CONFIGURATION_VERSION inválido"
  [[ "${EXPECTED_STATE:-}" =~ ^sv-[A-Za-z0-9]+$ ]] || [ "${EXPECTED_STATE:-}" = none ] || fail "EXPECTED_STATE inválido"
  local max_age="${PLAN_MAX_AGE_SECONDS:-3600}"
  [[ "${max_age}" =~ ^[0-9]+$ ]] || fail "PLAN_MAX_AGE_SECONDS inválido"

  # Workspace: sin auto-apply y con permiso de confirmar, leído ahora
  cmd_workspace apply > /dev/null

  run=$(run_json "${run_id}")
  require_owned "${run}"
  status=$(status_of "${run}")
  in_set "${status}" "${APPLY_STATES}" && observe_out_of_band "${run_id}"
  case "${status}" in
    planned_and_saved) ;;
    discarded) fail "el saved plan del run ${run_id} fue descartado (HCP lo descarta si el estado cambió): sin Apply" ;;
    *) fail "el run ${run_id} no es un saved plan a la espera de confirmación (status: ${status})" ;;
  esac
  jq -e '.attributes["has-changes"] == true and .attributes.actions["is-confirmable"] == true' <<< "${run}" > /dev/null ||
    fail "el run ${run_id} no es confirmable o no tiene cambios"
  jq -e --arg p "${EXPECTED_PLAN_ID}" '.relationships.plan.data.id == $p' <<< "${run}" > /dev/null ||
    fail "el plan del run ${run_id} no es el revisado (${EXPECTED_PLAN_ID})"
  jq -e --arg c "${EXPECTED_CONFIGURATION_VERSION}" '.relationships["configuration-version"].data.id == $c' <<< "${run}" > /dev/null ||
    fail "la configuración del run ${run_id} no es la revisada (${EXPECTED_CONFIGURATION_VERSION})"
  created=$(jq -r '.attributes["created-at"] // ""' <<< "${run}")
  created=$(date -d "${created}" +%s 2> /dev/null) || fail "el run ${run_id} no informa una fecha de creación legible"
  age=$(($(date +%s) - created))
  [ "${age}" -ge -300 ] || fail "la fecha de creación del run ${run_id} está en el futuro"
  [ "${age}" -le "${max_age}" ] ||
    fail "el saved plan del run ${run_id} tiene ${age}s (máximo ${max_age}s): volver a correr la cadena completa para un plan nuevo"

  busy=$(busy_runs "${run_id}")
  [ -z "${busy}" ] || fail "otro run ocupa el workspace ($(paste -sd' ' <<< "${busy}")): no se confirma detrás de él"

  fresh || fail "main avanzó: el saved plan del run ${run_id} no se confirma"

  # Estado: el de entrada informado (si HCP lo informa) y el actual deben ser el revisado.
  # Lo más tarde posible antes de la solicitud; después, HCP descarta el saved plan si el
  # estado cambia (la última lectura del run lo ve descartado)
  bind_state "${run}" "${EXPECTED_STATE}" > /dev/null

  # Última lectura antes de la solicitud: una confirmación ajena en la ventana se detecta
  run=$(run_json "${run_id}")
  status=$(status_of "${run}")
  in_set "${status}" "${APPLY_STATES}" && observe_out_of_band "${run_id}"
  [ "${status}" = planned_and_saved ] || fail "el run ${run_id} cambió a '${status}' antes de confirmarlo"

  body=$(mktemp) data=$(mktemp)
  jq -n --arg c "${APPLY_COMMENT:-Confirmado por la cadena de producción de GitHub}" '{comment: $c}' > "${data}"
  requested=$(date +%s)
  code=$(hcp POST "/runs/${run_id}/actions/apply" "${body}" "${data}")
  echo "Solicitud de Apply del run ${run_id}: HTTP ${code} (una sola solicitud; nunca se repite)" >&2

  case "${code}" in
    202)
      # Aceptada: HCP encola el Apply; se espera a verlo confirmado
      for i in $(seq 1 "${ACK_ATTEMPTS}"); do
        run=$(run_json "${run_id}")
        status=$(status_of "${run}")
        if in_set "${status}" "${APPLY_STATES}"; then
          echo "apply_request=accepted"
          echo "run_status=${status}"
          return 0
        fi
        [ "${status}" = planned_and_saved ] ||
          fail "HCP aceptó la solicitud pero el run ${run_id} terminó en '${status}' sin aplicar"
        sleep "${POLL_INTERVAL}"
      done
      fail "HCP aceptó la solicitud pero el run ${run_id} no empezó el Apply a tiempo (sin reintento; la limpieza lo descarta si todavía puede)"
      ;;
    409)
      run=$(run_json "${run_id}")
      status=$(status_of "${run}")
      in_set "${status}" "${APPLY_STATES}" && observe_out_of_band "${run_id}"
      [ "${status}" != discarded ] ||
        fail "HCP rechazó la confirmación (409): el saved plan fue descartado (el estado cambió); sin Apply"
      fail "HCP rechazó la confirmación (409) con el run en '${status}': sin Apply y sin reintento"
      ;;
    *)
      # Resultado incierto (sin respuesta, 5xx u otro código): se relee, nunca se repite
      for i in $(seq 1 "${ACK_ATTEMPTS}"); do
        run=$(run_json "${run_id}")
        status=$(status_of "${run}")
        if in_set "${status}" "${APPLY_STATES}"; then
          confirmed_after "${run}" "${requested}" || observe_out_of_band "${run_id}"
          echo "::warning title=Terraform::La solicitud de Apply del run ${run_id} tuvo una respuesta incierta (HTTP ${code}); HCP muestra el run confirmado después de la solicitud" >&2
          echo "apply_request=accepted_after_ambiguous"
          echo "run_status=${status}"
          return 0
        fi
        [ "${status}" = planned_and_saved ] ||
          fail "respuesta incierta (HTTP ${code}) y el run ${run_id} terminó en '${status}' sin aplicar"
        sleep "${POLL_INTERVAL}"
      done
      fail "respuesta incierta (HTTP ${code}) y el run ${run_id} sigue sin confirmar: sin reintento (la limpieza lo descarta si todavía puede)"
      ;;
  esac
}

# cleanup_one <run_id>: libera un run propio; escribe el resultado (una palabra)
cleanup_one() {
  local run_id="$1" run status body data code
  run=$(run_json "${run_id}")
  require_mine "${run}"
  status=$(status_of "${run}")
  if jq -e '.attributes.actions["is-discardable"] == true' <<< "${run}" > /dev/null; then
    if discard_owned "${run_id}" "Cadena de producción interrumpida antes de confirmar el plan"; then
      echo discarded
      return 0
    fi
    # Pudo confirmarse entre la lectura y el descarte: se informa lo que HCP tiene ahora
    run=$(run_json "${run_id}")
    status=$(status_of "${run}")
    in_set "${status}" "${APPLY_STATES}" ||
      fail "no se pudo descartar el run ${run_id} (status: ${status}); revisarlo en HCP: $(run_link "${run_id}")"
  fi
  if in_set "${status}" "${APPLY_STATES}"; then
    echo "El run ${run_id} ya fue confirmado (${status}): HCP aceptó el Apply y cancelar el job no lo deshace; el siguiente run reconcilia el estado" >&2
    echo apply_accepted
  elif in_set "${status}" "${PLANNING_STATES}" &&
       jq -e '.attributes.actions["is-cancelable"] == true' <<< "${run}" > /dev/null; then
    body=$(mktemp) data=$(mktemp)
    jq -n '{comment: "Cadena de producción interrumpida durante el plan"}' > "${data}"
    code=$(hcp POST "/runs/${run_id}/actions/cancel" "${body}" "${data}")
    [ "${code}" = 202 ] || fail "HCP no aceptó la cancelación del plan ${run_id} (HTTP ${code}); revisarlo en HCP"
    echo canceled
  else
    echo none
  fi
}

cmd_cleanup() {
  local run_id="${1:-}" body code ids results="" r
  : "${RUN_MESSAGE:?RUN_MESSAGE no definido}"
  require_identity
  if [ -n "${run_id}" ]; then
    [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
    ids="${run_id}"
  else
    # GitHub no registró el run: se busca por el mensaje exacto de este intento
    body=$(mktemp)
    code=$(hcp GET "/workspaces/${HCP_WORKSPACE_ID}/runs?page%5Bsize%5D=20" "${body}")
    [ "${code}" = 200 ] || fail "no se pudieron listar los runs del workspace (HTTP ${code}): buscar en HCP un run con el mensaje '${RUN_MESSAGE}'"
    ids=$(jq -r --arg m "${RUN_MESSAGE}" '.data | if type == "array" then .[] | select(.attributes.message == $m) | .id else error("x") end' "${body}" 2> /dev/null) ||
      fail "lista de runs ilegible: buscar en HCP un run con el mensaje '${RUN_MESSAGE}'"
    if [ -z "${ids}" ]; then
      echo "Ningún run con el mensaje de este intento entre los 20 más recientes: HCP no lo creó (o ya no es reciente; revisar en HCP)" >&2
      echo "cleanup=none"
      echo "run_ids="
      return 0
    fi
  fi
  for id in ${ids}; do
    [[ "${id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "id de run inválido: '${id}'"
    r=$(cleanup_one "${id}")
    results="${results:+${results},}${r}"
  done
  echo "cleanup=${results}"
  echo "run_ids=$(paste -sd, <<< "${ids}")"
}

cmd_run_state() {
  local run_id="$1" before="$2" run status workspace apply_id linked current sv_run
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
  [[ "${before}" =~ ^sv-[A-Za-z0-9]+$ ]] || [ "${before}" = none ] || fail "state version previo inválido: '${before}'"

  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    run="$(api "/runs/${run_id}")" || fail "no se pudo leer el run ${run_id}"
    status="$(jq -r '.data.attributes.status // ""' <<< "${run}")"
    case "${status}" in
      applied | planned_and_finished) break ;;
      errored | discarded | canceled | force_canceled | policy_soft_failed)
        fail "el run ${run_id} terminó en '${status}'"
        ;;
      *)
        echo "Run ${run_id} en '${status}' — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
        status=""
        sleep "${POLL_INTERVAL}"
        ;;
    esac
  done
  [ -n "${status}" ] || fail "el run ${run_id} no terminó a tiempo"

  workspace="$(jq -r '.data.relationships.workspace.data.id // ""' <<< "${run}")"
  [ "${workspace}" = "${HCP_WORKSPACE_ID}" ] ||
    fail "el run ${run_id} pertenece a '${workspace}', no a ${HCP_WORKSPACE_ID}"

  apply_id="$(jq -r '.data.relationships.apply.data.id // ""' <<< "${run}")"
  if [ -n "${apply_id}" ]; then
    linked="$(api "/applies/${apply_id}" | jq -c '[.data.relationships["state-versions"].data[]?.id]')" ||
      fail "no se pudo leer el apply ${apply_id} del run ${run_id}"
  else
    linked='[]'
  fi

  current="$(current_state)"

  if [ "$(jq 'length' <<< "${linked}")" -gt 0 ]; then
    jq -e --arg sv "${current}" 'index($sv) != null' <<< "${linked}" > /dev/null ||
      fail "el state version actual (${current}) no es de este run (${run_id} produjo ${linked}): estado reemplazado o ambiguo"
    sv_run="$(api "/state-versions/${current}" | jq -r '.data.relationships.run.data.id // ""')" ||
      fail "no se pudo leer el state version ${current}"
    [ "${sv_run}" = "${run_id}" ] ||
      fail "el state version ${current} está enlazado al run '${sv_run}', no a ${run_id}"
    echo "run_status=${status}"
    echo "state_version_id=${current}"
    echo "state_source=run"
  else
    [ "${status}" = "planned_and_finished" ] ||
      fail "el run ${run_id} terminó en '${status}' sin state version enlazado"
    [ "${current}" = "${before}" ] ||
      fail "el run ${run_id} no produjo estado pero el actual cambió (${before} → ${current}): estado ajeno a este run"
    echo "run_status=${status}"
    echo "state_version_id=${current}"
    echo "state_source=unchanged"
  fi
}

cmd_outputs() {
  local sv="$1" outputs server_ip api_fqdn auth_fqdn current
  [[ "${sv}" =~ ^sv-[A-Za-z0-9]+$ ]] || fail "state version inválido: '${sv}' (sin estado no hay outputs que consumir)"
  current="$(current_state)"
  [ "${current}" = "${sv}" ] || fail "${sv} ya no es el state version actual (${current}): estado reemplazado"

  # Los outputs de un state version recién creado pueden tardar en procesarse
  for i in $(seq 1 12); do
    outputs="$(api "/state-versions/${sv}/outputs")" || fail "no se pudieron leer los outputs de ${sv}"
    server_ip="$(jq -r '[.data[] | select(.attributes.name == "server_ip") | .attributes.value] | if length == 1 then .[0] // "" else "" end' <<< "${outputs}")"
    api_fqdn="$(jq -r '[.data[] | select(.attributes.name == "api_fqdn") | .attributes.value] | if length == 1 then .[0] // "" else "" end' <<< "${outputs}")"
    auth_fqdn="$(jq -r '[.data[] | select(.attributes.name == "auth_fqdn") | .attributes.value] | if length == 1 then .[0] // "" else "" end' <<< "${outputs}")"
    if [ -n "${server_ip}" ] && [ -n "${api_fqdn}" ] && [ -n "${auth_fqdn}" ]; then
      break
    fi
    echo "Outputs de ${sv} incompletos — esperando ${OUTPUTS_INTERVAL}s ($i/12)" >&2
    sleep "${OUTPUTS_INTERVAL}"
  done

  [[ "${server_ip}" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || fail "server_ip ausente o inválido en ${sv}"
  [[ "${api_fqdn}" =~ ^[a-z0-9.-]+$ ]] || fail "api_fqdn ausente o inválido en ${sv}"
  [[ "${auth_fqdn}" =~ ^[a-z0-9.-]+$ ]] || fail "auth_fqdn ausente o inválido en ${sv}"

  current="$(current_state)"
  [ "${current}" = "${sv}" ] || fail "${sv} fue reemplazado (${current}) mientras se leían sus outputs"

  echo "server_ip=${server_ip}"
  echo "api_fqdn=${api_fqdn}"
  echo "auth_fqdn=${auth_fqdn}"
}

USAGE="workspace [apply] | wait-idle | current | upload <dir> | create-run <cv> | plan <run> | apply <run> | cleanup [run] | run-state <run> <sv_antes|none> | outputs <sv>"
[ "$#" -ge 1 ] || fail "uso: $0 ${USAGE}"
case "$1" in
  workspace)
    [ "$#" -le 2 ] || fail "uso: $0 workspace [apply]"
    [ "${2:-read}" = read ] || [ "${2:-}" = apply ] || fail "uso: $0 workspace [apply]"
    cmd_workspace "${2:-read}"
    ;;
  wait-idle) [ "$#" -eq 1 ] || fail "uso: $0 wait-idle"; cmd_wait_idle ;;
  current) [ "$#" -eq 1 ] || fail "uso: $0 current"; current_state ;;
  upload) [ "$#" -eq 2 ] || fail "uso: $0 upload <directorio>"; cmd_upload "$2" ;;
  create-run) [ "$#" -eq 2 ] || fail "uso: $0 create-run <configuration_version_id>"; cmd_create_run "$2" ;;
  plan) [ "$#" -eq 2 ] || fail "uso: $0 plan <run_id>"; cmd_plan "$2" ;;
  apply) [ "$#" -eq 2 ] || fail "uso: $0 apply <run_id>"; cmd_apply "$2" ;;
  cleanup) [ "$#" -le 2 ] || fail "uso: $0 cleanup [run_id]"; cmd_cleanup "${2:-}" ;;
  run-state) [ "$#" -eq 3 ] || fail "uso: $0 run-state <run_id> <sv_antes|none>"; cmd_run_state "$2" "$3" ;;
  outputs) [ "$#" -eq 2 ] || fail "uso: $0 outputs <sv>"; cmd_outputs "$2" ;;
  *) fail "subcomando desconocido: $1" ;;
esac
