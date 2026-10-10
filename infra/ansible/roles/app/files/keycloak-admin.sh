#!/usr/bin/env bash
# Operaciones de la API de administración de Keycloak para el ciclo técnico
# (keycloak_lifecycle.yml). Corre DENTRO del contenedor keycloak (docker exec) con kcadm.sh
# contra el servidor local.
#
# Uso: keycloak-admin.sh <acción>
#   probe      automatización: ¿autentica y ve el cliente backend? (clasificación P1/P3)
#   reconcile  automatización: secreto del backend = deseado; verifica que el backend autentica
#              con él y llega a la API de administración (su consumidor real)
#   establish  autoridad temporal: principal de automatización (forma, secreto, roles) y forma
#              y roles del backend
#   cleanup    autoridad temporal: elimina toda autoridad temporal listada (la propia al final)
#              y verifica que la propia ya no autentica
#   platform-admin  autoridad temporal, solo P2: crea el administrador humano permanente de
#              master. Nunca adopta ni modifica un usuario existente
#
# Secretos solo por entorno, nunca en argv ni en la salida. kcadm 26.5 ignora
# KC_CLI_CLIENT_SECRET junto con --no-config, así que cada sesión se abre con
# `config credentials` sobre un archivo en /dev/shm (memoria, 0600), que guarda el secreto y
# se borra al salir.
#
# Salida: líneas clave=valor. Código distinto de 0 = la acción no terminó (ver error=).
#
# Entorno:
#   KCL_SERVER, KCL_REALM, KCL_AUTOMATION_CLIENT_ID, KCL_BACKEND_CLIENT_ID   (siempre)
#   KCL_AUTOMATION_SECRET                     probe, reconcile, establish
#   KCL_BACKEND_SECRET, KCL_BACKEND_SECRET_JSON_B64       reconcile
#   KCL_AUTOMATION_SECRET_JSON_B64            establish
#   Los *_JSON_B64 son {"secret": …} armados por Ansible (to_json, en base64 para que el
#   valor no se reinterprete en el camino): ningún secreto se interpola en JSON desde bash,
#   así que no se les impone ninguna lista de caracteres prohibidos.
#   KCL_TEMP_CLIENT_ID, KCL_TEMP_SECRET       establish, cleanup
#   KCL_AUTOMATION_ROLES_ADD/_REMOVE,
#   KCL_BACKEND_ROLES_ADD/_REMOVE             establish: <clientId>:<rol> o realm:<rol>, por comas
#   KCL_BACKEND_SHAPE                         establish: JSON con las banderas del backend
#   KCL_TEMPORARY_PRINCIPALS                  cleanup: <client|user>:<nombre>:<uuid>, por comas
#   KCL_TEMP_CLIENT_ID, KCL_TEMP_SECRET,
#   KCL_PLATFORM_ADMIN_USERNAME, KCL_PLATFORM_ADMIN_ROLE,
#   KCL_PLATFORM_ADMIN_PASSWORD, KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64,
#   KCL_MASTER_PASSWORD_POLICY                                               platform-admin
#     (el nombre lo valida Ansible; la credencial es {"type":"password","value":…,
#     "temporary":true}, armada por Ansible como las demás)
set -euo pipefail

KCADM=/opt/keycloak/bin/kcadm.sh
: "${KCL_SERVER:?}" "${KCL_REALM:?}" "${KCL_AUTOMATION_CLIENT_ID:?}" "${KCL_BACKEND_CLIENT_ID:?}"

SESSIONS="$(mktemp -d /dev/shm/kcl.XXXXXX)"
trap 'rm -rf "${SESSIONS}"' EXIT
ERR="${SESSIONS}/stderr"

die() { echo "error=$1"; [ ! -s "${ERR}" ] || sed 's/^/kcadm: /' "${ERR}" >&2; exit 1; }

# login <sesión> <realm> <clientId> <variable con el secreto>
# Imprime auth.<sesión>=ok|rejected|unavailable|error; 0 solo si autenticó.
login() {
  local out
  if out="$(KC_CLI_CLIENT_SECRET="${!4:-}" "${KCADM}" config credentials --config "${SESSIONS}/$1" \
      --server "${KCL_SERVER}" --realm "$2" --client "$3" 2>&1)"; then
    echo "auth.$1=ok"
    return 0
  fi
  case "${out}" in
    *"Invalid client"*|*invalid_client*|*unauthorized_client*|*"401"*) echo "auth.$1=rejected" ;;
    *"HTTP request error"*|*"Connect to"*|*"Connection refused"*) echo "auth.$1=unavailable" ;;
    *) echo "auth.$1=error"; printf '%s\n' "${out}" | head -3 >&2 ;;
  esac
  return 1
}

