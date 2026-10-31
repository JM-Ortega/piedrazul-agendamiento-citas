#!/usr/bin/env bash
# Identidad SSH del servidor de producción: pin administrado a mano, verificación estricta y
# configuración SSH de este run. Ninguna rama confía en una clave nueva ni actualiza el pin.
#
# Uso:
#   host-trust.sh check-pin                         forma del pin, sin red
#   host-trust.sh verify <server_id> <server_ip>    pin ↔ estado ↔ clave ofrecida
#   host-trust.sh wait-ssh <server_ip>              espera SSH con la identidad verificada
#   host-trust.sh sshd-check <server_ip>            configuración efectiva de sshd
#
# Entorno:
#   EXPECTED_HOST_PIN          vars.PRODUCTION_SSH_HOST_PIN del environment production-hetzner:
#                              "<server_id> <server_ip> ssh-ed25519 SHA256:<43 caracteres>"
#   PIN_OUTSIDE_ENVIRONMENT    "false" solo si el job classify, que no tiene environment, no
#                              ve la variable. `vars` resuelve environment > repositorio >
#                              organización: una copia fuera del environment podría ocupar
#                              el lugar del pin protegido sin que nada lo muestre.
#   SSH_TRUST_DIR              por defecto ${RUNNER_TEMP}/ssh-trust
#   SSH_KEY_FILE, SSH_USER     por defecto ~/.ssh/id_ed25519 y ansible
#   HOST_TRUST_TIMEOUT, HOST_TRUST_INTERVAL, HOST_TRUST_ATTEMPTS  plazos de espera
#   SSH_PORT, SSH_BIN, SSH_KEYSCAN_BIN, SSH_KEYGEN_BIN            existen para las pruebas
#
# Contrato de verify:
#   1. El pin se valida y se concilia con la instancia y la dirección del state version
#      exacto antes de cualquier contacto de red. Un pin de otra instancia es obsoleto aunque
#      la IP coincida: una dirección reutilizada no hereda confianza.
#   2. La clave ofrecida se lee con ssh-keyscan: sin credenciales ni identidades del cliente.
#      Solo la falta de respuesta se reintenta, con plazo; una identidad distinta falla en
#      el primer intento y nunca se muestra su huella, para que no parezca un reemplazo.
#   3. Solo si coincide se escribe known_hosts y un ssh_config de este run. La coincidencia
#      no autentica: una clave pública se puede copiar. La autenticación es el handshake de
#      cada conexión con StrictHostKeyChecking=yes contra ese known_hosts.
#
# Salida de verify (GITHUB_OUTPUT): host_trust=verified, ssh_config, known_hosts.
set -euo pipefail

fail() {
  echo "::error title=Identidad SSH del host::$*" >&2
  exit 1
}

PIN_RE='^([0-9]+) ([0-9]{1,3}(\.[0-9]{1,3}){3}) ssh-ed25519 (SHA256:[A-Za-z0-9+/]{43})$'
IP_RE='^[0-9]{1,3}(\.[0-9]{1,3}){3}$'
PORT="${SSH_PORT:-22}"
TRUST_DIR="${SSH_TRUST_DIR:-${RUNNER_TEMP:-}/ssh-trust}"
SSH_USER="${SSH_USER:-ansible}"
SSH="${SSH_BIN:-ssh}"
KEYSCAN="${SSH_KEYSCAN_BIN:-ssh-keyscan}"
KEYGEN="${SSH_KEYGEN_BIN:-ssh-keygen}"
CONFIG="${TRUST_DIR}/ssh_config"
KNOWN_HOSTS="${TRUST_DIR}/known_hosts"

[[ "${PORT}" =~ ^[1-9][0-9]*$ ]] || fail "SSH_PORT inválido"

# Deja PIN_ID, PIN_IP y PIN_FINGERPRINT; nunca recurre a otra fuente
parse_pin() {
  [ "${PIN_OUTSIDE_ENVIRONMENT:-}" = false ] ||
    fail "[pin] PRODUCTION_SSH_HOST_PIN existe en el repositorio o la organización, o no se pudo comprobar (PIN_OUTSIDE_ENVIRONMENT='${PIN_OUTSIDE_ENVIRONMENT:-}'): el pin vive solo en el environment production-hetzner. Eliminar esa copia; no se conecta"
  [ -n "${EXPECTED_HOST_PIN:-}" ] ||
    fail "[pin] falta PRODUCTION_SSH_HOST_PIN en el environment production-hetzner: la identidad del servidor no está registrada. Autenticarla fuera de banda y registrar el pin (instalacion-nueva.md); no se conecta"
  [[ "${EXPECTED_HOST_PIN}" =~ ${PIN_RE} ]] ||
    fail "[pin] PRODUCTION_SSH_HOST_PIN está mal formado (esperado: '<server_id> <server_ip> ssh-ed25519 SHA256:<huella>'): no se conecta"
  PIN_ID="${BASH_REMATCH[1]}"
  PIN_IP="${BASH_REMATCH[2]}"
  PIN_FINGERPRINT="${BASH_REMATCH[4]}"
}

