#!/usr/bin/env bash
# Workspace, run y estado de HCP Terraform para la cadena Plan → confirmación → Apply → Deploy.
#
# Uso:
#   hcp-state.sh workspace [apply]
#   hcp-state.sh wait-idle
#   hcp-state.sh current
#   hcp-state.sh create-run <configuration_version_id>
#   hcp-state.sh plan <run_id>
#   hcp-state.sh await-confirm <run_id>
#   hcp-state.sh cleanup [run_id]
#   hcp-state.sh run-state <run_id> <state_version_antes|none>
#   hcp-state.sh outputs <state_version_id>
#
# Entorno: TF_API_TOKEN, HCP_WORKSPACE_ID, HCP_ORGANIZATION (o TF_CLOUD_ORGANIZATION),
#   HCP_WORKSPACE_NAME (o TF_WORKSPACE); HCP_API_URL (por defecto app.terraform.io/api/v2),
#   HCP_APP_URL (enlaces; por defecto https://app.terraform.io). create-run, plan,
#   await-confirm y cleanup además: RUN_MESSAGE (el mensaje exacto del run de esta
#   ejecución: identifica al run propio).
#
# Contrato:
#   - workspace: el id es el workspace declarado (nombre y organización), el token lo lee y
#     puede leer su estado, y la ejecución es remota (remote/agent). Con `apply`, además: el
#     token puede encolar runs y el workspace NO aplica solo (auto-apply y
#     auto-apply-run-trigger en false). Un 401/403/404 es credencial o workspace incorrectos,
#     nunca "workspace vacío".
#   - wait-idle: espera a que el último run del workspace esté en un estado final
#     (applied, planned_and_finished, discarded, canceled, force_canceled, errored,
#     policy_soft_failed). Un run final fallido no bloquea; uno activo o a la espera de
#     una resolución (p. ej. policy_override, planned) sí.
#   - current: id del state version actual, o `none` si el workspace (verificado como en
#     `workspace`) no tiene ninguno: solo cuando current-state-version responde 404 Y la
#     lista de state versions del workspace está vacía. Cualquier otro código, cuerpo
#     ilegible o contradicción falla: un error nunca se lee como instalación limpia.
#   - create-run: crea, con la configuración subida por esta ejecución, un run aplicable
#     (no speculative, no destroy, no saved plan) con auto-apply=false explícito, y verifica
#     en la respuesta que HCP lo registró así (auto-apply, workspace, configuración,
#     mensaje). Nada lo confirma: solo una persona en HCP.
#   - plan: espera a que el plan del run termine y lo describe. Sin cambios
#     (planned_and_finished) → needs_confirmation=false. Con cambios y esperando
#     confirmación → needs_confirmation=true. Si una persona ya lo confirmó en HCP antes de
#     esta lectura (con auto-apply=false verificado), already_confirmed=true. Fallo,
#     descarte, cancelación, override de políticas o saved plan fallan.
#     PLAN_WATCH (opcional): "<nombre>=<dirección> …"; por cada uno informa la acción
#     OBSERVADA en el JSON del plan, watch_<nombre>=create|delete|replace|update|no-op|none,
#     o unknown (JSON ilegible, sin permiso o malformado) o ambiguous (la dirección aparece
#     más de una vez). Una creación observada con resource-additions=0 es una contradicción
#     y falla. PLAN_CHANGES_FILE (opcional): una línea "<acciones> <dirección>" por cambio.
#   - Frescura (FRESHNESS_CMD, opcional; p. ej. verify-main-tip.sh) en plan y await-confirm:
#     mientras ninguna mutación empezó (plan sin cambios, o con cambios sin confirmar), un
#     commit que dejó de ser la punta de main falla (y el run propio se descarta si espera
#     confirmación). Si el run YA fue confirmado en HCP, el Apply empezó y no se interrumpe:
#     se informa confirmed_while_stale=true y la cadena termina coherentemente (el run del
#     commit nuevo sigue en la cola). GitHub y HCP no ofrecen una operación atómica
#     "confirmar si main no avanzó", y HCP deja confirmar en cuanto el plan es confirmable,
#     antes de que este subcomando termine: una confirmación temprana no se puede impedir,
#     solo detectar (already_confirmed). Confirmando después de que el paso del plan termina
#     (procedimiento), la ventana es de, como mucho, un intervalo de sondeo; confirmando
#     antes, es todo el tiempo desde la última comprobación previa al plan.
#   - await-confirm: espera la confirmación humana en HCP del run propio que espera
#     confirmación. Confirmado (apply en curso o terminado) → 0 (con confirmed_while_stale).
#     Descartado/cancelado/fallido → falla. Main avanzado mientras espera → descarta y
#     falla. Sin confirmación dentro de HCP_CONFIRM_ATTEMPTS × HCP_POLL_INTERVAL → descarta
#     el run propio y falla.
#   - cleanup: solo sobre runs propios (este workspace y exactamente RUN_MESSAGE; uno ajeno
#     no se toca y falla). Sin run_id (GitHub no llegó a registrarlo: la creación falló o se
#     cortó después del POST), busca el run por RUN_MESSAGE entre los 20 más recientes del
#     workspace. Esperando confirmación → lo descarta. Planificando → lo cancela.
#     Confirmado o aplicando → no lo toca: un Apply que HCP ya aceptó no se deshace cancelando
#     el job (lo informa). Terminado → nada. Salida: cleanup=discarded|canceled|
#     apply_accepted|none (varios valores separados por coma si hubiera más de un run).
#   - run-state: espera a que <run_id> (creado por esta misma ejecución) termine y
#     determina el state version exacto que el Deploy debe consumir:
#       * si el apply del run tiene state versions enlazados, el actual del workspace
#         debe ser uno de ellos (si no, otro run lo reemplazó: falla);
#       * si no tiene, el run debe ser planned_and_finished y el actual debe seguir
#         siendo <state_version_antes> (`none` si el workspace no tenía estado).
#     Cualquier otro estado final, un run de otro workspace o una contradicción falla.
#   - outputs: lee los outputs de ese state version exacto, y solo si sigue siendo el
#     actual antes y después de leerlos.
#
# Salida en formato GITHUB_OUTPUT. El token nunca va en la línea de comandos.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

