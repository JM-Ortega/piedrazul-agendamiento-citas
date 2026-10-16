#!/usr/bin/env bash
# Identidad de estado de HCP Terraform para la cadena Apply → Deploy.
#
# Uso:
#   hcp-state.sh wait-idle
#   hcp-state.sh current
#   hcp-state.sh run-state <run_id> <state_version_antes>
#   hcp-state.sh outputs <state_version_id>
#
# Entorno: TF_API_TOKEN, HCP_WORKSPACE_ID; HCP_API_URL (por defecto app.terraform.io).
#
# Contrato:
#   - wait-idle: espera a que el último run del workspace esté en un estado final
#     (applied, planned_and_finished, discarded, canceled, force_canceled, errored,
#     policy_soft_failed). Un run final fallido no bloquea; uno activo o a la espera de
#     una resolución (p. ej. policy_override, planned) sí.
#   - current: id del state version actual del workspace.
#   - run-state: espera a que <run_id> (creado por esta misma ejecución) termine y
#     determina el state version exacto que el Deploy debe consumir:
#       * si el apply del run tiene state versions enlazados, el actual del workspace
#         debe ser uno de ellos (si no, otro run lo reemplazó: falla);
#       * si no tiene, el run debe ser planned_and_finished y el actual debe seguir
#         siendo <state_version_antes>.
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
POLL_INTERVAL="${HCP_POLL_INTERVAL:-15}"
POLL_ATTEMPTS="${HCP_POLL_ATTEMPTS:-60}"
OUTPUTS_INTERVAL="${HCP_OUTPUTS_INTERVAL:-5}"

[[ "${HCP_WORKSPACE_ID}" =~ ^ws-[A-Za-z0-9]+$ ]] || fail "HCP_WORKSPACE_ID inválido"

api() {
  curl -sSf \
    -H @<(printf 'Authorization: Bearer %s\n' "${TF_API_TOKEN}") \
    -H "Content-Type: application/vnd.api+json" \
    "${API}$1"
}

current_state() {
  local sv
  sv="$(api "/workspaces/${HCP_WORKSPACE_ID}/current-state-version" | jq -r '.data.id // ""')" ||
    fail "no se pudo leer el state version actual del workspace"
  [[ "${sv}" =~ ^sv-[A-Za-z0-9]+$ ]] || fail "state version actual inválido: '${sv}'"
  echo "${sv}"
}

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

cmd_run_state() {
  local run_id="$1" before="$2" run status workspace apply_id linked current sv_run
  [[ "${run_id}" =~ ^run-[A-Za-z0-9]+$ ]] || fail "run_id inválido: '${run_id}'"
  [[ "${before}" =~ ^sv-[A-Za-z0-9]+$ ]] || fail "state version previo inválido: '${before}'"

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
  [[ "${sv}" =~ ^sv-[A-Za-z0-9]+$ ]] || fail "state version inválido: '${sv}'"
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

[ "$#" -ge 1 ] || fail "uso: $0 wait-idle | current | run-state <run_id> <sv_antes> | outputs <sv>"
case "$1" in
  wait-idle) [ "$#" -eq 1 ] || fail "uso: $0 wait-idle"; cmd_wait_idle ;;
  current) [ "$#" -eq 1 ] || fail "uso: $0 current"; current_state ;;
  run-state) [ "$#" -eq 3 ] || fail "uso: $0 run-state <run_id> <sv_antes>"; cmd_run_state "$2" "$3" ;;
  outputs) [ "$#" -eq 2 ] || fail "uso: $0 outputs <sv>"; cmd_outputs "$2" ;;
  *) fail "subcomando desconocido: $1" ;;
esac
