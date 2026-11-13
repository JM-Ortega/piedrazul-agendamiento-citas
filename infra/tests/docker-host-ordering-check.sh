#!/usr/bin/env bash
# Orden del reinicio del daemon de Docker en el role docker_host.
#
# Defecto que cubre: el handler `Restart Docker daemon` se notificaba al configurar Docker pero
# Ansible lo ejecutaba al FINAL del play de converge, cuando postgres, keycloak y traefik ya
# estaban creados y verificados. El reinicio los detuvo a todos (live-restore solo protege si el
# daemon que se detiene ya lo tenía) y dejó sin valor las verificaciones de readiness anteriores.
#
# Ejecuta Ansible de verdad contra localhost, sin Docker, sin root y sin red. El role real se
# transforma en un fixture: se conservan sus tareas de orden (notify, meta, espera del daemon,
# verificación) tal cual y se sustituye por un registro lo que tocaría el sistema (apt, archivos,
# systemd, `docker info`). Lo que prueba es el motor de handlers de Ansible sobre la estructura
# real del role; no prueba apt, systemd ni Docker (verificación en vivo).
#
# Uso: infra/tests/docker-host-ordering-check.sh
# Requiere: ansible-playbook, python3 con PyYAML (el de ansible)
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANSIBLE_DIR="${ROOT}/infra/ansible"
ROLE="${ANSIBLE_DIR}/roles/docker_host"

PY=""
for candidate in python3 /usr/bin/python3; do
  if command -v "${candidate}" > /dev/null && "${candidate}" -c 'import yaml' 2> /dev/null; then PY="${candidate}"; break; fi
