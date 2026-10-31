#!/usr/bin/env bash
# Identidad SSH del host (host-trust.sh) y ausencia de verificación relajada.
#
# Tres partes:
#   1. Lógica con ssh-keyscan y ssh falsos y ssh-keygen real: el pin decide antes de la red.
#   2. Regresión estática: ningún camino de la cadena relaja la verificación.
#   3. Runtime: OpenSSH y Ansible reales contra un sshd desechable en Docker (Ubuntu 24.04, como
#      la imagen de producción), publicado solo en 127.0.0.1. Prueba handshake, rotación de
#      clave, sshd efectivo y que leer la clave no envía credenciales.
#
# Sin nube, sin Hetzner y sin GitHub. La consola de Hetzner y el firewall real quedan para la
# verificación en vivo.
#
# Uso: infra/tests/ssh-host-trust-check.sh
#   SKIP_RUNTIME=1 omite la parte 3 (se informa como no ejecutada).
# Requiere: jq, yq, ssh, ssh-keygen; parte 3: ssh-keyscan, docker, ansible.
# Salida: 0 si todo lo ejecutado pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TRUST="${ROOT}/.github/scripts/host-trust.sh"
READY="${ROOT}/.github/scripts/host-ready.sh"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
ANSIBLE_CFG="${ROOT}/infra/ansible/ansible.cfg"

for bin in jq yq ssh-keygen; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
CONTAINER="piedrazul-ssh-trust-$$"
cleanup() {
  docker rm -f "${CONTAINER}" > /dev/null 2>&1 || true
  rm -rf "${TMP}"
}
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

# ── 1. Lógica ───────────────────────────────────────────────────────────────
ssh-keygen -q -t ed25519 -N '' -C '' -f "${TMP}/host_good"
ssh-keygen -q -t ed25519 -N '' -C '' -f "${TMP}/host_other"
ssh-keygen -q -t rsa -b 2048 -N '' -C '' -f "${TMP}/host_rsa"
fp() { ssh-keygen -lf "$1.pub" -E sha256 | awk '{print $2}'; }
GOOD_FP="$(fp "${TMP}/host_good")"
OTHER_FP="$(fp "${TMP}/host_other")"
ID=12345678
IP=203.0.113.7
PIN="${ID} ${IP} ssh-ed25519 ${GOOD_FP}"
line_of() { printf '%s %s\n' "$2" "$(cut -d' ' -f1,2 "$1.pub")"; }

mkdir -p "${TMP}/bin"
cat > "${TMP}/bin/fake-keyscan" << 'EOF'
#!/usr/bin/env bash
echo "$*" >> "${FAKE_LOG}"
n=$(grep -c '' "${FAKE_LOG}")
[ -z "${FAKE_SILENT_UNTIL:-}" ] || [ "${n}" -ge "${FAKE_SILENT_UNTIL}" ] || exit 1
[ -z "${FAKE_SCAN:-}" ] || printf '%s\n' "${FAKE_SCAN}"
EOF
cat > "${TMP}/bin/fake-ssh" << 'EOF'
#!/usr/bin/env bash
echo "$*" >> "${FAKE_LOG}"
[ -z "${FAKE_SSH_ERR:-}" ] || { echo "${FAKE_SSH_ERR}" >&2; exit 255; }
[ -z "${FAKE_SSH_OUT:-}" ] || printf '%s\n' "${FAKE_SSH_OUT}"
exit "${FAKE_SSH_RC:-0}"
EOF
chmod +x "${TMP}/bin/fake-keyscan" "${TMP}/bin/fake-ssh"
export FAKE_LOG="${TMP}/fake.log"

verify() { # <id> <ip> [VAR=valor...]: salida en ${TMP}/o y ${TMP}/e; directorio limpio
  local id="$1" ip="$2"
  shift 2
  rm -rf "${TMP}/trust"
  : > "${FAKE_LOG}"
  env SSH_TRUST_DIR="${TMP}/trust" SSH_KEYSCAN_BIN="${TMP}/bin/fake-keyscan" HOST_TRUST_TIMEOUT=0 HOST_TRUST_INTERVAL=0 \
    PIN_OUTSIDE_ENVIRONMENT=false EXPECTED_HOST_PIN="${PIN}" FAKE_SCAN="$(line_of "${TMP}/host_good" "${IP}")" \
    "$@" "${TRUST}" verify "${id}" "${ip}" > "${TMP}/o" 2> "${TMP}/e"
}
accepts() { # <descripción> <id> <ip> [VAR=valor...]
  local d="$1"
  shift
  if verify "$@"; then ok "verify acepta: ${d}"; else ko "verify acepta: ${d} ($(tail -1 "${TMP}/e"))"; fi
}
# <descripción> <patrón del diagnóstico> <red: sin|con> <id> <ip> [VAR=valor...]
rejects() {
  local d="$1" pattern="$2" net="$3"
  shift 3
  if verify "$@"; then ko "verify rechaza: ${d} (no falló)"
  elif ! grep -q -- "${pattern}" "${TMP}/e"; then ko "verify rechaza: ${d} (diagnóstico: $(tail -1 "${TMP}/e" | cut -c1-160))"
  elif [ -e "${TMP}/trust/known_hosts" ] || [ -e "${TMP}/trust/ssh_config" ]; then ko "verify rechaza: ${d} (dejó material de confianza)"
  elif [ "${net}" = sin ] && [ -s "${FAKE_LOG}" ]; then ko "verify rechaza: ${d} (contactó la red antes de validar el pin)"
  else ok "verify rechaza: ${d}"; fi
}