# as <sesión> <argumentos de kcadm...>
as() { local s="$1"; shift; "${KCADM}" "$@" --config "${SESSIONS}/${s}" 2>"${ERR}"; }

# client_uuid <sesión> <realm> <clientId>  → uuid o vacío
client_uuid() { as "$1" get clients -r "$2" -q "clientId=$3" --fields id --format csv --noquotes; }

# roles <sesión> <add-roles|remove-roles> <realm> <usuario> <lista>
roles() {
  local spec client role
  IFS=',' read -ra specs <<< "$5"
  for spec in "${specs[@]}"; do
    [ -n "${spec}" ] || continue
    client="${spec%%:*}" role="${spec#*:}"
    if [ "${client}" = realm ]; then
      as "$1" "$2" -r "$3" --uusername "$4" --rolename "${role}" || die "$2:${spec}"
    else
      as "$1" "$2" -r "$3" --uusername "$4" --cclientid "${client}" --rolename "${role}" || die "$2:${spec}"
    fi
    echo "role.$2=$4:${spec}"
  done
}

probe() {
  login automation master "${KCL_AUTOMATION_CLIENT_ID}" KCL_AUTOMATION_SECRET || return 0
  local id
  if id="$(client_uuid automation "${KCL_REALM}" "${KCL_BACKEND_CLIENT_ID}")"; then
    echo "backend.visible=$([ -n "${id}" ] && echo yes || echo no)"
  elif grep -q '403' "${ERR}"; then
    echo "backend.visible=forbidden"
  else
    die probe_backend
  fi
}

reconcile() {
  : "${KCL_BACKEND_SECRET:?}" "${KCL_BACKEND_SECRET_JSON_B64:?}"
  login automation master "${KCL_AUTOMATION_CLIENT_ID}" KCL_AUTOMATION_SECRET || die automation_login
  local id
  id="$(client_uuid automation "${KCL_REALM}" "${KCL_BACKEND_CLIENT_ID}")" || die backend_lookup
  [ -n "${id}" ] || die backend_client_absent
  # Sin leer el secreto vigente: si el backend ya autentica con el deseado, no hay cambio
  case "$(login backend "${KCL_REALM}" "${KCL_BACKEND_CLIENT_ID}" KCL_BACKEND_SECRET || true)" in
    auth.backend=ok)
      echo "backend.secret=unchanged" ;;
    auth.backend=rejected)
      printf '%s' "${KCL_BACKEND_SECRET_JSON_B64}" | base64 -d \
        | as automation update "clients/${id}" -r "${KCL_REALM}" -f - --merge || die backend_secret_update
      echo "backend.secret=updated"
      login backend "${KCL_REALM}" "${KCL_BACKEND_CLIENT_ID}" KCL_BACKEND_SECRET || die backend_login ;;
    *)
      die backend_login_unverified ;;
  esac
  # El consumidor real: client credentials del backend + lectura de su realm (view-realm)
  as backend get "realms/${KCL_REALM}" --fields realm >/dev/null || die backend_admin_api
  echo "backend.admin_api=ok"
}

