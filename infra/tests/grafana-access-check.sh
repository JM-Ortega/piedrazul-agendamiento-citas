#!/usr/bin/env bash
# Acceso de operadores a Grafana: Cloudflare Access (JWT validado por Grafana) por el router de
# Traefik, y recuperación por el loopback (túnel SSH) con el administrador local.
#
# No contacta Cloudflare, GitHub ni ningún host: el "equipo de Access" es un servidor JWKS
# sintético por HTTPS dentro de la red del proyecto (alias <equipo>.cloudflareaccess.com), con un
# CA propio; los JWT los firma infra/tests/access-jwt.py con claves creadas por ejecución; el
# certificado de cliente de Authenticated Origin Pulls lo emite un CA sintético. Credenciales
# sintéticas, nunca impresas. Es evidencia de implementación, no de la configuración real de
# Access (política, duración de sesión, revocación): eso se verifica en vivo (runbook).
#
# Uso: infra/tests/grafana-access-check.sh
#   STATIC_ONLY=1   solo las comprobaciones sin contenedores (1 y 2)
#   KEEP=1          no elimina el proyecto al terminar
# Requiere: docker (compose v2), jq, yq (mikefarah v4), ansible-playbook, python3, openssl, curl.
#
# Verifica:
#   1. Configuración: Grafana con JWT de Access (cabecera, JWKS del equipo, iss/aud/type, rol
#      estricto por lista, sin Admin ni administrador del servidor, sin login por URL), cookies
#      seguras, correo reservado del administrador local, solo loopback publicado; solo Grafana
#      en la red de entrada (externa) y Traefik la crea interna; router grafana solo con
#      grafana_public_domain, sin /login y quitando Authorization y Cookie.
#   2. Ansible: entradas inválidas de Access, de las rutas y del receptor fallan sin escribir
#      nada y sin mostrar el webhook.
#   3. JWT (directo a Grafana): válido de Viewer/Editor → su rol, nunca Admin; sin cabecera,
#      basura, expirado, sin exp, emisor/audiencia/tipo ajenos, alg none, HS256 con el JWKS,
#      clave fuera del JWKS, correo fuera de las listas, con comas o sin correo → sin acceso;
#      correo del administrador local → rechazado y la cuenta intacta; sin cookie de sesión.
#   4. Permisos: Viewer no edita ni administra; Editor edita dashboards pero no fuentes de datos
#      ni usuarios.
#   5. Traefik (router real de Ansible + tls.yml real): sin certificado de origen → rechazado;
#      con JWT → acceso; sin JWT → 401; /login (GET y POST) → 404; Basic del administrador y
#      cookie de sesión local se descartan; consultas y búsqueda funcionan sin cookies.
#   6. Recuperación: login local por el loopback; con el JWKS caído o la clave rotada, el JWT
#      falla cerrado y el login local sigue funcionando.
# Salida: 0 si todo pasa; 1 si algo falla.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OBS="${ROOT}/infra/observability"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
JWT="${ROOT}/infra/tests/access-jwt.py"
ID="$$-$(head -c 3 /dev/urandom | od -An -tx1 | tr -d ' \n')"
PROJECT="pzgac-${ID}"
APP_NET="pzgac_${ID}_app"
EDGE_NET="pzgac_${ID}_edge"
TEAM="piedrazul-check.cloudflareaccess.com"
GHOST="grafana.access-check.test"
GPORT="${GRAFANA_LOCAL_PORT:-13310}"

for bin in docker jq yq ansible-playbook python3 openssl curl; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
FAILS=0
# Autoverificación del harness: ninguna función suya puede ejecutarse dentro de un `bash -c` (el
# subproceso no la ve: ejecutaría otro comando con ese nombre, como `code` de VS Code en WSL, o
# fallaría y un `!` lo convertiría en PASS). Solo cuenta lo que corre en el hijo: `\$(fn`, o `fn`
# al inicio o tras `!`, `&&`, `||`, `;` o `|`; `$(fn` sin escapar se expande antes, en este shell.
if ! python3 -I - "$0" > "${TMP}/lint.txt" <<'LINT'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
fns = set(re.findall(r"^\s*([a-z_]+)\(\)\s*\{", src, re.M))
bad = []
for n, line in enumerate(src.splitlines(), 1):
    for m in re.finditer(r'bash -c "((?:[^"\\]|\\.)*)"', line):
        body = m.group(1)
        for fn in fns:
            if re.search(r"(\\\$\(\s*|^\s*|(?:^|[^$])(?:!|&&|\|\||;|\|)\s*)" + re.escape(fn) + r"(?=\s|$|\\\")", body):
                bad.append(f"{n}: {fn}")
print("\n".join(bad))
sys.exit(1 if bad else 0)
LINT
then
  printf 'FAIL  harness: función llamada dentro de bash -c (no existe en el subproceso): %s\n' "$(paste -sd' ' "${TMP}/lint.txt")" >&2
  exit 1