accepts "pin, instancia, dirección y clave coinciden" "${ID}" "${IP}"
check "verify: salida para GITHUB_OUTPUT" bash -c "grep -qx host_trust=verified '${TMP}/o' && grep -qx 'ssh_config=${TMP}/trust/ssh_config' '${TMP}/o'"
check "verify: known_hosts solo con la clave que coincidió" bash -c "[ \"\$(cat '${TMP}/trust/known_hosts')\" = \"\$(cut -d' ' -f1,2 '${TMP}/host_good.pub' | sed 's/^/${IP} /')\" ]"
check "verify: material privado del run (700/600)" bash -c "[ \"\$(stat -c %a '${TMP}/trust')\" = 700 ] && [ \"\$(stat -c %a '${TMP}/trust/ssh_config')\" = 600 ] && [ \"\$(stat -c %a '${TMP}/trust/known_hosts')\" = 600 ]"
for o in 'StrictHostKeyChecking yes' 'GlobalKnownHostsFile /dev/null' 'HostKeyAlgorithms ssh-ed25519' 'UpdateHostKeys no' 'VerifyHostKeyDNS no' 'BatchMode yes' 'IdentitiesOnly yes'; do
  check "verify: ssh_config exige '${o}'" grep -qx "  ${o}" "${TMP}/trust/ssh_config"
done
check "verify: lee solo ed25519 del host y puerto esperados" grep -qx -- "-T 5 -t ed25519 -p 22 ${IP}" "${FAKE_LOG}"
accepts "un banner del servidor en comentario no cuenta como clave" "${ID}" "${IP}" FAKE_SCAN="# ${IP}:22 SSH-2.0-OpenSSH
$(line_of "${TMP}/host_good" "${IP}")"
accepts "espera a un servidor que aún no responde" "${ID}" "${IP}" HOST_TRUST_TIMEOUT=5 FAKE_SILENT_UNTIL=3
check "verify: solo la falta de respuesta se reintenta (3 intentos)" test "$(grep -c '' "${FAKE_LOG}")" = 3

rejects "pin ausente (borrado)" 'falta PRODUCTION_SSH_HOST_PIN' sin "${ID}" "${IP}" EXPECTED_HOST_PIN=
for bad in "${ID} ${IP} ${GOOD_FP}" "${ID} ${IP} ssh-rsa ${GOOD_FP}" "x${ID} ${IP} ssh-ed25519 ${GOOD_FP}" \
  "${ID}  ${IP} ssh-ed25519 ${GOOD_FP}" "${ID} ${IP} ssh-ed25519 ${GOOD_FP} " "${ID} ${IP} ssh-ed25519 SHA256:corta" \
  "${ID} ${IP} ssh-ed25519 ${GOOD_FP#SHA256:}" "${ID} host.example ssh-ed25519 ${GOOD_FP}" "${PIN}
${PIN}"; do
  rejects "pin corrupto ('$(tr '\n' '|' <<< "${bad}" | cut -c1-34)…')" 'mal formado' sin "${ID}" "${IP}" EXPECTED_HOST_PIN="${bad}"