establish() {
  : "${KCL_TEMP_CLIENT_ID:?}" "${KCL_AUTOMATION_SECRET:?}" "${KCL_AUTOMATION_SECRET_JSON_B64:?}" "${KCL_BACKEND_SHAPE:?}"
  login temp master "${KCL_TEMP_CLIENT_ID}" KCL_TEMP_SECRET || die temp_login
  local id
  id="$(client_uuid temp master "${KCL_AUTOMATION_CLIENT_ID}")" || die automation_lookup
  # Forma fija: confidencial, solo service account. El secreto viaja por stdin, en el JSON
  # que arma Ansible.
  local shape='"enabled":true,"publicClient":false,"bearerOnly":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"clientAuthenticatorType":"client-secret"'
  if [ -z "${id}" ]; then
    printf '{"clientId":"%s","name":"Piedrazul Keycloak Automation","description":"Reconciliación técnica (Ansible). Sin flujos interactivos.",%s}' \
        "${KCL_AUTOMATION_CLIENT_ID}" "${shape}" \
      | as temp create clients -r master -f - >/dev/null || die automation_create
    id="$(client_uuid temp master "${KCL_AUTOMATION_CLIENT_ID}")" || die automation_lookup
    echo "automation.client=created"
  else
    printf '{%s}' "${shape}" | as temp update "clients/${id}" -r master -f - --merge || die automation_update
    echo "automation.client=reconciled"
  fi
  printf '%s' "${KCL_AUTOMATION_SECRET_JSON_B64}" | base64 -d | as temp update "clients/${id}" -r master -f - --merge \
    || die automation_secret
  roles temp remove-roles master "service-account-${KCL_AUTOMATION_CLIENT_ID}" "${KCL_AUTOMATION_ROLES_REMOVE:-}"
  roles temp add-roles master "service-account-${KCL_AUTOMATION_CLIENT_ID}" "${KCL_AUTOMATION_ROLES_ADD:-}"

  id="$(client_uuid temp "${KCL_REALM}" "${KCL_BACKEND_CLIENT_ID}")" || die backend_lookup
  [ -n "${id}" ] || die backend_client_absent
  printf '%s' "${KCL_BACKEND_SHAPE}" | as temp update "clients/${id}" -r "${KCL_REALM}" -f - --merge \
    || die backend_shape_update
  echo "backend.shape=reconciled"
  roles temp remove-roles "${KCL_REALM}" "service-account-${KCL_BACKEND_CLIENT_ID}" "${KCL_BACKEND_ROLES_REMOVE:-}"
  roles temp add-roles "${KCL_REALM}" "service-account-${KCL_BACKEND_CLIENT_ID}" "${KCL_BACKEND_ROLES_ADD:-}"

  # La automatización queda operativa antes de retirar la autoridad temporal
  login automation master "${KCL_AUTOMATION_CLIENT_ID}" KCL_AUTOMATION_SECRET || die automation_login
}

cleanup() {
  : "${KCL_TEMP_CLIENT_ID:?}"
  login temp master "${KCL_TEMP_CLIENT_ID}" KCL_TEMP_SECRET || die temp_login
  local entry kind rest name uuid self=""
  IFS=',' read -ra entries <<< "${KCL_TEMPORARY_PRINCIPALS:-}"
  for entry in "${entries[@]}"; do
    [ -n "${entry}" ] || continue
    kind="${entry%%:*}" rest="${entry#*:}"
    name="${rest%:*}" uuid="${rest##*:}"
    if [ "${kind}" = client ] && [ "${name}" = "${KCL_TEMP_CLIENT_ID}" ]; then
      self="${uuid}"
      continue
    fi
    case "${kind}" in
      client) as temp delete "clients/${uuid}" -r master || die "delete_client:${name}" ;;
      user) as temp delete "users/${uuid}" -r master || die "delete_user:${name}" ;;
      *) die "unknown_principal:${kind}" ;;
    esac
    echo "deleted=${kind}:${name}"
  done
  # La propia, al final: sin ella ya no hay sesión con la que seguir
  [ -n "${self}" ] || self="$(client_uuid temp master "${KCL_TEMP_CLIENT_ID}")" || die temp_lookup
  [ -n "${self}" ] || die temp_client_absent
  as temp delete "clients/${self}" -r master || die "delete_client:${KCL_TEMP_CLIENT_ID}"
  echo "deleted=client:${KCL_TEMP_CLIENT_ID}"
  rm -f "${SESSIONS}/temp"
  # Revocada = Keycloak rechaza la credencial (no basta con que no responda)
  case "$(login revoked master "${KCL_TEMP_CLIENT_ID}" KCL_TEMP_SECRET || true)" in
    auth.revoked=rejected) echo "temp.revoked=yes" ;;
    auth.revoked=ok) die temp_still_authenticates ;;
    *) die temp_revocation_unverified ;;
  esac
}

# user_id <sesión> <nombre> → uuid o vacío (nombre exacto; Keycloak los guarda en minúsculas)
user_id() { as "$1" get users -r master -q "username=$2" -q exact=true --fields id --format csv --noquotes; }

