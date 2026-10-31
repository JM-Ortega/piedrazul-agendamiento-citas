#!/usr/bin/env bash
# Publicación del frontend en Cloudflare Pages dentro de la cadena de producción.
#
# Uso:
#   pages-release.sh target
#   pages-release.sh probe
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
# Modelo: proyecto de subida directa (Direct Upload) creado por Terraform, sin integración
#   Git ni builds de Cloudflare. Autoridad única = el proyecto NO tiene `source`; el único
#   publicador es esta cadena (wrangler). Un proyecto con cualquier `source` (GitHub,
#   GitLab u otro) falla cerrado.
#
# Lectura del proyecto: GET del proyecto con el código HTTP explícito. 200 con un proyecto
#   válido = presente. 404 con el error de Pages "proyecto inexistente" (8000007) = ausente,
#   pero solo después de comprobar que el token sí lista los proyectos de esa cuenta y que
#   el nombre no está en la lista (una cuenta equivocada, un token sin permiso o un 404 de
#   otra cosa nunca se leen como "ausente"). Cualquier otro código, cuerpo ilegible o
#   contradicción falla.
#
# Dos niveles de comprobación de un proyecto existente:
#   identidad y seguridad (siempre, falla cerrado): nombre, rama de producción, subdominio
#     *.pages.dev y autoridad única (subida directa, sin source);
#   completitud (lo que Terraform reconcilia): el dominio propio asociado.
#
# probe (antes de la primera mutación; CLOUDFLARE_API_TOKEN de Pages, PAGES_ACCOUNT_ID,
#   PAGES_PROJECT, PAGES_BRANCH, PAGES_DOMAIN, PAGES_ALLOW_PROVISIONING=true|false). El
#   workflow fija PAGES_ALLOW_PROVISIONING=true únicamente cuando este run aplica Terraform.
#   - Existe, identidad correcta y completo → project_state=present (+ salidas de preflight).
#   - Existe, identidad correcta, incompleto (p. ej. un Apply anterior creó el proyecto y
#     falló antes de asociar el dominio) → project_state=incomplete solo con
#     PAGES_ALLOW_PROVISIONING=true; si no, falla.
#   - No existe → project_state=absent solo con PAGES_ALLOW_PROVISIONING=true; si no, falla.
#   - Identidad o autoridad incorrectas → falla siempre, antes de mutar.
#   Con absent o incomplete, el workflow exige además que el plan de este run muestre la
#   creación de lo que falta (acción observada, nunca inferida) y preflight exige el
#   proyecto completo después del Apply.
#
# preflight (estricto, solo lectura; mismas PAGES_*): después de Terraform y antes de la
#   primera mutación del servidor y de wrangler. El proyecto existe y es el declarado
#   (nombre, rama de producción, dominio propio asociado, subdominio *.pages.dev) y es de
#   subida directa. El estado del dominio propio (activación/certificado) se informa y no
#   bloquea: pendiente no prueba ni activación ni fallo permanente (p. ej. Cloudflare Access
#   o la emisión del certificado), y la publicación no se verifica por el dominio propio.
#   Salida: project_state=present, pages_source=direct_upload, previous_deployment_id
#   (vacío en un proyecto sin publicaciones), previous_deployment_commit, production_url
#   (alias *.pages.dev de producción), custom_domain_status.
#
# publish (CLOUDFLARE_API_TOKEN, mismas PAGES_*, PREVIOUS_DEPLOYMENT_ID y PRODUCTION_URL del
#   preflight, GITHUB_SHA, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, ENTRY_ASSET, WRANGLER_BIN):
#   1. vuelve a exigir la autoridad única (subida directa) y que el deployment de producción
#      vigente siga siendo PREVIOUS_DEPLOYMENT_ID (nadie publicó durante el run; vacío =
#      primera publicación de un proyecto nuevo, y debe seguir sin producción);
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
#   No se verifica el dominio propio: puede estar detrás de Cloudflare Access; su estado se
#   informa aparte (custom_domain_status), sin bloquear.
#   Salida: deployment_id, deployment_url, custom_domain_status.
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

# cf_status <ruta> <archivo>: GET sin --fail; el cuerpo va a <archivo> y el código HTTP a
# la salida ("000" si no hubo respuesta)
cf_status() {
  local code
  code=$(curl -sS --max-time 30 -o "$2" -w '%{http_code}' \
    -H @<(printf 'Authorization: Bearer %s\n' "${CLOUDFLARE_API_TOKEN}") \
    "${API}$1") || code="000"
  echo "${code}"
}

