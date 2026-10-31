#!/usr/bin/env bash
# Artefacto del frontend de producción: el mismo build validado antes del gate es el que
# production publica en Cloudflare Pages. Pages no vuelve a construir el código fuente.
#
# Uso:
#   frontend-artifact.sh package <dist_dir> <dir_salida>
#   frontend-artifact.sh fetch <dir_salida>
#
# package (frontend_build, sin privilegios; entorno: GITHUB_SHA, GITHUB_RUN_ID,
#   GITHUB_RUN_ATTEMPT): valida el sitio construido y lo empaqueta en <dir_salida>:
#     frontend-dist.tar       el sitio (entradas relativas ./, solo archivos y directorios)
#     frontend-manifest.json  commit, árbol de frontend/, run, sha256 del tar, punto de entrada
#   Salida (GITHUB_OUTPUT): dist_sha256, file_count, entry_asset, frontend_tree.
#
# fetch (production, tras el gate; entorno: GH_TOKEN con actions: read,
#   GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_SHA, EXPECTED_ARTIFACT_ID,
#   EXPECTED_ARTIFACT_NAME, EXPECTED_DIST_SHA256): descarga el artifact por el id que
#   frontend_build entregó como output de job y lo acepta solo si:
#     - origen: la API lo liga a ESTE run, a este repositorio (no un fork) y a este commit,
#       con el nombre esperado, y no está vencido;
#     - integridad: digest registrado por GitHub (fetch_verified_artifact) y sha256 del tar
#       igual al que frontend_build validó (output de job, no un dato del propio artifact);
#     - contenido: el manifiesto corresponde a este commit y run, la extracción es segura
#       (sin rutas absolutas, `..`, enlaces ni dispositivos) y el sitio vuelve a validar.
#   Deja el sitio en <dir_salida>/site. Salida: site_dir, entry_asset, file_count,
#   frontend_tree.
#
# Validación del sitio: index.html no vacío; todo recurso local que index.html referencia
# (src/href) existe; exactamente un punto de entrada main-<hash>.js; sin enlaces simbólicos;
# límites de Pages (20 000 archivos, 25 MiB por archivo).
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

MAX_FILES=20000
MAX_FILE_BYTES=$((25 * 1024 * 1024))
TAR_NAME=frontend-dist.tar
MANIFEST_NAME=frontend-manifest.json
SHA_RE='^[0-9a-f]{40}$'

# validate_site <dir> → imprime el punto de entrada (main-<hash>.js)
validate_site() {
  local dir="$1" index ref path entries count big
  index="${dir}/index.html"
  if [ ! -f "${index}" ] || [ -L "${index}" ] || [ ! -s "${index}" ]; then
    fail "el sitio no tiene un index.html no vacío"
  fi

  [ -z "$(find "${dir}" ! -type f ! -type d -print -quit)" ] ||
    fail "el sitio contiene entradas que no son archivos ni directorios (enlaces, dispositivos)"
  count=$(find "${dir}" -type f | wc -l)
  [ "${count}" -le "${MAX_FILES}" ] || fail "el sitio tiene ${count} archivos (límite de Pages: ${MAX_FILES})"
  big=$(find "${dir}" -type f -size +"${MAX_FILE_BYTES}"c -print -quit)
  [ -z "${big}" ] || fail "${big#"${dir}"/} supera 25 MiB (límite de Pages por archivo)"

  # Recursos locales referenciados por index.html (base href "/"): deben existir. Un
  # chunk ausente es una página en blanco publicada.
  while IFS= read -r ref; do
    case "${ref}" in
      "" | "/" | \#* | data:* | mailto:* | tel:* | http:* | https:* | //*) continue ;;
    esac
    path="${ref%%[?#]*}"
    path="${path#/}"
    [ -n "${path}" ] || continue
    [[ "${path}" != *..* ]] || fail "index.html referencia una ruta fuera del sitio: ${ref}"
    [ -f "${dir}/${path}" ] || fail "index.html referencia ${ref}, que no está en el sitio"
  done < <(grep -oE '(src|href)="[^"]*"' "${index}" | sed -E 's/^(src|href)="(.*)"$/\2/')

  entries=$(grep -oE 'src="/?main-[A-Za-z0-9]+\.js"' "${index}" | sed -E 's/^src="\/?(.*)"$/\1/' | sort -u)
  [ "$(grep -c . <<< "${entries}")" = 1 ] ||
    fail "index.html debe cargar exactamente un main-<hash>.js (encontrados: ${entries:-ninguno})"
  echo "${entries}"
}