done
rejects "copia del pin fuera del environment" 'existe en el repositorio' sin "${ID}" "${IP}" PIN_OUTSIDE_ENVIRONMENT=true
rejects "sin comprobación de copia fuera del environment (falla cerrado)" 'no se pudo comprobar' sin "${ID}" "${IP}" PIN_OUTSIDE_ENVIRONMENT=
rejects "misma IP con instancia nueva: no hereda confianza" 'pin obsoleto' sin 99999999 "${IP}"
rejects "misma instancia con otra dirección" 'pin obsoleto' sin "${ID}" 203.0.113.8
rejects "server_id inválido en el estado" 'server_id inválido' sin "abc" "${IP}"
rejects "IP inválida en el estado" 'IP del servidor inválida' sin "${ID}" 'bad;ip'
rejects "misma instancia y dirección con otra clave (rotación o suplantación)" 'identidad distinta' con "${ID}" "${IP}" FAKE_SCAN="$(line_of "${TMP}/host_other" "${IP}")"
check "verify: una identidad distinta falla en el primer intento, sin esperar el plazo" test "$(grep -c '' "${FAKE_LOG}")" = 1
check "verify: la huella observada nunca se muestra como reemplazo" bash -c "! grep -qF '${OTHER_FP}' '${TMP}/e' '${TMP}/o'"
check "verify: el diagnóstico apunta a la consola y no sugiere actualizar con lo observado" grep -q 'consola de Hetzner' "${TMP}/e"
rejects "más de una respuesta" 'respuestas' con "${ID}" "${IP}" FAKE_SCAN="$(line_of "${TMP}/host_good" "${IP}")
$(line_of "${TMP}/host_other" "${IP}")"
rejects "clave de otro tipo" 'no es una clave ed25519' con "${ID}" "${IP}" FAKE_SCAN="$(line_of "${TMP}/host_rsa" "${IP}")"
rejects "respuesta a nombre de otra dirección" 'no es una clave ed25519' con "${ID}" "${IP}" FAKE_SCAN="$(line_of "${TMP}/host_good" 203.0.113.8)"
rejects "clave ilegible" 'identidad\|no se pudo interpretar' con "${ID}" "${IP}" FAKE_SCAN="${IP} ssh-ed25519 AAAAnoesunaclave"
rejects "sin respuesta: es conectividad, no identidad" 'sin respuesta' con "${ID}" "${IP}" FAKE_SCAN=
check "verify: sin respuesta dice que el pin no cambia" grep -q 'el pin no cambia' "${TMP}/e"

check_pin() { env PIN_OUTSIDE_ENVIRONMENT=false EXPECTED_HOST_PIN="$1" "${TRUST}" check-pin; }
check "check-pin: pin válido, sin red" check_pin "${PIN}"
if check_pin "mal" 2> /dev/null; then ko "check-pin: rechaza un pin corrupto"; else ok "check-pin: rechaza un pin corrupto"; fi
if env -u EXPECTED_HOST_PIN PIN_OUTSIDE_ENVIRONMENT=false "${TRUST}" check-pin 2> /dev/null; then ko "check-pin: rechaza un pin ausente"; else ok "check-pin: rechaza un pin ausente"; fi

# wait-ssh y sshd-check exigen un verify previo
trust_env=(SSH_TRUST_DIR="${TMP}/trust" SSH_BIN="${TMP}/bin/fake-ssh" HOST_TRUST_INTERVAL=0)
rm -rf "${TMP}/trust"; : > "${FAKE_LOG}"
if env "${trust_env[@]}" "${TRUST}" wait-ssh "${IP}" 2> /dev/null; then ko "wait-ssh: sin verify no conecta"; elif [ -s "${FAKE_LOG}" ]; then ko "wait-ssh: sin verify no conecta (conectó)"; else ok "wait-ssh: sin verify no conecta"; fi
if env "${trust_env[@]}" "${TRUST}" sshd-check "${IP}" 2> /dev/null; then ko "sshd-check: sin verify no conecta"; elif [ -s "${FAKE_LOG}" ]; then ko "sshd-check: sin verify no conecta (conectó)"; else ok "sshd-check: sin verify no conecta"; fi
if env SSH_BIN="${TMP}/bin/fake-ssh" "${READY}" "${IP}" 2> /dev/null; then ko "host-ready: sin ssh_config verificado no conecta"; elif [ -s "${FAKE_LOG}" ]; then ko "host-ready: sin ssh_config verificado no conecta (conectó)"; else ok "host-ready: sin ssh_config verificado no conecta"; fi
verify "${ID}" "${IP}"
wait_case() { # <descripción> <mensaje de ssh> <intentos esperados>
  : > "${FAKE_LOG}"
  if env "${trust_env[@]}" HOST_TRUST_ATTEMPTS=4 FAKE_SSH_ERR="$2" "${TRUST}" wait-ssh "${IP}" 2> "${TMP}/e"; then ko "wait-ssh: $1 (no falló)"
  elif [ "$(grep -c '' "${FAKE_LOG}")" != "$3" ]; then ko "wait-ssh: $1 ($(grep -c '' "${FAKE_LOG}") intentos, esperados $3)"
  else ok "wait-ssh: $1"; fi
}
wait_case "un rechazo de la identidad se diagnostica en el primer intento" "Host key verification failed." 1
check "wait-ssh: el rechazo se nombra como identidad, no como plazo" grep -q 'identidad distinta' "${TMP}/e"
wait_case "clave cambiada respecto de known_hosts: primer intento" "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!" 1
wait_case "sin clave ed25519 conocida: primer intento" "No ED25519 host key is known for ${IP} and you have requested strict checking." 1
wait_case "conexión rechazada se reintenta hasta el límite" "ssh: connect to host ${IP} port 22: Connection refused" 4
check "wait-ssh: agotar los intentos se nombra como acceso, no como identidad" grep -q 'sin acceso' "${TMP}/e"
wait_case "usuario aún no creado se reintenta" "ansible@${IP}: Permission denied (publickey)." 4
: > "${FAKE_LOG}"
check "wait-ssh: conecta solo con el ssh_config verificado" env "${trust_env[@]}" "${TRUST}" wait-ssh "${IP}"
check "wait-ssh: pasa -F y ninguna opción de relajación" bash -c "grep -qF -- '-F ${TMP}/trust/ssh_config ansible@${IP} true' '${FAKE_LOG}' && ! grep -q 'StrictHostKeyChecking' '${FAKE_LOG}'"

