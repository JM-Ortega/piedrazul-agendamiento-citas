#!/usr/bin/env bash
# Verificación de la configuración de logs EFECTIVA de los contenedores en producción
# (roles/app/tasks/verify_log_config.yml, incluida desde verify_running_images.yml).
#
# Defecto que cubre: un contenedor toma su LogConfig al crearse. postgres y keycloak se crearon
# antes de que el daemon corriera con daemon.json (el reinicio llegó al final del play) y quedaron
# json-file SIN rotación; Compose no los recrea (su hash no cambió) y ninguna verificación lo veía.
#
# Dos partes:
#   1. Lógica de la política con el task file real y resúmenes de contenedor sintéticos
#      (sin Docker): conforme, sin opciones (el incidente), opciones distintas, driver erróneo,
#      journald esperado e inesperado, daemon.json ausente / ilegible / sin rotación, otros
#      valores de daemon.json (la autoridad es el archivo, no 10m x 3) y etapas del ciclo de vida.
#   2. Con contenedores desechables reales y verify_running_images.yml completo: que el resumen lee
#      HostConfig.LogConfig de verdad, que con el gate desactivado (desarrollo, harnesses) un
#      contenedor sin rotación sigue sirviendo y que con el gate un contenedor recreado conforme pasa.
#
# No toca producción ni el daemon de quien lo corre: el daemon.json de la prueba es un archivo
# temporal. El journald real no puede crearse en un daemon de desarrollo (Docker Desktop no tiene
# journald): su caso se cubre con resúmenes sintéticos.
#
# Uso: infra/tests/log-config-check.sh
# Requiere: ansible-playbook con community.docker 5.x (ANSIBLE_COLLECTIONS_PATH), jq; docker para la parte 2
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
export ANSIBLE_COLLECTIONS_PATH="${ANSIBLE_COLLECTIONS_PATH:-${HOME}/.ansible/collections}"

for bin in ansible-playbook jq; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
# Solo se borran postgres y traefik si los creó esta prueba (DOCKER_PART se fija tras comprobar
# que no existían)
cleanup() { [ -z "${DOCKER_PART:-}" ] || docker rm -f postgres traefik > /dev/null 2>&1 || true; rm -rf "${TMP}"; }
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
printf '[defaults]\n' > "${TMP}/ansible.cfg"

