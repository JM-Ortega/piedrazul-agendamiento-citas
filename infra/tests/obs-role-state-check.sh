#!/usr/bin/env bash
# Estado deseado persistente de S3 en el rol observability: las tareas reales
# access_alerts_preflight.yml y access_alerts_state.yml, en local, sobre un directorio temporal que
# hace de /opt/piedrazul-obs. Sin contenedores del stack ni host remoto; solo `docker network
# inspect` (la red de entrada de prueba). Webhook y correos sintéticos; el webhook nunca se imprime.
#
# Contrato verificado (docs/operations/runbooks/grafana-acceso.md, "Estado deseado"):
#   dado (-e) > guardado en .env del host > inicial. Un run sin -e no cambia nada; desactivar es
#   explícito y conserva identificadores y listas; volver a habilitar solo necesita el interruptor.
#   El webhook es aparte: se conserva sin la variable, se rota con ella y su cambio pide recrear
#   Alertmanager. Modo privado (acceso deshabilitado) sin la red de entrada.
#
# Uso: infra/tests/obs-role-state-check.sh
# Requiere: ansible-playbook, docker (solo para crear e inspeccionar una red), jq.
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
for bin in ansible-playbook docker jq sha256sum; do command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }; done

TMP="$(mktemp -d)"
H="${TMP}/obs"
EDGE="pzstate-$$-edge"
FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
cleanup() { docker network rm "${EDGE}" >/dev/null 2>&1 || true; [ "${KEEP:-0}" = 1 ] || rm -rf "${TMP}"; }
trap cleanup EXIT

rnd() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
PASSWORD="synthetic-$(rnd 12)"
SECRET_A="hookA$(rnd 20)"; SECRET_B="hookB$(rnd 20)"
HOOK_A="https://discord.com/api/webhooks/123456789012345678/${SECRET_A}"
HOOK_B="https://discord.com/api/webhooks/123456789012345678/${SECRET_B}"
AUD="$(rnd 32)"
TEAM=piedrazul.cloudflareaccess.com
mkdir -p "${H}"
# .env como lo crea el rol la primera vez (contraseña y red de la aplicación)
printf 'OBS_APP_NETWORK=piedrazul_net\nGRAFANA_ADMIN_PASSWORD=%s\n' "${PASSWORD}" > "${H}/.env"
chmod 600 "${H}/.env"

printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"
cat > "${TMP}/state.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_role: {name: observability, tasks_from: access_alerts_preflight.yml}
    - ansible.builtin.include_role: {name: observability, tasks_from: access_alerts_state.yml}
    - ansible.builtin.copy:
        dest: "${TMP}/facts.json"
        content: "{{ {'access_on': obs_grafana_access_on, 'files': obs_compose_files, 'receiver': obs_s3_receiver,
                      'environment': obs_s3_environment, 'recreate': obs_alertmanager_recreate} | to_json }}"
EOF
run() { # <etiqueta> [args...] → 0/1; log en TMP/<etiqueta>.log
  local tag="$1"; shift
  rm -f "${TMP}/facts.json"
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 ANSIBLE_ROLES_PATH="${ANSIBLE_DIR}/roles" \
    OBS_DISCORD_WEBHOOK_URL="${HOOK:-}" ansible-playbook -i "${TMP}/hosts.yml" "${TMP}/state.yml" \
      -e "obs_dir=${H}" -e "obs_env_owner=$(id -un)" -e "obs_secrets_owner=$(id -un)" \
      -e "obs_alertmanager_uid=$(id -u)" -e "obs_edge_network=${EDGE}" "$@" </dev/null > "${TMP}/${tag}.log" 2>&1
}
# Cambios solo de las tareas de estado (la copia de facts.json siempre cambia)
changed() { local c; c="$(sed -nE 's/^vps\s+:.*changed=([0-9]+).*/\1/p' "${TMP}/$1.log")"; echo $(( ${c:-99} - 1 )); }
envv() { sed -n "s/^$1=//p" "${H}/.env"; }
fact() { jq -r "$1" "${TMP}/facts.json"; }
secret() { cat "${H}/secrets/discord_webhook_url"; }
pw_intact() { test "$(envv GRAFANA_ADMIN_PASSWORD)" = "${PASSWORD}"; }
no_leak() { ! grep -qE "${SECRET_A}|${SECRET_B}|${PASSWORD}" "${TMP}/$1.log"; }
ACCESS=(-e obs_grafana_access_enabled=true -e obs_grafana_public_domain=grafana.piedrazul.org
        -e "obs_grafana_access_team_domain=${TEAM}" -e "obs_grafana_access_aud=${AUD}"
        -e '{"obs_grafana_access_viewers": ["viewer.one@ops.example.org", "viewer.two@ops.example.org"], "obs_grafana_access_editors": []}')