SSHD_OK='permitrootlogin no
passwordauthentication no
kbdinteractiveauthentication no
permitemptypasswords no
pubkeyauthentication yes
x11forwarding no'
sshd_case() { # <descripción> <pasa|falla> <salida de sshd -T> [patrón]
  local rc=0
  env "${trust_env[@]}" FAKE_SSH_OUT="$3" "${TRUST}" sshd-check "${IP}" 2> "${TMP}/e" || rc=$?
  if { [ "$2" = pasa ] && [ "${rc}" -eq 0 ]; } || { [ "$2" = falla ] && [ "${rc}" -ne 0 ] && grep -q -- "${4:-sshd}" "${TMP}/e"; }; then ok "sshd-check: $1"; else ko "sshd-check: $1 (rc=${rc}, $(tail -1 "${TMP}/e" | cut -c1-160))"; fi
}
sshd_case "configuración conforme" pasa "${SSHD_OK}"
sshd_case "root remoto habilitado" falla "${SSHD_OK/permitrootlogin no/permitrootlogin yes}" 'permitrootlogin=yes'
sshd_case "root con clave (prohibit-password) tampoco se admite" falla "${SSHD_OK/permitrootlogin no/permitrootlogin prohibit-password}" 'permitrootlogin=prohibit-password'
sshd_case "contraseñas habilitadas" falla "${SSHD_OK/passwordauthentication no/passwordauthentication yes}" 'passwordauthentication=yes'
sshd_case "kbd-interactive habilitado" falla "${SSHD_OK/kbdinteractiveauthentication no/kbdinteractiveauthentication yes}" 'kbdinteractiveauthentication=yes'
sshd_case "contraseñas vacías" falla "${SSHD_OK/permitemptypasswords no/permitemptypasswords yes}" 'permitemptypasswords=yes'
sshd_case "un ajuste ausente no equivale a conforme" falla "$(grep -v permitrootlogin <<< "${SSHD_OK}")" 'permitrootlogin=ausente'
sshd_case "salida vacía" falla "" 'ausente'
if env "${trust_env[@]}" FAKE_SSH_RC=1 FAKE_SSH_OUT="${SSHD_OK}" "${TRUST}" sshd-check "${IP}" 2> /dev/null; then ko "sshd-check: sudo o sshd fallan aunque haya salida → falla"; else ok "sshd-check: sudo o sshd fallan aunque haya salida → falla"; fi

# ── 2. Regresión estática ───────────────────────────────────────────────────
scan_paths=("${ROOT}/.github/workflows" "${ROOT}/.github/scripts" "${ROOT}/.github/actions" "${ROOT}/infra/ansible/ansible.cfg"
  "${ROOT}/infra/ansible/inventories" "${ROOT}/infra/ansible/playbooks" "${ROOT}/infra/ansible/roles")
check "regresión: ningún bypass de la clave del host en workflows, scripts ni Ansible" \
  bash -c '! grep -rEIn "StrictHostKeyChecking[= ]+(no|accept-new|off)|accept-new|UserKnownHostsFile[= ]+/dev/null|HOST_KEY_CHECKING: *.?[Ff]alse|host_key_checking *= *[Ff]alse|ansible_ssh_extra_args|ansible_host_key_checking" "$@"' _ "${scan_paths[@]}"
