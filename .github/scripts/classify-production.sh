#!/usr/bin/env bash
# Clasifica la intención de producción de un rango de commits de main.
#
# Uso: classify-production.sh <base> <head>
#   base: SHA del último run push exitoso del workflow de producción. Debe ser ancestro
#         de head. Vacío = no hay un rango procesado confiable: se clasifica el árbol
#         completo de head (todo lo que el run podría necesitar aplicar).
#   head: SHA a desplegar.
#
# Ser más inclusivo que el cambio real es seguro (Apply sin cambios y redeploy son
# idempotentes); omitir un cambio no lo es. Por eso el rango siempre parte del último
# éxito, no del commit anterior: un push descartado por la cola de concurrencia o un run
# fallido quedan incluidos en el siguiente.
#
# Salida (formato GITHUB_OUTPUT):
#   range_mode=incremental|full   range_base=<sha|vacío>
#   terraform_required=true|false
#   deploy_required=true|false    (= run_build || run_build_postgres || run_host_config || run_app_deploy)
#   run_build, run_build_postgres, run_host_config, run_app_deploy
#   intent=apply_only|deploy_only|apply_then_deploy|noop
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 2 ] || fail "uso: $0 <base|''> <head>"
BASE="$1"
HEAD="$2"

SHA_RE='^[0-9a-f]{40}$'
[[ "${HEAD}" =~ ${SHA_RE} ]] || fail "head inválido: '${HEAD}'"
git cat-file -e "${HEAD}^{commit}" 2>/dev/null || fail "head ${HEAD} no existe en el checkout"

if [ -n "${BASE}" ]; then
  [[ "${BASE}" =~ ${SHA_RE} ]] || fail "base inválido: '${BASE}'"
  git cat-file -e "${BASE}^{commit}" 2>/dev/null || fail "base ${BASE} no existe en el checkout"
  git merge-base --is-ancestor "${BASE}" "${HEAD}" || fail "base ${BASE} no es ancestro de ${HEAD}"
  MODE=incremental
  FROM="${BASE}"
else
  MODE=full
  # Contra el árbol vacío: todo archivo de head cuenta como cambiado
  FROM="$(git hash-object -t tree /dev/null)"
fi

# Rutas que cada concepto de producción consume. Pathspecs de git (directorio = todo
# lo que contiene; :(exclude) quita subárboles).
# La documentación (*.md) bajo Terraform y Ansible no la ejecuta ni la copia nada
# (setup.yml copia compose/, keycloak/ y traefik/, no infra/ansible), así que no cuenta.
DOCS=(':(exclude,glob)infra/terraform/**/*.md' ':(exclude,glob)infra/ansible/**/*.md')
TERRAFORM=(
  infra/terraform/live/prod
  infra/terraform/modules/hetzner
  infra/terraform/modules/cloudflare
  infra/terraform/modules/shared
  "${DOCS[0]}"
)
BACKEND=(backend ':(exclude)backend/.idea' ':(exclude)backend/target')
POSTGRES=(infra/postgres)
ANSIBLE=(infra/ansible "${DOCS[1]}")
# Lo que app/setup.yml copia al servidor o Compose monta
APP=(
  infra/compose
  infra/keycloak/realm
  infra/keycloak/themes/keycloak-theme
  infra/traefik
)

changed() {
  [ -n "$(git diff --name-only "${FROM}" "${HEAD}" -- "$@")" ]
}

flag() {
  if changed "$@"; then echo true; else echo false; fi
}

TERRAFORM_REQUIRED="$(flag "${TERRAFORM[@]}")"
RUN_BUILD="$(flag "${BACKEND[@]}")"
RUN_BUILD_POSTGRES="$(flag "${POSTGRES[@]}")"
ANSIBLE_CHANGED="$(flag "${ANSIBLE[@]}")"
APP_CHANGED="$(flag "${APP[@]}")"

RUN_HOST_CONFIG=false
if [ "${ANSIBLE_CHANGED}" = true ] || [ "${APP_CHANGED}" = true ]; then
  RUN_HOST_CONFIG=true
fi

RUN_APP_DEPLOY=false
if [ "${RUN_BUILD}" = true ] || [ "${RUN_BUILD_POSTGRES}" = true ] || [ "${APP_CHANGED}" = true ]; then
  RUN_APP_DEPLOY=true
fi

DEPLOY_REQUIRED=false
if [ "${RUN_HOST_CONFIG}" = true ] || [ "${RUN_APP_DEPLOY}" = true ]; then
  DEPLOY_REQUIRED=true
fi

case "${TERRAFORM_REQUIRED}/${DEPLOY_REQUIRED}" in
  true/true) INTENT=apply_then_deploy ;;
  true/false) INTENT=apply_only ;;
  false/true) INTENT=deploy_only ;;
  false/false) INTENT=noop ;;
esac

echo "range_mode=${MODE}"
echo "range_base=${BASE}"
echo "terraform_required=${TERRAFORM_REQUIRED}"
echo "deploy_required=${DEPLOY_REQUIRED}"
echo "run_build=${RUN_BUILD}"
echo "run_build_postgres=${RUN_BUILD_POSTGRES}"
echo "run_host_config=${RUN_HOST_CONFIG}"
echo "run_app_deploy=${RUN_APP_DEPLOY}"
echo "intent=${INTENT}"
