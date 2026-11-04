#!/usr/bin/env bash
# Prueba de Ansible de los logs contra un host de LABORATORIO, desde el controlador: instalación,
# idempotencia, rechazo de una fuente no admitida sin cambios, vuelta a métricas sin logs en sus
# dos pasos (observability.yml de la versión sin logs y observability-logs-rollback.yml),
# purge opcional y reinstalación.
#
# SOLO contra un host de laboratorio desechable: instala el stack, escribe el drop-in de
# journald, reinicia journald y retira Loki. El host debe tener el usuario LAB_USER con sudo sin
# contraseña, Docker con Compose (rol docker_host) y la red de la aplicación con sus destinos de
# métricas: la verificación del rol (verify.yml) exige backend, keycloak y traefik leídos, también
# en el paso 1 con LAB_BASE_REF. Con la aplicación instalada (converge) ya están; en un laboratorio
# sin aplicación no basta con docker network create piedrazul_net: hacen falta productores
# SINTÉTICOS en esa red con esos nombres, puertos y rutas (backend:8081/actuator/prometheus,
# keycloak:9000/metrics, traefik:8082/metrics) y un Content-Type de exposición de Prometheus
# (text/plain; version=0.0.4), sin el cual Prometheus 3 rechaza la lectura. Que esos destinos
# estén up no verifica la aplicación. Ver docs/operations/runbooks/logs.md, "Verificación".
#
# Inventario aislado: nunca usa inventories/prod. Genera un inventario de un solo host (vps, el
# nombre que esperan los playbooks) en un directorio temporal sin group_vars ni host_vars, y le
# pasa solo las variables de conexión de abajo. Así no se heredan variables de producción
# (dominios, ACME, secretos, IP), y ANSIBLE_INVENTORY apunta al mismo archivo para que ningún
# comando caiga en el inventario por defecto de ansible.cfg. De ansible.cfg se conservan
# remote_user, become (sudo), host_key_checking y collections_path.
#
# Uso (desde la raíz del repositorio, con las colecciones de requirements.yml instaladas):
#   LAB_HOST=<ip> LAB_CONFIRM=<ip> LAB_BASE_REF=<commit sin logs> infra/tests/logs-lab-ansible-check.sh
#   LAB_HOST=192.0.2.10 LAB_CONFIRM=192.0.2.10 LAB_RENDER_ONLY=1 infra/tests/logs-lab-ansible-check.sh
#     (solo genera y muestra el inventario resuelto; no conecta)
# Entorno:
#   LAB_HOST         dirección IP literal del laboratorio (no un alias de ssh_config)
#   LAB_CONFIRM      la misma IP, como confirmación explícita
#   LAB_BASE_REF     commit de la versión sin logs, para el paso 1 de la retirada
#   LAB_USER         usuario SSH (por defecto ansible)
#   LAB_SSH_KEY      clave privada (opcional; si no, el agente SSH)
#   LAB_KNOWN_HOSTS  known_hosts con la clave del laboratorio (por defecto ~/.ssh/known_hosts)
#   PURGE=1          prueba además la purga de volúmenes de logs
# SSH: -F /dev/null (ningún Host de ~/.ssh/config redirige el destino),
# StrictHostKeyChecking=yes contra LAB_KNOWN_HOSTS. La clave del host debe estar confiada antes.
#
# Verifica:
#   1. Instalación: drop-in, Loki, OBS_JOURNAL_GID = grupo del journal.
#   2. Idempotencia: el segundo run no cambia nada.
#   3. Política: una fuente condicionada sin controles falla antes de tocar nada.
#   4. Orden de la retirada: el paso 2 antes del 1 falla sin cambios.
#   5. Retirada: paso 1 deja métricas sin logs con Loki huérfano; paso 2 retira Loki, su fuente
#      de datos, su configuración, las variables de logs y el drop-in; journald activo;
#      volúmenes de métricas conservados; repetir no cambia nada; PURGE=1 borra los de logs.
#   6. Reinstalación: vuelve a converger y un run más es idempotente.
# Salida: 0 si todo pasa; 1 si algo falla; 2 si falta la confirmación, una variable o una
# herramienta.
set -uo pipefail