fi
RUNTIME_STARTED=false
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
# Modo con acceso público: compose.yml + compose.access.yml (como COMPOSE_FILE del rol)
obs() { docker compose -p "${PROJECT}" --project-directory "${OBS}" -f "${OBS}/compose.yml" -f "${OBS}/compose.access.yml" -f "${TMP}/override.yml" --env-file "${TMP}/obs.env" "$@"; }
cleanup() {
  if [ "${RUNTIME_STARTED}" = true ] && [ "${KEEP:-0}" != 1 ]; then
    obs down -v --remove-orphans >/dev/null 2>&1 || true
    docker network rm "${APP_NET}" "${EDGE_NET}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

# ── 1. Configuración ─────────────────────────────────────────────────────────
ADMIN_PW="$(synthetic admin)"
AUD="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
VIEWER=viewer@ops.example.org
EDITOR=editor@ops.example.org
ADMIN_EMAIL=admin@piedrazul-obs.invalid
mkdir -p "${TMP}/secrets" && : > "${TMP}/secrets/discord_webhook_url"
cat > "${TMP}/obs.env" <<EOF
GRAFANA_ADMIN_PASSWORD=${ADMIN_PW}
OBS_APP_NETWORK=${APP_NET}
OBS_EDGE_NETWORK=${EDGE_NET}
GRAFANA_LOCAL_PORT=${GPORT}
GRAFANA_ROOT_URL=https://${GHOST}/
GRAFANA_ACCESS_ENABLED=true
GRAFANA_ACCESS_TEAM_DOMAIN=${TEAM}
GRAFANA_ACCESS_AUD=${AUD}
OBS_GRAFANA_ACCESS_FILE=${TMP}/secrets/grafana_access.ini
OBS_DISCORD_WEBHOOK_FILE=${TMP}/secrets/discord_webhook_url
OBS_ENVIRONMENT=access-check
EOF
printf 'name: %s\n' "${PROJECT}" > "${TMP}/override.yml"
C="${TMP}/config.json"
obs config --format json > "${C}" 2>/dev/null || ko "Compose: renderiza con Access habilitado"
GENV() { jq -r --arg k "$1" '.services.grafana.environment[$k] // empty' "${C}"; }
check "Grafana: JWT por Cf-Access-Jwt-Assertion con el JWKS del equipo" \
  bash -c "test '$(GENV GF_AUTH_JWT_ENABLED)' = true && test '$(GENV GF_AUTH_JWT_HEADER_NAME)' = Cf-Access-Jwt-Assertion && test '$(GENV GF_AUTH_JWT_JWK_SET_URL)' = 'https://${TEAM}/cdn-cgi/access/certs'"
check "Grafana: exige iss del equipo, aud de la aplicación y type=app" \
  test "$(GENV GF_AUTH_JWT_EXPECT_CLAIMS | jq -cS .)" = "$(jq -cnS --arg i "https://${TEAM}" --arg a "${AUD}" '{iss:$i,aud:$a,type:"app"}')"
check "Grafana: rol estricto, sin Admin ni administrador del servidor, sin login por URL" \
  bash -c "test '$(GENV GF_AUTH_JWT_ROLE_ATTRIBUTE_STRICT)' = true && test '$(GENV GF_AUTH_JWT_ALLOW_ASSIGN_GRAFANA_ADMIN)' = false && test '$(GENV GF_AUTH_JWT_SKIP_ORG_ROLE_SYNC)' = false && test '$(GENV GF_AUTH_JWT_URL_LOGIN)' = false"
check "Grafana: las listas no pasan por el entorno (archivo de configuración secreto, sin variable que lo anule)" \
  bash -c "test -z '$(GENV GF_AUTH_JWT_ROLE_ATTRIBUTE_PATH)' && test '$(GENV GF_PATHS_CONFIG)' = /run/secrets/grafana_access && jq -e '[.services.grafana.secrets[].source] == [\"grafana_access\"]' '${C}' && ! jq -r '.services.grafana.environment | tostring' '${C}' | grep -q 'ops.example.org'"
check "Grafana: JWKS en caché 5m (rotación y caída de Access acotadas)" test "$(GENV GF_AUTH_JWT_CACHE_TTL)" = 5m
check "Grafana: cookies Secure y SameSite=strict; administrador local con correo .invalid" \
  bash -c "test '$(GENV GF_SECURITY_COOKIE_SECURE)' = true && test '$(GENV GF_SECURITY_COOKIE_SAMESITE)' = strict && test '$(GENV GF_SECURITY_ADMIN_EMAIL)' = '${ADMIN_EMAIL}'"
check "Grafana: root_url pública con Access; por defecto el loopback" \
  bash -c "test '$(GENV GF_SERVER_ROOT_URL)' = 'https://${GHOST}/' && printf 'GRAFANA_ADMIN_PASSWORD=x\n' > '${TMP}/min.env' && test \"\$(docker compose -f '${OBS}/compose.yml' --env-file '${TMP}/min.env' config --format json 2>/dev/null | jq -r '.services.grafana.environment.GF_SERVER_ROOT_URL + \" \" + .services.grafana.environment.GF_AUTH_JWT_ENABLED')\" = 'http://localhost:3000/ false'"
check "Compose: solo grafana en la red de entrada, externa; grafana publica solo en 127.0.0.1" \
  bash -c "test \"\$(jq -r '[.services | to_entries[] | select(.value.networks | has(\"edge_net\")) | .key] | join(\",\")' '${C}')\" = grafana && jq -e '.networks.edge_net.external == true' '${C}' && test \"\$(jq -r '[.services.grafana.ports[].host_ip] | unique | join(\",\")' '${C}')\" = 127.0.0.1"
check "Compose: grafana no está en la red de la aplicación (no alcanza productores)" \
  jq -e '.services.grafana.networks | has("app_net") | not' "${C}"
check "modo privado: compose.yml solo no declara ni exige la red de entrada (S1/S2 sin la aplicación F2)" \
  bash -c "docker compose -p '${PROJECT}-priv' -f '${OBS}/compose.yml' --env-file '${TMP}/obs.env' config --format json 2>/dev/null | jq -e '(.networks | has(\"edge_net\") | not) and (.services.grafana.networks | keys == [\"obs_net\"])'"
check "compose.access.yml: solo agrega la red de entrada (externa) a grafana" \
  test "$(yq -o=json -I=0 '.' "${OBS}/compose.access.yml" | jq -c '[(.services | keys), (.services.grafana | keys), .networks.edge_net.external]')" = '[["grafana"],["networks"],true]'
check "traefik.yml: crea la red de entrada piedrazul_obs_edge interna y solo traefik se une" \
  bash -c "test \"\$(yq -r '.networks.obs_edge.name + \" \" + (.networks.obs_edge.internal | tostring)' '${COMPOSE_DIR}/traefik.yml')\" = 'piedrazul_obs_edge true' && test \"\$(yq -r '.services.traefik.networks | join(\",\")' '${COMPOSE_DIR}/traefik.yml')\" = piedrazul_net,obs_edge && test \"\$(grep -l 'obs_edge' '${COMPOSE_DIR}'/*.yml '${ROOT}/docker-compose.yml' | xargs -n1 basename | paste -sd,)\" = traefik.yml"
check "Defaults: la red de entrada de Compose y del rol coinciden con traefik.yml" \
  bash -c "test \"\$(yq -r '.obs_edge_network' '${ANSIBLE_DIR}/roles/observability/defaults/main/stack.yml')\" = piedrazul_obs_edge && grep -q 'OBS_EDGE_NETWORK:-piedrazul_obs_edge' '${OBS}/compose.access.yml'"

# ── 2. Ansible: rutas y entradas ─────────────────────────────────────────────
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"
mkdir -p "${TMP}/roles/routescheck/tasks" "${TMP}/roles/routescheck/templates"
grep -vE '^ +(owner|group): ansible$' "${ANSIBLE_DIR}/roles/app/tasks/traefik_routes.yml" > "${TMP}/roles/routescheck/tasks/main.yml"
cp "${ANSIBLE_DIR}/roles/app/templates/traefik-routes.yml.j2" "${TMP}/roles/routescheck/templates/"
cat > "${TMP}/routes.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_role: {name: routescheck}
EOF
ansible_local() { # <playbook> [args...]
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ANSIBLE_ROLES_PATH="${TMP}/roles:${ANSIBLE_DIR}/roles" \
    ansible-playbook -i "${TMP}/hosts.yml" "$@" </dev/null
}
ROUTE_VARS=(-e api_public_domain=api.access-check.test -e kc_hostname=auth.access-check.test -e server_port=8080 -e kc_port=8180)
render_routes() { # <dir> [args...]
  local dir="$1"; shift
  mkdir -p "${dir}/traefik/dynamic"
  ansible_local "${TMP}/routes.yml" -e "app_dir=${dir}" "${ROUTE_VARS[@]}" "$@"
}
render_routes "${TMP}/r-none" > "${TMP}/r-none.log" 2>&1
check "rutas: sin grafana_public_domain no hay router, servicio ni middleware de grafana" \
  bash -c "test \"\$(yq -r '[.http.routers | keys | .[]] | join(\",\")' '${TMP}/r-none/traefik/dynamic/routes.yml')\" = backend,keycloak && test \"\$(yq -r '.http.middlewares // \"none\"' '${TMP}/r-none/traefik/dynamic/routes.yml')\" = none"
check "rutas: el inventario de producción deriva el router de Grafana del dominio y del interruptor del stack (una sola fuente)" \
  bash -c "test \"\$(yq -r '.grafana_public_domain' '${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml')\" = \"{{ obs_grafana_public_domain if obs_grafana_access_enabled | bool else '' }}\" && test \"\$(yq -r '.obs_grafana_public_domain' '${ANSIBLE_DIR}/inventories/prod/group_vars/all.yml')\" = 'grafana.{{ base_domain }}'"
render_routes "${TMP}/r-g" -e "grafana_public_domain=${GHOST}" > "${TMP}/r-g.log" 2>&1
RG="${TMP}/r-g/traefik/dynamic/routes.yml"
check "rutas: router grafana → Host(${GHOST}) sin /login, websecure, AOP + letsencrypt" \
  test "$(yq -r '.http.routers.grafana | [.rule, (.entryPoints | join(",")), .service, .tls.options, .tls.certResolver] | join("|")' "${RG}" 2>/dev/null)" = "Host(\`${GHOST}\`) && !PathPrefix(\`/login\`)|websecure|grafana|cloudflare-aop|letsencrypt"
check "rutas: grafana quita Authorization y Cookie; backend y keycloak sin middlewares" \
  bash -c "test \"\$(yq -o=json -I=0 '.http.middlewares' '${RG}')\" = '{\"grafana-access-only\":{\"headers\":{\"customRequestHeaders\":{\"Authorization\":\"\",\"Cookie\":\"\"}}}}' && test \"\$(yq -r '.http.routers.grafana.middlewares | join(\",\")' '${RG}')\" = grafana-access-only && test \"\$(yq -r '[.http.routers.backend.middlewares, .http.routers.keycloak.middlewares] | map(. // \"-\") | join(\",\")' '${RG}')\" = '-,-'"
check "rutas: servicio grafana → http://grafana:3000" \
  test "$(yq -r '.http.services.grafana.loadBalancer.servers[0].url' "${RG}")" = "http://grafana:3000"
reject_routes() { # <descripción> [args...]
  local desc="$1" dir; shift
  dir="${TMP}/reject-$((RANDOM))"
  if ! render_routes "${dir}" "$@" > "${dir}.log" 2>&1 && grep -q 'RUTAS DE TRAEFIK' "${dir}.log" \
     && [ ! -e "${dir}/traefik/dynamic/routes.yml" ]; then ok "rutas rechazadas sin escribir: ${desc}"; else ko "rutas rechazadas sin escribir: ${desc}"; fi
}
reject_routes "grafana en mayúsculas" -e grafana_public_domain=Grafana.access-check.test
reject_routes "grafana con inyección en la regla" -e 'grafana_public_domain=g.access-check.test`) || Host(`x.test'
reject_routes "grafana igual al host del API" -e grafana_public_domain=api.access-check.test

# Comprobaciones del rol observability (sin tocar nada: falla antes de la red de entrada)
cat > "${TMP}/preflight.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_role: {name: observability, tasks_from: access_alerts_preflight.yml}
EOF
GOOD=(-e obs_grafana_access_enabled=true -e "obs_grafana_public_domain=${GHOST}" -e "obs_grafana_access_team_domain=${TEAM}" -e "obs_grafana_access_aud=${AUD}")
WEBHOOK_SECRET="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
FAKE_WEBHOOK="https://discord.com/api/webhooks/123456789012345678/${WEBHOOK_SECRET}${WEBHOOK_SECRET}"
preflight() { OBS_DISCORD_WEBHOOK_URL="${PF_WEBHOOK:-}" GRAFANA_VIEWER_EMAILS="${PF_VIEW-${VIEWER}}" GRAFANA_EDITOR_EMAILS="${PF_EDIT-none}" \
  ansible_local "${TMP}/preflight.yml" -e "obs_dir=${TMP}/pf-obs" -e "obs_edge_network=${PF_NET:-pzgac-no-such-net}" "$@"; }
reject_pf() { # <descripción> <marca> [args...]
  local desc="$1" mark="$2" log; shift 2
  log="${TMP}/pf-$((RANDOM)).log"
  if ! preflight "$@" > "${log}" 2>&1 && grep -q "${mark}" "${log}" && ! grep -q "${WEBHOOK_SECRET}" "${log}" && [ ! -e "${TMP}/pf-obs" ]; then
    ok "rol rechaza sin escribir: ${desc}"
  else
    ko "rol rechaza sin escribir: ${desc}"; grep -E 'fatal|msg' "${log}" | head -3
  fi
}
# Archivo de acceso de Grafana con las tareas reales del rol (access_lists.yml + grafana_access_file.yml)
cat > "${TMP}/access-file.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_role: {name: observability, tasks_from: access_lists.yml}
    - ansible.builtin.include_role: {name: observability, tasks_from: grafana_access_file.yml}
EOF
access_file() { # <viewers> <editors>
  GRAFANA_VIEWER_EMAILS="$1" GRAFANA_EDITOR_EMAILS="$2" ansible_local "${TMP}/access-file.yml" -e "obs_secrets_dir=${TMP}/secrets" \
    -e "obs_secrets_owner=$(id -un)" -e "obs_grafana_uid=$(id -u)" -e obs_secret_mode=0444 > "${TMP}/access-file.log" 2>&1
}
log="${TMP}/pf-good.log"
if ! preflight "${GOOD[@]}" > "${log}" 2>&1 && grep -q 'No existe la red interna pzgac-no-such-net' "${log}"; then
  ok "rol: entradas válidas de Access pasan; sin la red de entrada falla antes de escribir"
else ko "rol: entradas válidas de Access pasan; sin la red de entrada falla"; fi
reject_pf "Access sin AUD" 'ACCESO A GRAFANA' "${GOOD[@]}" -e obs_grafana_access_aud=
reject_pf "AUD que no es de 64 hex" 'ACCESO A GRAFANA' "${GOOD[@]}" -e obs_grafana_access_aud=ABC
reject_pf "equipo fuera de cloudflareaccess.com" 'ACCESO A GRAFANA' "${GOOD[@]}" -e obs_grafana_access_team_domain=evil.example.org
# Listas vacías explícitas (none/none): válidas con Access habilitado (nadie entra por JWT; Access y
# el modo no cambian): pasan las comprobaciones y fallan después, solo por la red de entrada
log="${TMP}/pf-empty.log"
if ! PF_VIEW=none PF_EDIT=none preflight "${GOOD[@]}" > "${log}" 2>&1 && grep -q 'No existe la red interna pzgac-no-such-net' "${log}"; then
  ok "rol: listas none/none con Access habilitado aceptadas (sin desactivar Access)"
else ko "rol: listas none/none con Access habilitado aceptadas"; fi
PF_VIEW= reject_pf "lista Viewer ausente (sin conservar la anterior)" 'LISTAS DE GRAFANA' "${GOOD[@]}"
PF_VIEW=Op@ops.example.org reject_pf "correo en mayúsculas" 'LISTAS DE GRAFANA' "${GOOD[@]}"
PF_EDIT="${ADMIN_EMAIL}" reject_pf "correo del administrador local (.invalid)" 'LISTAS DE GRAFANA' "${GOOD[@]}"
PF_VIEW=admin@localhost reject_pf "admin@localhost (correo de una base antigua)" 'LISTAS DE GRAFANA' "${GOOD[@]}"
PF_VIEW="x'@y.org" reject_pf "correo con comilla (inyección en la expresión)" 'LISTAS DE GRAFANA' "${GOOD[@]}"
PF_EDIT="${VIEWER}" reject_pf "correo repetido entre Editor y Viewer" 'LISTAS DE GRAFANA' "${GOOD[@]}"
check "rol: los rechazos de las listas no muestran correos" bash -c "! grep -lE 'ops\\.example\\.org|Op@|admin@localhost' '${TMP}'/pf-*.log"
reject_pf "enabled no booleano" 'ACCESO A GRAFANA' -e obs_grafana_access_enabled=maybe
reject_pf "receptor desconocido" 'NOTIFICACIONES' -e obs_alert_receiver=slack
reject_pf "discord sin entorno de origen" 'NOTIFICACIONES' -e obs_alert_receiver=discord
reject_pf "discord sin webhook (ni en el controlador ni en el host)" 'NOTIFICACIONES' -e obs_alert_receiver=discord -e obs_environment=staging
PF_WEBHOOK="http://discord.com/api/webhooks/123456789012345678/${WEBHOOK_SECRET}${WEBHOOK_SECRET}" \
  reject_pf "webhook sin https (no se muestra)" 'NOTIFICACIONES' -e obs_alert_receiver=discord -e obs_environment=staging
PF_WEBHOOK="https://evil.example.org/api/webhooks/123456789012345678/${WEBHOOK_SECRET}${WEBHOOK_SECRET}" \
  reject_pf "webhook de otro host (no se muestra)" 'NOTIFICACIONES' -e obs_alert_receiver=discord -e obs_environment=staging
log="${TMP}/pf-wh.log"
if PF_WEBHOOK="${FAKE_WEBHOOK}" preflight -e obs_alert_receiver=discord -e obs_environment=staging > "${log}" 2>&1 && ! grep -q "${WEBHOOK_SECRET}" "${log}" && [ ! -e "${TMP}/pf-obs" ]; then
  ok "rol: webhook bien formado aceptado sin mostrarse; modo privado sin red de entrada"
else ko "rol: webhook bien formado aceptado sin mostrarse"; fi

if access_file "${VIEWER},second.viewer@ops.example.org" "${EDITOR}"; then ok "rol: archivo de acceso de Grafana generado"; else ko "rol: archivo de acceso de Grafana"; fi
check "archivo de acceso: Editor y Viewer por lista, nunca Admin" \
  bash -c "grep -q \"^role_attribute_path = .*'Editor'.*'Viewer'\" '${TMP}/secrets/grafana_access.ini' && ! grep -q \"'Admin'\" '${TMP}/secrets/grafana_access.ini'"
check "archivo de acceso: la generación no muestra correos" bash -c "! grep -q 'ops.example.org' '${TMP}/access-file.log'"

if [ "${STATIC_ONLY:-0}" = 1 ]; then
  echo "runtime: omitido (STATIC_ONLY=1) — NO DEMOSTRADO"
  echo; [ "${FAILS}" -eq 0 ] && { echo "OK: todas las comprobaciones pasaron"; exit 0; } || { echo "FALLAS: ${FAILS}"; exit 1; }
fi

# ── Runtime: JWKS sintético, Grafana real y Traefik con el router de Ansible ────────────
mkdir -p "${TMP}/keys" "${TMP}/rogue" "${TMP}/jwks-tls" "${TMP}/www/cdn-cgi/access" "${TMP}/pki" "${TMP}/dynamic" "${TMP}/certs"
python3 -I "${JWT}" keygen "${TMP}/keys" k1
python3 -I "${JWT}" keygen "${TMP}/rogue" rogue
cp "${TMP}/keys/jwks.json" "${TMP}/www/cdn-cgi/access/certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=${TEAM}" -addext "subjectAltName=DNS:${TEAM}" \
  -keyout "${TMP}/jwks-tls/key.pem" -out "${TMP}/jwks-tls/cert.pem" 2>/dev/null
cat > "${TMP}/jwks.py" <<'EOF'
import http.server, ssl, os
os.chdir('/www')
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
s = http.server.ThreadingHTTPServer(('0.0.0.0', 443), H)
c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); c.load_cert_chain('/tls/cert.pem', '/tls/key.pem')
s.socket = c.wrap_socket(s.socket, server_side=True); s.serve_forever()
EOF
# PKI de Authenticated Origin Pulls: CA "de Cloudflare" sintético y otro ajeno
pki() {
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$1 test CA" -keyout "${TMP}/pki/$1-ca.key" -out "${TMP}/pki/$1-ca.pem" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$1 client" -keyout "${TMP}/pki/$1-client.key" -out "${TMP}/pki/$1-client.csr" 2>/dev/null
  openssl x509 -req -in "${TMP}/pki/$1-client.csr" -CA "${TMP}/pki/$1-ca.pem" -CAkey "${TMP}/pki/$1-ca.key" -CAcreateserial -days 1 -out "${TMP}/pki/$1-client.pem" 2>/dev/null
}
pki origin; pki other
CA_FILE="$(yq -r '.tls.options.cloudflare-aop.clientAuth.caFiles[0]' "${ROOT}/infra/traefik/dynamic/tls.yml")"
cp "${TMP}/pki/origin-ca.pem" "${TMP}/certs/${CA_FILE#/certs/}"
cp "${ROOT}/infra/traefik/dynamic/tls.yml" "${TMP}/dynamic/"
cp "${RG}" "${TMP}/dynamic/routes.yml"
chmod -R a+rX "${TMP}/www" "${TMP}/jwks-tls" "${TMP}/dynamic" "${TMP}/certs" "${TMP}/jwks.py"
chmod a+rx "${TMP}"