check "regresión: ssh-keyscan solo en host-trust.sh" \
  bash -c "[ \"\$(grep -rlI 'ssh-keyscan' '${ROOT}/.github/workflows' '${ROOT}/.github/scripts' '${ROOT}/infra/ansible/playbooks' '${ROOT}/infra/ansible/roles')\" = '${TRUST}' ]"
check "regresión: el workflow no invoca ssh directamente; todo pasa por los scripts verificados" \
  bash -c "! yq -r '.jobs[].steps[].run // \"\"' '${WORKFLOW}' | grep -Eq '(^|[[:space:]])(ssh|scp|sftp|rsync)[[:space:]]'"
check "regresión: Ansible exige la clave del host (workflow y ansible.cfg)" \
  bash -c "[ \"\$(yq -r '.jobs.production.env.ANSIBLE_HOST_KEY_CHECKING' '${WORKFLOW}')\" = True ] && grep -Eq '^host_key_checking += +True$' '${ANSIBLE_CFG}'"
check "regresión: el inventario de este run aplica el ssh_config verificado" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"access\") | .run' '${WORKFLOW}' | grep -qF 'ansible_ssh_common_args: \"-F %s\"' && [ \"\$(yq -r '.jobs.production.steps[] | select(.id == \"access\") | .env.SSH_CONFIG' '${WORKFLOW}')\" = '\${{ steps.host_trust.outputs.ssh_config }}' ]"
check "regresión: todo paso de Ansible con host remoto usa ese inventario" \
  bash -c "[ -z \"\$(yq -o=json '.jobs.production.steps' '${WORKFLOW}' | jq -r '.[] | select(.id != \"kc_admin_preflight\") | select((.run // \"\") | test(\"(^|\\\\s)ansible(-playbook)? \")) | select((.run | contains(\"hosts.runtime.yml\")) | not) | .name')\" ]"
check "regresión: la verificación temprana de Keycloak es local (sin SSH antes de la confianza)" \
  test "$(yq -r '.[0].connection' "${ROOT}/infra/ansible/playbooks/keycloak-admin-preflight.yml")" = local
check "regresión: la clave privada se escribe después de verificar la identidad" \
  bash -c "ids=\$(yq -r '.jobs.production.steps[].id // \"\"' '${WORKFLOW}'); [ \"\$(grep -nx host_trust <<< \"\$ids\" | cut -d: -f1)\" -lt \"\$(grep -nx access <<< \"\$ids\" | cut -d: -f1)\" ]"
check "regresión: host-trust.sh no escribe el pin ni confía en una clave observada fuera de la comparación" \
  bash -c "! grep -Eq 'gh (api|variable)|curl|GITHUB_TOKEN|GH_TOKEN' '${TRUST}'"

# ── 3. Runtime: OpenSSH y Ansible reales ────────────────────────────────────
if [ -n "${SKIP_RUNTIME:-}" ]; then
  echo "NOTE  parte de runtime omitida (SKIP_RUNTIME)"
elif ! docker info > /dev/null 2>&1; then
  ko "runtime: Docker no disponible (usar SKIP_RUNTIME=1 para omitirlo de forma explícita)"
else
  ssh-keygen -q -t ed25519 -N '' -C '' -f "${TMP}/client_key"
  mkdir -p "${TMP}/image"
  cat > "${TMP}/image/Dockerfile" << 'EOF'
FROM ubuntu:24.04
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends openssh-server sudo python3 > /dev/null && \
    useradd -m -s /bin/bash ansible && echo 'ansible:*' | chpasswd -e && \
    mkdir -p /run/sshd /home/ansible/.ssh && chmod 700 /home/ansible/.ssh && \
    echo 'ansible ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ansible && \
    printf '%s\n' 'PasswordAuthentication no' 'PermitRootLogin no' 'KbdInteractiveAuthentication no' 'LogLevel VERBOSE' \
      > /etc/ssh/sshd_config.d/60-piedrazul-hardening.conf && \
    printf '#!/bin/sh\n[ "$1" = status ] && [ "$2" = --wait ] && exit 0\necho %s\n' \
      "'{\"status\":\"done\",\"extended_status\":\"done\",\"errors\":[],\"recoverable_errors\":{}}'" > /usr/local/bin/cloud-init && \
    chmod +x /usr/local/bin/cloud-init