# ── 1. Instalación inicial (sin variables de S3), sin la red de entrada ─────────
if run init; then ok "inicial: termina sin la red de entrada (modo privado)"; else ko "inicial: termina"; tail -5 "${TMP}/init.log"; fi
check "inicial: acceso deshabilitado, receptor none, solo compose.yml" \
  test "$(envv GRAFANA_ACCESS_ENABLED) $(envv OBS_ALERT_RECEIVER) $(envv COMPOSE_FILE)" = "false none compose.yml"
check "inicial: sin GRAFANA_ROOT_URL (Grafana en el loopback)" bash -c "! grep -q '^GRAFANA_ROOT_URL=' '${H}/.env'"
check "inicial: archivo del webhook vacío 0400 en directorio 0700" \
  bash -c "test ! -s '${H}/secrets/discord_webhook_url' && test \"\$(stat -c %a '${H}/secrets/discord_webhook_url') \$(stat -c %a '${H}/secrets')\" = '400 700'"
check "inicial: la contraseña de Grafana intacta" pw_intact

# ── 2. Activación ───────────────────────────────────────────────────────────
if ! HOOK="${HOOK_A}" run activate-nonet "${ACCESS[@]}" -e obs_alert_receiver=discord -e obs_environment=staging \
   && grep -q "No existe la red interna ${EDGE}" "${TMP}/activate-nonet.log" && pw_intact \
   && test "$(envv GRAFANA_ACCESS_ENABLED)" = false; then
  ok "activación sin la red de entrada: falla antes de escribir nada"
else ko "activación sin la red de entrada: falla antes de escribir nada"; fi
docker network create --internal "${EDGE}" >/dev/null
if HOOK="${HOOK_A}" run activate "${ACCESS[@]}" -e obs_alert_receiver=discord -e obs_environment=staging; then
  ok "activación: termina (changed=$(changed activate))"; else ko "activación"; tail -5 "${TMP}/activate.log"; fi
check "activación: .env con el estado deseado" \
  test "$(envv GRAFANA_ACCESS_ENABLED)|$(envv GRAFANA_PUBLIC_DOMAIN)|$(envv GRAFANA_ROOT_URL)|$(envv GRAFANA_ACCESS_TEAM_DOMAIN)|$(envv GRAFANA_ACCESS_AUD)|$(envv GRAFANA_ACCESS_VIEWERS)|$(envv GRAFANA_ACCESS_EDITORS)|$(envv OBS_ALERT_RECEIVER)|$(envv OBS_ENVIRONMENT)|$(envv COMPOSE_FILE)" \
       = "true|grafana.piedrazul.org|https://grafana.piedrazul.org/|${TEAM}|${AUD}|viewer.one@ops.example.org,viewer.two@ops.example.org||discord|staging|compose.yml:compose.access.yml"
check "activación: webhook escrito y Alertmanager a recrear" bash -c "test \"\$(cat '${H}/secrets/discord_webhook_url')\" = '${HOOK_A}' && test \"\$(jq -r .recreate '${TMP}/facts.json')\" = true"
check "activación: sin el webhook ni la contraseña en la salida" no_leak activate
check "activación: la contraseña de Grafana intacta" pw_intact

# ── 3. Mantenimiento sin -e ni webhook ──────────────────────────────────────
cp "${H}/.env" "${TMP}/env.after-activate"
if run rerun; then ok "mantenimiento: termina sin -e"; else ko "mantenimiento: termina"; tail -5 "${TMP}/rerun.log"; fi
check "mantenimiento: nada cambia (changed=$(changed rerun)) y .env idéntico" \
  bash -c "test '$(changed rerun)' = 0 && cmp -s '${TMP}/env.after-activate' '${H}/.env'"
check "mantenimiento: sigue con Access y discord; webhook conservado; sin recrear" \
  test "$(fact .access_on) $(fact .receiver) $(fact .files[1]) $(secret | sha256sum | cut -c1-12) $(fact .recreate)" = "true discord compose.access.yml $(printf '%s' "${HOOK_A}" | sha256sum | cut -c1-12) false"

# ── 4. Rotación del webhook ─────────────────────────────────────────────────
if HOOK="${HOOK_B}" run rotate; then ok "rotación: termina"; else ko "rotación"; fi
check "rotación: webhook nuevo, Alertmanager a recrear, .env sin cambios" \
  bash -c "test \"\$(cat '${H}/secrets/discord_webhook_url')\" = '${HOOK_B}' && test \"\$(jq -r .recreate '${TMP}/facts.json')\" = true && cmp -s '${TMP}/env.after-activate' '${H}/.env'"
check "rotación: sin el webhook en la salida" no_leak rotate
HOOK="${HOOK_B}" run rotate-same
check "rotación: repetir con el mismo webhook no recrea (changed=$(changed rotate-same))" \
  test "$(changed rotate-same) $(fact .recreate)" = "0 false"