LAB_HOST="${LAB_HOST:-}"
[ -n "${LAB_HOST}" ] && [ "${LAB_CONFIRM:-}" = "${LAB_HOST}" ] || {
  echo "Solo contra un host de laboratorio: LAB_HOST=<ip> LAB_CONFIRM=<la misma ip> (ver cabecera)" >&2; exit 2; }
# Destino inequívoco: una IPv4 o IPv6 literal, sin espacios, comas ni patrones de Ansible
python3 -c 'import ipaddress,sys; ipaddress.ip_address(sys.argv[1])' "${LAB_HOST}" 2>/dev/null || {
  echo "LAB_HOST debe ser una IP literal (no un nombre ni un alias): ${LAB_HOST}" >&2; exit 2; }
for bin in ansible-playbook ansible-inventory python3; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
LAB_USER="${LAB_USER:-ansible}"
LAB_KNOWN_HOSTS="${LAB_KNOWN_HOSTS:-${HOME}/.ssh/known_hosts}"
[ -r "${LAB_KNOWN_HOSTS}" ] || { echo "no se puede leer ${LAB_KNOWN_HOSTS}" >&2; exit 2; }
[ -z "${LAB_SSH_KEY:-}" ] || [ -r "${LAB_SSH_KEY}" ] || { echo "no se puede leer ${LAB_SSH_KEY}" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
INV="${TMP}/inventory/hosts.yml"
mkdir -p "${TMP}/inventory"
python3 -I - "${INV}" "${LAB_HOST}" "${LAB_USER}" "${LAB_KNOWN_HOSTS}" "${LAB_SSH_KEY:-}" <<'EOF'
import json, sys
out, host, user, known, key = sys.argv[1:]
v = {
    "ansible_host": host,
    "ansible_user": user,
    "ansible_ssh_common_args": f"-F /dev/null -o StrictHostKeyChecking=yes -o UserKnownHostsFile={known}",
}
if key:
    v["ansible_ssh_private_key_file"] = key
# JSON es YAML válido: sin interpolación de valores en el texto
json.dump({"all": {"hosts": {"vps": v}}}, open(out, "w"), indent=2)
EOF

export ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg"
export ANSIBLE_INVENTORY="${INV}"
export ANSIBLE_HOST_KEY_CHECKING=True

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

# Inventario resuelto: un solo host y solo las variables de conexión
ansible-inventory -i "${INV}" --list > "${TMP}/inventory.json" 2>/dev/null
RESOLVED_HOSTS="$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(",".join(sorted(d["_meta"]["hostvars"])))' "${TMP}/inventory.json")"
RESOLVED_VARS="$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(",".join(sorted(d["_meta"]["hostvars"].get("vps", {}))))' "${TMP}/inventory.json")"
EXPECTED_VARS="ansible_host,ansible_ssh_common_args,ansible_user${LAB_SSH_KEY:+,ansible_ssh_private_key_file}"
EXPECTED_VARS="$(tr ',' '\n' <<< "${EXPECTED_VARS}" | sort | paste -sd,)"
if [ "${RESOLVED_HOSTS}" != vps ] || [ "${RESOLVED_VARS}" != "${EXPECTED_VARS}" ]; then
  echo "inventario no aislado: hosts=${RESOLVED_HOSTS} variables=${RESOLVED_VARS}" >&2; exit 2
fi
if [ "${LAB_RENDER_ONLY:-0}" = 1 ]; then
  python3 -I -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["_meta"]["hostvars"], indent=2))' "${TMP}/inventory.json"
  exit 0
fi
[ -n "${LAB_BASE_REF:-}" ] || { echo "LAB_BASE_REF=<commit de la versión sin logs> es obligatorio" >&2; exit 2; }
git -C "${ROOT}" cat-file -e "${LAB_BASE_REF}^{commit}" 2>/dev/null || { echo "no existe ${LAB_BASE_REF}" >&2; exit 2; }
mkdir -p "${TMP}/base"
git -C "${ROOT}" archive "${LAB_BASE_REF}" infra/ansible infra/observability | tar -x -C "${TMP}/base"
test ! -e "${TMP}/base/infra/observability/loki" || { echo "${LAB_BASE_REF} ya tiene logs" >&2; exit 2; }