TRAEFIK_REF="$(yq -r '.services.traefik.image' "${COMPOSE_DIR}/traefik.yml")"
# Diferencias con producción, solo de prueba: el CA del JWKS sintético (GF_AUTH_JWT_TLS_CLIENT_CA),
# la caché del JWKS en 20 s en lugar de 5 m (para observar la rotación y la caída sin esperar) y
# Traefik con el comando real más el log y un ACME inexistente, en la red de la aplicación de
# prueba (para publicar 443 en loopback) y en la de entrada.
{
  echo "name: ${PROJECT}"
  echo "services:"
  echo "  jwks:"
  echo "    image: python:3.12-slim"
  echo "    command: [python, /jwks.py]"
  echo "    volumes: ['${TMP}/www:/www:ro', '${TMP}/jwks-tls:/tls:ro', '${TMP}/jwks.py:/jwks.py:ro']"
  echo "    networks:"
  echo "      obs_net:"
  echo "        aliases: ['${TEAM}']"
  echo "  grafana:"
  echo "    environment:"
  echo "      GF_AUTH_JWT_TLS_CLIENT_CA: /etc/jwks-ca.pem"
  echo "      GF_AUTH_JWT_CACHE_TTL: 20s"
  echo "    volumes: ['${TMP}/jwks-tls/cert.pem:/etc/jwks-ca.pem:ro']"
  echo "  traefik:"
  echo "    image: ${TRAEFIK_REF}"
  echo "    command:"
  yq -r '.services.traefik.command[] | select(test("certificatesresolvers.letsencrypt.acme.dnschallenge|acme.email") | not) | "      - \"" + . + "\""' "${COMPOSE_DIR}/traefik.yml"
  echo "      - \"--certificatesresolvers.letsencrypt.acme.caserver=https://127.0.0.1:9/directory\""
  echo "      - \"--certificatesresolvers.letsencrypt.acme.tlschallenge=true\""
  echo "    ports: ['127.0.0.1::443']"
  echo "    volumes: ['${TMP}/dynamic:/etc/traefik/dynamic:ro', '${TMP}/certs:/certs:ro']"
  echo "    networks: [app_net, edge_net]"
} > "${TMP}/override.yml"

