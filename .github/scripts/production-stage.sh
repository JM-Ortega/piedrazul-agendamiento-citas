#!/usr/bin/env bash
# Etapas de la cadena de producción y modo de los push a main.
#
# Uso:
#   production-stage.sh validate                                   (entorno IN_*)
#   production-stage.sh plan-gate <stage> <has_changes> <watch_server> <push_mode>
#   production-stage.sh push-mode <valor de vars.PRODUCTION_PUSH_DEPLOY>
#
# Etapas de un dispatch (input `stage`):
#   ordinary   operación normal: los flags deciden. Nunca crea ni reemplaza el servidor.
#   provision  Run P de un servidor nuevo o reemplazado: revisa el plan y aplica, con las
#              mismas comprobaciones previas de una instalación completa, y termina antes de
#              cualquier conexión SSH. Solo un Apply que creó o reemplazó el servidor se informa
#              como PROVISIONED / TRUST PENDING / NOT INSTALLED.
#   install    Run I, después de que un operador registró el pin autenticado fuera de banda:
#              plan nuevo que debe salir sin cambios, sin Apply, y configuración, deploy,
#              health y frontend contra el host verificado.
#
# validate: combinación de flags del dispatch. provision e install declaran toda la
#   instalación (los flags son los que usan las comprobaciones previas de Run P y lo que
#   ejecuta Run I); install además prohíbe pedir Apply. Rollback, recuperación de Keycloak y
#   break-glass son operaciones de otro tipo y van en un dispatch ordinary.
#
# plan-gate: decide, con la acción OBSERVADA sobre el servidor en el plan revisado, si la
#   etapa admite ese plan. Sin cambios siempre pasa: no habrá Apply. Un plan sin cambios no
#   prueba nada sobre el sistema operativo del host; Run I configura el servidor igual.
#     ordinary   create/replace → falla (solo Run P crea o reemplaza el servidor);
#                no-op/update → pasa (controles existentes del plan revisado);
#     provision  create/replace → pasa solo con los push retenidos (push_mode=deferred);
#                no-op/update → pasa (recuperación de un Apply parcial: no crea ni
#                reemplaza nada, así que la retención no la bloquea);
#     install    cualquier cambio → falla: Run I nunca aplica.
#     En todas   delete → falla: destruir el servidor sin reemplazarlo es un retiro, una
#                decisión operativa aparte que esta cadena no ofrece.
#   Una acción no interpretable (none, ambiguous, unknown, …) con cambios falla cerrado.
#   push_mode es lo que deciden los push, leído en classify: ese job no tiene environment,
#   así que ve el mismo valor que un push. Dentro del environment, `vars` podría estar
#   ensombrecida. Vacío o `invalid` no retienen nada: falla cerrado.
#
# push-mode: `enabled` procesa los push; vacío o `deferred` los difiere. Otro valor falla: un
#   error de escritura no debe leerse como una decisión.
set -euo pipefail

fail() {
  echo "::error title=Etapa de producción::$*" >&2
  exit 1
}

cmd_validate() {
  local stage="${IN_STAGE:-ordinary}" name flag
  case "${stage}" in
    ordinary) return 0 ;;
    provision | install) ;;
    *) fail "stage '${stage}' no es ordinary, provision ni install" ;;
  esac

  for flag in IN_HOST_CONFIG IN_BUILD IN_BUILD_POSTGRES IN_APP_DEPLOY IN_FRONTEND; do
    name="${flag#IN_}"
    [ "${!flag:-}" = true ] || fail "stage=${stage} declara la instalación completa: requiere run_${name,,}=true"
  done
  if [ "${stage}" = provision ]; then
    [ "${IN_TERRAFORM:-}" = true ] || fail "stage=provision requiere run_terraform_apply=true"
  else
    [ "${IN_TERRAFORM:-}" = false ] || fail "stage=install nunca aplica Terraform: requiere run_terraform_apply=false (el plan nuevo corre igual y debe salir sin cambios)"
  fi
  [ -z "${IN_IMAGE_TAG:-}" ] || fail "stage=${stage} no admite image_tag: un rollback va en un dispatch ordinary"
  [ "${IN_KEYCLOAK_RECOVERY:-false}" = false ] || fail "stage=${stage} no admite keycloak_recovery"
  [ -z "${IN_BREAK_GLASS:-}" ] || fail "stage=${stage} no admite break_glass_authorization"
}

cmd_plan_gate() {
  local stage="$1" has_changes="$2" server="$3" push_mode="$4"
  case "${stage}" in
    ordinary | provision | install) ;;
    *) fail "etapa inválida en la revisión del plan: '${stage}'" ;;
  esac
  case "${has_changes}" in
    false)
      echo "Etapa ${stage}: plan sin cambios, no habrá Apply"
      return 0
      ;;
    true) ;;
    *) fail "resultado del plan inválido: has_changes='${has_changes}'" ;;
  esac

  [ "${stage}" != install ] ||
    fail "stage=install exige un plan sin cambios y este tiene cambios (acción sobre el servidor: ${server:-sin dato}). Run I nunca aplica: si el plan es esperado, correr stage=provision; el saved plan se descarta"

  case "${server}" in
    create | replace)
      [ "${stage}" = provision ] ||
        fail "el plan crea o reemplaza el servidor (acción: ${server}) y solo stage=provision puede hacerlo; ningún push ni deploy normal lo hace. El saved plan se descarta. Ver docs/operations/runbooks/instalacion-nueva.md"
      [ "${push_mode}" = deferred ] ||
        fail "el plan crea o reemplaza el servidor (acción: ${server}) y los push no están retenidos (modo: ${push_mode:-sin dato}): definir vars.PRODUCTION_PUSH_DEPLOY=deferred en el repositorio y volver a lanzar Run P. El saved plan se descarta. Una recuperación que no crea ni reemplaza el servidor no necesita este paso"
      ;;
    no-op | update) ;;
    delete)
      fail "el plan destruye el servidor sin crear otro (etapa ${stage}): un retiro es una decisión operativa aparte y esta cadena no lo aplica; el saved plan se descarta"
      ;;
    *)
      fail "acción del plan sobre el servidor no interpretable (${server:-sin dato}; none = la dirección no está en el plan, ambiguous = repetida, unknown = JSON ilegible); el saved plan se descarta"
      ;;
  esac
  echo "Etapa ${stage}: el plan con cambios es admisible (acción sobre el servidor: ${server})"
}

cmd_push_mode() {
  case "$1" in
    enabled) echo enabled ;;
    "" | deferred) echo deferred ;;
    *) fail "vars.PRODUCTION_PUSH_DEPLOY='$1' no es válido (enabled o deferred; sin definir equivale a deferred)" ;;
  esac
}

case "${1:-}" in
  validate)
    [ "$#" -eq 1 ] || fail "uso: $0 validate"
    cmd_validate
    ;;
  plan-gate)
    [ "$#" -eq 5 ] || fail "uso: $0 plan-gate <stage> <has_changes> <watch_server> <push_mode>"
    cmd_plan_gate "$2" "$3" "$4" "$5"
    ;;
  push-mode)
    [ "$#" -eq 2 ] || fail "uso: $0 push-mode <valor>"
    cmd_push_mode "$2"
    ;;
  *) fail "uso: $0 validate | plan-gate <stage> <has_changes> <watch_server> <push_mode> | push-mode <valor>" ;;
esac
