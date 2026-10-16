#!/usr/bin/env bash
# Publicación del frontend en Cloudflare Pages dentro de la cadena de producción.
#
# Uso:
#   pages-release.sh target
#   pages-release.sh preflight
#   pages-release.sh publish <site_dir>
#
# target (sin credenciales): destino declarado por Terraform en este commit. El proyecto
#   de Pages es var.project (locals.tf: pages_name = var.project), la cuenta es
#   cloudflare_account_id y el dominio propio es base_domain (frontend_domain), todos en
#   PAGES_TFVARS (por defecto infra/terraform/live/prod/piedrazul.auto.tfvars). La rama de
#   producción es main (módulo pages en live/prod/main.tf). Nada se adivina: un valor
#   ausente o con otra forma falla.
#   Salida: project_name, account_id, production_branch, custom_domain.
#
# preflight (solo lectura; CLOUDFLARE_API_TOKEN de Pages, PAGES_ACCOUNT_ID, PAGES_PROJECT,
#   PAGES_BRANCH, PAGES_DOMAIN): antes de cualquier mutación del run, el token lee el
#   proyecto y el proyecto es el declarado (nombre, rama de producción, dominio propio).
#   Autoridad única: falla si la integración Git del proyecto puede publicar producción
#   por su cuenta. Solo se acepta production_deployments_enabled=false (o el
#   deployments_enabled=false heredado, que apaga también las previews); true, ausente,
#   ilegible o un proyecto sin integración Git reconocible fallan cerrado.
#   Salida: git_production_deploys=disabled, previous_deployment_id,
#   previous_deployment_commit, production_url (alias *.pages.dev de producción).
#
# publish (CLOUDFLARE_API_TOKEN, mismas PAGES_*, PREVIOUS_DEPLOYMENT_ID y PRODUCTION_URL del
#   preflight, GITHUB_SHA, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, ENTRY_ASSET, WRANGLER_BIN):
#   1. vuelve a exigir la autoridad única y que el deployment de producción vigente siga
#      siendo PREVIOUS_DEPLOYMENT_ID (nadie publicó durante el run);
#   2. sube <site_dir> tal cual con `wrangler pages deploy` (sin build) a la rama de
#      producción, con el commit y un mensaje propio de este intento
#      ("production-hetzner <run_id>/<intento>");
#   3. identidad: el deployment es el que creó ESTA invocación. wrangler escribe el id que
#      devolvió su propia creación en WRANGLER_OUTPUT_FILE_PATH (entrada
#      pages-deploy-detailed); debe haber exactamente una, de este proyecto y commit, y
#      distinta de la producción anterior. La API debe confirmar ese id como deployment
#      de producción ad_hoc de la rama main, con este commit y este mensaje, terminado en
#      deploy: success, único con este mensaje y sin otro deployment de producción más
#      nuevo. Coincidir solo en el commit nunca basta (un build Git o un re-run pueden
#      compartirlo); cualquier ambigüedad falla;
#   4. vigencia y contenido: ese id es el canonical_deployment del proyecto; su URL propia
#      y el alias de producción sirven un index.html que carga ENTRY_ASSET con los mismos
#      bytes que el artefacto validado; y sigue siendo el canonical después de comprobar
#      el contenido (un reemplazo entre ambas lecturas falla).
#   No se verifica el dominio propio: puede estar detrás de Cloudflare Access.
#   Salida: deployment_id, deployment_url.
#
# El token nunca va en la línea de comandos. CF_API_URL, PAGES_POLL_ATTEMPTS y
# PAGES_POLL_INTERVAL existen para las pruebas.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

API="${CF_API_URL:-https://api.cloudflare.com/client/v4}"
POLL_ATTEMPTS="${PAGES_POLL_ATTEMPTS:-12}"
POLL_INTERVAL="${PAGES_POLL_INTERVAL:-10}"
PRODUCTION_BRANCH=main

cf() { # <ruta>
  curl -sS --fail-with-body --max-time 30 \
    -H @<(printf 'Authorization: Bearer %s\n' "${CLOUDFLARE_API_TOKEN}") \
    "${API}$1"
}

require_target() {
  : "${CLOUDFLARE_API_TOKEN:?CLOUDFLARE_API_TOKEN no definido}"
  : "${PAGES_ACCOUNT_ID:?}" "${PAGES_PROJECT:?}" "${PAGES_BRANCH:?}" "${PAGES_DOMAIN:?}"
  [[ "${PAGES_ACCOUNT_ID}" =~ ^[0-9a-f]{32}$ ]] || fail "PAGES_ACCOUNT_ID inválido"
  [[ "${PAGES_PROJECT}" =~ ^[a-z0-9][a-z0-9-]{0,57}$ ]] || fail "PAGES_PROJECT inválido"
  [ "${PAGES_BRANCH}" = "${PRODUCTION_BRANCH}" ] || fail "la rama de producción de Pages debe ser ${PRODUCTION_BRANCH}"
}

