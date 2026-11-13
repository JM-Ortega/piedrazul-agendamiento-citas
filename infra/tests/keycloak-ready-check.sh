#!/usr/bin/env bash
# Readiness de Keycloak en la precondición de deploy (roles/app/tasks/keycloak_ready.yml).
#
# Defecto que cubre: el deploy leía el health de Keycloak UNA vez. Un Keycloak correcto, con la
# identidad esperada, que el daemon acababa de reiniciar estaba `starting` (su healthcheck vuelve a
# empezar) y el deploy fallaba sin mutar nada, aunque segundos después estuviera healthy.
#
# Ejecuta el task file real con Ansible y contenedores desechables en lugar de Keycloak (misma
# imagen base que postgres, fijada por digest, con un healthcheck que cada escenario controla).
# No usa Keycloak ni PostgreSQL de verdad, ni producción. Lo que prueba es la lógica de espera y de
# decisión sobre el estado de Docker; el arranque real de Keycloak lo cubre el harness del ciclo
# técnico.
#
# Uso: infra/tests/keycloak-ready-check.sh
# Requiere: docker, ansible-playbook con community.docker 5.x (ANSIBLE_COLLECTIONS_PATH), python3
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
IMAGE="$(sed -n 's/^FROM \(postgres:[^ ]*\).*/\1/p' "${ROOT}/infra/postgres/Dockerfile" | head -1)"
export ANSIBLE_COLLECTIONS_PATH="${ANSIBLE_COLLECTIONS_PATH:-${HOME}/.ansible/collections}"

for bin in docker ansible-playbook; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done
docker info > /dev/null 2>&1 || { echo "el daemon de Docker no responde" >&2; exit 2; }
docker image inspect "${IMAGE}" > /dev/null 2>&1 || docker pull -q "${IMAGE}" > /dev/null || { echo "falta la imagen ${IMAGE}" >&2; exit 2; }

TMP="$(mktemp -d)"
PREFIX="pzkcready-$$"
cleanup() { docker ps -aq --filter "label=pz-test=${PREFIX}" | xargs -r docker rm -f > /dev/null 2>&1 || true; rm -rf "${TMP}"; }
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
CANARY="pz-secret-canary-$$-$(date +%s)"

cat > "${TMP}/play.yml" << 'EOF'
---
- name: Precondición de Keycloak (keycloak_ready.yml)
  hosts: localhost
  connection: local
  gather_facts: false
  tasks:
    - block:
        - ansible.builtin.include_role:
            name: app
            tasks_from: keycloak_ready.yml
      always:
        - ansible.builtin.debug:
            msg: "READY_ATTEMPTS={{ app_deploy_keycloak_before_info.attempts | default('none') }}"
EOF
printf '[defaults]\n' > "${TMP}/ansible.cfg"