# project_lookup: PROJECT_STATE=present (PROJECT_JSON = el proyecto) | absent. Cualquier
# otra respuesta falla: solo un "proyecto inexistente" comprobado es ausencia.
PROJECT_STATE="" PROJECT_JSON=""
project_lookup() {
  local body code list
  body=$(mktemp)
  code=$(cf_status "/accounts/${PAGES_ACCOUNT_ID}/pages/projects/${PAGES_PROJECT}" "${body}")
  case "${code}" in
    200)
      jq -e '.success == true and (.result | type == "object")' "${body}" > /dev/null 2>&1 ||
        fail "respuesta inválida al leer el proyecto ${PAGES_PROJECT}"
      PROJECT_STATE=present
      PROJECT_JSON=$(jq -c '.result' "${body}")
      ;;
    404)
      jq -e '.success == false and ([.errors[]?.code] | index(8000007) != null)' "${body}" > /dev/null 2>&1 ||
        fail "HTTP 404 al leer ${PAGES_PROJECT} sin el error de proyecto inexistente de Pages (cuenta o ruta incorrectas)"
      # La ausencia solo cuenta si el token lista los proyectos de ESTA cuenta y el nombre
      # no está entre ellos
      list=$(mktemp)
      code=$(cf_status "/accounts/${PAGES_ACCOUNT_ID}/pages/projects" "${list}")
      [ "${code}" = 200 ] ||
        fail "no se pudieron listar los proyectos de Pages de la cuenta ${PAGES_ACCOUNT_ID} (HTTP ${code}): token sin permiso o cuenta incorrecta"
      jq -e '.success == true and (.result | type == "array")' "${list}" > /dev/null 2>&1 ||
        fail "respuesta inválida al listar los proyectos de Pages"
      jq -e '(.result_info.total_pages // 1) <= 1' "${list}" > /dev/null 2>&1 ||
        fail "la cuenta tiene más de una página de proyectos: no se puede confirmar la ausencia de ${PAGES_PROJECT}"
      jq -e --arg n "${PAGES_PROJECT}" '[.result[].name] | index($n) == null' "${list}" > /dev/null 2>&1 ||
        fail "la API dice que ${PAGES_PROJECT} no existe pero aparece en la lista de la cuenta: respuesta contradictoria"
      PROJECT_STATE=absent
      PROJECT_JSON=""
      ;;
    000) fail "sin respuesta de la API de Cloudflare al leer ${PAGES_PROJECT}" ;;
    *) fail "no se pudo leer el proyecto ${PAGES_PROJECT} (HTTP ${code}: token sin permiso de Pages o cuenta incorrecta)" ;;
  esac
}