project() {
  local body
  body=$(cf "/accounts/${PAGES_ACCOUNT_ID}/pages/projects/${PAGES_PROJECT}") ||
    fail "no se pudo leer el proyecto ${PAGES_PROJECT} (token sin permiso de Pages, cuenta u proyecto incorrectos)"
  jq -e '.success == true and (.result | type == "object")' <<< "${body}" > /dev/null ||
    fail "respuesta inválida al leer el proyecto ${PAGES_PROJECT}"
  jq -c '.result' <<< "${body}"
}

cmd_target() {
  local tfvars="${PAGES_TFVARS:-infra/terraform/live/prod/piedrazul.auto.tfvars}" project account domain
  [ -f "${tfvars}" ] || fail "no existe ${tfvars}"
  value() { # <variable> → valor de `variable = "valor"`, exactamente una vez
    local v
    v=$(sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"([^\"]*)\"[[:space:]]*(#.*)?$/\1/p" "${tfvars}")
    [ "$(grep -c . <<< "${v}")" = 1 ] || fail "$1 debe declararse exactamente una vez en ${tfvars}"
    echo "${v}"
  }
  project=$(value project)
  account=$(value cloudflare_account_id)
  domain=$(value base_domain)
  [[ "${project}" =~ ^[a-z0-9][a-z0-9-]{0,57}$ ]] || fail "project '${project}' no es un nombre de proyecto de Pages"
  [[ "${account}" =~ ^[0-9a-f]{32}$ ]] || fail "cloudflare_account_id '${account}' inválido"
  [[ "${domain}" =~ ^([a-z0-9-]+\.)+[a-z]{2,}$ ]] || fail "base_domain '${domain}' inválido"
  echo "project_name=${project}"
  echo "account_id=${account}"
  echo "production_branch=${PRODUCTION_BRANCH}"
  echo "custom_domain=${domain}"
}