RUNTIME_STARTED=true
docker network create "${APP_NET}" >/dev/null || { ko "runtime: red de la aplicación de prueba"; exit 1; }
docker network create --internal "${EDGE_NET}" >/dev/null || { ko "runtime: red de entrada de prueba"; exit 1; }
if obs up -d --wait --wait-timeout 180 jwks grafana traefik alertmanager > "${TMP}/up.log" 2>&1; then
  ok "runtime: grafana (Access habilitado), alertmanager (none), JWKS sintético y traefik arriba (${PROJECT})"
else
  ko "runtime: arranque"; tail -20 "${TMP}/up.log" >&2; exit 1
fi
G="http://127.0.0.1:${GPORT}"
HTTPS_PORT="$(obs port traefik 443 | sed 's/.*://')"
NOW_BASE="\"iss\":\"https://${TEAM}\",\"aud\":[\"${AUD}\"],\"type\":\"app\""
tok() { # <claims sin llaves> [kid] [alg] [dir]
  python3 -I "${JWT}" sign "${4:-${TMP}/keys}" "${2:-k1}" "{${NOW_BASE},$1}" "${3:-RS256}"
}
claims() { printf '"sub":"%s","email":"%s","_iat_in":0,"_exp_in":600' "$1" "$2"; }
gapi() { # <token|-> <ruta> [curl args...] → cuerpo + código
  local t="$1" p="$2"; shift 2
  if [ "$t" = - ]; then curl -s --max-time 10 -w '\n%{http_code}' "$@" "${G}${p}"
  else curl -s --max-time 10 -w '\n%{http_code}' -H "Cf-Access-Jwt-Assertion: ${t}" "$@" "${G}${p}"; fi
}
# Código HTTP de la última línea (curl -w '\n%{http_code}'). Solo tres dígitos; si no, ERR y
# error: ninguna comprobación puede pasar con una extracción fallida. Nombre propio: `code` en WSL
# es la CLI de VS Code, y un subproceso que no viera la función la ejecutaría.
http_status() { local c; c="$(tail -n1 <<< "$1")"; if [[ "${c}" =~ ^[0-9]{3}$ ]]; then printf '%s' "${c}"; else printf 'ERR'; return 1; fi; }
# status_in <respuesta> <códigos...>: el código existe y está en la lista
status_in() { local c; c="$(http_status "$1")" || return 1; shift; [[ " $* " == *" ${c} "* ]]; }
body() { sed '$d' <<< "$1"; }
deny() { # <descripción> <token> <códigos aceptables>
  local out; out="$(gapi "$2" /api/user)"
  if [[ " $3 " == *" $(http_status "${out}") "* ]] && ! body "${out}" | jq -e '.id' >/dev/null 2>&1; then
    ok "JWT rechazado: $1 ($(http_status "${out}"))"
  else ko "JWT rechazado: $1 ($(http_status "${out}"))"; fi
}
admin_login_ok() {
  curl -s --max-time 10 -c "${TMP}/admin.jar" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "$(jq -cn --arg p "${ADMIN_PW}" '{user:"admin",password:$p}')" "${G}/login" | grep -qx 200 \
  && test "$(curl -s -b "${TMP}/admin.jar" "${G}/api/user" | jq -r '.login + " " + (.isGrafanaAdmin | tostring)')" = "admin true"
}

