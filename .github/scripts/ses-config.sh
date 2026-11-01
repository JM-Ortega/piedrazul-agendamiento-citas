#!/usr/bin/env bash
# Contrato de la configuración del correo por SES antes de tocar producción. Sin red: no
# comprueba que la región tenga SES, ni que las credenciales valgan, ni entrega alguna.
#
# Uso:
#   ses-config.sh check-host <host>   ¿es un endpoint SMTP público de SES? (sin mostrarlo)
#   ses-config.sh preflight           valida la decisión y sus entradas (formato GITHUB_OUTPUT)
#
# Entorno de preflight (nunca se muestran los valores):
#   SES_VARS_OUTSIDE_ENVIRONMENT  "false" solo si el job classify, que no tiene environment, no
#                                 ve ninguna variable de SES. `vars` resuelve environment >
#                                 repositorio > organización: una copia fuera del environment,
#                                 también una que diga false, podría decidir por él. classify
#                                 busca también el nombre; no ve otros environments, y una copia
#                                 vacía solo es segura de detectar con audit-github-trust.sh.
#   NOTIFICATIONS_SES_ENABLED     true habilita; false o ausente deshabilita; otro valor falla.
#                                 Se compara aquí porque en una expresión de GitHub Actions
#                                 `==` no distingue mayúsculas: 'TRUE' == 'true' es verdadero.
#   SES_SMTP_HOST                 variable del environment; solo se exige habilitado
#   RUN_HOST_CONFIG               la intención del run: true si incluye converge
#   SES_SMTP_USERNAME/PASSWORD    secretos: solo los recibe un run con converge y habilitado
#   SES_GROUP_VARS                group_vars de producción (remitente)
#
# Salida: ses_enabled=true|false y ses_credentials=none|converge|host (none: deshabilitado;
# converge: el run entrega las credenciales; host: un deploy sin secretos usa las del .env).
set -euo pipefail

# Misma expresión que ses_smtp_host_pattern en group_vars (notifications-contract-check.sh
# exige que coincidan): endpoint estándar o FIPS, en amazonaws.com o api.aws
HOST_RE='^email-smtp(-fips)?\.[a-z]{2}(-[a-z]+)+-[0-9]+\.(amazonaws\.com|api\.aws)$'
# ASCII imprimible sin espacios: lo que representa el .env sin ambigüedad y sin salto de línea
CREDENTIAL_RE='^[!-~]{1,512}$'
EMAIL_RE='^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$'

LC_ALL=C

fail() {
  echo "::error title=Correo SES::$*"
  exit 1
}

check_host() { [[ "$1" =~ ${HOST_RE} ]]; }

preflight() {
  [ "${SES_VARS_OUTSIDE_ENVIRONMENT:-}" = false ] ||
    fail "NOTIFICATIONS_SES_ENABLED o SES_SMTP_HOST existe en el repositorio o la organización, o no se pudo comprobar (SES_VARS_OUTSIDE_ENVIRONMENT='${SES_VARS_OUTSIDE_ENVIRONMENT:-}'): viven solo en el environment production-hetzner, también con valor false. Eliminar esa copia; no se toca el servidor"

  case "${NOTIFICATIONS_SES_ENABLED:-}" in
    "" | false)
      echo "ses_enabled=false"
      echo "ses_credentials=none"
      return 0
      ;;
    true) ;;
    *) fail "NOTIFICATIONS_SES_ENABLED debe ser exactamente true o false, o no estar definida (valor no mostrado)" ;;
  esac

  local problems=() sender
  if [ -z "${SES_SMTP_HOST:-}" ]; then
    problems+=("falta la variable SES_SMTP_HOST")
  elif ! check_host "${SES_SMTP_HOST}"; then
    problems+=("SES_SMTP_HOST no es un endpoint SMTP de SES (email-smtp[-fips].<región>.amazonaws.com o .api.aws)")
  fi

  sender="$(yq -r '.notifications_email_from // ""' "${SES_GROUP_VARS:-infra/ansible/inventories/prod/group_vars/all.yml}" 2> /dev/null)" || sender=""
  [[ "${sender}" =~ ${EMAIL_RE} ]] || problems+=("notifications_email_from no es un remitente válido en group_vars")

  local credentials=host
  if [ "${RUN_HOST_CONFIG:-}" = true ]; then
    credentials=converge
    local name
    for name in SES_SMTP_USERNAME SES_SMTP_PASSWORD; do
      if [ -z "${!name:-}" ]; then
        problems+=("falta el secreto ${name}")
      elif ! [[ "${!name}" =~ ${CREDENTIAL_RE} ]]; then
        problems+=("${name} tiene espacios, caracteres de control, caracteres no ASCII o más de 512 caracteres")
      fi
    done
  fi

  if [ "${#problems[@]}" -gt 0 ]; then
    local joined
    joined="$(printf '%s; ' "${problems[@]}")"
    fail "SES habilitado con configuración inválida: ${joined%; } (valores no mostrados)"
  fi

  if [ "${credentials}" = host ]; then
    echo "::warning title=Correo SES::Deploy sin configuración del servidor: compara la decisión y el endpoint con el host, pero no recibe credenciales ni puede comprobar que las del host coincidan con las de GitHub. Este run no las renueva; una rotación exige run_host_config=true"
  fi
  echo "ses_enabled=true"
  echo "ses_credentials=${credentials}"
}

case "${1:-}" in
  check-host)
    [ "$#" -eq 2 ] || { echo "uso: $0 check-host <host>" >&2; exit 64; }
    check_host "$2"
    ;;
  preflight)
    preflight
    ;;
  *)
    echo "uso: $0 check-host <host> | preflight" >&2
    exit 64
    ;;
esac