# check_tar_entries <tar>: solo ./<ruta relativa>, archivos regulares y directorios
check_tar_entries() {
  local tar="$1" line type name
  while IFS= read -r line; do
    type="${line:0:1}"
    [ "${type}" = "-" ] || [ "${type}" = "d" ] || fail "el tar contiene una entrada no permitida: ${line}"
  done < <(tar -tvf "${tar}")
  while IFS= read -r name; do
    if [ "${name}" = "./" ] || [ "${name}" = "." ]; then continue; fi
    [[ "${name}" == ./* ]] || fail "entrada del tar fuera de ./: ${name}"
    [[ "/${name}/" != */../* ]] || fail "entrada del tar con '..': ${name}"
  done < <(tar -tf "${tar}")
}

cmd_package() {
  local dist="$1" out="$2" entry sha count tree
  : "${GITHUB_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
  [[ "${GITHUB_SHA}" =~ ${SHA_RE} ]] || fail "GITHUB_SHA inválido"
  [ -d "${dist}" ] || fail "no existe el directorio construido ${dist}"
  [ "$(git rev-parse HEAD)" = "${GITHUB_SHA}" ] || fail "el checkout no es ${GITHUB_SHA}"
  [ -z "$(git status --porcelain --untracked-files=no -- frontend)" ] ||
    fail "frontend/ tiene cambios sobre ${GITHUB_SHA}: el artefacto no correspondería al commit"
  tree=$(git rev-parse "${GITHUB_SHA}:frontend")

  entry=$(validate_site "${dist}")
  count=$(find "${dist}" -type f | wc -l)

  mkdir -p "${out}"
  [ ! -e "${out}/${TAR_NAME}" ] || fail "${out}/${TAR_NAME} ya existe"
  # Orden, dueños y fechas fijos: mismo sitio → mismo tar
  tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --format=gnu \
    -C "${dist}" -cf "${out}/${TAR_NAME}" .
  check_tar_entries "${out}/${TAR_NAME}"
  sha="sha256:$(sha256sum "${out}/${TAR_NAME}" | cut -d' ' -f1)"

  jq -n --arg commit "${GITHUB_SHA}" --arg tree "${tree}" --arg run "${GITHUB_RUN_ID}" \
    --arg attempt "${GITHUB_RUN_ATTEMPT}" --arg sha "${sha}" --argjson count "${count}" \
    --arg entry "${entry}" --arg node "$(node --version 2> /dev/null || echo unknown)" '
    {schema: 1, commit: $commit, frontend_tree: $tree, run_id: $run, run_attempt: $attempt,
     dist_sha256: $sha, file_count: $count, entry_asset: $entry, node_version: $node}' \
    > "${out}/${MANIFEST_NAME}"

  echo "dist_sha256=${sha}"
  echo "file_count=${count}"
  echo "entry_asset=${entry}"
  echo "frontend_tree=${tree}"
}

cmd_fetch() {
  local out="$1" repo_id artifact name raw sha entry manifest
  : "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_SHA:?}"
  : "${EXPECTED_ARTIFACT_ID:?}" "${EXPECTED_ARTIFACT_NAME:?}" "${EXPECTED_DIST_SHA256:?}"
  [[ "${GITHUB_RUN_ID}" =~ ^[1-9][0-9]*$ ]] || fail "GITHUB_RUN_ID inválido"
  [[ "${GITHUB_SHA}" =~ ${SHA_RE} ]] || fail "GITHUB_SHA inválido"
  [[ "${EXPECTED_ARTIFACT_ID}" =~ ^[1-9][0-9]*$ ]] || fail "id de artifact del frontend inválido: '${EXPECTED_ARTIFACT_ID}'"
  [[ "${EXPECTED_ARTIFACT_NAME}" =~ ^frontend-dist-${GITHUB_RUN_ID}-[1-9][0-9]*$ ]] ||
    fail "nombre de artifact del frontend inválido: '${EXPECTED_ARTIFACT_NAME}'"
  [[ "${EXPECTED_DIST_SHA256}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "sha256 esperado del frontend inválido"
  # shellcheck source=.github/scripts/scan-artifact-lib.sh
  . "$(cd "$(dirname "$0")" && pwd)/scan-artifact-lib.sh"

  repo_id=$(gh api "repos/${GITHUB_REPOSITORY}" --jq '.id') || fail "no se pudo leer el repositorio"
  [[ "${repo_id}" =~ ^[1-9][0-9]*$ ]] || fail "id de repositorio inválido"
  artifact=$(gh api "repos/${GITHUB_REPOSITORY}/actions/artifacts/${EXPECTED_ARTIFACT_ID}") ||
    fail "no se pudo leer el artifact ${EXPECTED_ARTIFACT_ID}"
  jq -e --arg name "${EXPECTED_ARTIFACT_NAME}" --argjson run "${GITHUB_RUN_ID}" \
    --argjson repo "${repo_id}" --arg sha "${GITHUB_SHA}" '
      .name == $name and .expired == false and .workflow_run.id == $run
      and .workflow_run.repository_id == $repo and .workflow_run.head_repository_id == $repo
      and .workflow_run.head_sha == $sha' <<< "${artifact}" > /dev/null ||
    fail "el artifact ${EXPECTED_ARTIFACT_ID} no es ${EXPECTED_ARTIFACT_NAME} vigente de este run, repositorio y commit"

  name=$(jq -r '.name' <<< "${artifact}")
  raw="${out}/artifact"
  fetch_verified_artifact "${artifact}" "${raw}" "${TAR_NAME},${MANIFEST_NAME}" ||
    fail "${name}: artefacto no verificable"

  sha="sha256:$(sha256sum "${raw}/${TAR_NAME}" | cut -d' ' -f1)"
  [ "${sha}" = "${EXPECTED_DIST_SHA256}" ] ||
    fail "el tar descargado (${sha}) no es el que validó frontend_build (${EXPECTED_DIST_SHA256})"
  manifest="${raw}/${MANIFEST_NAME}"
  jq -e --arg commit "${GITHUB_SHA}" --arg run "${GITHUB_RUN_ID}" --arg sha "${sha}" '
      .schema == 1 and .commit == $commit and .run_id == $run and .dist_sha256 == $sha
      and (.entry_asset | type == "string") and (.frontend_tree | test("^[0-9a-f]{40}$"))' \
    "${manifest}" > /dev/null || fail "el manifiesto no corresponde a este commit, run y tar"

  check_tar_entries "${raw}/${TAR_NAME}"
  mkdir -p "${out}/site"
  tar --no-same-owner --no-same-permissions -C "${out}/site" -xf "${raw}/${TAR_NAME}"
  entry=$(validate_site "${out}/site")
  [ "${entry}" = "$(jq -r '.entry_asset' "${manifest}")" ] ||
    fail "el punto de entrada extraído (${entry}) no es el del manifiesto"

  echo "${name}: ${sha} verificado (commit ${GITHUB_SHA})" >&2
  echo "site_dir=${out}/site"
  echo "entry_asset=${entry}"
  echo "file_count=$(jq -r '.file_count' "${manifest}")"
  echo "frontend_tree=$(jq -r '.frontend_tree' "${manifest}")"
}

[ "$#" -ge 1 ] || fail "uso: $0 package <dist_dir> <dir_salida> | fetch <dir_salida>"
case "$1" in
  package) [ "$#" -eq 3 ] || fail "uso: $0 package <dist_dir> <dir_salida>"; cmd_package "$2" "$3" ;;
  fetch) [ "$#" -eq 2 ] || fail "uso: $0 fetch <dir_salida>"; cmd_fetch "$2" ;;
  *) fail "subcomando desconocido: $1" ;;
esac
