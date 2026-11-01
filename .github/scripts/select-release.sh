#!/usr/bin/env bash
# Selección final de las identidades exactas de backend y postgres para un deploy.
#
# Corre dentro del job de producción, después de leer el último éxito del servidor.
# Para cada imagen:
#   build / rollback → la identidad inmutable que el workflow ya resolvió antes del gate
#   reuse            → la identidad registrada en el último éxito del servidor
# Nunca resuelve un tag. Cualquier identidad ausente, malformada o ambigua falla cerrado.
#
# Uso: select-release.sh
# Entorno:
#   RUN_APP_DEPLOY                         true|false
#   BACKEND_REPOSITORY, POSTGRES_REPOSITORY  p. ej. ghcr.io/<owner>/<repo>/backend
#   BACKEND_SOURCE    rollback|build|reuse  POSTGRES_SOURCE  build|reuse
#   BACKEND_IMAGE_REF, BACKEND_INDEX_DIGEST, BACKEND_TAG      (vacíos en reuse)
#   POSTGRES_IMAGE_REF, POSTGRES_INDEX_DIGEST, POSTGRES_TAG   (vacíos en reuse)
#   STATE_FILE        copia local del último éxito del servidor (puede no existir)
#
# Formato del último éxito (lo escribe app/tasks/record_release.yml):
#   BACKEND_IMAGE_REF=<repo>@sha256:<linux/amd64>   BACKEND_INDEX_DIGEST=sha256:<índice>
#   BACKEND_IMAGE_TAG=<tag>                          (ídem POSTGRES_*)
#   RELEASE_COMMIT=<sha>  RELEASE_RUN=<run_id>/<intento>
# Cada clave exactamente una vez; comentarios (#) y líneas vacías permitidos.
#
# Salida (GITHUB_OUTPUT): {backend,postgres}_{source,image_ref,index_digest,tag},
# state_status (absent|valid|invalid), state_error, previous_{backend,postgres}_image_ref
# y previous_release_commit. Un último éxito inválido que no se necesita (ambas imágenes
# explícitas) no bloquea: se informa y el deploy exitoso lo reemplaza.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

DIGEST_RE='^sha256:[0-9a-f]{64}$'
TAG_RE='^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'

: "${BACKEND_REPOSITORY:?}" "${POSTGRES_REPOSITORY:?}" "${STATE_FILE:?}"
RUN_APP_DEPLOY="${RUN_APP_DEPLOY:-}"
[[ "${RUN_APP_DEPLOY}" =~ ^(true|false)$ ]] || fail "RUN_APP_DEPLOY inválido: '${RUN_APP_DEPLOY}'"

valid_ref() { # <repo> <ref>
  [[ "$2" == "$1@sha256:"* ]] && [[ "${2#"$1@"}" =~ ${DIGEST_RE} ]]
}
valid_tag() {
  [[ "$1" =~ ${TAG_RE} ]] && ! [[ "${1,,}" =~ ^(none|null|undefined|latest)$ ]]
}

# ── Último éxito del servidor ────────────────────────────────────────────────
STATE_STATUS=absent
STATE_ERROR=""
declare -A STATE=()