done
[ -n "${PY}" ] || { echo "falta python3 con PyYAML" >&2; exit 2; }
command -v ansible-playbook > /dev/null || { echo "falta ansible-playbook" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

# ── Fixture: el role real, con lo que toca el sistema sustituido por un registro ─────────────
# build_fixture <directorio> [sin-flush]
#   Conserva: las tareas con notify (como tareas que cambian o no según STUB_CHANGED), la meta
#   flush_handlers, la espera del daemon (reintentos, until y delay reales; solo cambia el comando)
#   y la verificación de la configuración. Descarta el resto (apt, GPG, grupos, directorios).
#   "sin-flush" quita la meta: reproduce el orden anterior al arreglo.
build_fixture() {
  "${PY}" - "${ROLE}" "$1" "${2:-}" << 'PYEOF'
import sys, os, yaml
role, out, variant = sys.argv[1:4]
tasks = yaml.safe_load(open(os.path.join(role, 'tasks/main.yml')))
handlers = yaml.safe_load(open(os.path.join(role, 'handlers/main.yml')))
LOG = "{{ lookup('ansible.builtin.env', 'STUB_LOG') }}"
stub = []
for t in tasks:
    name = t.get('name', '')
    if 'ansible.builtin.meta' in t:
        if variant == 'sin-flush':
            continue
        stub.append(t)
    elif 'notify' in t:
        stub.append({
            'name': name,
            'ansible.builtin.shell': 'echo "TASK %s" >> "%s"' % (name.replace('"', ''), LOG),
            'changed_when': "{{ lookup('ansible.builtin.env', 'STUB_CHANGED') == 'true' }}",
            'notify': t['notify'],
        })
    elif t.get('register') == 'docker_host_info':
        t = dict(t)
        t.pop('ansible.builtin.command')
        # Mismo until / retries / delay que el role real salvo STUB_RETRIES / STUB_DELAY (solo la
        # prueba del plazo agotado los reduce para no esperar 30 s)
        t['retries'] = "{{ lookup('ansible.builtin.env', 'STUB_RETRIES') | default(%s, true) | int }}" % t['retries']
        t['delay'] = "{{ lookup('ansible.builtin.env', 'STUB_DELAY') | default(%s, true) | int }}" % t['delay']
        t['ansible.builtin.shell'] = (
            'n=$(cat "$STUB_COUNTER" 2>/dev/null || echo 0); echo $((n+1)) > "$STUB_COUNTER"; '
            'case "$STUB_INFO" in '
            'ok) echo "true json-file" ;; '
            'flaky) [ "$n" -ge 2 ] && echo "true json-file" || exit 1 ;; '
            'down) exit 1 ;; '
            'wrong) echo "false json-file" ;; '
            'esac')
        t['environment'] = {
            'STUB_COUNTER': "{{ lookup('ansible.builtin.env', 'STUB_COUNTER') }}",
            'STUB_INFO': "{{ lookup('ansible.builtin.env', 'STUB_INFO') }}",
        }
        stub.append(t)
    elif 'ansible.builtin.assert' in t:
        stub.append(t)
os.makedirs(os.path.join(out, 'roles/docker_host/tasks'))
os.makedirs(os.path.join(out, 'roles/docker_host/handlers'))
yaml.safe_dump(stub, open(os.path.join(out, 'roles/docker_host/tasks/main.yml'), 'w'), allow_unicode=True, sort_keys=False)
h = []
for item in handlers:
    h.append({
        'name': item['name'],
        'ansible.builtin.shell': 'echo "HANDLER" >> "%s"; test "{{ lookup(\'ansible.builtin.env\', \'STUB_HANDLER_FAIL\') }}" != "true"' % LOG,
    })
yaml.safe_dump(h, open(os.path.join(out, 'roles/docker_host/handlers/main.yml'), 'w'), allow_unicode=True, sort_keys=False)
play = [{
    'name': 'Converge simulado (roles y luego tareas, como converge.yml)',
    'hosts': 'localhost', 'connection': 'local', 'gather_facts': False,
    'roles': [{'role': 'docker_host'}],
    'tasks': [{
        'name': 'Tarea de aplicación (crea contenedores)',
        'ansible.builtin.shell': 'echo "APP" >> "%s"' % LOG,
    }],
}]
yaml.safe_dump(play, open(os.path.join(out, 'play.yml'), 'w'), allow_unicode=True, sort_keys=False)
PYEOF
}

# run_fixture <directorio> <log> [VAR=valor...] → código de salida de ansible-playbook
run_fixture() {
  local dir="$1" log="$2"; shift 2
  : > "${log}"
  rm -f "${dir}/counter"
  printf '[defaults]\n' > "${dir}/ansible.cfg"
  ( cd "${dir}" && env STUB_LOG="${log}" STUB_COUNTER="${dir}/counter" STUB_CHANGED=true STUB_INFO=ok \
      "$@" ANSIBLE_CONFIG="${dir}/ansible.cfg" ANSIBLE_ROLES_PATH="${dir}/roles" ANSIBLE_NOCOLOR=1 \
      ANSIBLE_LOCALHOST_WARNING=false ANSIBLE_INVENTORY_UNPARSED_WARNING=false ANSIBLE_BECOME=false \
      ansible-playbook -i localhost, play.yml > "${dir}/ansible.out" 2>&1 )
}
order_of() { grep -v '^TASK ' "$1" | paste -sd, -; }   # p. ej. HANDLER,APP

FIXED="${TMP}/fixed";  mkdir -p "${FIXED}";  build_fixture "${FIXED}"
BEFORE="${TMP}/before"; mkdir -p "${BEFORE}"; build_fixture "${BEFORE}" sin-flush

# ── 1. El orden: el reinicio ocurre antes de cualquier tarea de aplicación ──────────────────────
LOG="${TMP}/s1.log"
if run_fixture "${FIXED}" "${LOG}"; then
  [ "$(order_of "${LOG}")" = "HANDLER,APP" ] && ok "con el arreglo, el daemon se reinicia ANTES de la primera tarea de aplicación (y una sola vez aunque se notifique dos veces)" \
    || ko "orden con el arreglo: $(order_of "${LOG}")"
else
  ko "el fixture arreglado no corrió (ver ${FIXED}/ansible.out)"; sed 's/^/  | /' "${FIXED}/ansible.out" | tail -20
fi

LOG="${TMP}/s2.log"
if run_fixture "${BEFORE}" "${LOG}"; then
  [ "$(order_of "${LOG}")" = "APP,HANDLER" ] && ok "sin el flush se reproduce el defecto: la tarea de aplicación corre ANTES del reinicio (la prueba lo detecta)" \
    || ko "el orden anterior no se reprodujo: $(order_of "${LOG}")"
else
  ko "el fixture anterior al arreglo no corrió"
fi

# ── 2. Sin cambios en daemon.json no hay reinicio ───────────────────────────────────────────────
LOG="${TMP}/s3.log"
if run_fixture "${FIXED}" "${LOG}" STUB_CHANGED=false; then
  [ "$(order_of "${LOG}")" = "APP" ] && ok "un converge sin cambios no reinicia el daemon" || ko "reinicio sin cambios: $(order_of "${LOG}")"
else
  ko "converge sin cambios no corrió"
fi

# ── 3. Un reinicio que falla detiene la secuencia ───────────────────────────────────────────────
LOG="${TMP}/s4.log"
if run_fixture "${FIXED}" "${LOG}" STUB_HANDLER_FAIL=true; then
  ko "un handler que falla no detuvo el play"
else
  [ "$(order_of "${LOG}")" = "HANDLER" ] && ok "si el reinicio falla, el play se detiene y no se crea ningún contenedor" || ko "tras el fallo del handler: $(order_of "${LOG}")"
fi

# ── 4. Readiness del daemon: por estado, acotada ────────────────────────────────────────────────
LOG="${TMP}/s5.log"
if run_fixture "${FIXED}" "${LOG}" STUB_INFO=flaky STUB_DELAY=0; then
  [ "$(order_of "${LOG}")" = "HANDLER,APP" ] && [ "$(cat "${FIXED}/counter")" -ge 3 ] \
    && ok "el daemon que tarda en responder se espera por reintentos ($(cat "${FIXED}/counter") consultas)" \
    || ko "espera por reintentos: $(order_of "${LOG}") / consultas $(cat "${FIXED}/counter" 2> /dev/null)"
else
  ko "la espera del daemon con reintentos falló"
fi

LOG="${TMP}/s6.log"
if run_fixture "${FIXED}" "${LOG}" STUB_INFO=down STUB_RETRIES=3 STUB_DELAY=0; then
  ko "un daemon que nunca responde no detuvo el play"
else
  [ "$(order_of "${LOG}")" = "HANDLER" ] && [ "$(cat "${FIXED}/counter")" = 4 ] \
    && ok "un daemon que no responde agota los reintentos (el intento inicial más 3 reintentos) y detiene el play antes de la aplicación" \
    || ko "plazo agotado: $(order_of "${LOG}") / consultas $(cat "${FIXED}/counter" 2> /dev/null)"
fi

LOG="${TMP}/s7.log"
if run_fixture "${FIXED}" "${LOG}" STUB_INFO=wrong; then
  ko "un daemon que responde sin la configuración de daemon.json no detuvo el play"
else
  grep -q "no corre con /etc/docker/daemon.json" "${FIXED}/ansible.out" && [ "$(order_of "${LOG}")" = "HANDLER" ] \
    && ok "un daemon que responde sin live-restore/json-file (reinicio no aplicado) falla con un mensaje accionable" \
    || ko "configuración efectiva errónea no se diagnosticó"
fi

# ── 5. Propiedades del role real (no del fixture) ───────────────────────────────────────────────
real_prop() { # <expresión Python sobre tasks, handlers> → verdadera
  "${PY}" - "${ROLE}" "$1" << 'PYEOF'
import sys, os, yaml
role, expr = sys.argv[1:3]
tasks = yaml.safe_load(open(os.path.join(role, 'tasks/main.yml')))
handlers = yaml.safe_load(open(os.path.join(role, 'handlers/main.yml')))
sys.exit(0 if eval(expr) else 1)
PYEOF
}
check "el role real: toda tarea que notifica el reinicio va antes del flush" real_prop \
  "(lambda n, f: n and f and max(n) < min(f))([i for i,t in enumerate(tasks) if t.get('notify')=='Restart Docker daemon'], [i for i,t in enumerate(tasks) if 'ansible.builtin.meta' in t and t['ansible.builtin.meta']=='flush_handlers'])"
check "el role real: la espera del daemon y su verificación van después del flush" real_prop \
  "(lambda f, w, a: f < w < a)(next(i for i,t in enumerate(tasks) if 'ansible.builtin.meta' in t), next(i for i,t in enumerate(tasks) if t.get('register')=='docker_host_info'), max(i for i,t in enumerate(tasks) if 'ansible.builtin.assert' in t))"
check "el role real: la espera del daemon está acotada (reintentos x espera <= 60 s)" real_prop \
  "(lambda t: t['retries'] * t['delay'] <= 60 and t['retries'] > 1)(next(t for t in tasks if t.get('register')=='docker_host_info'))"
check "el role real declara un único handler (el flush no puede vaciar otro)" real_prop "len(handlers) == 1 and handlers[0]['name'] == 'Restart Docker daemon'"

no_other_handlers() {
  # Ningún otro notify / listen / handlers en el árbol de Ansible: el flush solo puede vaciar el reinicio
  ! grep -rnE '^\s*(notify|listen):|^\s*handlers:|force_handlers' "${ANSIBLE_DIR}" --include='*.yml' --include='*.cfg' \
    | grep -v "roles/docker_host/tasks/main.yml:.*notify: Restart Docker daemon" \
    | grep -v "^${ANSIBLE_DIR}/roles/docker_host/handlers/main.yml" | grep .
}
check "ningún otro handler, notify, listen ni force_handlers en infra/ansible (nada inesperado se vacía con el flush)" no_other_handlers

converge_order() {
  "${PY}" - "${ANSIBLE_DIR}/playbooks/converge.yml" << 'PYEOF'
import sys, yaml
play = yaml.safe_load(open(sys.argv[1]))[0]
roles = [r['role'] if isinstance(r, dict) else r for r in play['roles']]
# docker_host precede a las tareas de aplicación (setup, Keycloak, backend), que son `tasks`
sys.exit(0 if 'docker_host' in roles and play.get('tasks') else 1)
PYEOF
}
check "converge.yml aplica docker_host (role) antes de las tareas de aplicación" converge_order

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "${FAILS} comprobación(es) fallaron"
  exit 1
fi
echo "Todo correcto"