play() { # play <nombre del log> <directorio ansible> <playbook> [args...]
  local log="${TMP}/$1.log" dir="$2" pb="$3"; shift 3
  ANSIBLE_CONFIG="${dir}/ansible.cfg" ansible-playbook -i "${INV}" --limit vps "${dir}/playbooks/${pb}" "$@" </dev/null > "${log}" 2>&1
}
recap() { grep -E '^vps\s+:' "${TMP}/$1.log" | tail -1; }
changed() { recap "$1" | sed -nE 's/.*changed=([0-9]+).*/\1/p'; }
# host <comando de shell> (como root en el laboratorio). El comando pasa por la plantilla de Ansible:
# las llaves de Go de docker --format se escapan ({{ "{{" }} … {{ "}}" }}). La salida de -o es una
# línea (los saltos llegan como \n literal): los comandos imprimen una sola línea.
host() {
  ansible -i "${INV}" vps -b -m ansible.builtin.shell -a "$1" -o 2>/dev/null \
    | sed -nE 's/^vps \| [A-Z]+ \| rc=[0-9]+ \| \(stdout\) ?//p'
}
OBS_PS='docker ps --filter label=com.docker.compose.project=piedrazul-obs --format "{{ "{{" }}.Label \"com.docker.compose.service\"{{ "}}" }}" | sort | tr "\n" ","'
GRAFANA_DS='cd /opt/piedrazul-obs && docker compose exec -T grafana sh -c '"'"'wget -qO- --header "Authorization: Basic $(printf "admin:%s" "$GF_SECURITY_ADMIN_PASSWORD" | base64)" http://localhost:3000/api/datasources'"'"' | python3 -c "import json,sys; print(\",\".join(sorted(d[\"uid\"] for d in json.load(sys.stdin))))"'

# ── 1. Instalación ───────────────────────────────────────────────────────────
if play install "${ANSIBLE_DIR}" observability.yml; then ok "instalación: observability.yml termina ($(recap install))"; else ko "instalación: observability.yml"; tail -30 "${TMP}/install.log" >&2; exit 1; fi
check "instalación: drop-in de journald con los valores del rol" \
  test "$(host 'grep -E "^(Storage|SystemMaxUse|SystemMaxFileSize|MaxRetentionSec)=" /etc/systemd/journald.conf.d/60-piedrazul-obs.conf | tr "\n" " "')" = "Storage=persistent SystemMaxUse=2G SystemMaxFileSize=128M MaxRetentionSec=7day "
check "instalación: cuatro servicios en piedrazul-obs" test "$(host "${OBS_PS}")" = "alloy,grafana,loki,prometheus,"
check "instalación: OBS_JOURNAL_GID = grupo de los archivos del journal" \
  test "$(host 'grep ^OBS_JOURNAL_GID= /opt/piedrazul-obs/.env | cut -d= -f2')" = "$(host 'stat -c %g /var/log/journal/$(cat /etc/machine-id)')"

# ── 2. Idempotencia ──────────────────────────────────────────────────────────
play second "${ANSIBLE_DIR}" observability.yml
check "idempotencia: segundo run sin cambios ($(recap second))" test "$(changed second)" = 0

# ── 3. Política ──────────────────────────────────────────────────────────────
STATE='docker ps -q --filter label=com.docker.compose.project=piedrazul-obs | sort | tr "\n" " "; sha256sum /opt/piedrazul-obs/compose.yml /opt/piedrazul-obs/.env | cut -c1-16 | tr "\n" " "'
BEFORE="$(host "${STATE}")"
if play policy "${ANSIBLE_DIR}" observability.yml -e '{"obs_log_services": ["traefik", "backend"]}'; then
  ko "política: backend sin controles de privacidad debía fallar"