: "${TF_API_TOKEN:?TF_API_TOKEN no definido}"
: "${HCP_WORKSPACE_ID:?HCP_WORKSPACE_ID no definido}"
API="${HCP_API_URL:-https://app.terraform.io/api/v2}"
APP="${HCP_APP_URL:-https://app.terraform.io}"
ORGANIZATION="${HCP_ORGANIZATION:-${TF_CLOUD_ORGANIZATION:-}}"
WORKSPACE_NAME="${HCP_WORKSPACE_NAME:-${TF_WORKSPACE:-}}"
POLL_INTERVAL="${HCP_POLL_INTERVAL:-15}"
POLL_ATTEMPTS="${HCP_POLL_ATTEMPTS:-60}"
CONFIRM_ATTEMPTS="${HCP_CONFIRM_ATTEMPTS:-120}"
OUTPUTS_INTERVAL="${HCP_OUTPUTS_INTERVAL:-5}"

[[ "${HCP_WORKSPACE_ID}" =~ ^ws-[A-Za-z0-9]+$ ]] || fail "HCP_WORKSPACE_ID inválido"

# Estados de un run (API de HCP Terraform)
PLANNING_STATES=" pending fetching fetching_completed pre_plan_running pre_plan_completed queuing plan_queued planning planned cost_estimating cost_estimated policy_checking policy_checked post_plan_running post_plan_completed "
# Esperando confirmación (con actions.is-confirmable)
CONFIRMABLE_STATES=" planned cost_estimated policy_checked post_plan_completed "
# Confirmado: HCP ya aceptó el Apply
APPLY_STATES=" confirmed apply_queued queuing_apply pre_apply_running pre_apply_completed applying applied "
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
  code=$(curl -sS -X "$1" -o "$3" -w '%{http_code}' \
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

# require_owned <run JSON>: propio y nunca aplica solo
require_owned() {
  require_mine "$1"
  jq -e '.attributes["auto-apply"] == false and .attributes["plan-only"] == false and .attributes["is-destroy"] == false' <<< "$1" > /dev/null ||
    fail "el run $(jq -r .id <<< "$1") no es un run aplicable con auto-apply=false"
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

cmd_wait_idle() {
  local status
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    status="$(api "/workspaces/${HCP_WORKSPACE_ID}/runs?page%5Bsize%5D=1" |
      jq -r '.data[0].attributes.status // "none"')" ||
      fail "no se pudo leer el último run del workspace"
    case "${status}" in
      applied | planned_and_finished | discarded | canceled | force_canceled | none)
        echo "Terraform libre (status: ${status})" >&2
        return 0
        ;;
      errored | policy_soft_failed)
        # Estados finales: el run ya no ocupa el workspace. Un fallo anterior no bloquea
        # esta ejecución; su propio Apply y el state version que consume se verifican
        # después (run-state / outputs).
        echo "Terraform libre: el último run terminó en un estado final fallido (${status})" >&2
        return 0
        ;;
      *)
        # Cualquier otro estado (pending, planning, planned, policy_override,
        # apply_queued, applying, planned_and_saved, ...) puede seguir avanzando o
        # espera una resolución: no se compite con él.
        echo "Terraform no está libre (status: ${status}) — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
        sleep "${POLL_INTERVAL}"
        ;;
    esac
  done
  fail "Terraform no liberó el workspace a tiempo"
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

  data=$(mktemp)
  jq -n --arg m "${RUN_MESSAGE}" --arg ws "${HCP_WORKSPACE_ID}" --arg cv "${cv}" '
    {data: {type: "runs",
      attributes: {message: $m, "auto-apply": false, "plan-only": false, "is-destroy": false,
                   "save-plan": false, refresh: true},
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

# plan_changes <plan_id>: lista "<acciones> <dirección>" en PLAN_CHANGES_FILE (si está) y
# escribe una línea watch_<nombre>=<acción> por cada entrada de PLAN_WATCH. Solo acciones
# observadas: sin JSON legible todas son unknown (nunca se infiere una creación).
plan_changes() {
  local json out name addr
  out="${PLAN_CHANGES_FILE:-/dev/null}"
  json=$(mktemp)
  if ! curl -sSfL -o "${json}" \
       -H @<(printf 'Authorization: Bearer %s\n' "${TF_API_TOKEN}") \
       "${API}/plans/$1/json-output" 2> /dev/null ||
     ! jq -e '(.resource_changes | type == "array") and all(.resource_changes[]; (.address | type == "string") and (.change.actions | type == "array"))' "${json}" > /dev/null 2>&1; then
    echo "(el JSON del plan no se pudo leer o interpretar con este token; revisar el plan en HCP)" > "${out}"
    json=""
  else
    jq -r '.resource_changes[] | select(.change.actions != ["no-op"] and .change.actions != ["read"])
           | "\(.change.actions | join(",")) \(.address)"' "${json}" > "${out}"
  fi
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
  local run_id="$1" run status plan_id body code confirmed=false needs watches stale=false
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
  require_identity
  status=""
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    run=$(run_json "${run_id}")
    require_owned "${run}"
    status=$(status_of "${run}")
    if in_set "${status}" "${CONFIRMABLE_STATES}" &&
       jq -e '.attributes.actions["is-confirmable"] == true' <<< "${run}" > /dev/null; then
      needs=true
      break
    fi
    case "${status}" in
      planned_and_finished) needs=false; break ;;
      errored | canceled | force_canceled) fail "el plan del run ${run_id} terminó en '${status}' ($(run_link "${run_id}"))" ;;
      discarded) fail "el run ${run_id} fue descartado antes de terminar el plan" ;;
      policy_override | policy_soft_failed | post_plan_awaiting_decision)
        fail "el run ${run_id} requiere una decisión de políticas o run tasks (${status}) que la cadena no admite ($(run_link "${run_id}"))" ;;
      planned_and_saved) fail "el run ${run_id} quedó como saved plan; la cadena no crea saved plans" ;;
    esac
    if in_set "${status}" "${APPLY_STATES}"; then
      # auto-apply=false verificado: solo una confirmación en HCP lo lleva aquí
      needs=true confirmed=true
      break
    fi
    in_set "${status}" "${PLANNING_STATES}" || fail "el run ${run_id} está en un estado inesperado: '${status}'"
    echo "Run ${run_id} en '${status}' — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
    status=""
    sleep "${POLL_INTERVAL}"
  done
  [ -n "${status}" ] || fail "el plan del run ${run_id} no terminó a tiempo"

  if [ "${needs}" = false ]; then
    jq -e '.attributes["has-changes"] == false' <<< "${run}" > /dev/null ||
      fail "el run ${run_id} terminó sin aplicar (planned_and_finished) pero informa cambios: contradicción"
  else
    jq -e '.attributes["has-changes"] == true' <<< "${run}" > /dev/null ||
      fail "el run ${run_id} espera confirmación pero no informa cambios: contradicción"
  fi

  plan_id=$(jq -r '.relationships.plan.data.id // ""' <<< "${run}")
  [[ "${plan_id}" =~ ^plan-[A-Za-z0-9]+$ ]] || fail "el run ${run_id} no tiene un plan válido"
  body=$(mktemp)
  code=$(hcp GET "/plans/${plan_id}" "${body}")
  [ "${code}" = 200 ] || fail "no se pudo leer el plan ${plan_id} (HTTP ${code})"
  jq -e '.data.attributes.status == "finished"' "${body}" > /dev/null 2>&1 ||
    fail "el plan ${plan_id} no terminó (status: $(jq -r '.data.attributes.status // "?"' "${body}" 2> /dev/null))"
  watches=$(plan_changes "${plan_id}")
  if grep -qx 'watch_[a-z_]*=create' <<< "${watches}" &&
     jq -e '(.data.attributes["resource-additions"] // 0) == 0' "${body}" > /dev/null; then
    fail "el JSON del plan ${plan_id} muestra creaciones pero el plan informa 0 recursos a crear: contradicción"
  fi

  # Frescura: mientras nada mutó, un commit viejo no sigue; un Apply ya confirmado no se corta
  if ! fresh; then
    if [ "${confirmed}" = true ]; then
      echo "AVISO: el run ${run_id} se confirmó en HCP pero main ya avanzó: el Apply empezó y la cadena termina; el run del commit nuevo sigue después" >&2
      stale=true
    elif [ "${needs}" = true ]; then
      discard_owned "${run_id}" "main avanzó antes de la confirmación; este run ya no puede aplicar" || true
      fail "main avanzó antes de que el plan del run ${run_id} se confirmara: run descartado, sin Apply"
    else
      fail "main avanzó: el plan del run ${run_id} no tiene cambios y la cadena no sigue con un commit viejo (nada mutó)"
    fi
  fi

  echo "run_status=${status}"
  echo "has_changes=$(jq -r '.attributes["has-changes"]' <<< "${run}")"
  echo "needs_confirmation=${needs}"
  echo "already_confirmed=${confirmed}"
  echo "plan_id=${plan_id}"
  jq -r '.data.attributes | "additions=\(.["resource-additions"] // 0)\nchanges=\(.["resource-changes"] // 0)\ndestructions=\(.["resource-destructions"] // 0)\nimports=\(.["resource-imports"] // 0)"' "${body}"
  [ -z "${watches}" ] || echo "${watches}"
  echo "confirmed_while_stale=${stale}"
  echo "run_link=$(run_link "${run_id}")"
}

