#!/usr/bin/env bash
# Contrato de Traefik: file provider sin socket de Docker, rutas generadas desde el
# inventario y, si hay Docker, comportamiento real de la imagen fijada.
#
# Uso: infra/tests/traefik-config-check.sh
#   TRAEFIK_CHECK_RUNTIME=0 → solo la parte estática (sin contenedores)
# Requiere: docker (compose v2, buildx), jq, yq (mikefarah v4), openssl, curl,
#   ansible-playbook; para la identidad real (traefik_image.yml, verify_running_images.yml)
#   community.docker 5.x (ANSIBLE_COLLECTIONS_PATH) y python con requests.
#
# Estático:
#   1. Compose: TRAEFIK_IMAGE obligatoria (sin ella Compose falla), sin socket de Docker ni
#      Docker provider, sin labels traefik.* en ningún servicio, solo Traefik publica
#      puertos, montajes de configuración de solo lectura, redirección HTTP → HTTPS,
#      ACME por DNS de Cloudflare y strictTLSOptions.
#   2. Rutas (Ansible, plantilla y validaciones reales): routers backend/keycloak con los
#      hosts del inventario, TLS cloudflare-aop + letsencrypt, servicios por nombre de
#      contenedor y puerto interno; entradas vacías, inyectadas, repetidas o puertos
#      inválidos fallan sin escribir routes.yml.
# Runtime (Docker, red y proyecto propios, upstreams sintéticos traefik/whoami, CA y
#   certificados de cliente sintéticos en lugar del CA de Cloudflare, ACME apuntado a un
#   servidor inexistente: nunca contacta Let's Encrypt ni Cloudflare):
#   3. identidad: traefik_image.yml descarga y valida el manifiesto linux/amd64 fijado, y
#      verify_running_images.yml confirma que el contenedor lo ejecuta; un ref distinto falla
#   4. enrutamiento: HTTPS con certificado de cliente → upstream correcto; sin certificado o
#      con otro CA → rechazado; HTTP → 301 (GET) / 308 (otros métodos) a HTTPS; host desconocido → 404; cabeceras
#      X-Forwarded-* las fija Traefik (las del cliente no se confían)
#   5. ciclo de vida: upstream recreado con otra IP → enrutado sin API de Docker;
#      upstream detenido → 502 visible y vuelve al arrancar; reinicio de Traefik conserva
#      las rutas; cambio de rutas recargado sin reinicio
#   6. fallo visible: un archivo dinámico malformado se registra como error; Traefik
#      conserva la última configuración válida y, si arranca con él, no enruta (404)
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/infra/compose"
ANSIBLE_DIR="${ROOT}/infra/ansible"
TASKS="${ANSIBLE_DIR}/roles/app/tasks"
TEMPLATE="${ANSIBLE_DIR}/roles/app/templates/traefik-routes.yml.j2"
RESOLVE="${ROOT}/.github/scripts/resolve-amd64-image.sh"
# Upstream sintético: traefik/whoami v1.11.0 (índice OCI fijado)
WHOAMI_IMAGE="traefik/whoami:v1.11.0@sha256:200689790a0a0ea48ca45992e0450bc26ccab5307375b41c84dfc4f2475937ab"
# Proyecto Compose propio de esta invocación (también nombra su red y su volumen): la
# limpieza solo alcanza recursos con esta etiqueta, nunca los de otra ejecución o proyecto
PROJECT="traefikcheck-$$-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"