# run_ready <contenedor> <imagen esperada> <reintentos> <espera> [VAR=valor...] → salida en ${TMP}/out
run_ready() {
  local rc=0 container="$1" image="$2" retries="$3" delay="$4"
  shift 4
  ( cd "${TMP}" && env "$@" ANSIBLE_CONFIG="${TMP}/ansible.cfg" ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" ANSIBLE_NOCOLOR=1 \
      ANSIBLE_LOCALHOST_WARNING=false ANSIBLE_INVENTORY_UNPARSED_WARNING=false ANSIBLE_BECOME=false \
      ansible-playbook -i localhost, play.yml \
      -e "app_keycloak_ready_container=${container}" -e "{\"app_deploy_keycloak_image\": [\"${image}\"]}" \
      -e "app_keycloak_ready_retries=${retries}" -e "app_keycloak_ready_delay=${delay}" > "${TMP}/out" 2>&1 ) || rc=$?
  cat "${TMP}/out" >> "${TMP}/all.out"
  return "${rc}"
}
attempts() { sed -n 's/.*READY_ATTEMPTS=\([0-9a-z]*\).*/\1/p' "${TMP}/out" | tail -1; }
flat() { tr '\n' ' ' < "${TMP}/out" | tr -s ' '; }
# start <nombre> <args de health...> -- <comando>
start() {
  local name="$1"; shift
  local args=()
  while [ "$1" != "--" ]; do args+=("$1"); shift; done
  shift
  docker run -d --name "${name}" --label "pz-test=${PREFIX}" -e "SYNTHETIC_SECRET=${CANARY}" \
    --entrypoint sh "${args[@]}" "${IMAGE}" -c "$1" > /dev/null
}
status_of() { docker inspect "$1" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2> /dev/null; }
wait_for() { # <contenedor> <estado de health> [segundos]
  for _ in $(seq 1 "${3:-30}"); do [ "$(status_of "$1")" = "$2" ] && return 0; sleep 1; done
  return 1
}
image_of() { docker inspect "$1" --format '{{.Config.Image}}'; }

HC=(--health-interval 1s --health-timeout 2s)

# ── 1. starting → healthy: espera y pasa ────────────────────────────────────────────────────────
N="${PREFIX}-s1"
start "${N}" "${HC[@]}" --health-cmd 'test -f /tmp/ready' --health-retries 3 --health-start-period 60s -- 'sleep 5; touch /tmp/ready; sleep 600'
[ "$(status_of "${N}")" = starting ] || ko "escenario 1 no partió de starting"
ID1="$(docker inspect "${N}" --format '{{.Id}}')"; ST1="$(docker inspect "${N}" --format '{{.State.StartedAt}}')"
if run_ready "${N}" "$(image_of "${N}")" 30 1; then
  A="$(attempts)"
  { [ "${A}" -gt 1 ] && grep -q 'healthy' "${TMP}/out"; } && ok "starting → healthy: espera ($((A)) lecturas) y pasa" || ko "starting → healthy: lecturas=${A}"
else
  ko "starting → healthy falló: $(flat | cut -c1-300)"
fi
[ "$(docker inspect "${N}" --format '{{.Id}}')" = "${ID1}" ] && [ "$(docker inspect "${N}" --format '{{.State.StartedAt}}')" = "${ST1}" ] \
  && ok "la precondición solo lee: el contenedor es el mismo (Id e inicio) tras la espera" || ko "el contenedor cambió durante la espera"

# Control: una sola lectura (el comportamiento anterior del deploy) reproduce el incidente con el
# mismo escenario: Keycloak correcto, con la identidad esperada, todavía en arranque
N="${PREFIX}-s1b"
start "${N}" "${HC[@]}" --health-cmd 'test -f /tmp/ready' --health-retries 3 --health-start-period 60s -- 'sleep 30; touch /tmp/ready; sleep 600'
if run_ready "${N}" "$(image_of "${N}")" 0 1; then
  ko "control: con una sola lectura el contenedor en arranque debió fallar (no reproduce el incidente)"
else
  flat | grep -q 'health starting' && ok "control: con una sola lectura (comportamiento anterior) el mismo escenario falla en 'starting' — el incidente" \
    || ko "control: falló por otra causa: $(flat | cut -c1-300)"
fi
docker rm -f "${N}" > /dev/null 2>&1 || true

# ── 2. Ya healthy: pasa en la primera lectura, sin esperar ──────────────────────────────────────
N="${PREFIX}-s2"
start "${N}" "${HC[@]}" --health-cmd 'test -f /tmp/ready' --health-retries 3 -- 'touch /tmp/ready; sleep 600'
wait_for "${N}" healthy 30 || ko "escenario 2 no llegó a healthy"
if run_ready "${N}" "$(image_of "${N}")" 30 5; then
  [ "$(attempts)" = 1 ] && ok "ya healthy: pasa en la primera lectura (sin espera)" || ko "ya healthy: lecturas=$(attempts)"
else
  ko "ya healthy falló: $(flat | cut -c1-300)"
fi

# ── 3. starting → unhealthy: falla de inmediato, no al agotar el plazo ──────────────────────────
N="${PREFIX}-s3"
start "${N}" --health-interval 3s --health-timeout 2s --health-cmd false --health-retries 2 -- 'sleep 600'
[ "$(status_of "${N}")" = starting ] || ko "escenario 3 no partió de starting"
if run_ready "${N}" "$(image_of "${N}")" 30 2; then
  ko "starting → unhealthy no falló"
else
  A="$(attempts)"
  { [ "${A}" -gt 1 ] && [ "${A}" -lt 10 ] && flat | grep -q 'health unhealthy'; } \
    && ok "starting → unhealthy: espera mientras arranca y falla cerrado al pasar a unhealthy (${A} lecturas, plazo de 30), con el estado en el mensaje" \
    || ko "starting → unhealthy: lecturas=${A} / $(flat | cut -c1-300)"
fi

# ── 4. starting persistente: falla al agotar el plazo ───────────────────────────────────────────
N="${PREFIX}-s4"
start "${N}" "${HC[@]}" --health-cmd false --health-retries 2 --health-start-period 600s -- 'sleep 600'
if run_ready "${N}" "$(image_of "${N}")" 3 1; then
  ko "starting persistente no falló"
else
  { [ "$(attempts)" -ge 3 ] && flat | grep -q 'health starting' && flat | grep -q 'el plazo se agotó'; } \
    && ok "starting persistente: agota el plazo acotado (3 reintentos) y lo dice" || ko "starting persistente: lecturas=$(attempts) / $(flat | cut -c1-300)"
fi

# ── 5. Detenido (exited), ausente y reinicio continuo: fallan cerrado ───────────────────────────
N="${PREFIX}-s5"
start "${N}" "${HC[@]}" --health-cmd false -- 'exit 1'
for _ in $(seq 1 20); do [ "$(docker inspect "${N}" --format '{{.State.Running}}')" = false ] && break; sleep 1; done
if run_ready "${N}" "$(image_of "${N}")" 30 2; then ko "contenedor detenido no falló"
else { [ "$(attempts)" = 1 ] && flat | grep -q 'detenido'; } && ok "detenido: falla de inmediato (una lectura)" || ko "detenido: lecturas=$(attempts) / $(flat | cut -c1-200)"; fi

if run_ready "${PREFIX}-s6-no-existe" "${IMAGE}" 30 2; then ko "contenedor ausente no falló"
else { [ "$(attempts)" = 1 ] && flat | grep -q 'ausente'; } && ok "ausente: falla de inmediato (una lectura)" || ko "ausente: lecturas=$(attempts) / $(flat | cut -c1-200)"; fi

N="${PREFIX}-s7"
start "${N}" "${HC[@]}" --health-cmd false --health-start-period 600s --restart always -- 'sleep 1; exit 1'
if run_ready "${N}" "$(image_of "${N}")" 30 2; then ko "reinicio continuo no falló"
else { [ "$(attempts)" -lt 15 ]; } && ok "reinicio continuo: deja de esperar y falla cerrado ($(attempts) lecturas de 31)" || ko "reinicio continuo: lecturas=$(attempts)"; fi
docker rm -f "${N}" > /dev/null 2>&1 || true

# ── 6. Identidad distinta: falla de inmediato sin aceptar la readiness ──────────────────────────
N="${PREFIX}-s8"
start "${N}" "${HC[@]}" --health-cmd 'test -f /tmp/ready' --health-retries 3 --health-start-period 600s -- 'touch /tmp/ready; sleep 600'
wait_for "${N}" healthy 60 || true   # healthy no basta: la identidad decide
OTHER="quay.io/keycloak/keycloak:26.0.0@sha256:$(printf '0%.0s' $(seq 1 64))"
if run_ready "${N}" "${OTHER}" 30 2; then ko "identidad distinta no falló aunque está healthy"
else { [ "$(attempts)" = 1 ] && flat | grep -q 'creado desde'; } && ok "identidad distinta: falla de inmediato aunque el contenedor esté healthy" || ko "identidad distinta: lecturas=$(attempts) / $(flat | cut -c1-300)"; fi

N="${PREFIX}-s9"
start "${N}" "${HC[@]}" --health-cmd false --health-start-period 600s -- 'sleep 600'
if run_ready "${N}" "${OTHER}" 30 2; then ko "identidad distinta en arranque no falló"
else [ "$(attempts)" = 1 ] && ok "identidad distinta mientras arranca: no espera (una lectura) ni acepta la readiness" || ko "identidad distinta en arranque: lecturas=$(attempts)"; fi

# ── 6b. Un error del propio Docker no se disfraza de contenedor ausente ─────────────────────────
if run_ready "${PREFIX}-s6-no-existe" "${IMAGE}" 30 2 DOCKER_HOST=unix:///nonexistent/docker.sock; then ko "con el daemon inalcanzable la precondición no falló"
else { [ "$(attempts)" = none ] || [ "$(attempts)" = 1 ]; } && ! flat | grep -q 'está ausente\|ausente,' && ok "daemon inalcanzable: falla por el error del módulo (no como 'ausente') y sin reintentos" || ko "daemon inalcanzable: $(flat | cut -c1-300)"; fi

# ── 7. Sin fugas: el entorno del contenedor no aparece en ninguna salida de la prueba ─────────────────────────────
if grep -q "${CANARY}" "${TMP}/all.out"; then ko "el entorno del contenedor apareció en la salida"; else ok "el entorno del contenedor (secreto sintético) no aparece en la salida"; fi

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "${FAILS} comprobación(es) fallaron"
  exit 1
fi
echo "Todo correcto"