play() { # <playbook.yml> <vars.json> → salida en ${TMP}/out
  ( cd "${TMP}" && env ANSIBLE_CONFIG="${TMP}/ansible.cfg" ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" ANSIBLE_NOCOLOR=1 \
      ANSIBLE_LOCALHOST_WARNING=false ANSIBLE_INVENTORY_UNPARSED_WARNING=false ANSIBLE_BECOME=false \
      ansible-playbook -i localhost, "$1" -e "@$2" > "${TMP}/out" 2>&1 )
}
flat() { tr '\n' ' ' < "${TMP}/out" | tr -s ' '; }

# ── Parte 1: la política con resúmenes sintéticos ───────────────────────────────────────────────
cat > "${TMP}/gate.yml" << 'EOF'
---
- name: Política de logs efectivos (verify_log_config.yml)
  hosts: localhost
  connection: local
  gather_facts: false
  tasks:
    - ansible.builtin.include_role:
        name: app
        tasks_from: verify_log_config.yml
EOF
write_daemon() { printf '%s' "$1" > "${TMP}/daemon.json"; }
DAEMON_OK='{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"},"live-restore":true}'
J10='{"log_type":"json-file","log_opts":{"max-size":"10m","max-file":"3"}}'
NOOPTS='{"log_type":"json-file","log_opts":{}}'
JOURNALD='{"log_type":"journald","log_opts":{"tag":"traefik"}}'

# gate <servicios json> <resumen json> [obs_log_services json] [ruta de daemon.json]
gate() {
  jq -n --argjson s "$1" --argjson r "$2" --argjson o "${3:-[\"traefik\"]}" --arg d "${4:-${TMP}/daemon.json}" \
    '{app_release_services: $s, app_release_running: $r, obs_log_services: $o, app_docker_daemon_json: $d}' > "${TMP}/vars.json"
  play gate.yml "${TMP}/vars.json"
}
expect_pass() { local d="$1"; shift; if gate "$@"; then ok "${d}"; else ko "${d}: $(flat | cut -c1-400)"; fi; }
expect_fail() { # <descripción> <texto esperado en el mensaje> <args de gate...>
  local d="$1" want="$2"; shift 2
  if gate "$@"; then ko "${d} (no falló)"; elif flat | grep -qF -- "${want}"; then ok "${d}"; else ko "${d}: mensaje sin '${want}': $(flat | cut -c1-400)"; fi
}

write_daemon "${DAEMON_OK}"
expect_pass "conforme: json-file con max-size y max-file de daemon.json, y journald donde la política lo declara" \
  '["postgres","keycloak","traefik"]' "{\"postgres\":${J10},\"keycloak\":${J10},\"traefik\":${JOURNALD}}"
expect_fail "sin opciones de rotación (el incidente: contenedor creado antes que daemon.json) falla y nombra el contenedor, lo exigido y la guía" \
  "reconciliar-logs-contenedores.md" '["postgres"]' "{\"postgres\":${NOOPTS}}"
expect_fail "…el mensaje dice qué opciones difieren" "max-size, max-file" '["postgres"]' "{\"postgres\":${NOOPTS}}"
expect_fail "opciones distintas a daemon.json (max-size 50m) fallan" "max-size" '["keycloak"]' \
  '{"keycloak":{"log_type":"json-file","log_opts":{"max-size":"50m","max-file":"3"}}}'
expect_fail "falta una de las dos opciones (solo max-size) falla" "max-file" '["postgres"]' \
  '{"postgres":{"log_type":"json-file","log_opts":{"max-size":"10m"}}}'
expect_fail "driver distinto del de daemon.json (local) falla" "usa local" '["postgres"]' \
  '{"postgres":{"log_type":"local","log_opts":{"max-size":"10m","max-file":"3"}}}'
expect_fail "journald esperado y el contenedor usa json-file falla" "exige journald" '["traefik"]' "{\"traefik\":${J10}}"
expect_fail "journald inesperado (el servicio hereda daemon.json) falla" "exige json-file" '["postgres"]' "{\"postgres\":${JOURNALD}}"
expect_pass "una opción adicional del contenedor no es una discrepancia" \
  '["postgres"]' '{"postgres":{"log_type":"json-file","log_opts":{"max-size":"10m","max-file":"3","labels":"x"}}}'

# La autoridad es daemon.json, no 10m x 3: otros valores declarados se exigen tal cual
write_daemon '{"log-driver":"json-file","log-opts":{"max-size":"5m","max-file":"2"}}'
expect_pass "otros valores en daemon.json (5m x 2) se exigen tal cual: el contenedor que los tiene pasa" \
  '["postgres"]' '{"postgres":{"log_type":"json-file","log_opts":{"max-size":"5m","max-file":"2"}}}'
expect_fail "…y uno con 10m x 3 deja de ser conforme" "max-size, max-file" '["postgres"]' "{\"postgres\":${J10}}"

# daemon.json ausente, ilegible o sin rotación: falla cerrado con la gate activada
expect_fail "daemon.json ausente falla (gate activada)" "no se pudo leer" '["postgres"]' "{\"postgres\":${J10}}" '["traefik"]' "${TMP}/no-existe.json"
printf '{ esto no es json' > "${TMP}/daemon.json"
expect_fail "daemon.json ilegible falla" "no es un JSON válido" '["postgres"]' "{\"postgres\":${J10}}"
write_daemon '{"live-restore":true}'
expect_fail "daemon.json sin rotación declarada falla" "no declara log-driver json-file con log-opts max-size y max-file" '["postgres"]' "{\"postgres\":${J10}}"
write_daemon '{"log-driver":"json-file"}'
expect_fail "daemon.json con json-file y sin log-opts falla" "no declara log-driver json-file" '["postgres"]' "{\"postgres\":${J10}}"

# Etapas del ciclo de vida: solo se juzgan los servicios que la verificación pide (los que la
# identidad ya probó que existen y corren); los que todavía no se esperan no producen un falso fallo
write_daemon "${DAEMON_OK}"
expect_pass "converge antes del deploy: se juzgan postgres/keycloak/traefik y un backend aún inexistente no estorba" \
  '["postgres","keycloak","traefik"]' "{\"postgres\":${J10},\"keycloak\":${J10},\"traefik\":${JOURNALD}}"
expect_fail "después del deploy el backend esperado no se escapa de la verificación" "backend usa json-file {}" '["postgres","backend"]' \
  "{\"postgres\":${J10},\"backend\":${NOOPTS}}"

# ── Parte 2: contenedores reales y verify_running_images.yml completo ───────────────────────────
if docker info > /dev/null 2>&1; then
  IMAGE="$(sed -n 's/^FROM \(postgres:[^ ]*\).*/\1/p' "${ROOT}/infra/postgres/Dockerfile" | head -1)"
  DIGEST="${IMAGE#*@}"
  REF="postgres@${DIGEST}"
  if docker ps -a --format '{{.Names}}' | grep -qxE 'postgres|traefik'; then
    echo "ya existen contenedores postgres o traefik; no se corre la parte 2" >&2
  else
    DOCKER_PART=1
    docker image inspect "${IMAGE}" > /dev/null 2>&1 || docker pull -q "${IMAGE}" > /dev/null
    cat > "${TMP}/real.yml" << 'EOF'
---
- name: verify_running_images.yml con contenedores reales
  hosts: localhost
  connection: local
  gather_facts: false
  tasks:
    - ansible.builtin.include_role:
        name: app
        tasks_from: verify_running_images.yml
EOF
    real_vars() { # <app_verify_log_config|"unset"> <servicios json>
      jq -n --arg r "${REF}" --argjson s "$2" --arg d "${TMP}/daemon.json" --arg g "$1" \
        '{app_release_services: $s, postgres_image_ref: $r, traefik_image_ref: $r, obs_log_services: ["traefik"], app_docker_daemon_json: $d}
         + (if $g == "unset" then {} else {app_verify_log_config: ($g == "true")} end)' > "${TMP}/real-vars.json"
    }
    run() { docker run -d --name "$1" --entrypoint sh "${@:2}" "${REF}" -c 'sleep 600' > /dev/null; }
    write_daemon "${DAEMON_OK}"

    # El estado del incidente: postgres creado sin las opciones de rotación
    run postgres --log-driver json-file
    real_vars true '["postgres"]'
    if play real.yml "${TMP}/real-vars.json"; then ko "contenedor real sin rotación + gate: no falló"
    elif flat | grep -qF "postgres usa json-file {}"; then ok "contenedor real creado sin rotación (el incidente) con la gate activada: falla; el resumen lee HostConfig.LogConfig"
    else ko "contenedor real sin rotación: $(flat | cut -c1-400)"; fi

    real_vars unset '["postgres"]'
    if play real.yml "${TMP}/real-vars.json"; then ok "sin la variable (desarrollo, harnesses): el mismo contenedor sin rotación sigue sirviendo"; else ko "sin la gate falló: $(flat | cut -c1-300)"; fi
    real_vars false '["postgres"]'
    if play real.yml "${TMP}/real-vars.json"; then ok "app_verify_log_config=false: el mismo contenedor sin rotación sigue sirviendo"; else ko "gate falsa falló: $(flat | cut -c1-300)"; fi

    # Recreado conforme (como lo deja la reconciliación con el daemon ya configurado)
    docker rm -f postgres > /dev/null
    run postgres --log-driver json-file --log-opt max-size=10m --log-opt max-file=3
    real_vars true '["postgres"]'
    if play real.yml "${TMP}/real-vars.json"; then ok "contenedor real recreado con la rotación: pasa la identidad y los logs"; else ko "recreado conforme falló: $(flat | cut -c1-400)"; fi

    # Etapas del ciclo de vida con contenedores reales: un servicio que la verificación ya pide pero
    # que todavía no existe falla por su identidad (el mensaje de siempre), no por un falso fallo de
    # logs; y uno que no se pide no se juzga aunque tampoco exista
    real_vars true '["postgres","traefik"]'
    if play real.yml "${TMP}/real-vars.json"; then ko "un servicio esperado y ausente (traefik) debió fallar"
    elif flat | grep -qF "IDENTIDAD EN EJECUCIÓN: traefik" && ! flat | grep -qF "LOGS:"; then ok "servicio esperado y aún no creado (traefik): falla por su identidad, sin un falso fallo de logs"
    else ko "traefik ausente: $(flat | cut -c1-400)"; fi
    real_vars true '["postgres"]'
    if play real.yml "${TMP}/real-vars.json"; then ok "servicio que todavía no se espera (traefik no está en la lista): no se juzga ni estorba"; else ko "traefik fuera de la lista estorbó: $(flat | cut -c1-300)"; fi

    # traefik debe usar journald por política; aquí usa json-file (un daemon de desarrollo no tiene journald)
    run traefik --log-driver json-file --log-opt max-size=10m --log-opt max-file=3
    real_vars true '["postgres","traefik"]'
    if play real.yml "${TMP}/real-vars.json"; then ko "traefik con json-file debió fallar (política journald)"
    elif flat | grep -qF "traefik usa json-file"; then ok "contenedor real con un driver distinto del declarado para su servicio (traefik: journald) falla"
    else ko "traefik: $(flat | cut -c1-400)"; fi
  fi
else
  echo "SKIP  parte 2: el daemon de Docker no responde (contenedores reales)"
fi

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "${FAILS} comprobación(es) fallaron"
  exit 1
fi
echo "Todo correcto"