# Orden seguro: la cuenta nace deshabilitada y solo se habilita cuando la contraseña
# temporal, las acciones requeridas y el rol están verificados. Si algo falla antes, queda
# deshabilitada (inutilizable) y visible como incompleta; nunca se repara ni se borra sola.
platform_admin() {
  : "${KCL_TEMP_CLIENT_ID:?}" "${KCL_PLATFORM_ADMIN_USERNAME:?}" "${KCL_PLATFORM_ADMIN_ROLE:?}" \
    "${KCL_PLATFORM_ADMIN_PASSWORD:?}" "${KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64:?}" "${KCL_MASTER_PASSWORD_POLICY:?}"
  login temp master "${KCL_TEMP_CLIENT_ID}" KCL_TEMP_SECRET || die temp_login
  local u="${KCL_PLATFORM_ADMIN_USERNAME}" id
  # Un usuario con ese nombre en un master recién creado no es de este evento: no se adopta
  id="$(user_id temp "${u}")" || die platform_admin_lookup
  [ -z "${id}" ] || die platform_admin_collision
  # Política de contraseñas de master (de realm: vale para toda contraseña nueva o reemplazada
  # en master). Antes de fijar la contraseña inicial, para que Keycloak también la valide. Un
  # master recién creado no tiene política; cualquier otra distinta de la deseada no se pisa.
  local policy
  policy="$(as temp get realms/master --fields passwordPolicy --format csv --noquotes)" || die master_password_policy_read
  if [ -z "${policy}" ]; then
    as temp update realms/master -s "passwordPolicy=${KCL_MASTER_PASSWORD_POLICY}" || die master_password_policy_set
    policy="$(as temp get realms/master --fields passwordPolicy --format csv --noquotes)" || die master_password_policy_read
    [ "${policy}" = "${KCL_MASTER_PASSWORD_POLICY}" ] || die master_password_policy_readback
    echo "master.password_policy=set"
  elif [ "${policy}" = "${KCL_MASTER_PASSWORD_POLICY}" ]; then
    echo "master.password_policy=unchanged"
  else
    die master_password_policy_unexpected
  fi
  printf '{"username":"%s","enabled":false,"requiredActions":["CONFIGURE_TOTP","UPDATE_PASSWORD"]}' "${u}" \
    | as temp create users -r master -f - >/dev/null || die platform_admin_create
  id="$(user_id temp "${u}")" || die platform_admin_lookup
  [ -n "${id}" ] || die platform_admin_absent_after_create
  echo "platform_admin.user=created_disabled"
  printf '%s' "${KCL_PLATFORM_ADMIN_CREDENTIAL_JSON_B64}" | base64 -d \
    | as temp update "users/${id}/reset-password" -r master -f - -n || die platform_admin_password
  echo "platform_admin.password=temporary"
  as temp add-roles -r master --uid "${id}" --rolename "${KCL_PLATFORM_ADMIN_ROLE}" || die platform_admin_role
  echo "platform_admin.role=${KCL_PLATFORM_ADMIN_ROLE}"

  # Verificación antes de habilitar: exactamente lo pedido, leído de vuelta
  local got
  field() { as temp get "users/${id}$1" -r master --fields "$2" --format csv --noquotes || die platform_admin_readback; }
  got="$(field '' enabled)"; [ "${got}" = false ] || die platform_admin_readback_enabled
  got="$(field '' federationLink,serviceAccountClientId)"; [ "${got}" = , ] || die platform_admin_readback_kind
  got="$(field '' requiredActions | tr ',' '\n' | sort | paste -sd, -)"
  [ "${got}" = CONFIGURE_TOTP,UPDATE_PASSWORD ] || die platform_admin_readback_actions
  got="$(field /credentials type)"; [ "${got}" = password ] || die platform_admin_readback_credentials
  field /role-mappings/realm name | grep -qx "${KCL_PLATFORM_ADMIN_ROLE}" || die platform_admin_readback_role
  echo "platform_admin.verified=yes"

  as temp update "users/${id}" -r master -s enabled=true || die platform_admin_enable
  echo "platform_admin.enabled=yes"
  # La contraseña entregada es la que custodia el operador y por sí sola no da acceso:
  # Keycloak exige completar las acciones requeridas (cambio de contraseña y OTP)
  local res
  res="$(KC_CLI_PASSWORD="${KCL_PLATFORM_ADMIN_PASSWORD}" "${KCADM}" config credentials --config "${SESSIONS}/human" \
         --server "${KCL_SERVER}" --realm master --user "${u}" 2>&1 || true)"
  rm -f "${SESSIONS}/human"
  case "${res}" in
    *"Account is not fully set up"*) echo "platform_admin.login=actions_required" ;;
    *"Invalid user credentials"*) die platform_admin_password_mismatch ;;
    *) die platform_admin_login_unverified ;;
  esac
  echo "platform_admin.created=yes"
}

case "${1:-}" in
  probe|reconcile|establish|cleanup) "$1" ;;
  platform-admin) platform_admin ;;
  *) echo "uso: $0 probe|reconcile|establish|cleanup|platform-admin" >&2; exit 64 ;;
esac