for bin in jq yq ansible-playbook docker; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
W="${TMP}/app"
RUNTIME_STARTED=false
# Contenedor auxiliar: solo se elimina por el Id que devolvió su propia creación
IPHOLDER_ID=""
remove_ipholder() {
  [ -n "${IPHOLDER_ID}" ] || return 0
  docker rm -f "${IPHOLDER_ID}" >/dev/null 2>&1 || true
  IPHOLDER_ID=""
}
cleanup() {
  if [ "${RUNTIME_STARTED}" = true ]; then
    remove_ipholder
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
synthetic() { printf 'synthetic-%s-%s' "$1" "$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }

# ── Entradas sintéticas (.env desde env.j2, image.env) ───────────────────────
printf 'all:\n  hosts:\n    vps:\n      ansible_host: 127.0.0.1\n' > "${TMP}/hosts.yml"
cat > "${TMP}/render-env.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.template:
        src: "{{ env_template }}"
        dest: "{{ env_out }}"
        mode: "0600"
      vars:
        app_db_secrets:
          POSTGRES_PASSWORD: "{{ lookup('env', 'S_PG') }}"
          APP_DB_PASSWORD: "{{ lookup('env', 'S_APP') }}"
          MIGRATION_DB_PASSWORD: "{{ lookup('env', 'S_MIG') }}"
          KC_DB_PASSWORD: "{{ lookup('env', 'S_KCDB') }}"
        app_kc_backend_secret: "{{ lookup('env', 'S_BACKEND') }}"
EOF
ansible_local() { # <playbook> [args...]
  ANSIBLE_CONFIG="${ANSIBLE_DIR}/ansible.cfg" ANSIBLE_BECOME=false ANSIBLE_NOCOLOR=1 \
    ANSIBLE_ROLES_PATH="${TMP}/roles:${ANSIBLE_DIR}/roles" \
    ansible-playbook -i "${ANSIBLE_DIR}/inventories/prod" -i "${TMP}/hosts.yml" \
      -e "compose_src_dir=${COMPOSE_DIR}" "$@" </dev/null
}
mkdir -p "${W}/compose" "${W}/traefik"
if ! ( export S_PG="$(synthetic pg)" S_APP="$(synthetic app)" S_MIG="$(synthetic mig)" S_KCDB="$(synthetic kc)" \
         S_BACKEND="$(synthetic kcb)" CLOUDFLARE_DNS_API_TOKEN="$(synthetic cf)"
       ansible_local "${TMP}/render-env.yml" -e "env_template=${ANSIBLE_DIR}/roles/app/templates/env.j2" \
         -e "env_out=${W}/.env" ) > "${TMP}/render-env.log" 2>&1; then
  ko "env.j2 renderiza con group_vars de producción"
  tail -5 "${TMP}/render-env.log"
  exit 1
fi
TRAEFIK_DECLARED="$(yq -r '.services.traefik.image' "${COMPOSE_DIR}/traefik.yml")"
image_env() { # <TRAEFIK_IMAGE>
  printf 'BACKEND_IMAGE=ghcr.io/example/backend@sha256:%s\nPOSTGRES_IMAGE=ghcr.io/example/postgres@sha256:%s\nKEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:%s\nTRAEFIK_IMAGE=%s\n' \
    "$(printf 'a%.0s' {1..64})" "$(printf 'b%.0s' {1..64})" "$(printf 'c%.0s' {1..64})" "$1"
}
STATIC_TRAEFIK_REF="docker.io/library/traefik@sha256:$(printf 'd%.0s' {1..64})"
image_env "${STATIC_TRAEFIK_REF}" > "${TMP}/image.env"
prod_config() { # <image.env>
  docker compose --project-name piedrazul-traefik-contract --file "${COMPOSE_DIR}/prod.yml" \
    --env-file "${W}/.env" --env-file "$1" --profile migrate config --format json
}

# ── 1. Compose ───────────────────────────────────────────────────────────────
check "Compose: prod.yml resuelve con .env (env.j2) e image.env" bash -c "$(declare -f prod_config); W='${W}' COMPOSE_DIR='${COMPOSE_DIR}'; prod_config '${TMP}/image.env' > '${TMP}/config.json'"
prod_config "${TMP}/image.env" > "${TMP}/config.json" 2>/dev/null || true
grep -v '^TRAEFIK_IMAGE=' "${TMP}/image.env" > "${TMP}/image-no-traefik.env"
check "Compose: sin TRAEFIK_IMAGE falla (nunca cae a un tag)" \
  bash -c "$(declare -f prod_config); W='${W}' COMPOSE_DIR='${COMPOSE_DIR}'; ! prod_config '${TMP}/image-no-traefik.env'"
TCMD="$(jq -r '.services.traefik.command | join("\n")' "${TMP}/config.json")"
check "Compose: traefik corre exactamente TRAEFIK_IMAGE" \
  test "$(jq -r '.services.traefik.image' "${TMP}/config.json")" = "${STATIC_TRAEFIK_REF}"
check "Compose: ningún servicio monta el socket de Docker" \
  bash -c "! jq -r '.services[].volumes[]?.source // empty' '${TMP}/config.json' | grep -q 'docker.sock'"
check "Compose: montajes de traefik = /letsencrypt, dinámica y CA (estas dos de solo lectura)" \
  bash -c "test \"\$(jq -r '[.services.traefik.volumes[] | .target] | sort | join(\",\")' '${TMP}/config.json')\" = '/certs,/etc/traefik/dynamic,/letsencrypt' && test \"\$(jq -r '[.services.traefik.volumes[] | select(.target != \"/letsencrypt\") | .read_only] | all' '${TMP}/config.json')\" = true"
check "Compose: sin Docker provider" bash -c "! grep -q 'providers.docker' <<< \"\$1\"" _ "${TCMD}"
check "Compose: file provider con watch sobre /etc/traefik/dynamic" \
  bash -c "grep -qx -- '--providers.file.directory=/etc/traefik/dynamic' <<< \"\$1\" && grep -qx -- '--providers.file.watch=true' <<< \"\$1\"" _ "${TCMD}"
check "Compose: core.strictTLSOptions=true" bash -c "grep -qx -- '--core.strictTLSOptions=true' <<< \"\$1\"" _ "${TCMD}"
check "Compose: redirección permanente web → websecure (https)" \
  bash -c "grep -qx -- '--entrypoints.web.http.redirections.entrypoint.to=websecure' <<< \"\$1\" && grep -qx -- '--entrypoints.web.http.redirections.entrypoint.scheme=https' <<< \"\$1\" && grep -qx -- '--entrypoints.web.http.redirections.entrypoint.permanent=true' <<< \"\$1\"" _ "${TCMD}"
check "Compose: resolver letsencrypt por DNS de Cloudflare con almacenamiento persistente" \
  bash -c "grep -qx -- '--certificatesresolvers.letsencrypt.acme.dnschallenge.provider=cloudflare' <<< \"\$1\" && grep -qx -- '--certificatesresolvers.letsencrypt.acme.storage=/letsencrypt/acme.json' <<< \"\$1\" && test \"\$(jq -r '.services.traefik.volumes[] | select(.target == \"/letsencrypt\") | .type' '${TMP}/config.json')\" = volume" _ "${TCMD}"
check "Compose: ningún servicio declara labels traefik.*" \
  bash -c "! jq -r '.services[].labels // {} | keys[]' '${TMP}/config.json' | grep -q '^traefik\\.'"
check "Compose: solo traefik publica puertos (80 y 443)" \
  bash -c "test \"\$(jq -r '[.services | to_entries[] | select((.value.ports // []) | length > 0) | .key] | join(\",\")' '${TMP}/config.json')\" = traefik && test \"\$(jq -r '[.services.traefik.ports[].target | tonumber] | sort | map(tostring) | join(\",\")' '${TMP}/config.json')\" = '80,443'"
check "Compose: traefik, backend y keycloak comparten piedrazul_net" \
  bash -c "for s in traefik backend keycloak; do jq -e --arg s \"\$s\" '.services[\$s].networks | has(\"piedrazul_net\")' '${TMP}/config.json' || exit 1; done"
check "Repositorio: sin configuración del Docker provider (labels ni flags) en compose ni Ansible" \
  bash -c "! grep -rqE 'traefik\\.(enable|http\\.)|providers\\.docker|docker\\.sock' '${COMPOSE_DIR}' '${ANSIBLE_DIR}/roles'"
check "Repositorio: infra/traefik/dynamic solo versiona tls.yml (routes.yml lo genera Ansible)" \
  test "$(cd "${ROOT}/infra/traefik/dynamic" && ls)" = tls.yml
check "tls.yml: cloudflare-aop exige certificado de cliente del CA montado en /certs" \
  bash -c "test \"\$(yq -r '.tls.options.cloudflare-aop.clientAuth.clientAuthType' '${ROOT}/infra/traefik/dynamic/tls.yml')\" = RequireAndVerifyClientCert && f=\$(yq -r '.tls.options.cloudflare-aop.clientAuth.caFiles[0]' '${ROOT}/infra/traefik/dynamic/tls.yml') && test -s '${ROOT}/infra/traefik/certs/'\"\${f#/certs/}\""

# ── 2. Rutas (Ansible) ───────────────────────────────────────────────────────
# Rol mínimo con la tarea y la plantilla reales; solo se quitan owner/group (el usuario
# ansible del servidor no existe aquí). Se exige que sea la única diferencia.
mkdir -p "${TMP}/roles/traefikcheck/tasks" "${TMP}/roles/traefikcheck/templates"
grep -vE '^ +(owner|group): ansible$' "${TASKS}/traefik_routes.yml" > "${TMP}/roles/traefikcheck/tasks/main.yml"
cp "${TEMPLATE}" "${TMP}/roles/traefikcheck/templates/"
check "rutas: la copia de prueba de traefik_routes.yml solo difiere en owner/group" \
  test "$(diff "${TASKS}/traefik_routes.yml" "${TMP}/roles/traefikcheck/tasks/main.yml" | grep -c '^<')" = 2
cat > "${TMP}/routes.yml" <<'EOF'
- hosts: vps
  gather_facts: false
  connection: local
  tasks:
    - ansible.builtin.include_role: {name: traefikcheck}
EOF
render_routes() { # <app_dir> [ansible args...]
  local dir="$1"; shift
  mkdir -p "${dir}/traefik/dynamic"
  ansible_local "${TMP}/routes.yml" -e "app_dir=${dir}" "$@"
}
R="${TMP}/render-prod"
if render_routes "${R}" > "${TMP}/routes.log" 2>&1; then ok "rutas: renderizan con el inventario de producción"; else ko "rutas: renderizan con el inventario de producción"; grep -E 'fatal|FAILED|msg' "${TMP}/routes.log" || true; fi
RF="${R}/traefik/dynamic/routes.yml"
# Misma autoridad que el .env: env.j2 se renderizó con el mismo inventario
API_HOST="$(sed -n 's/^API_PUBLIC_DOMAIN=//p' "${W}/.env")"
KC_HOST="$(sed -n 's/^KC_HOSTNAME=//p' "${W}/.env")"
check "rutas: el inventario da hosts distintos y no vacíos (${API_HOST:-?}, ${KC_HOST:-?})" \
  bash -c "[ -n '${API_HOST}' ] && [ -n '${KC_HOST}' ] && [ '${API_HOST}' != '${KC_HOST}' ]"
check "rutas: router backend → Host(${API_HOST}), websecure, servicio backend" \
  bash -c "test \"\$(yq -r '.http.routers.backend | [.rule, (.entryPoints | join(\",\")), .service] | join(\"|\")' '${RF}')\" = 'Host(\`${API_HOST}\`)|websecure|backend'"
check "rutas: router keycloak → Host(${KC_HOST}), websecure, servicio keycloak" \
  bash -c "test \"\$(yq -r '.http.routers.keycloak | [.rule, (.entryPoints | join(\",\")), .service] | join(\"|\")' '${RF}')\" = 'Host(\`${KC_HOST}\`)|websecure|keycloak'"
check "rutas: ambos routers con TLS cloudflare-aop y certResolver letsencrypt" \
  test "$(yq -r '[.http.routers[].tls | .options + "/" + .certResolver] | unique | join(",")' "${RF}")" = "cloudflare-aop/letsencrypt"
SERVER_PORT="$(sed -n 's/^SERVER_PORT=//p' "${W}/.env")"
KC_PORT="$(sed -n 's/^KC_PORT=//p' "${W}/.env")"
check "rutas: servicios por nombre de contenedor y puerto interno del .env (backend:${SERVER_PORT}, keycloak:${KC_PORT})" \
  bash -c "test \"\$(yq -r '.http.services.backend.loadBalancer.servers[0].url' '${RF}')\" = 'http://backend:${SERVER_PORT}' && test \"\$(yq -r '.http.services.keycloak.loadBalancer.servers[0].url' '${RF}')\" = 'http://keycloak:${KC_PORT}'"
check "rutas: sin llaves de plantilla en el resultado (Traefik también interpreta Go templates)" \
  bash -c "! grep -q '{{' '${RF}'"
reject_routes() { # <descripción> [ansible args...]
  local desc="$1" dir; shift
  dir="${TMP}/reject-$((RANDOM))"
  if ! render_routes "${dir}" "$@" > "${dir}.log" 2>&1 && grep -q 'RUTAS DE TRAEFIK' "${dir}.log" \
     && [ ! -e "${dir}/traefik/dynamic/routes.yml" ]; then
    ok "rutas rechazadas sin escribir: ${desc}"
  else
    ko "rutas rechazadas sin escribir: ${desc}"
  fi
}
reject_routes "api_public_domain vacío" -e api_public_domain=
reject_routes "host con comilla invertida (inyección en la regla)" -e 'api_public_domain=api.example.org`) || Host(`x.org'
reject_routes "host en mayúsculas" -e kc_hostname=Auth.example.org
reject_routes "mismo host para backend y keycloak" -e "kc_hostname=${API_HOST}"
reject_routes "puerto no numérico" -e server_port=http

# ── Runtime ──────────────────────────────────────────────────────────────────
if [ "${TRAEFIK_CHECK_RUNTIME:-1}" = 0 ]; then
  echo "runtime: omitido (TRAEFIK_CHECK_RUNTIME=0) — NO DEMOSTRADO"
elif ! docker info >/dev/null 2>&1; then
  echo "runtime: Docker no disponible — NO DEMOSTRADO"
  FAILS=$((FAILS + 1))
else
  for c in traefik backend keycloak; do
    if docker container inspect "$c" >/dev/null 2>&1; then
      echo "runtime: ya existe un contenedor '$c' (nombre fijo de producción); no se corre" >&2
      exit 2
    fi
  done
  RUNTIME_STARTED=true
  # Layout del servidor: compose/ (traefik.yml real + archivo de prueba), traefik/dynamic
  # (tls.yml real + routes.yml de Ansible), traefik/certs (CA sintético con el nombre del de
  # Cloudflare)
  cp "${COMPOSE_DIR}/traefik.yml" "${W}/compose/traefik.yml"
  mkdir -p "${W}/traefik/dynamic" "${W}/traefik/certs" "${TMP}/pki"
  cp "${ROOT}/infra/traefik/dynamic/tls.yml" "${W}/traefik/dynamic/"
  CA_FILE="$(yq -r '.tls.options.cloudflare-aop.clientAuth.caFiles[0]' "${ROOT}/infra/traefik/dynamic/tls.yml")"
  pki() { # <nombre> → CA <nombre>-ca y cliente <nombre>-client firmado por él
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$1 test CA" \
      -keyout "${TMP}/pki/$1-ca.key" -out "${TMP}/pki/$1-ca.pem" 2>/dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$1 client" \
      -keyout "${TMP}/pki/$1-client.key" -out "${TMP}/pki/$1-client.csr" 2>/dev/null
    openssl x509 -req -in "${TMP}/pki/$1-client.csr" -CA "${TMP}/pki/$1-ca.pem" -CAkey "${TMP}/pki/$1-ca.key" \
      -CAcreateserial -days 1 -out "${TMP}/pki/$1-client.pem" 2>/dev/null
  }
  pki origin
  pki other
  cp "${TMP}/pki/origin-ca.pem" "${W}/traefik/certs/${CA_FILE#/certs/}"

  T_API=api.traefik-check.test
  T_AUTH=auth.traefik-check.test
  if render_routes "${W}" -e "api_public_domain=${T_API}" -e "kc_hostname=${T_AUTH}" > "${TMP}/routes-rt.log" 2>&1; then
    ok "runtime: routes.yml generado por Ansible para ${T_API} / ${T_AUTH}"
  else
    ko "runtime: routes.yml generado por Ansible"; grep -E 'fatal|msg' "${TMP}/routes-rt.log" || true
  fi

  # Identidad: el workflow resuelve la referencia declarada a su manifiesto linux/amd64
  IDENTITY="$(DOCKER_CONFIG="${TMP}/dockercfg" bash -c "mkdir -p \"\$DOCKER_CONFIG\" && echo '{}' > \"\$DOCKER_CONFIG/config.json\" && '${RESOLVE}' '${TRAEFIK_DECLARED}'")" || IDENTITY=""
  T_INDEX="$(sed -n 's/^image_index_digest=//p' <<< "${IDENTITY}")"
  T_REF="$(sed -n 's/^image_ref=//p' <<< "${IDENTITY}")"
  check "runtime: ${TRAEFIK_DECLARED} resuelve a un manifiesto linux/amd64 (${T_REF:-sin resolver})" \
    bash -c "[[ '${T_REF}' =~ ^docker\\.io/library/traefik@sha256:[0-9a-f]{64}$ ]] && [ '${T_INDEX}' = '${TRAEFIK_DECLARED#*@}' ]"
  image_env "${T_REF}" > "${W}/image.env"

  # traefik_image.yml real: descarga por digest y valida plataforma y versión
  cat > "${TMP}/identity.yml" <<EOF
- hosts: vps
  gather_facts: false
  connection: local
  vars:
    ansible_python_interpreter: "${TRAEFIK_CHECK_PYTHON:-$(command -v python3)}"
  tasks:
    - ansible.builtin.include_role: {name: app, tasks_from: "{{ task_file }}"}
EOF
  if ansible_local "${TMP}/identity.yml" -e task_file=traefik_image.yml -e "traefik_image_ref=${T_REF}" \
       -e "traefik_image_index_digest=${T_INDEX}" > "${TMP}/identity.log" 2>&1; then
    ok "runtime: traefik_image.yml acepta la identidad resuelta (linux/amd64, versión del tag)"
  else
    ko "runtime: traefik_image.yml acepta la identidad resuelta"; grep -E 'fatal|msg' "${TMP}/identity.log" | head -5 || true
  fi
  if ! ansible_local "${TMP}/identity.yml" -e task_file=traefik_image.yml -e "traefik_image_ref=docker.io/library/traefik@${T_INDEX}" \
       -e "traefik_image_index_digest=${T_INDEX}" > "${TMP}/identity-neg.log" 2>&1 && grep -q 'IDENTIDAD DE TRAEFIK' "${TMP}/identity-neg.log"; then
    ok "runtime: traefik_image.yml rechaza el índice en lugar del manifiesto"
  else
    ko "runtime: traefik_image.yml rechaza el índice en lugar del manifiesto"
  fi

  # Proyecto de prueba: traefik.yml real (imagen TRAEFIK_IMAGE como en prod.yml) + upstreams
  # sintéticos con los nombres y puertos de producción, red propia. Al comando real solo se
  # agregan el log y un servidor ACME inexistente.
  {
    echo "name: ${PROJECT}"
    echo "networks:"
    echo "  piedrazul_net:"
    echo "    name: ${PROJECT}_net"
    echo "include:"
    echo "  - ./traefik.yml"
    echo "services:"
    echo "  traefik:"
    echo "    image: \${TRAEFIK_IMAGE:?}"
    echo "    ports: !override"
    echo "      - \"127.0.0.1::80\""
    echo "      - \"127.0.0.1::443\""
    echo "    command:"
    yq -r '.services.traefik.command[] | "      - \"" + . + "\""' "${COMPOSE_DIR}/traefik.yml"
    echo "      - \"--log.level=DEBUG\""
    echo "      - \"--certificatesresolvers.letsencrypt.acme.caserver=https://127.0.0.1:9/directory\""
    for svc in backend:"${SERVER_PORT}" keycloak:"${KC_PORT}"; do
      echo "  ${svc%%:*}:"
      echo "    image: ${WHOAMI_IMAGE}"
      echo "    container_name: ${svc%%:*}"
      echo "    command: [\"--port\", \"${svc##*:}\", \"--name\", \"${svc%%:*}\"]"
      echo "    networks: [piedrazul_net]"
    done
  } > "${W}/compose/check.yml"
  compose() { docker compose --project-name "${PROJECT}" --project-directory "${W}/compose" --file "${W}/compose/check.yml" --env-file "${W}/.env" --env-file "${W}/image.env" "$@"; }

  if compose up -d --wait --wait-timeout 120 > "${TMP}/up.log" 2>&1; then ok "runtime: traefik y upstreams arrancan"; else ko "runtime: traefik y upstreams arrancan"; tail -5 "${TMP}/up.log"; fi
  # Puertos efímeros del host: Docker los reasigna en cada reinicio de traefik
  read_ports() {
    HTTP_PORT="$(compose port traefik 80 | sed 's/.*://')"
    HTTPS_PORT="$(compose port traefik 443 | sed 's/.*://')"
  }
  read_ports
  req() { # <host> [curl args...] → cuerpo; código en la última línea
    local host="$1"; shift
    # whoami responde con fin de línea CRLF
    curl -sk --max-time 10 --resolve "${host}:${HTTPS_PORT}:127.0.0.1" -w '\n%{http_code}' "$@" "https://${host}:${HTTPS_PORT}/probe" | tr -d '\r'
  }
  as_origin=(--cert "${TMP}/pki/origin-client.pem" --key "${TMP}/pki/origin-client.key")
  code() { tail -n1 <<< "$1"; }
  wait_code() { # <esperado> <host> → espera hasta 30 s a que el host responda <esperado>
    local i out
    for i in $(seq 1 30); do
      out="$(req "$2" "${as_origin[@]}" 2>/dev/null || true)"
      [ "$(code "${out}")" = "$1" ] && return 0
      sleep 1
    done
    return 1
  }
  wait_code 200 "${T_API}" || true

  # 3. Identidad en ejecución (verify_running_images.yml real) y sin socket
  if ansible_local "${TMP}/identity.yml" -e task_file=verify_running_images.yml -e "traefik_image_ref=${T_REF}" \
       -e '{"app_release_services": ["traefik"]}' > "${TMP}/verify.log" 2>&1; then
    ok "runtime: verify_running_images.yml confirma que traefik ejecuta ${T_REF}"
  else
    ko "runtime: verify_running_images.yml confirma la identidad en ejecución"; grep -E 'fatal|msg' "${TMP}/verify.log" | head -5 || true
  fi
  if ! ansible_local "${TMP}/identity.yml" -e task_file=verify_running_images.yml \
       -e "traefik_image_ref=docker.io/library/traefik@sha256:$(printf 'e%.0s' {1..64})" \
       -e '{"app_release_services": ["traefik"]}' > "${TMP}/verify-neg.log" 2>&1 && grep -q 'IDENTIDAD EN EJECUCIÓN' "${TMP}/verify-neg.log"; then
    ok "runtime: verify_running_images.yml falla con otra identidad esperada"
  else
    ko "runtime: verify_running_images.yml falla con otra identidad esperada"
  fi
  check "runtime: el contenedor traefik no monta el socket de Docker" \
    bash -c "! docker inspect traefik --format '{{range .Mounts}}{{.Source}} {{end}}' | grep -q docker.sock"

  # 4. Enrutamiento
  OUT="$(req "${T_API}" "${as_origin[@]}" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Forwarded-Proto: http' -H 'X-Forwarded-Host: evil.example' || true)"
  check "runtime: ${T_API} con certificado de origen → backend (200)" \
    bash -c "[ '$(code "${OUT}")' = 200 ] && grep -q '^Name: backend' <<< \"\$1\"" _ "${OUT}"
  check "runtime: X-Forwarded-Proto/Host/For los fija Traefik (https, host de la petición); los del cliente no se confían" \
    bash -c "grep -qx 'X-Forwarded-Proto: https' <<< \"\$1\" && grep -qx 'X-Forwarded-Host: ${T_API}:${HTTPS_PORT}' <<< \"\$1\" && ! grep -q '6.6.6.6' <<< \"\$1\" && ! grep -q 'evil.example' <<< \"\$1\" && grep -q '^X-Forwarded-For: ' <<< \"\$1\"" _ "${OUT}"
  OUT="$(req "${T_AUTH}" "${as_origin[@]}" || true)"
  check "runtime: ${T_AUTH} con certificado de origen → keycloak (200)" \
    bash -c "[ '$(code "${OUT}")' = 200 ] && grep -q '^Name: keycloak' <<< \"\$1\"" _ "${OUT}"
  check "runtime: sin certificado de cliente → handshake rechazado (AOP)" \
    bash -c "! curl -sk --max-time 10 --resolve '${T_API}:${HTTPS_PORT}:127.0.0.1' -o /dev/null 'https://${T_API}:${HTTPS_PORT}/'"
  check "runtime: certificado de cliente de otro CA → rechazado" \
    bash -c "! curl -sk --max-time 10 --cert '${TMP}/pki/other-client.pem' --key '${TMP}/pki/other-client.key' --resolve '${T_AUTH}:${HTTPS_PORT}:127.0.0.1' -o /dev/null 'https://${T_AUTH}:${HTTPS_PORT}/'"
  REDIR="$(curl -s --max-time 10 --resolve "${T_API}:${HTTP_PORT}:127.0.0.1" -o /dev/null -w '%{http_code} %{redirect_url}' "http://${T_API}:${HTTP_PORT}/a?b=1")"
  check "runtime: HTTP GET → 301 permanente a https://${T_API}/a?b=1 (${REDIR})" test "${REDIR}" = "301 https://${T_API}/a?b=1"
  REDIR="$(curl -s --max-time 10 -X POST --resolve "${T_API}:${HTTP_PORT}:127.0.0.1" -o /dev/null -w '%{http_code} %{redirect_url}' "http://${T_API}:${HTTP_PORT}/a")"
  check "runtime: HTTP POST → 308 permanente (conserva el método) a https://${T_API}/a (${REDIR})" test "${REDIR}" = "308 https://${T_API}/a"
  OUT="$(curl -sk --max-time 10 --resolve "unknown.traefik-check.test:${HTTPS_PORT}:127.0.0.1" -w '\n%{http_code}' "https://unknown.traefik-check.test:${HTTPS_PORT}/" || true)"
  check "runtime: host desconocido → 404 (sin ruta implícita)" test "$(code "${OUT}")" = 404

  # 5. Ciclo de vida de los upstreams y de Traefik
  OLD_IP="$(docker inspect backend --format "{{(index .NetworkSettings.Networks \"${PROJECT}_net\").IPAddress}}")"
  compose stop backend >/dev/null 2>&1
  OUT="$(req "${T_API}" "${as_origin[@]}" || true)"
  check "runtime: backend detenido → 502 visible (no 404 ni otra ruta)" test "$(code "${OUT}")" = 502
  # Otro contenedor ocupa la IP liberada: el backend recreado recibe otra
  # Si la creación falla no hay Id y no se elimina nada (ni un contenedor ajeno con ese nombre)
  IPHOLDER_ID="$(docker run -d --rm --name "${PROJECT}-ipholder" --label "com.docker.compose.project=${PROJECT}" \
    --network "${PROJECT}_net" --ip "${OLD_IP}" "${WHOAMI_IMAGE}" 2>/dev/null)" || IPHOLDER_ID=""
  compose up -d --force-recreate --no-deps --wait backend >/dev/null 2>&1
  NEW_IP="$(docker inspect backend --format "{{(index .NetworkSettings.Networks \"${PROJECT}_net\").IPAddress}}")"
  NEW_ID="$(docker inspect backend --format '{{slice .Id 0 12}}')"
  OUT=""; wait_code 200 "${T_API}" && OUT="$(req "${T_API}" "${as_origin[@]}" || true)"
  check "runtime: backend recreado con otra IP (${OLD_IP} → ${NEW_IP}) → enrutado al contenedor nuevo sin API de Docker" \
    bash -c "[ '${OLD_IP}' != '${NEW_IP}' ] && [ '$(code "${OUT}")' = 200 ] && grep -qx 'Hostname: ${NEW_ID}' <<< \"\$1\"" _ "${OUT}"
  remove_ipholder
  compose restart traefik >/dev/null 2>&1
  read_ports
  check "runtime: reinicio de Traefik conserva ambas rutas" bash -c "$(declare -f req wait_code code); HTTPS_PORT='${HTTPS_PORT}'; as_origin=(--cert '${TMP}/pki/origin-client.pem' --key '${TMP}/pki/origin-client.key'); wait_code 200 '${T_API}' && wait_code 200 '${T_AUTH}'"
  T_API2=api2.traefik-check.test
  render_routes "${W}" -e "api_public_domain=${T_API2}" -e "kc_hostname=${T_AUTH}" >/dev/null 2>&1 || true
  check "runtime: cambio de rutas por Ansible recargado sin reiniciar (${T_API2} 200, ${T_API} 404)" \
    bash -c "$(declare -f req wait_code code); HTTPS_PORT='${HTTPS_PORT}'; as_origin=(--cert '${TMP}/pki/origin-client.pem' --key '${TMP}/pki/origin-client.key'); wait_code 200 '${T_API2}' && wait_code 404 '${T_API}'"
  render_routes "${W}" -e "api_public_domain=${T_API}" -e "kc_hostname=${T_AUTH}" >/dev/null 2>&1 || true
  wait_code 200 "${T_API}" || true

  # 6. Configuración dinámica malformada
  SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'http:\n  routers: [\n' > "${W}/traefik/dynamic/broken.yml"
  sleep 3
  check "runtime: archivo dinámico malformado → error en el log de Traefik" \
    bash -c "docker logs --since '${SINCE}' traefik 2>&1 | grep -i 'error' | grep -qi -e 'broken.yml' -e 'yaml' -e 'file'"
  OUT="$(req "${T_API}" "${as_origin[@]}" || true)"
  check "runtime: con el archivo malformado en caliente se conserva la última configuración válida (200)" test "$(code "${OUT}")" = 200
  compose restart traefik >/dev/null 2>&1
  read_ports
  sleep 3
  OUT="$(req "${T_API}" "${as_origin[@]}" || true)"
  check "runtime: arrancar con configuración malformada no enruta (fallo visible, no ruta parcial)" bash -c "[ '$(code "${OUT}")' != 200 ]"
  rm -f "${W}/traefik/dynamic/broken.yml"
  check "runtime: retirado el archivo malformado, las rutas vuelven sin reiniciar" bash -c "$(declare -f req wait_code code); HTTPS_PORT='${HTTPS_PORT}'; as_origin=(--cert '${TMP}/pki/origin-client.pem' --key '${TMP}/pki/origin-client.key'); wait_code 200 '${T_API}'"
  check "runtime: ACME nunca contactó Let's Encrypt ni Cloudflare (servidor ACME local inexistente)" \
    bash -c "! docker logs traefik 2>&1 | grep -qi -e 'acme-v02.api.letsencrypt.org' -e 'api.cloudflare.com'"
fi

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "traefik-config-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "traefik-config-check: todo PASS"