CMD ["/usr/sbin/sshd", "-D", "-e"]
EOF
  if ! docker build -q -t piedrazul-ssh-trust:local "${TMP}/image" > "${TMP}/build.log" 2>&1; then
    ko "runtime: no se pudo construir el sshd de prueba ($(tail -1 "${TMP}/build.log" | cut -c1-200))"
  else
    docker run -d --name "${CONTAINER}" -p 127.0.0.1::22 piedrazul-ssh-trust:local > /dev/null
    dexec() { docker exec "${CONTAINER}" sh -c "$1"; }
    dexec "ssh-keygen -q -t ed25519 -N '' -C '' -f /etc/ssh/ssh_host_ed25519_key -y > /dev/null 2>&1 || true"
    docker cp "${TMP}/client_key.pub" "${CONTAINER}:/home/ansible/.ssh/authorized_keys"
    dexec 'chown -R ansible:ansible /home/ansible/.ssh && chmod 600 /home/ansible/.ssh/authorized_keys'
    PORT="$(docker port "${CONTAINER}" 22/tcp | head -1 | sed 's/.*://')"
    LOCAL=127.0.0.1
    export SSH_PORT="${PORT}" SSH_KEY_FILE="${TMP}/client_key"
    host_fp() { dexec 'ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256' | awk '{print $2}'; }
    # La huella se lee dentro del contenedor, como el operador en la consola de Hetzner
    for _ in $(seq 1 30); do ssh-keyscan -T 2 -t ed25519 -p "${PORT}" "${LOCAL}" 2> /dev/null | grep -q ed25519 && break; sleep 1; done
    PIN_RT="${ID} ${LOCAL} ssh-ed25519 $(host_fp)"
    rt() { # <subcomando...>: host-trust.sh con el pin autenticado
      env SSH_TRUST_DIR="${TMP}/rt" PIN_OUTSIDE_ENVIRONMENT=false EXPECTED_HOST_PIN="${PIN_RT}" HOST_TRUST_TIMEOUT=10 HOST_TRUST_INTERVAL=1 HOST_TRUST_ATTEMPTS=3 \
        "${TRUST}" "$@"
    }
    LOG_MARK="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    sleep 1
    if rt verify "${ID}" "${LOCAL}" > "${TMP}/rt.out" 2> "${TMP}/rt.err"; then ok "runtime: pin autenticado ↔ ssh-keyscan real"; else ko "runtime: pin autenticado ↔ ssh-keyscan real ($(tail -1 "${TMP}/rt.err" | cut -c1-200))"; fi
    sleep 1
    check "runtime: known_hosts con el formato del puerto usado" grep -q "^\[${LOCAL}\]:${PORT} ssh-ed25519 " "${TMP}/rt/known_hosts"
    check "runtime: leer la clave no intentó autenticar ni envió identidades" \
      bash -c "! docker logs --since '${LOG_MARK}' '${CONTAINER}' 2>&1 | grep -Eiq 'publickey|userauth|invalid user|Failed|Accepted'"
    check "runtime: wait-ssh con handshake estricto real" rt wait-ssh "${LOCAL}"
    check "runtime: ssh real con el ssh_config verificado ejecuta como ansible" \
      bash -c "[ \"\$(ssh -F '${TMP}/rt/ssh_config' ansible@${LOCAL} 'id -un')\" = ansible ]"
    check "runtime: host-ready.sh real lee cloud-init por la conexión estricta" \
      bash -c "env SSH_CONFIG_FILE='${TMP}/rt/ssh_config' HOST_READY_TIMEOUT=20 '${READY}' ${LOCAL} | grep -qx cloud_init_status=done"
    check "runtime: sshd -T -C real sobre la configuración conforme" rt sshd-check "${LOCAL}"

    printf 'all:\n  hosts:\n    vps:\n      ansible_host: %s\n      ansible_ssh_common_args: "-F %s"\n' "${LOCAL}" "${TMP}/rt/ssh_config" > "${TMP}/hosts.runtime.yml"
    mkdir -p "${TMP}/ansible-home"
    an() { # módulo y argumentos, como el workflow: ansible.cfg del repo, verificación estricta
      env HOME="${TMP}/ansible-home" ANSIBLE_CONFIG="${ANSIBLE_CFG}" ANSIBLE_HOST_KEY_CHECKING=True ANSIBLE_LOCAL_TEMP="${TMP}/ansible-home/tmp" \
        ANSIBLE_REMOTE_TEMP=/tmp/.ansible-tmp ansible vps -i "${ROOT}/infra/ansible/inventories/prod" -i "${TMP}/hosts.runtime.yml" "$@"
    }
    if an -m ansible.builtin.command -a 'install -m 600 /dev/null /root/ansible-probe' > "${TMP}/an.out" 2>&1 &&
      [ "$(dexec 'stat -c %U /root/ansible-probe')" = root ]; then
      ok "runtime: Ansible real conecta y escala con la identidad verificada"
    else
      ko "runtime: Ansible real conecta y escala con la identidad verificada ($(tr '\n' ' ' < "${TMP}/an.out" | tr -s ' *' | cut -c1-600))"
    fi

    # sshd efectivo: bloques Match incluidos
    sshd_rt() { # <descripción> <contenido del drop-in> <patrón>
      local rc=0
      dexec "printf '%s\n' '$2' > /etc/ssh/sshd_config.d/10-prueba.conf"
      rt sshd-check "${LOCAL}" 2> "${TMP}/rt.err" || rc=$?
      dexec 'rm -f /etc/ssh/sshd_config.d/10-prueba.conf'
      if [ "${rc}" -ne 0 ] && grep -q -- "$3" "${TMP}/rt.err"; then ok "runtime: sshd-check rechaza $1"; else ko "runtime: sshd-check rechaza $1 (rc=${rc}, $(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi
    }
    sshd_rt "root habilitado por un drop-in anterior" 'PermitRootLogin yes' 'permitrootlogin=yes'
    dexec "printf '\nMatch User root\n  PasswordAuthentication yes\n' >> /etc/ssh/sshd_config"
    if rt sshd-check "${LOCAL}" 2> "${TMP}/rt.err"; then ko "runtime: sshd-check ve un bloque Match para root"; elif grep -q 'passwordauthentication=yes' "${TMP}/rt.err"; then ok "runtime: sshd-check ve un bloque Match para root"; else ko "runtime: sshd-check ve un bloque Match para root ($(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi
    dexec "sed -i '/^Match User root/,\$d' /etc/ssh/sshd_config"
    check "runtime: restaurado, sshd-check vuelve a pasar" rt sshd-check "${LOCAL}"

    # Misma instancia y dirección, clave nueva (reinstalación o rotación)
    GOOD_LINE="$(cat "${TMP}/rt/known_hosts")"
    dexec 'rm -f /etc/ssh/ssh_host_ed25519_key* && ssh-keygen -q -t ed25519 -N "" -C "" -f /etc/ssh/ssh_host_ed25519_key'
    docker restart "${CONTAINER}" > /dev/null
    PORT2="$(docker port "${CONTAINER}" 22/tcp | head -1 | sed 's/.*://')"
    [ "${PORT2}" = "${PORT}" ] || { export SSH_PORT="${PORT2}"; PORT="${PORT2}"; GOOD_LINE="[${LOCAL}]:${PORT} ${GOOD_LINE#* }"; printf '%s\n' "${GOOD_LINE}" > "${TMP}/rt/known_hosts"; sed -i "s/^  Port .*/  Port ${PORT}/" "${TMP}/rt/ssh_config"; }
    for _ in $(seq 1 30); do ssh-keyscan -T 2 -t ed25519 -p "${PORT}" "${LOCAL}" 2> /dev/null | grep -q ed25519 && break; sleep 1; done
    NEW_FP="$(host_fp)"
    check "runtime (control): la clave del host cambió" test "${NEW_FP}" != "${PIN_RT##* }"
    check "runtime (control): sin verificación estricta el mismo servidor sí aceptaría la sesión" \
      ssh -F /dev/null -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o IdentitiesOnly=yes -i "${TMP}/client_key" -p "${PORT}" "ansible@${LOCAL}" true
    # Lo que queda del run anterior sigue exigiendo la clave anterior: el fallo es del handshake real
    if ssh -F "${TMP}/rt/ssh_config" "ansible@${LOCAL}" true 2> "${TMP}/rt.err"; then ko "runtime: ssh estricto rechaza la clave rotada"; elif grep -Eqi 'host key|identification has changed' "${TMP}/rt.err"; then ok "runtime: ssh estricto rechaza la clave rotada"; else ko "runtime: ssh estricto rechaza la clave rotada ($(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi
    if env SSH_CONFIG_FILE="${TMP}/rt/ssh_config" HOST_READY_TIMEOUT=20 "${READY}" "${LOCAL}" > /dev/null 2> "${TMP}/rt.err"; then ko "runtime: host-ready.sh rechaza la clave rotada"; else ok "runtime: host-ready.sh rechaza la clave rotada"; fi
    START=$SECONDS
    if rt wait-ssh "${LOCAL}" 2> "${TMP}/rt.err"; then ko "runtime: wait-ssh diagnostica la clave rotada"; elif grep -q 'identidad distinta' "${TMP}/rt.err" && [ $((SECONDS - START)) -lt 5 ]; then ok "runtime: wait-ssh diagnostica la clave rotada de inmediato, sin plazo genérico"; else ko "runtime: wait-ssh diagnostica la clave rotada ($(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi
    if an -m ansible.builtin.ping > "${TMP}/an.out" 2>&1; then ko "runtime: Ansible rechaza la clave rotada"; elif grep -Eqi 'host key verification failed|identification has changed' "${TMP}/an.out"; then ok "runtime: Ansible rechaza la clave rotada por la identidad, no por otra causa"; else ko "runtime: Ansible rechaza la clave rotada ($(tail -2 "${TMP}/an.out" | cut -c1-180))"; fi
    # Y un run nuevo tampoco la acepta: el pin no cambió
    rm -rf "${TMP}/rt-new"
    if env SSH_TRUST_DIR="${TMP}/rt-new" PIN_OUTSIDE_ENVIRONMENT=false EXPECTED_HOST_PIN="${PIN_RT}" HOST_TRUST_TIMEOUT=5 HOST_TRUST_INTERVAL=1 "${TRUST}" verify "${ID}" "${LOCAL}" > /dev/null 2> "${TMP}/rt.err"; then
      ko "runtime: verify rechaza la clave rotada con el pin anterior"
    elif grep -q 'identidad distinta' "${TMP}/rt.err" && ! grep -qF "${NEW_FP}" "${TMP}/rt.err" && [ ! -e "${TMP}/rt-new/known_hosts" ]; then
      ok "runtime: verify rechaza la clave rotada con el pin anterior, sin mostrar la huella ni dejar material"
    else ko "runtime: verify rechaza la clave rotada con el pin anterior ($(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi

    # La coincidencia de la clave pública no autentica: quien la copia no supera el handshake
    rm -rf "${TMP}/rt-copy"
    cat > "${TMP}/bin/keyscan-copy" << EOF