parse_state() {
  local line key value
  local -a allowed=(
    BACKEND_IMAGE_REF BACKEND_INDEX_DIGEST BACKEND_IMAGE_TAG
    POSTGRES_IMAGE_REF POSTGRES_INDEX_DIGEST POSTGRES_IMAGE_TAG
    RELEASE_COMMIT RELEASE_RUN
  )
  while IFS= read -r line || [ -n "${line}" ]; do
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    [[ "${line}" =~ ^([A-Z_]+)=(.*)$ ]] || { STATE_ERROR="línea malformada"; return 1; }
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    [[ " ${allowed[*]} " == *" ${key} "* ]] || { STATE_ERROR="clave desconocida: ${key}"; return 1; }
    [ -z "${STATE[${key}]+x}" ] || { STATE_ERROR="clave repetida: ${key}"; return 1; }
    STATE[${key}]="${value}"
  done < "${STATE_FILE}"

  for key in "${allowed[@]}"; do
    [ -n "${STATE[${key}]+x}" ] || { STATE_ERROR="falta ${key}"; return 1; }
  done
  valid_ref "${BACKEND_REPOSITORY}" "${STATE[BACKEND_IMAGE_REF]}" || { STATE_ERROR="BACKEND_IMAGE_REF no es ${BACKEND_REPOSITORY}@sha256:<digest>"; return 1; }
  valid_ref "${POSTGRES_REPOSITORY}" "${STATE[POSTGRES_IMAGE_REF]}" || { STATE_ERROR="POSTGRES_IMAGE_REF no es ${POSTGRES_REPOSITORY}@sha256:<digest>"; return 1; }
  [[ "${STATE[BACKEND_INDEX_DIGEST]}" =~ ${DIGEST_RE} ]] || { STATE_ERROR="BACKEND_INDEX_DIGEST inválido"; return 1; }
  [[ "${STATE[POSTGRES_INDEX_DIGEST]}" =~ ${DIGEST_RE} ]] || { STATE_ERROR="POSTGRES_INDEX_DIGEST inválido"; return 1; }
  valid_tag "${STATE[BACKEND_IMAGE_TAG]}" || { STATE_ERROR="BACKEND_IMAGE_TAG inválido"; return 1; }
  valid_tag "${STATE[POSTGRES_IMAGE_TAG]}" || { STATE_ERROR="POSTGRES_IMAGE_TAG inválido"; return 1; }
  [[ "${STATE[RELEASE_COMMIT]}" =~ ^[0-9a-f]{40}$ ]] || { STATE_ERROR="RELEASE_COMMIT inválido"; return 1; }
  return 0
}

if [ -e "${STATE_FILE}" ]; then
  if [ -f "${STATE_FILE}" ] && parse_state; then
    STATE_STATUS=valid
  else
    STATE_STATUS=invalid
    STATE_ERROR="${STATE_ERROR:-no es un archivo regular}"
  fi
fi

# ── Selección por imagen ─────────────────────────────────────────────────────
select_surface() { # <SURFACE> <prefijo de salida> <fuentes explícitas permitidas>
  local surface="$1" out="$2" explicit="$3"
  local source_var="${surface}_SOURCE" ref_var="${surface}_IMAGE_REF"
  local index_var="${surface}_INDEX_DIGEST" tag_var="${surface}_TAG" repo_var="${surface}_REPOSITORY"
  local source="${!source_var:-}" ref="${!ref_var:-}" index="${!index_var:-}" tag="${!tag_var:-}" repo="${!repo_var}"

  if [ "${source}" = reuse ]; then
    case "${STATE_STATUS}" in
      valid) ;;
      absent) fail "${out}: reuso sin último éxito registrado en el servidor — despliega con build (backend y postgres) o rollback" ;;
      *) fail "${out}: el último éxito del servidor es inválido o ambiguo (${STATE_ERROR}) — no se reusa" ;;
    esac
    ref="${STATE[${surface}_IMAGE_REF]}"
    index="${STATE[${surface}_INDEX_DIGEST]}"
    tag="${STATE[${surface}_IMAGE_TAG]}"
  elif [[ " ${explicit} " == *" ${source} "* ]]; then
    [ "${RUN_APP_DEPLOY}" = true ] ||
      fail "${out}: fuente '${source}' sin deploy de aplicación (sin deploy solo se reusa el último éxito)"
    valid_ref "${repo}" "${ref}" || fail "${out} (${source}): '${ref}' no es ${repo}@sha256:<linux/amd64>"
    [[ "${index}" =~ ${DIGEST_RE} ]] || fail "${out} (${source}): digest de índice inválido '${index}'"
    valid_tag "${tag}" || fail "${out} (${source}): tag inválido '${tag}'"
  else
    fail "${out}: fuente inválida '${source}'"
  fi

  echo "${out}_source=${source}"
  echo "${out}_image_ref=${ref}"
  echo "${out}_index_digest=${index}"
  echo "${out}_tag=${tag}"
}

RESULT="$(
  select_surface BACKEND backend "rollback build"
  select_surface POSTGRES postgres "build"
)"

echo "${RESULT}"
echo "state_status=${STATE_STATUS}"
echo "state_error=${STATE_ERROR}"
if [ "${STATE_STATUS}" = valid ]; then
  echo "previous_backend_image_ref=${STATE[BACKEND_IMAGE_REF]}"
  echo "previous_postgres_image_ref=${STATE[POSTGRES_IMAGE_REF]}"
  echo "previous_release_commit=${STATE[RELEASE_COMMIT]}"
else
  echo "previous_backend_image_ref="
  echo "previous_postgres_image_ref="
  echo "previous_release_commit="
fi