# ── 3. JWT ───────────────────────────────────────────────────────────────────
VT="$(tok "$(claims sub-viewer "${VIEWER}")")"
ET="$(tok "$(claims sub-editor "${EDITOR}")")"
OUT="$(gapi "${VT}" /api/user -D "${TMP}/viewer.hdr")"
check "JWT válido de Viewer → usuario externo, no administrador del servidor" \
  bash -c "test \"\$(jq -r '.login + \" \" + (.isGrafanaAdmin | tostring) + \" \" + (.isExternal | tostring)' <<< \"\$1\")\" = '${VIEWER} false true'" _ "$(body "${OUT}")"
check "JWT válido de Viewer → rol Viewer en la organización" \
  test "$(body "$(gapi "${VT}" /api/user/orgs)" | jq -r '[.[].role] | join(",")')" = Viewer
check "JWT válido de Editor → rol Editor, no administrador del servidor" \
  test "$(body "$(gapi "${ET}" /api/user/orgs)" | jq -r '[.[].role] | join(",")') $(body "$(gapi "${ET}" /api/user)" | jq -r '.isGrafanaAdmin')" = "Editor false"
check "JWT con el correo en otra capitalización → mismo rol (lower)" \
  test "$(body "$(gapi "$(tok "$(claims sub-viewer2 Second.Viewer@OPS.example.org)")" /api/user/orgs)" | jq -r '[.[].role] | join(",")')" = Viewer
check "JWT: la respuesta no crea sesión (sin Set-Cookie): la identidad se valida en cada petición" \
  bash -c "! grep -qi '^set-cookie' '${TMP}/viewer.hdr'"
deny "sin cabecera" - "401"
deny "token basura" "abc.def.ghi" "401"
deny "expirado" "$(tok '"sub":"s","email":"'"${VIEWER}"'","_iat_in":-900,"_exp_in":-300')" "401"
deny "sin exp" "$(tok '"sub":"s","email":"'"${VIEWER}"'","_iat_in":0')" "401 403"
deny "emisor ajeno" "$(python3 -I "${JWT}" sign "${TMP}/keys" k1 "{\"iss\":\"https://evil.cloudflareaccess.com\",\"aud\":[\"${AUD}\"],\"type\":\"app\",$(claims s "${VIEWER}")}")" "401"
deny "audiencia de otra aplicación" "$(python3 -I "${JWT}" sign "${TMP}/keys" k1 "{\"iss\":\"https://${TEAM}\",\"aud\":[\"$(printf 'b%.0s' {1..64})\"],\"type\":\"app\",$(claims s "${VIEWER}")}")" "401"
deny "tipo org (token de organización, no de la aplicación)" "$(python3 -I "${JWT}" sign "${TMP}/keys" k1 "{\"iss\":\"https://${TEAM}\",\"aud\":[\"${AUD}\"],\"type\":\"org\",$(claims s "${VIEWER}")}")" "401"
deny "alg none" "$(tok "$(claims s "${VIEWER}")" k1 none)" "401"
deny "HS256 con el JWKS público como secreto" "$(tok "$(claims s "${VIEWER}")" k1 HS256)" "401"
deny "firmado con una clave fuera del JWKS" "$(tok "$(claims s "${VIEWER}")" rogue RS256 "${TMP}/rogue")" "401"
deny "kid del JWKS con firma de otra clave" "$(python3 -I -B - "${JWT}" "${TMP}" "$(claims s "${VIEWER}")" "${NOW_BASE}" <<'EOF'
import importlib.util, sys, shutil, os
spec = importlib.util.spec_from_file_location("j", sys.argv[1]); j = importlib.util.module_from_spec(spec); spec.loader.exec_module(j)
d = os.path.join(sys.argv[2], "forge"); os.makedirs(d, exist_ok=True)
shutil.copy(os.path.join(sys.argv[2], "rogue", "rogue.key"), os.path.join(d, "k1.key"))
print(j.sign(d, "k1", "{" + sys.argv[4] + "," + sys.argv[3] + "}"))
EOF
)" "401"
deny "correo fuera de las listas" "$(tok "$(claims s-x outsider@ops.example.org)")" "403"
deny "correo con comas que contiene un autorizado" "$(tok "$(claims s-c "x@y.org,${VIEWER}")")" "401 403"
deny "sin claim email" "$(tok '"sub":"s-noemail","_iat_in":0,"_exp_in":600')" "401 403"
deny "correo del administrador local" "$(tok "$(claims s-admin "${ADMIN_EMAIL}")")" "401 403"
check "recuperación: tras el intento con el correo del administrador, su cuenta sigue intacta (login local)" admin_login_ok
check "recuperación: el administrador local conserva login admin y su correo reservado" \
  test "$(curl -s -u "admin:${ADMIN_PW}" "${G}/api/users/lookup?loginOrEmail=admin" | jq -r '.login + " " + .email + " " + (.isGrafanaAdmin | tostring)')" = "admin ${ADMIN_EMAIL} true"