cmd_await_confirm() {
  local run_id="$1" run status
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
  require_identity
  echo "Esperando la confirmación humana del run ${run_id} en HCP: $(run_link "${run_id}")" >&2
  for i in $(seq 1 "${CONFIRM_ATTEMPTS}"); do
    run=$(run_json "${run_id}")
    require_owned "${run}"
    status=$(status_of "${run}")
    if in_set "${status}" "${APPLY_STATES}"; then
      echo "Run ${run_id} confirmado en HCP (status: ${status}, confirmado: $(jq -r '.attributes["status-timestamps"]["confirmed-at"] // "?"' <<< "${run}"))" >&2
      echo "confirmed=true"
      echo "run_status=${status}"
      # El Apply ya empezó: si main avanzó en la última ventana de sondeo, solo se informa
      if fresh; then
        echo "confirmed_while_stale=false"
      else
        echo "AVISO: el run ${run_id} se confirmó en HCP mientras main avanzaba: el Apply empezó y la cadena termina; el run del commit nuevo sigue después" >&2
        echo "confirmed_while_stale=true"
      fi
      return 0
    fi
    case "${status}" in
      discarded) fail "el plan del run ${run_id} fue rechazado (descartado) en HCP: no hay Apply" ;;
      errored | canceled | force_canceled) fail "el run ${run_id} terminó en '${status}' sin aplicar" ;;
    esac
    in_set "${status}" "${CONFIRMABLE_STATES}" ||
      fail "transición inesperada del run ${run_id} mientras esperaba confirmación: '${status}'"
    if ! fresh; then
      discard_owned "${run_id}" "main avanzó mientras el plan esperaba confirmación; este run ya no puede aplicar" || true
      fail "main avanzó mientras el run ${run_id} esperaba confirmación: run descartado, sin Apply"
    fi
    if [ $((i % 4)) -eq 1 ]; then
      echo "Esperando confirmación en HCP ($i/${CONFIRM_ATTEMPTS}): $(run_link "${run_id}")" >&2
    fi
    sleep "${POLL_INTERVAL}"
  done
  discard_owned "${run_id}" "Sin confirmación dentro del plazo de la cadena de producción" || true
  fail "el run ${run_id} no se confirmó a tiempo: run descartado (si HCP no aceptó el descarte, revisar el run)"
}