# ── 5. Desactivación explícita ──────────────────────────────────────────────
if run disable -e obs_grafana_access_enabled=false -e obs_alert_receiver=none; then ok "desactivación: termina"; else ko "desactivación"; fi
check "desactivación: acceso false, sin root_url pública, solo compose.yml, receptor none" \
  bash -c "test \"$(envv GRAFANA_ACCESS_ENABLED) $(envv COMPOSE_FILE) $(envv OBS_ALERT_RECEIVER)\" = 'false compose.yml none' && ! grep -q '^GRAFANA_ROOT_URL=' '${H}/.env'"
check "desactivación: conserva identificadores, listas, entorno y webhook" \
  test "$(envv GRAFANA_ACCESS_TEAM_DOMAIN)|$(envv GRAFANA_ACCESS_AUD)|$(envv GRAFANA_ACCESS_VIEWERS)|$(envv OBS_ENVIRONMENT)|$(secret)" \
       = "${TEAM}|${AUD}|viewer.one@ops.example.org,viewer.two@ops.example.org|staging|${HOOK_B}"
docker network rm "${EDGE}" >/dev/null
if run disabled-rerun; then ok "desactivado: un run sin -e termina sin la red de entrada"; else ko "desactivado: run sin -e"; fi
check "desactivado: sigue desactivado y nada cambia (changed=$(changed disabled-rerun))" \
  test "$(changed disabled-rerun) $(envv GRAFANA_ACCESS_ENABLED) $(envv OBS_ALERT_RECEIVER)" = "0 false none"

# ── 6. Volver a habilitar solo con los interruptores ─────────────────────────
docker network create --internal "${EDGE}" >/dev/null
if run reenable -e obs_grafana_access_enabled=true -e obs_alert_receiver=discord; then ok "rehabilitación: termina solo con los interruptores"; else ko "rehabilitación"; tail -5 "${TMP}/reenable.log"; fi
check "rehabilitación: vuelve exactamente al estado activado" cmp -s "${TMP}/env.after-activate" "${H}/.env"

# ── 7. Rechazos ─────────────────────────────────────────────────────────────
reject() { # <etiqueta> <marca> [args...]
  local tag="$1" mark="$2"; shift 2
  cp "${H}/.env" "${TMP}/env.before-${tag}"
  if ! run "${tag}" "$@" && grep -q "${mark}" "${TMP}/${tag}.log" && cmp -s "${TMP}/env.before-${tag}" "${H}/.env" && no_leak "${tag}"; then
    ok "rechazo sin escribir: ${tag}"; else ko "rechazo sin escribir: ${tag}"; fi
}
reject discord-sin-entorno NOTIFICACIONES -e obs_environment=
reject entorno-invalido NOTIFICACIONES -e 'obs_environment=Staging Prod'
reject habilitado-no-booleano ACCESO -e obs_grafana_access_enabled=maybe
reject lista-no-lista ACCESO -e obs_grafana_access_viewers=viewer.one@ops.example.org
# .env existente pero ilegible: el rol falla antes de cambiar nada (no vuelve al estado inicial)
cp "${H}/.env" "${TMP}/env.before-unreadable"; SECRET_BEFORE="$(secret | sha256sum)"
chmod 000 "${H}/.env"
if ! run unreadable && grep -q 'ESTADO DESEADO' "${TMP}/unreadable.log" && ! grep -q 'Mantener el estado deseado' "${TMP}/unreadable.log" \
   && no_leak unreadable; then
  ok "estado ilegible: .env existente sin permiso de lectura → falla antes de escribir"
else ko "estado ilegible: .env existente sin permiso de lectura → falla antes de escribir"; fi
chmod 600 "${H}/.env"
check "estado ilegible: .env y webhook intactos (no se volvió al estado inicial)" \
  bash -c "cmp -s '${TMP}/env.before-unreadable' '${H}/.env' && test \"\$(sha256sum < '${H}/secrets/discord_webhook_url')\" = '${SECRET_BEFORE}' && grep -qx 'GRAFANA_ACCESS_ENABLED=true' '${H}/.env'"
# .env que no es un archivo regular
mv "${H}/.env" "${TMP}/env.saved"; mkdir "${H}/.env"
if ! run envdir && grep -q 'ESTADO DESEADO' "${TMP}/envdir.log"; then ok "estado ilegible: .env que es un directorio → falla antes de escribir"; else ko "estado ilegible: .env que es un directorio"; fi
rmdir "${H}/.env"; mv "${TMP}/env.saved" "${H}/.env"
printf 'GRAFANA_ACCESS_AUD=NOT-HEX\n' >> "${H}/.env"
reject guardado-invalido ACCESO
sed -i '$d' "${H}/.env"

echo
if [ "${FAILS}" -eq 0 ]; then echo "OK: todas las comprobaciones pasaron"; exit 0; else echo "FALLAS: ${FAILS}"; exit 1; fi