# project: el proyecto JSON; ausente o ilegible falla
project() {
  project_lookup
  [ "${PROJECT_STATE}" = present ] || fail "el proyecto ${PAGES_PROJECT} no existe en la cuenta ${PAGES_ACCOUNT_ID}"
  echo "${PROJECT_JSON}"
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

# pages_source <proyecto JSON> → direct_upload | git | unknown
# Subida directa: el proyecto no tiene `source` (ausente o null). Cualquier otro valor es
# una integración (git) o algo no reconocido (unknown): ambos fallan.
pages_source() {
  jq -r '
    if (has("source") | not) or .source == null then "direct_upload"
    elif (.source | type) == "object" and ((.source.type // "") | IN("github", "gitlab")) then "git"
    else "unknown" end' <<< "$1"
}

require_single_authority() { # <proyecto JSON>
  local state
  state=$(pages_source "$1")
  [ "${state}" = direct_upload ] ||
    fail "${PAGES_PROJECT} no es un proyecto de subida directa (source: ${state}): una integración Git (o un origen desconocido) puede publicar por su cuenta; la cadena no publica mientras haya otra autoridad. El proyecto lo crea Terraform sin source (modules/cloudflare/pages); un proyecto con Git no se convierte: hay que recrearlo"
}

# domain_status: estado de activación del dominio propio, solo informativo (nunca falla)
domain_status() {
  local body code
  body=$(mktemp)
  code=$(cf_status "/accounts/${PAGES_ACCOUNT_ID}/pages/projects/${PAGES_PROJECT}/domains/${PAGES_DOMAIN}" "${body}")
  if [ "${code}" = 200 ] &&
     jq -e '.success == true and ((.result.status // "") | test("^[a-z_]+$"))' "${body}" > /dev/null 2>&1; then
    jq -r '.result.status' "${body}"
  else
    echo "unreadable"
  fi
}

# validate_identity <proyecto JSON>: es el declarado y de subida directa (siempre exigido)
validate_identity() {
  local p="$1" subdomain
  jq -e --arg n "${PAGES_PROJECT}" '.name == $n' <<< "${p}" > /dev/null ||
    fail "el proyecto leído no es ${PAGES_PROJECT}"
  jq -e --arg b "${PAGES_BRANCH}" '.production_branch == $b' <<< "${p}" > /dev/null ||
    fail "la rama de producción de ${PAGES_PROJECT} es '$(jq -r '.production_branch' <<< "${p}")', no ${PAGES_BRANCH}"
  subdomain=$(jq -r '.subdomain // ""' <<< "${p}")
  [[ "${subdomain}" =~ ^[a-z0-9-]+\.pages\.dev$ ]] || fail "subdominio de producción inválido: '${subdomain}'"
  require_single_authority "${p}"
}

# is_complete <proyecto JSON>: tiene asociado el dominio declarado
is_complete() {
  jq -e --arg d "${PAGES_DOMAIN}" '(.domains // []) | (type == "array") and (index($d) != null)' <<< "$1" > /dev/null
}

# validate_project <proyecto JSON>: identidad y completitud (preflight estricto)
validate_project() {
  validate_identity "$1"
  is_complete "$1" || fail "${PAGES_PROJECT} no tiene asociado el dominio declarado ${PAGES_DOMAIN}"
}

emit_present() { # <proyecto JSON>
  echo "project_state=present"
  echo "pages_source=direct_upload"
  echo "previous_deployment_id=$(jq -r '.canonical_deployment.id // ""' <<< "$1")"
  echo "previous_deployment_commit=$(jq -r '.canonical_deployment.deployment_trigger.metadata.commit_hash // ""' <<< "$1")"
  echo "production_url=https://$(jq -r '.subdomain' <<< "$1")"
  echo "custom_domain_status=$(domain_status)"
}

cmd_probe() {
  require_target
  case "${PAGES_ALLOW_PROVISIONING:-}" in
    true | false) ;;
    *) fail "PAGES_ALLOW_PROVISIONING debe ser true o false" ;;
  esac
  project_lookup
  if [ "${PROJECT_STATE}" = present ]; then
    validate_identity "${PROJECT_JSON}"
    if is_complete "${PROJECT_JSON}"; then
      emit_present "${PROJECT_JSON}"
    elif [ "${PAGES_ALLOW_PROVISIONING}" = true ]; then
      echo "${PAGES_PROJECT} existe con la identidad declarada pero sin el dominio ${PAGES_DOMAIN} asociado: lo debe completar el Apply de este run; preflight lo exige después" >&2
      echo "project_state=incomplete"
      echo "pages_source=direct_upload"
    else
      fail "${PAGES_PROJECT} no tiene asociado el dominio declarado ${PAGES_DOMAIN} y este run no aplica Terraform (que es quien lo completa)"
    fi
  elif [ "${PAGES_ALLOW_PROVISIONING}" = true ]; then
    echo "El proyecto ${PAGES_PROJECT} no existe en la cuenta ${PAGES_ACCOUNT_ID}: lo debe crear el Apply de este run; preflight lo exige después" >&2
    echo "project_state=absent"
  else
    fail "el proyecto ${PAGES_PROJECT} no existe en la cuenta ${PAGES_ACCOUNT_ID} y este run no aplica Terraform (que es quien lo crea)"
  fi
}

cmd_preflight() {
  local p
  require_target
  p=$(project)
  validate_project "${p}"
  emit_present "${p}"
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
  echo "custom_domain_status=$(domain_status)"
}

[ "$#" -ge 1 ] || fail "uso: $0 target | probe | preflight | publish <site_dir>"
case "$1" in
  target) [ "$#" -eq 1 ] || fail "uso: $0 target"; cmd_target ;;
  probe) [ "$#" -eq 1 ] || fail "uso: $0 probe"; cmd_probe ;;
  preflight) [ "$#" -eq 1 ] || fail "uso: $0 preflight"; cmd_preflight ;;
  publish) [ "$#" -eq 2 ] || fail "uso: $0 publish <site_dir>"; cmd_publish "$2" ;;
  *) fail "subcomando desconocido: $1" ;;
esac
