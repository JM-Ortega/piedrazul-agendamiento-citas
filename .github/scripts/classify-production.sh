#!/usr/bin/env bash
# Clasifica la intención de producción de un rango de commits de main.
#
# Uso: classify-production.sh <base> <head> [<base_observabilidad>]
#   base: SHA del último run que procesó el rango del núcleo (señal "Rango procesado",
#         production-range-base.sh). Debe ser ancestro de head. Vacío = no hay un rango procesado
#         confiable: se clasifica el árbol completo de head (todo lo que el run podría necesitar
#         aplicar).
#   head: SHA a desplegar.
#   base_observabilidad: lo mismo para el stack de observabilidad (señal "Observabilidad
#         procesada"). Cada componente tiene su propio progreso: un fallo del stack no obliga a
#         repetir el núcleo ni al revés. Vacío u omitido = árbol completo para el stack.
#
# Ser más inclusivo que el cambio real es seguro (Apply sin cambios y redeploy son
# idempotentes); omitir un cambio no lo es. Por eso el rango siempre parte del último
# rango procesado, no del commit anterior: un push descartado por la cola de concurrencia,
# un run fallido o un push diferido quedan incluidos en el siguiente.
#
# Salida (formato GITHUB_OUTPUT):
#   range_mode=incremental|full   range_base=<sha|vacío>
#   terraform_required=true|false
#   deploy_required=true|false    (= run_build || run_build_postgres || run_host_config || run_app_deploy)
#   run_build, run_build_postgres, run_host_config, run_app_deploy
#   frontend_required=true|false  (publicar en Cloudflare Pages el build de frontend/)
#   observability_range_mode=incremental|full   observability_range_base=<sha|vacío>
#   observability_required=true|false  (operación full del stack de observabilidad)
#   observability_scope=install|integration|changed  (verificación: árbol completo; cambios
#          compartidos con la aplicación; cambios solo del stack)
#   observability_images_changed=true|false  (referencias fijadas de las imágenes del stack en
#          infra/observability/compose.yml distintas de las de la base: escanear antes de aplicar)
#   intent=apply_only|deploy_only|apply_then_deploy|noop                    sin frontend
#          frontend_only|apply_then_frontend|deploy_then_frontend|
#          apply_then_deploy_then_frontend                                  con frontend
#   La publicación del frontend va siempre al final: después del Apply y del deploy (y
#   de sus verificaciones) que el mismo rango requiera.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 2 ] || [ "$#" -eq 3 ] || fail "uso: $0 <base|''> <head> [<base_observabilidad|''>]"
BASE="$1"
HEAD="$2"
OBS_BASE="${3:-}"

SHA_RE='^[0-9a-f]{40}$'
[[ "${HEAD}" =~ ${SHA_RE} ]] || fail "head inválido: '${HEAD}'"
git cat-file -e "${HEAD}^{commit}" 2>/dev/null || fail "head ${HEAD} no existe en el checkout"

EMPTY_TREE="$(git hash-object -t tree /dev/null)"
range_from() { # <base|vacío> <etiqueta> → modo y origen del diff
  if [ -n "$1" ]; then
    [[ "$1" =~ ${SHA_RE} ]] || fail "$2 inválido: '$1'"
    git cat-file -e "$1^{commit}" 2>/dev/null || fail "$2 $1 no existe en el checkout"
    git merge-base --is-ancestor "$1" "${HEAD}" || fail "$2 $1 no es ancestro de ${HEAD}"
    echo "incremental $1"
  else
    # Contra el árbol vacío: todo archivo de head cuenta como cambiado
    echo "full ${EMPTY_TREE}"
  fi
}
read -r MODE FROM <<< "$(range_from "${BASE}" base)" || exit 1
[ -n "${FROM}" ] || exit 1
read -r OBS_MODE OBS_FROM <<< "$(range_from "${OBS_BASE}" base_observabilidad)" || exit 1
[ -n "${OBS_FROM}" ] || exit 1

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
# Ansible del núcleo: todo infra/ansible salvo lo que solo consume el stack de observabilidad
# (su rol y sus playbooks). Lo que comparten ambos (SHARED) cuenta para los dos.
ANSIBLE=(infra/ansible "${DOCS[1]}"
  ':(exclude)infra/ansible/roles/observability'
  ':(exclude,glob)infra/ansible/playbooks/observability*.yml')