# git_production_deploys <proyecto JSON> → disabled | enabled | unknown
git_production_deploys() {
  jq -r '
    if (.source | type) != "object" or ((.source.type // "") | IN("github", "gitlab") | not) then "unknown"
    elif .source.config.deployments_enabled == false then "disabled"
    elif .source.config.production_deployments_enabled == false then "disabled"
    elif .source.config.production_deployments_enabled == true then "enabled"
    else "unknown" end' <<< "$1"
}

require_single_authority() { # <proyecto JSON>
  local state
  state=$(git_production_deploys "$1")
  [ "${state}" = disabled ] ||
    fail "la integración Git de ${PAGES_PROJECT} puede publicar producción por su cuenta (deployments automáticos de producción: ${state}); la cadena no publica mientras haya dos autoridades. Requiere desactivar 'automatic production branch deployments' (Branch control)"
}

cmd_preflight() {
  local p subdomain
  require_target
  p=$(project)
  jq -e --arg n "${PAGES_PROJECT}" '.name == $n' <<< "${p}" > /dev/null ||
    fail "el proyecto leído no es ${PAGES_PROJECT}"
  jq -e --arg b "${PAGES_BRANCH}" '.production_branch == $b' <<< "${p}" > /dev/null ||
    fail "la rama de producción de ${PAGES_PROJECT} es '$(jq -r '.production_branch' <<< "${p}")', no ${PAGES_BRANCH}"
  jq -e --arg d "${PAGES_DOMAIN}" '(.domains // []) | index($d) != null' <<< "${p}" > /dev/null ||
    fail "${PAGES_PROJECT} no sirve el dominio declarado ${PAGES_DOMAIN}"
  subdomain=$(jq -r '.subdomain // ""' <<< "${p}")
  [[ "${subdomain}" =~ ^[a-z0-9-]+\.pages\.dev$ ]] || fail "subdominio de producción inválido: '${subdomain}'"
  require_single_authority "${p}"
  echo "git_production_deploys=disabled"
  echo "previous_deployment_id=$(jq -r '.canonical_deployment.id // ""' <<< "${p}")"
  echo "previous_deployment_commit=$(jq -r '.canonical_deployment.deployment_trigger.metadata.commit_hash // ""' <<< "${p}")"
  echo "production_url=https://${subdomain}"
}

sha_of() { sha256sum | cut -d' ' -f1; }

canonical_id() { project | jq -r '.canonical_deployment.id // ""'; }

# serves_artifact <url base>: index.html carga ENTRY_ASSET y ENTRY_ASSET tiene los bytes validados
serves_artifact() {
  local base="$1" remote index
  remote=$(curl -sS --fail --max-time 30 "${base}/${ENTRY_ASSET}" | sha_of) || return 1
  # Capturado, no en tubería: grep -q cortaría la salida de curl (SIGPIPE con pipefail)
  index=$(curl -sS --fail --max-time 30 "${base}/") || return 1
  [ "${remote}" = "${LOCAL_SHA}" ] && grep -qF "${ENTRY_ASSET}" <<< "${index}"
}

cmd_publish() {
  local site="$1" p marker out entries entry id url deployment deployments stage i ok canonical created
  require_target
  : "${GITHUB_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}" "${ENTRY_ASSET:?}" "${WRANGLER_BIN:?}"
  : "${PREVIOUS_DEPLOYMENT_ID?PREVIOUS_DEPLOYMENT_ID no definido (salida del preflight)}" "${PRODUCTION_URL:?}"
  [[ "${GITHUB_SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "GITHUB_SHA inválido"
  if ! [[ "${GITHUB_RUN_ID}" =~ ^[1-9][0-9]*$ ]] || ! [[ "${GITHUB_RUN_ATTEMPT}" =~ ^[1-9][0-9]*$ ]]; then
    fail "GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT inválidos"
  fi
  [[ "${ENTRY_ASSET}" =~ ^main-[A-Za-z0-9]+\.js$ ]] || fail "ENTRY_ASSET inválido"
  [[ "${PRODUCTION_URL}" =~ ^https://[a-z0-9-]+\.pages\.dev$ ]] || fail "PRODUCTION_URL inválida"
  if [ ! -f "${site}/index.html" ] || [ ! -f "${site}/${ENTRY_ASSET}" ]; then
    fail "${site} no es el sitio validado"
  fi
  LOCAL_SHA=$(sha_of < "${site}/${ENTRY_ASSET}")

  # 1. Autoridad única y nadie publicó desde el preflight
  p=$(project)
  require_single_authority "${p}"
  canonical=$(jq -r '.canonical_deployment.id // ""' <<< "${p}")
  [ "${canonical}" = "${PREVIOUS_DEPLOYMENT_ID}" ] ||
    fail "la producción de Pages cambió durante el run (${PREVIOUS_DEPLOYMENT_ID:-ninguna} → ${canonical:-ninguna}): otra publicación; no se publica encima"

  # 2. Publicación: solo sube archivos (sin build, sin Functions)
  marker="production-hetzner ${GITHUB_RUN_ID}/${GITHUB_RUN_ATTEMPT}"
  out="$(mktemp -d)/wrangler-output.ndjson"
  CLOUDFLARE_ACCOUNT_ID="${PAGES_ACCOUNT_ID}" WRANGLER_SEND_METRICS=false CI=true \
    WRANGLER_OUTPUT_FILE_PATH="${out}" WRANGLER_OUTPUT_FILE_DIRECTORY="" \
    "${WRANGLER_BIN}" pages deploy "${site}" \
    --project-name="${PAGES_PROJECT}" \
    --branch="${PAGES_BRANCH}" \
    --commit-hash="${GITHUB_SHA}" \
    --commit-message="${marker}" \
    --commit-dirty=false >&2 ||
    fail "wrangler pages deploy falló: no se puede afirmar que Pages recibió el artefacto"

  # 3. Identidad: el id que devolvió la creación de ESTA invocación
  [ -s "${out}" ] || fail "wrangler no registró el deployment que creó (sin ${out})"
  entries=$(jq -sc '[.[] | select(.type == "pages-deploy-detailed")]' "${out}") ||
    fail "salida de wrangler ilegible"
  [ "$(jq 'length' <<< "${entries}")" = 1 ] ||
    fail "wrangler registró $(jq 'length' <<< "${entries}") deployments en esta invocación (se espera exactamente uno)"
  entry=$(jq -c '.[0]' <<< "${entries}")
  id=$(jq -r '.deployment_id // ""' <<< "${entry}")
  [[ "${id}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] ||
    fail "id de deployment inválido en la salida de wrangler: '${id}'"
  jq -e --arg p "${PAGES_PROJECT}" --arg sha "${GITHUB_SHA}" '
      .pages_project == $p and .deployment_trigger.metadata.commit_hash == $sha
      and ((.environment // "production") == "production")' <<< "${entry}" > /dev/null ||
    fail "el deployment ${id} que informó wrangler no es de producción de ${PAGES_PROJECT} para ${GITHUB_SHA}"
  [ "${id}" != "${PREVIOUS_DEPLOYMENT_ID}" ] || fail "wrangler informó el deployment anterior (${id}), no uno nuevo"

  deployment=""
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    deployment=$(cf "/accounts/${PAGES_ACCOUNT_ID}/pages/projects/${PAGES_PROJECT}/deployments/${id}" | jq -c '.result // empty') ||
      fail "no se pudo leer el deployment ${id}"
    jq -e --arg id "${id}" --arg sha "${GITHUB_SHA}" --arg b "${PAGES_BRANCH}" --arg m "${marker}" '
        .id == $id and .environment == "production" and .deployment_trigger.type == "ad_hoc"
        and .deployment_trigger.metadata.branch == $b
        and .deployment_trigger.metadata.commit_hash == $sha
        and .deployment_trigger.metadata.commit_message == $m' <<< "${deployment}" > /dev/null ||
      fail "el deployment ${id} no es la publicación de producción de este intento (${marker}, ${GITHUB_SHA})"
    stage=$(jq -r '"\(.latest_stage.name // "")/\(.latest_stage.status // "")"' <<< "${deployment}")
    case "${stage}" in
      deploy/success) break ;;
      */failure | */canceled) fail "el deployment ${id} terminó en ${stage}" ;;
    esac
    echo "Deployment ${id} en ${stage} — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
    stage=""
    sleep "${POLL_INTERVAL}"
  done
  [ "${stage:-}" = deploy/success ] || fail "el deployment ${id} no terminó a tiempo"
  url=$(jq -r '.url // ""' <<< "${deployment}")
  [[ "${url}" =~ ^https://[a-z0-9-]+\.[a-z0-9-]+\.pages\.dev$ ]] || fail "url de deployment inválida: '${url}'"
  [ "$(jq -r '.url // ""' <<< "${entry}")" = "${url}" ] || fail "la url de wrangler y la de la API no coinciden para ${id}"
  created=$(jq -r '.created_on // ""' <<< "${deployment}")

  deployments=$(cf "/accounts/${PAGES_ACCOUNT_ID}/pages/projects/${PAGES_PROJECT}/deployments?env=production&per_page=25") ||
    fail "no se pudieron listar los deployments de ${PAGES_PROJECT}"
  jq -e --arg id "${id}" --arg m "${marker}" '
      [.result[]? | select(.deployment_trigger.metadata.commit_message == $m) | .id] == [$id]' \
    <<< "${deployments}" > /dev/null ||
    fail "hay más de un deployment (o ninguno listado) con el mensaje de este intento (${marker}): publicación ambigua"
  jq -e --arg id "${id}" --arg c "${created}" '
      [.result[]? | select(.environment == "production" and .id != $id and .created_on > $c)] | length == 0' \
    <<< "${deployments}" > /dev/null ||
    fail "un deployment de producción posterior reemplazó a ${id}"

  # 4. Vigente y sirviendo el artefacto validado, comprobado antes y después del contenido
  canonical=$(canonical_id)
  [ "${canonical}" = "${id}" ] ||
    fail "el deployment ${id} no es el vigente de producción (vigente: ${canonical:-ninguno})"
  ok=false
  for i in $(seq 1 "${POLL_ATTEMPTS}"); do
    if serves_artifact "${url}" && serves_artifact "${PRODUCTION_URL}"; then
      ok=true
      break
    fi
    echo "${url} / ${PRODUCTION_URL} aún no sirven ${ENTRY_ASSET} validado — esperando ${POLL_INTERVAL}s ($i/${POLL_ATTEMPTS})" >&2
    sleep "${POLL_INTERVAL}"
  done
  [ "${ok}" = true ] || fail "${url} o ${PRODUCTION_URL} no sirven el artefacto validado (${ENTRY_ASSET})"
  canonical=$(canonical_id)
  [ "${canonical}" = "${id}" ] ||
    fail "otro deployment (${canonical:-ninguno}) reemplazó a ${id} mientras se verificaba el contenido"

  echo "Pages: ${id} (${url}) es producción y sirve ${GITHUB_SHA}" >&2
  echo "deployment_id=${id}"
  echo "deployment_url=${url}"
}

[ "$#" -ge 1 ] || fail "uso: $0 target | preflight | publish <site_dir>"
case "$1" in
  target) [ "$#" -eq 1 ] || fail "uso: $0 target"; cmd_target ;;
  preflight) [ "$#" -eq 1 ] || fail "uso: $0 preflight"; cmd_preflight ;;
  publish) [ "$#" -eq 2 ] || fail "uso: $0 publish <site_dir>"; cmd_publish "$2" ;;
  *) fail "subcomando desconocido: $1" ;;
esac