#!/usr/bin/env bash
printf '%s\n' '${GOOD_LINE}'
EOF
    chmod +x "${TMP}/bin/keyscan-copy"
    if env SSH_TRUST_DIR="${TMP}/rt-copy" SSH_KEYSCAN_BIN="${TMP}/bin/keyscan-copy" PIN_OUTSIDE_ENVIRONMENT=false EXPECTED_HOST_PIN="${PIN_RT}" "${TRUST}" verify "${ID}" "${LOCAL}" > /dev/null 2>&1; then
      ok "runtime: una clave pública copiada coincide con el pin en la lectura (no es autenticación)"
      if env SSH_TRUST_DIR="${TMP}/rt-copy" HOST_TRUST_ATTEMPTS=3 HOST_TRUST_INTERVAL=1 "${TRUST}" wait-ssh "${LOCAL}" 2> "${TMP}/rt.err"; then
        ko "runtime: el handshake rechaza al servidor sin la clave privada"
      elif grep -q 'identidad distinta' "${TMP}/rt.err"; then ok "runtime: el handshake rechaza al servidor sin la clave privada"
      else ko "runtime: el handshake rechaza al servidor sin la clave privada ($(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi
    else
      ko "runtime: una clave pública copiada coincide con el pin en la lectura"
    fi

    # Un operador autentica la nueva huella por el canal independiente: reintento sin otras etapas
    PIN_RT="${ID} ${LOCAL} ssh-ed25519 ${NEW_FP}"
    rm -rf "${TMP}/rt"
    check "runtime: con el pin nuevo registrado por el operador, verify pasa" bash -c "$(declare -f rt); $(printf 'TMP=%q PIN_RT=%q TRUST=%q ID=%q LOCAL=%q' "${TMP}" "${PIN_RT}" "${TRUST}" "${ID}" "${LOCAL}"); rt verify \"\${ID}\" \"\${LOCAL}\""
    check "runtime: y la sesión estricta vuelve a funcionar" bash -c "ssh -F '${TMP}/rt/ssh_config' ansible@${LOCAL} true"

    docker stop -t 1 "${CONTAINER}" > /dev/null
    rm -rf "${TMP}/rt-off"
    if env SSH_TRUST_DIR="${TMP}/rt-off" PIN_OUTSIDE_ENVIRONMENT=false EXPECTED_HOST_PIN="${PIN_RT}" HOST_TRUST_TIMEOUT=1 HOST_TRUST_INTERVAL=1 "${TRUST}" verify "${ID}" "${LOCAL}" > /dev/null 2> "${TMP}/rt.err"; then
      ko "runtime: un servidor apagado se informa como conectividad"
    elif grep -q 'sin respuesta' "${TMP}/rt.err"; then ok "runtime: un servidor apagado se informa como conectividad, no como identidad"
    else ko "runtime: un servidor apagado se informa como conectividad ($(tail -1 "${TMP}/rt.err" | cut -c1-180))"; fi
  fi
fi

echo
if [ "${FAILS}" -ne 0 ]; then
  echo "${FAILS} fallo(s)"
  exit 1
fi
echo "Todo pasó"