# Compartido entre el núcleo y el stack: la política de logs del rol observability (converge la
# aplica con include_role: su tarea y SOLO sus defaults, defaults_from: main/log_policy.yml; el
# resto de los defaults, defaults/main/stack.yml, es solo del stack), el inventario (listas de
# logs, dominio de Grafana, receptor) y la configuración de Ansible
SHARED=(infra/ansible/roles/observability/tasks/log_policy.yml
  infra/ansible/roles/observability/defaults/main/log_policy.yml
  infra/ansible/inventories/prod
  infra/ansible/ansible.cfg
  infra/ansible/requirements.yml)
# Lo que consume solo el stack de observabilidad (playbooks/observability.yml y su rol)
OBSERVABILITY=(infra/observability ':(exclude)infra/observability/tests'
  ':(exclude)infra/observability/.env.example' ':(exclude,glob)infra/observability/**/*.md'
  infra/ansible/roles/observability "${DOCS[1]}"
  ':(exclude,glob)infra/ansible/playbooks/observability-logs-rollback.yml'
  'infra/ansible/playbooks/observability*.yml')
# Productores de métricas y logs de la aplicación: un cambio no reconfigura el stack, pero su
# verificación debe volver a exigir los destinos de la aplicación
PRODUCERS=(infra/compose/traefik.yml infra/compose/backend.yml infra/compose/keycloak.yml
  infra/compose/logging.yml infra/compose/prod.yml)
# Lo que el build de producción del frontend consume (el README no entra al sitio)
FRONTEND=(frontend ':(exclude)frontend/node_modules' ':(exclude)frontend/dist'
  ':(exclude)frontend/.angular' ':(exclude)frontend/README.md')
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
obs_changed() {
  [ -n "$(git diff --name-only "${OBS_FROM}" "${HEAD}" -- "$@")" ]
}

flag() {
  if changed "$@"; then echo true; else echo false; fi
}

TERRAFORM_REQUIRED="$(flag "${TERRAFORM[@]}")"
RUN_BUILD="$(flag "${BACKEND[@]}")"
RUN_BUILD_POSTGRES="$(flag "${POSTGRES[@]}")"
ANSIBLE_CHANGED="$(flag "${ANSIBLE[@]}")"
[ "$(flag "${SHARED[@]}")" = false ] || ANSIBLE_CHANGED=true
APP_CHANGED="$(flag "${APP[@]}")"
FRONTEND_REQUIRED="$(flag "${FRONTEND[@]}")"

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
if [ "${FRONTEND_REQUIRED}" = true ]; then
  case "${INTENT}" in
    noop) INTENT=frontend_only ;;
    apply_only) INTENT=apply_then_frontend ;;
    deploy_only) INTENT=deploy_then_frontend ;;
    apply_then_deploy) INTENT=apply_then_deploy_then_frontend ;;
  esac
fi

# ── Stack de observabilidad, contra su propia base ──
OBS_REQUIRED=false
OBS_SCOPE=changed
if [ "${OBS_MODE}" = full ]; then
  OBS_REQUIRED=true
  OBS_SCOPE=install
elif obs_changed "${SHARED[@]}" || obs_changed "${PRODUCERS[@]}"; then
  OBS_REQUIRED=true
  OBS_SCOPE=integration
elif obs_changed "${OBSERVABILITY[@]}"; then
  OBS_REQUIRED=true
fi
obs_images() { # <commit|árbol vacío> → referencias fijadas de las imágenes del stack, ordenadas
  git show "$1:infra/observability/compose.yml" 2>/dev/null | yq -r '[.services[].image] | sort | .[]' 2>/dev/null
}
OBS_IMAGES_CHANGED=true
if [ "${OBS_MODE}" = incremental ]; then
  NOW_IMAGES="$(obs_images "${HEAD}")"
  [ -n "${NOW_IMAGES}" ] || fail "no se pudieron leer las imágenes de infra/observability/compose.yml en ${HEAD}"
  [ "$(obs_images "${OBS_BASE}")" != "${NOW_IMAGES}" ] || OBS_IMAGES_CHANGED=false
fi

echo "range_mode=${MODE}"
echo "range_base=${BASE}"
echo "terraform_required=${TERRAFORM_REQUIRED}"
echo "deploy_required=${DEPLOY_REQUIRED}"
echo "run_build=${RUN_BUILD}"
echo "run_build_postgres=${RUN_BUILD_POSTGRES}"
echo "run_host_config=${RUN_HOST_CONFIG}"
echo "run_app_deploy=${RUN_APP_DEPLOY}"
echo "frontend_required=${FRONTEND_REQUIRED}"
echo "intent=${INTENT}"
echo "observability_range_mode=${OBS_MODE}"
echo "observability_range_base=${OBS_BASE}"
echo "observability_required=${OBS_REQUIRED}"
echo "observability_scope=${OBS_SCOPE}"
echo "observability_images_changed=${OBS_IMAGES_CHANGED}"