check "usuarios: solo el administrador local es administrador del servidor" \
  test "$(curl -s -u "admin:${ADMIN_PW}" "${G}/api/users" | jq -r '[.[] | select(.isAdmin) | .login] | join(",")')" = admin

# ── 4. Permisos ──────────────────────────────────────────────────────────────
DS='{"name":"x","type":"prometheus","url":"http://x","access":"proxy"}'
DB='{"dashboard":{"title":"access-check"},"overwrite":true}'
pcode() { http_status "$(gapi "$1" "$2" "${@:3}")"; }
# presp <token> <ruta> [curl args...] → respuesta completa (cuerpo + código), para status_in
presp() { gapi "$1" "$2" "${@:3}"; }
check "Viewer: no crea dashboards (403)" test "$(pcode "${VT}" /api/dashboards/db -H 'Content-Type: application/json' -d "${DB}")" = 403
check "Viewer: no crea fuentes de datos (403)" test "$(pcode "${VT}" /api/datasources -H 'Content-Type: application/json' -d "${DS}")" = 403
check "Viewer: sin API de administración del servidor" status_in "$(presp "${VT}" /api/admin/settings)" 403 404
check "Editor: crea dashboards (200)" test "$(pcode "${ET}" /api/dashboards/db -H 'Content-Type: application/json' -d "${DB}")" = 200
check "Editor: no crea fuentes de datos (403)" test "$(pcode "${ET}" /api/datasources -H 'Content-Type: application/json' -d "${DS}")" = 403
SNAP='{"dashboard":{"title":"snap","panels":[]},"expires":3600}'
check "Editor: no crea snapshots (enlace sin JWT)" \
  status_in "$(presp "${ET}" /api/snapshots -H 'Content-Type: application/json' -d "${SNAP}")" 400 403 404
check "Editor: no crea dashboards públicos" \
  status_in "$(presp "${ET}" /api/dashboards/uid/piedrazul-plataforma/public-dashboards -H 'Content-Type: application/json' -d '{"isEnabled":true,"share":"public"}')" 400 403 404
check "Grafana: snapshots y dashboards públicos deshabilitados en la configuración" \
  bash -c "test '$(GENV GF_SNAPSHOTS_ENABLED)' = false && test '$(GENV GF_PUBLIC_DASHBOARDS_ENABLED)' = false"
check "Editor: no administra el servidor" status_in "$(presp "${ET}" /api/admin/settings)" 403 404
check "Editor: no administra usuarios de la organización" \
  status_in "$(presp "${ET}" /api/org/users -H 'Content-Type: application/json' -d '{"loginOrEmail":"x@y.org","role":"Admin"}')" 403 404

# ── 5. Traefik ───────────────────────────────────────────────────────────────
treq() { # <ruta> [curl args...] → cuerpo + código, con certificado de origen salvo que se pida otro
  local p="$1"; shift
  curl -sk --max-time 10 --resolve "${GHOST}:${HTTPS_PORT}:127.0.0.1" -w '\n%{http_code}' "$@" "https://${GHOST}:${HTTPS_PORT}${p}"
}
AOP=(--cert "${TMP}/pki/origin-client.pem" --key "${TMP}/pki/origin-client.key")
for _ in $(seq 1 30); do [ "$(http_status "$(treq /api/health "${AOP[@]}")")" = 200 ] && break; sleep 1; done
check "traefik: sin certificado de origen (AOP) → handshake rechazado" \
  bash -c "! curl -sk --max-time 10 --resolve '${GHOST}:${HTTPS_PORT}:127.0.0.1' -o /dev/null 'https://${GHOST}:${HTTPS_PORT}/api/health'"
check "traefik: certificado de otro CA → handshake rechazado" \
  bash -c "! curl -sk --max-time 10 --cert '${TMP}/pki/other-client.pem' --key '${TMP}/pki/other-client.key' --resolve '${GHOST}:${HTTPS_PORT}:127.0.0.1' -o /dev/null 'https://${GHOST}:${HTTPS_PORT}/api/health'"
check "traefik: con AOP y JWT de Viewer → Grafana como Viewer" \
  test "$(body "$(treq /api/user "${AOP[@]}" -H "Cf-Access-Jwt-Assertion: ${VT}")" | jq -r '.login')" = "${VIEWER}"
check "traefik: con AOP y sin JWT → 401 (sin acceso anónimo)" test "$(http_status "$(treq /api/search "${AOP[@]}")")" = 401
check "traefik: /login (GET) no se enruta (404)" test "$(http_status "$(treq /login "${AOP[@]}")")" = 404
check "traefik: /login (POST con la contraseña local) no se enruta (404)" \
  test "$(http_status "$(treq /login "${AOP[@]}" -H 'Content-Type: application/json' -d "$(jq -cn --arg p "${ADMIN_PW}" '{user:"admin",password:$p}')")")" = 404
check "traefik: /login/ y /login?x no se enrutan (404)" \
  bash -c "test \"\$(curl -sk --max-time 10 \"\${@}\" -o /dev/null -w '%{http_code}' --resolve '${GHOST}:${HTTPS_PORT}:127.0.0.1' 'https://${GHOST}:${HTTPS_PORT}/login/')\" = 404 && test \"\$(curl -sk --max-time 10 \"\${@}\" -o /dev/null -w '%{http_code}' --resolve '${GHOST}:${HTTPS_PORT}:127.0.0.1' 'https://${GHOST}:${HTTPS_PORT}/login?redirect=%2F')\" = 404" _ "${AOP[@]}"
# La variante no debe iniciar sesión: código HTTP válido y, si fuera 200, sin "Logged in"
login_refused() { local c; c="$(http_status "$1")" || return 1; [ "${c}" != 200 ] || ! grep -q 'Logged in' <<< "$1"; }
for variant in /LOGIN /Login /%6cogin; do
  out="$(treq "${variant}" "${AOP[@]}" -H 'Content-Type: application/json' -d "$(jq -cn --arg p "${ADMIN_PW}" '{user:"admin",password:$p}')")"
  check "traefik: variante ${variant} no inicia sesión local ($(http_status "${out}"))" login_refused "${out}"
done
check "traefik: Basic del administrador local se descarta (401)" \
  test "$(http_status "$(treq /api/user "${AOP[@]}" -u "admin:${ADMIN_PW}")")" = 401
admin_login_ok
SESSION_COOKIE="$(awk '$6 == "grafana_session" {print $6 "=" $7}' "${TMP}/admin.jar")"
check "recuperación: el login local por el loopback da una sesión de administrador" test -n "${SESSION_COOKIE}"
check "traefik: cookie de sesión del administrador sin JWT → 401 (la cookie se descarta)" \
  test "$(http_status "$(treq /api/user "${AOP[@]}" -H "Cookie: ${SESSION_COOKIE}")")" = 401