else
  check "política: falla en la comprobación de fuentes" grep -q 'no cumple la política de fuentes' "${TMP}/policy.log"
fi
check "política: el fallo no cambió contenedores, compose.yml ni .env" test "$(host "${STATE}")" = "${BEFORE}"

# ── 4. Orden de la retirada ──────────────────────────────────────────────────
if play early "${ANSIBLE_DIR}" observability-logs-rollback.yml; then ko "orden: la retirada antes del paso 1 debía fallar"; else
  check "orden: la retirada antes del paso 1 falla en su comprobación" grep -q 'todavía tiene la configuración con logs' "${TMP}/early.log"
fi
check "orden: ese fallo no cambió nada" test "$(host "${STATE}")" = "${BEFORE}"

# ── 5. Retirada ──────────────────────────────────────────────────────────────
VOLS='docker volume ls -q --filter label=com.docker.compose.project=piedrazul-obs | sed "s/^piedrazul-obs_//" | sort | tr "\n" ","'
if play step1 "${TMP}/base/infra/ansible" observability.yml; then ok "paso 1: observability.yml de ${LAB_BASE_REF} termina ($(recap step1))"; else ko "paso 1"; tail -30 "${TMP}/step1.log" >&2; fi
check "paso 1: Loki huérfano sigue y su fuente de datos sigue en Grafana" \
  bash -c "test \"\$1\" = alloy,grafana,loki,prometheus, && test \"\$2\" = loki,prometheus" _ "$(host "${OBS_PS}")" "$(host "${GRAFANA_DS}")"
if play step2 "${ANSIBLE_DIR}" observability-logs-rollback.yml; then ok "paso 2: la retirada termina ($(recap step2))"; else ko "paso 2"; tail -30 "${TMP}/step2.log" >&2; fi
check "paso 2: sin Loki" test "$(host "${OBS_PS}")" = "alloy,grafana,prometheus,"
check "paso 2: fuente de datos Loki retirada" test "$(host "${GRAFANA_DS}")" = prometheus
check "paso 2: sin configuración de Loki, drop-in ni variables de logs" \
  test "$(host 'echo "$(ls -d /opt/piedrazul-obs/loki /etc/systemd/journald.conf.d/60-piedrazul-obs.conf 2>/dev/null | wc -l) $(grep -c "^OBS_\(JOURNAL_GID\|LOG_PROJECT\|LOG_SERVICES\)=" /opt/piedrazul-obs/.env)"')" = "0 0"
check "paso 2: journald activo" test "$(host 'systemctl is-active systemd-journald')" = active
check "paso 2: volúmenes conservados" test "$(host "${VOLS}")" = "alloy_data,grafana_data,loki_data,prometheus_data,"
play step2-again "${ANSIBLE_DIR}" observability-logs-rollback.yml
check "paso 2: repetirlo no cambia nada ($(recap step2-again))" test "$(changed step2-again)" = 0
play step1-again "${TMP}/base/infra/ansible" observability.yml
check "paso 1: repetirlo tras la retirada no cambia nada ($(recap step1-again))" test "$(changed step1-again)" = 0
if [ "${PURGE:-0}" = 1 ]; then
  play purge "${ANSIBLE_DIR}" observability-logs-rollback.yml -e obs_logs_purge=true
  check "purge: borra los volúmenes de logs, conserva los de métricas ($(recap purge))" test "$(host "${VOLS}")" = "grafana_data,prometheus_data,"
fi

# ── 6. Reinstalación ─────────────────────────────────────────────────────────
if play reinstall "${ANSIBLE_DIR}" observability.yml; then ok "reinstalación: termina ($(recap reinstall))"; else ko "reinstalación"; tail -30 "${TMP}/reinstall.log" >&2; fi
play third "${ANSIBLE_DIR}" observability.yml
check "reinstalación: un run más sin cambios ($(recap third))" test "$(changed third)" = 0

if [ "${FAILS}" -eq 0 ]; then echo "OK"; exit 0; else echo "${FAILS} comprobación(es) fallida(s)"; exit 1; fi