require_ip() { [[ "$1" =~ ${IP_RE} ]] || fail "IP del servidor inválida: '$1'"; }

require_config() {
  [ -s "${CONFIG}" ] && [ -s "${KNOWN_HOSTS}" ] ||
    fail "no hay configuración SSH verificada en este run: 'verify' debe terminar antes de cualquier conexión"
}

# known_hosts escribe la dirección sin corchetes solo en el puerto 22
known_host_name() {
  if [ "${PORT}" = 22 ]; then echo "$1"; else echo "[$1]:${PORT}"; fi
}

cmd_check_pin() {
  parse_pin
  echo "Pin de identidad del host presente y bien formado (instancia ${PIN_ID}, ${PIN_IP})" >&2
}

cmd_verify() {
  local id="$1" ip="$2" name lines count line observed deadline timeout interval
  [[ "${id}" =~ ^[0-9]+$ ]] || fail "server_id inválido en el estado: '${id}'"
  require_ip "${ip}"
  parse_pin
  [ "${PIN_ID}" = "${id}" ] ||
    fail "[pin obsoleto] el pin es de la instancia ${PIN_ID} y el estado de Terraform tiene la instancia ${id}: el servidor se creó o reemplazó después de registrarlo. Una dirección o un nombre reutilizados no heredan confianza: autenticar la nueva identidad fuera de banda y registrar otro pin (instalacion-nueva.md); no se conecta"
  [ "${PIN_IP}" = "${ip}" ] ||
    fail "[pin obsoleto] el pin es para ${PIN_IP} y el estado de Terraform tiene ${ip} en la misma instancia ${id}: confirmar la dirección fuera de banda y registrar otro pin; no se conecta"

  timeout="${HOST_TRUST_TIMEOUT:-300}"
  interval="${HOST_TRUST_INTERVAL:-10}"
  [[ "${timeout}" =~ ^[0-9]+$ ]] && [[ "${interval}" =~ ^[0-9]+$ ]] || fail "plazos de espera inválidos"
  [ "${TRUST_DIR}" != /ssh-trust ] || fail "SSH_TRUST_DIR no definido"

  name="$(known_host_name "${ip}")"
  deadline=$((SECONDS + timeout))
  lines=""
  while :; do
    # Las líneas de comentario son el banner del servidor: dato no confiable, no se usa
    lines="$("${KEYSCAN}" -T 5 -t ed25519 -p "${PORT}" "${ip}" 2> /dev/null | grep -v '^#' || true)"
    [ -z "${lines}" ] || break
    [ "${SECONDS}" -lt "${deadline}" ] ||
      fail "[sin respuesta] ${ip}:${PORT} no ofreció una clave ed25519 en ${timeout}s: servidor apagado, firewall o sshd caído. Es un problema de conectividad, no una identidad distinta; el pin no cambia"
    echo "Sin clave ofrecida por ${ip}:${PORT} — esperando ${interval}s" >&2
    sleep "${interval}"
  done

  count="$(grep -c '' <<< "${lines}")"
  [ "${count}" = 1 ] ||
    fail "[identidad] ${ip}:${PORT} ofreció ${count} respuestas para una sola clave ed25519: no se confía en ninguna; no se conecta"
  line="${lines}"
  [[ "${line}" =~ ^[^[:space:]]+\ ssh-ed25519\ [A-Za-z0-9+/]+={0,3}$ ]] && [ "${line%% *}" = "${name}" ] ||
    fail "[identidad] la respuesta de ${ip}:${PORT} no es una clave ed25519 de esa dirección: no se confía; no se conecta"
  observed="$(printf '%s\n' "${line}" | "${KEYGEN}" -lf - -E sha256 2> /dev/null | awk 'NF >= 4 && $NF == "(ED25519)" { print $2 }')" ||
    fail "[identidad] la clave que ofrece ${ip}:${PORT} no se pudo interpretar: no se confía; no se conecta"
  [ "${observed}" = "${PIN_FINGERPRINT}" ] ||
    fail "[identidad distinta] la clave ed25519 que ofrece ${ip}:${PORT} NO es la registrada para la instancia ${id} (pin: ${PIN_FINGERPRINT}). Puede ser una reinstalación, una rotación de claves o una suplantación. No se muestra la huella observada: la única fuente válida de un pin nuevo es la consola de Hetzner (instalacion-nueva.md). No se conecta y el pin no cambia"

  ( umask 077 && mkdir -p "${TRUST_DIR}" )
  chmod 700 "${TRUST_DIR}"
  ( umask 077 && printf '%s\n' "${line}" > "${KNOWN_HOSTS}" )
  ( umask 077 && cat > "${CONFIG}" << EOF
Host *
  Port ${PORT}
  StrictHostKeyChecking yes
  UserKnownHostsFile "${KNOWN_HOSTS}"
  GlobalKnownHostsFile /dev/null
  HostKeyAlgorithms ssh-ed25519
  UpdateHostKeys no
  CheckHostIP no
  VerifyHostKeyDNS no
  BatchMode yes
  ConnectTimeout 10
  IdentitiesOnly yes
  IdentityFile "${SSH_KEY_FILE:-${HOME}/.ssh/id_ed25519}"
EOF
  )
  echo "Identidad del host verificada: instancia ${id}, ${ip}, ${PIN_FINGERPRINT}" >&2
  echo "host_trust=verified"
  echo "ssh_config=${CONFIG}"
  echo "known_hosts=${KNOWN_HOSTS}"
}