# cleanup_one <run_id>: libera un run propio; escribe el resultado (una palabra)
cleanup_one() {
  local run_id="$1" run status body data code
  run=$(run_json "${run_id}")
  require_mine "${run}"
  status=$(status_of "${run}")
  if jq -e '.attributes.actions["is-discardable"] == true' <<< "${run}" > /dev/null; then
    if discard_owned "${run_id}" "Cadena de producción interrumpida antes de la confirmación"; then
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

USAGE="workspace [apply] | wait-idle | current | create-run <cv> | plan <run> | await-confirm <run> | cleanup [run] | run-state <run> <sv_antes|none> | outputs <sv>"
[ "$#" -ge 1 ] || fail "uso: $0 ${USAGE}"
case "$1" in
  workspace)
    [ "$#" -le 2 ] || fail "uso: $0 workspace [apply]"
    [ "${2:-read}" = read ] || [ "${2:-}" = apply ] || fail "uso: $0 workspace [apply]"
    cmd_workspace "${2:-read}"
    ;;
  wait-idle) [ "$#" -eq 1 ] || fail "uso: $0 wait-idle"; cmd_wait_idle ;;
  current) [ "$#" -eq 1 ] || fail "uso: $0 current"; current_state ;;
  create-run) [ "$#" -eq 2 ] || fail "uso: $0 create-run <configuration_version_id>"; cmd_create_run "$2" ;;
  plan) [ "$#" -eq 2 ] || fail "uso: $0 plan <run_id>"; cmd_plan "$2" ;;
  await-confirm) [ "$#" -eq 2 ] || fail "uso: $0 await-confirm <run_id>"; cmd_await_confirm "$2" ;;
  cleanup) [ "$#" -le 2 ] || fail "uso: $0 cleanup [run_id]"; cmd_cleanup "${2:-}" ;;
  run-state) [ "$#" -eq 3 ] || fail "uso: $0 run-state <run_id> <sv_antes|none>"; cmd_run_state "$2" "$3" ;;
  outputs) [ "$#" -eq 2 ] || fail "uso: $0 outputs <sv>"; cmd_outputs "$2" ;;
  *) fail "subcomando desconocido: $1" ;;
esac