check "traefik: cookie de sesión del administrador con JWT inválido → 401" \
  test "$(http_status "$(treq /api/user "${AOP[@]}" -H "Cookie: ${SESSION_COOKIE}" -H 'Cf-Access-Jwt-Assertion: abc.def.ghi')")" = 401
UNL="$(tok "$(claims s-x2 outsider@ops.example.org)")"
check "traefik: cookie de sesión del administrador con JWT válido no autorizado → sin acceso" \
  status_in "$(treq /api/user "${AOP[@]}" -H "Cookie: ${SESSION_COOKIE}" -H "Cf-Access-Jwt-Assertion: ${UNL}")" 401 403
check "loopback (contraste): la misma cookie sin JWT sí autentica al administrador" \
  test "$(curl -s -H "Cookie: ${SESSION_COOKIE}" "${G}/api/user" | jq -r '.login')" = admin
QUERY='{"queries":[{"refId":"A","datasource":{"uid":"prometheus"},"expr":"1+1","instant":true}],"from":"now-5m","to":"now"}'
check "traefik: Viewer busca dashboards y consulta Prometheus sin cookies" \
  test "$(body "$(treq /api/search "${AOP[@]}" -H "Cf-Access-Jwt-Assertion: ${VT}")" | jq -r 'length > 0') $(http_status "$(treq /api/ds/query "${AOP[@]}" -H "Cf-Access-Jwt-Assertion: ${VT}" -H 'Content-Type: application/json' -d "${QUERY}")")" = "true 200"
check "traefik: host desconocido → 404" \
  test "$(curl -sk --max-time 10 "${AOP[@]}" --resolve "other.access-check.test:${HTTPS_PORT}:127.0.0.1" -o /dev/null -w '%{http_code}' "https://other.access-check.test:${HTTPS_PORT}/")" = 404
# Redes reales del contenedor: obs_net y la de entrada, nunca la de la aplicación (backend,
# Keycloak, PostgreSQL). Por la de entrada sí alcanza a Traefik, incluida su métrica :8082
# (limitación documentada en el runbook): se informa, no se oculta.
GNETS="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$(obs ps -q grafana)" | sed '/^$/d' | sort | paste -sd,)"
check "red: grafana solo en obs_net y la red de entrada, no en la de la aplicación (${GNETS})" \
  test "${GNETS}" = "$(printf '%s\n' "${EDGE_NET}" "${PROJECT}_obs_net" | sort | paste -sd,)"
if obs exec -T grafana sh -c 'wget -q -T 3 -O /dev/null http://traefik:8082/metrics' >/dev/null 2>&1; then
  echo "      límite documentado: grafana alcanza traefik:8082 por la red de entrada"
else echo "      informativo: grafana no alcanzó traefik:8082"; fi

# ── 5b. Navegador real: recuperación por túnel y ruta pública ────────────────
# Chromium y Firefox (Playwright, imagen fijada por digest más el paquete playwright de la misma
# versión). El contenedor del navegador está en obs_net (reenvía su 127.0.0.1:3000 a grafana:3000,
# como `ssh -L`) y en la red de la aplicación de prueba (alcanza a Traefik).
BROWSER_IMAGE="piedrazul-browsercheck:pw1.55.0"
if [ "${BROWSER_CHECK:-1}" = 0 ]; then
  echo "navegador: omitido (BROWSER_CHECK=0) — NO DEMOSTRADO"