# Un rechazo de la identidad no se mezcla con "todavía no responde": falla en el primer intento
cmd_wait_ssh() {
  local ip="$1" attempts err interval
  require_ip "${ip}"
  require_config
  attempts="${HOST_TRUST_ATTEMPTS:-30}"
  interval="${HOST_TRUST_INTERVAL:-10}"
  for i in $(seq 1 "${attempts}"); do
    if err="$("${SSH}" -F "${CONFIG}" "${SSH_USER}@${ip}" true 2>&1 > /dev/null)"; then
      echo "SSH disponible con la identidad verificada" >&2
      return 0
    fi
    if grep -Eqi 'host key verification failed|remote host identification has changed|no [a-z0-9-]+ host key is known|host key .* has changed' <<< "${err}"; then
      fail "[identidad distinta] ${ip} no probó la identidad del pin durante el handshake: la clave cambió o quien responde no tiene la clave privada. No se reintenta y el pin no cambia (instalacion-nueva.md)"
    fi
    echo "Intento ${i}/${attempts} — esperando ${interval}s..." >&2
    [ "${i}" -eq "${attempts}" ] || sleep "${interval}"
  done
  fail "[sin acceso] SSH no disponible en ${ip} con el usuario ${SSH_USER} después de ${attempts} intentos (último error: $(tail -c 200 <<< "${err}" | tr '\n' ' ')): identidad verificada, pero sin sesión"
}

# Antes de entregar los secretos del converge. `-C` evalúa los bloques Match para una conexión
# de root: `sshd -T` solo mostraría la configuración global. Lee la configuración, no el
# proceso en ejecución: una recarga pendiente no se detecta aquí.
cmd_sshd_check() {
  local ip="$1" out rc=0 name want have bad=""
  require_ip "${ip}"
  require_config
  out="$("${SSH}" -F "${CONFIG}" "${SSH_USER}@${ip}" 'sudo -n sshd -T -C user=root,host=piedrazul-check,addr=192.0.2.1' 2> /dev/null)" || rc=$?
  [ "${rc}" -eq 0 ] ||
    fail "[sshd] no se pudo leer la configuración efectiva de sshd en ${ip} (código ${rc}): no se entregan los secretos del converge"
  for setting in permitrootlogin=no passwordauthentication=no kbdinteractiveauthentication=no \
    permitemptypasswords=no pubkeyauthentication=yes; do
    name="${setting%=*}" want="${setting#*=}"
    have="$(awk -v n="${name}" 'tolower($1) == n { print tolower($2); exit }' <<< "${out}")"
    [ "${have}" = "${want}" ] || bad="${bad} ${name}=${have:-ausente} (se exige ${want})"
  done
  [ -z "${bad}" ] ||
    fail "[sshd] configuración efectiva insegura en ${ip}:${bad}. No se entregan los secretos del converge. Corregir con el usuario ops o desde la consola"
  echo "Configuración efectiva de sshd conforme: sin root ni contraseñas por SSH" >&2
}

case "${1:-}" in
  check-pin)
    [ "$#" -eq 1 ] || fail "uso: $0 check-pin"
    cmd_check_pin
    ;;
  verify)
    [ "$#" -eq 3 ] || fail "uso: $0 verify <server_id> <server_ip>"
    cmd_verify "$2" "$3"
    ;;
  wait-ssh)
    [ "$#" -eq 2 ] || fail "uso: $0 wait-ssh <server_ip>"
    cmd_wait_ssh "$2"
    ;;
  sshd-check)
    [ "$#" -eq 2 ] || fail "uso: $0 sshd-check <server_ip>"
    cmd_sshd_check "$2"
    ;;
  *) fail "uso: $0 check-pin | verify <server_id> <server_ip> | wait-ssh <server_ip> | sshd-check <server_ip>" ;;
esac