else
  if ! docker image inspect "${BROWSER_IMAGE}" >/dev/null 2>&1; then
    mkdir -p "${TMP}/browser-image"
    printf 'FROM mcr.microsoft.com/playwright/python:v1.55.0-noble@sha256:640d578aae63cfb632461d1b0aecb01414e4e020864ac3dd45a868dc0eff3078\nRUN pip install --no-cache-dir --break-system-packages playwright==1.55.0\n' > "${TMP}/browser-image/Dockerfile"
    docker build -q -t "${BROWSER_IMAGE}" "${TMP}/browser-image" >/dev/null 2>&1 || echo "navegador: no se pudo construir ${BROWSER_IMAGE}"
  fi
  chmod a+r "${TMP}/pki/origin-client.pem" "${TMP}/pki/origin-client.key"
  BTOKEN="$(python3 -I "${JWT}" sign "${TMP}/keys" k1 "{${NOW_BASE},\"sub\":\"sub-viewer\",\"email\":\"${VIEWER}\",\"_iat_in\":0,\"_exp_in\":1800}")"
  # El nombre público resuelve al Traefik de prueba por /etc/hosts del contenedor (Playwright lleva
  # el certificado de cliente por un proxy propio que no usa las reglas de resolución de Chromium)
  TRAEFIK_IP="$(docker inspect -f "{{(index .NetworkSettings.Networks \"${APP_NET}\").IPAddress}}" "$(obs ps -q traefik)")"
  BID="$(docker create --network "${PROJECT}_obs_net" --add-host "${GHOST}:${TRAEFIK_IP}" -e GRAFANA_UPSTREAM=grafana:3000 -e "ADMIN_PASSWORD=${ADMIN_PW}" \
    -e "PUBLIC_HOST=${GHOST}" -e "VIEWER_JWT=${BTOKEN}" \
    -e CLIENT_CERT=/pki/origin-client.pem -e CLIENT_KEY=/pki/origin-client.key \
    -v "${ROOT}/infra/tests/grafana-browser-recovery.py:/check.py:ro" -v "${TMP}/pki:/pki:ro" \
    --entrypoint python3 "${BROWSER_IMAGE}" /check.py 2>/dev/null)"
  if [ -n "${BID}" ] && docker network connect "${APP_NET}" "${BID}" >/dev/null 2>&1; then
    timeout 600 docker start -a "${BID}" > "${TMP}/browser.jsonl" 2>"${TMP}/browser.err" || true
    docker rm -f "${BID}" >/dev/null 2>&1
    while IFS= read -r line; do
      jq -e . >/dev/null 2>&1 <<< "${line}" || continue
      name="$(jq -r .name <<< "${line}")"; detail="$(jq -r .detail <<< "${line}")"
      if [ "$(jq -r .ok <<< "${line}")" = true ]; then ok "navegador: ${name}"; else ko "navegador: ${name} (${detail})"; fi
    done < "${TMP}/browser.jsonl"
    check "navegador: 35 comprobaciones informadas (2 navegadores × 2 orígenes × 8 + 3 públicas)" test "$(grep -c '^{' "${TMP}/browser.jsonl")" = 35
    check "navegador: la salida no contiene la contraseña ni el JWT" \
      bash -c "! grep -qF -- '${ADMIN_PW}' '${TMP}/browser.jsonl' '${TMP}/browser.err' && ! grep -qF -- '${BTOKEN}' '${TMP}/browser.jsonl' '${TMP}/browser.err'"
  else
    ko "navegador: contenedor de Playwright (${BROWSER_IMAGE})"
  fi
fi

# ── Verificación del rol (verify_access_alerts.yml real) contra este proyecto ───────────
# Mismas tareas que corre observability.yml tras levantar el stack, en local: obs_dir con el
# compose.yml real y el .env de la prueba
mkdir -p "${TMP}/obsdir" && cp "${OBS}/compose.yml" "${TMP}/obsdir/" && cp "${TMP}/obs.env" "${TMP}/obsdir/.env"
cat > "${TMP}/verify.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_role: {name: observability, tasks_from: access_lists.yml}
    - ansible.builtin.include_role: {name: observability, tasks_from: verify_access_alerts.yml}
EOF
export GRAFANA_VIEWER_EMAILS="${VIEWER},second.viewer@ops.example.org" GRAFANA_EDITOR_EMAILS="${EDITOR}"
VERIFY_VARS=(-e "obs_dir=${TMP}/obsdir" -e "obs_compose_project_name=${PROJECT}" -e obs_s3_receiver=none
             -e '{"obs_compose_files": ["compose.yml"]}'
             -e "obs_s3_public_domain=${GHOST}" -e "obs_s3_team_domain=${TEAM}" -e obs_s3_environment=access-check)
if ansible_local "${TMP}/verify.yml" "${VERIFY_VARS[@]}" -e obs_grafana_access_on=true > "${TMP}/verify-ok.log" 2>&1; then
  ok "rol: verify_access_alerts.yml acepta el stack (Prometheus → Alertmanager, receptor none, Grafana con JWT)"
else ko "rol: verify_access_alerts.yml acepta el stack"; grep -E 'fatal|msg' "${TMP}/verify-ok.log" | head -5; fi
if ! ansible_local "${TMP}/verify.yml" "${VERIFY_VARS[@]}" -e obs_grafana_access_on=false > "${TMP}/verify-ng1.log" 2>&1 \
   && grep -q 'auth.jwt.enabled no es false' "${TMP}/verify-ng1.log"; then
  ok "rol: verify_access_alerts.yml detecta Grafana con JWT cuando se pidió sin Access"
else ko "rol: verify_access_alerts.yml detecta el modo de acceso distinto"; fi
if ! ansible_local "${TMP}/verify.yml" "${VERIFY_VARS[@]}" -e obs_grafana_access_on=true -e obs_s3_receiver=discord > "${TMP}/verify-ng2.log" 2>&1 \
   && grep -q 'no corre con el receptor discord' "${TMP}/verify-ng2.log"; then
  ok "rol: verify_access_alerts.yml detecta un receptor distinto del pedido"
else ko "rol: verify_access_alerts.yml detecta un receptor distinto del pedido"; fi
if ! ansible_local "${TMP}/verify.yml" "${VERIFY_VARS[@]}" -e obs_grafana_access_on=true -e obs_s3_environment=produccion > "${TMP}/verify-ng3.log" 2>&1 \
   && grep -q 'external_labels.environment=produccion' "${TMP}/verify-ng3.log"; then
  ok "rol: verify_access_alerts.yml detecta una etiqueta de entorno distinta de la pedida"
else ko "rol: verify_access_alerts.yml detecta una etiqueta de entorno distinta"; fi
if ! GRAFANA_VIEWER_EMAILS="${VIEWER}" ansible_local "${TMP}/verify.yml" "${VERIFY_VARS[@]}" -e obs_grafana_access_on=true > "${TMP}/verify-ng4.log" 2>&1 \
   && grep -q 'role_attribute_path cargado no es el del archivo de acceso vigente' "${TMP}/verify-ng4.log"; then
  ok "rol: verify_grafana_auth.yml detecta listas cargadas distintas de las pedidas (sin mostrarlas)"
else ko "rol: verify_grafana_auth.yml detecta listas distintas"; fi
check "rol: la verificación no imprime correos" bash -c "! grep -q 'ops.example.org' '${TMP}/verify-ok.log' '${TMP}/verify-ng4.log'"
unset GRAFANA_VIEWER_EMAILS GRAFANA_EDITOR_EMAILS
check "rol: la verificación no imprime la contraseña local" \
  bash -c "! grep -qF -- '${ADMIN_PW}' '${TMP}/verify-ok.log' '${TMP}/verify-ng1.log' '${TMP}/verify-ng2.log'"

# ── 6. Revocación, rotación de claves y JWKS caído ───────────────────────────
# Quitar un correo de la lista y recrear Grafana (lo que hace el rol de Ansible) le quita el
# acceso en la petición siguiente, también con un JWT que ya había usado
access_file second.viewer@ops.example.org "${EDITOR}" || ko "revocación: archivo de acceso"
obs up -d --wait --wait-timeout 120 --force-recreate --no-deps grafana >/dev/null 2>&1
check "revocación: Viewer quitado de la lista → su JWT ya usado da 403" test "$(http_status "$(gapi "${VT}" /api/user)")" = 403
check "revocación: el resto de la lista sigue entrando" \
  test "$(http_status "$(gapi "$(tok "$(claims sub-viewer2 second.viewer@ops.example.org)")" /api/user)")" = 200
check "revocación: el Editor conserva su rol" test "$(body "$(gapi "${ET}" /api/user/orgs)" | jq -r '[.[].role] | join(",")')" = Editor
# Listas vacías explícitas: nadie entra por JWT; JWT sigue habilitado; el administrador local sí
access_file none none || ko "listas vacías: archivo de acceso"
obs up -d --wait --wait-timeout 120 --force-recreate --no-deps grafana >/dev/null 2>&1
deny "listas vacías: Editor" "${ET}" "403"
deny "listas vacías: Viewer restante" "$(tok "$(claims sub-viewer2 second.viewer@ops.example.org)")" "403"
check "listas vacías: Grafana sigue con JWT habilitado y estricto (Access no se desactiva)" \
  bash -c "curl -s -u 'admin:${ADMIN_PW}' '${G}/api/admin/settings' | jq -e '.\"auth.jwt\".enabled == \"true\" and .\"auth.jwt\".role_attribute_strict == \"true\"'"
check "listas vacías: el administrador local por el loopback sigue entrando" admin_login_ok
access_file second.viewer@ops.example.org "${EDITOR}" || ko "restaurar el archivo de acceso"
obs up -d --wait --wait-timeout 120 --force-recreate --no-deps grafana >/dev/null 2>&1
python3 -I "${JWT}" keygen "${TMP}/keys" k2
NEWTOK="$(tok "$(claims sub-viewer2 second.viewer@ops.example.org)" k2)"
deny "clave nueva aún no publicada en el JWKS" "${NEWTOK}" "401"
cp "${TMP}/keys/jwks.json" "${TMP}/www/cdn-cgi/access/certs"
accepted() { test "$(http_status "$(gapi "$1" /api/user)")" = 200; }
T0=$(date +%s); ROT=no
for _ in $(seq 1 20); do if accepted "${NEWTOK}"; then ROT=yes; break; fi; sleep 2; done
check "rotación: la clave publicada se acepta al vencer la caché del JWKS ($(( $(date +%s) - T0 )) s; caché de prueba 20 s, producción 5 m)" test "${ROT}" = yes
obs stop jwks >/dev/null 2>&1
sleep 25
deny "JWKS inalcanzable con la caché vencida: JWT nuevo (falla cerrado)" "$(tok "$(claims "sub-fresh-${RANDOM}" second.viewer@ops.example.org)" k1)" "401 500"
check "recuperación: con el JWKS (Cloudflare) caído, el login local por el loopback funciona" admin_login_ok
obs logs grafana > "${TMP}/grafana.log" 2>&1
check "logs de Grafana: sin la contraseña local ni JWT (eyJ...)" \
  bash -c "! grep -qF -- '${ADMIN_PW}' '${TMP}/grafana.log' && ! grep -q 'eyJ' '${TMP}/grafana.log'"

echo
if [ "${FAILS}" -eq 0 ]; then echo "OK: todas las comprobaciones pasaron"; exit 0; else echo "FALLAS: ${FAILS}"; exit 1; fi
