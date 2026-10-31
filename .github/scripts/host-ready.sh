#!/usr/bin/env bash
# Barrera de inicialización del servidor antes de la configuración (converge).
#
# Uso: host-ready.sh <server_ip>
# Entorno: SSH_CONFIG_FILE (ssh_config verificado de este run, de host-trust.sh verify;
#   obligatorio), HOST_READY_TIMEOUT (segundos, por defecto 600), SSH_USER (por defecto
#   ansible); SSH_BIN existe para las pruebas.
#
# Que SSH responda no prueba que el servidor terminó de inicializarse: cloud-init crea el
# usuario de automatización al principio del arranque y después instala paquetes (fail2ban)
# y escribe la configuración de SSH. converge no debe competir con eso (lock de apt,
# fail2ban todavía ausente). Aquí se espera, con plazo, a que cloud-init termine
# (`cloud-init status --wait`) y se lee su estado final (`cloud-init status --format json`):
#   - éxito: status "done", extended_status "done" (o ausente en versiones antiguas), sin
#     errores ni errores recuperables → listo;
#   - degradado: "degraded done" o cualquier error recuperable (WARNING, DEPRECATED, …) →
#     falla cerrado. Un error recuperable puede ser un paquete o una configuración inicial
#     que no se aplicó; no hay hoy condiciones verificadas que permitan darlo por inocuo
#     para este contrato de arranque;
#   - fallo: errores, status "error", "running", "disabled", "not started" o estado
#     extendido desconocido → falla cerrado;
#   - indeterminado: salida ilegible o SSH fallido → falla cerrado;
#   - plazo vencido → falla cerrado.
# Cada caso deja un diagnóstico propio (resultado, estado y errores) en el log.
# Solo lee: no cambia nada en el servidor.
# Identidad del host: la conexión usa el ssh_config de este run, con verificación estricta
# contra la clave que coincidió con el pin. Sin esa configuración no conecta.
# Salida (GITHUB_OUTPUT): cloud_init_status=done.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "uso: $0 <server_ip>"
IP="$1"
[[ "${IP}" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || fail "IP del servidor inválida: '${IP}'"
TIMEOUT="${HOST_READY_TIMEOUT:-600}"
[[ "${TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || fail "HOST_READY_TIMEOUT inválido"
CONFIG="${SSH_CONFIG_FILE:-}"
[ -n "${CONFIG}" ] && [ -s "${CONFIG}" ] || fail "SSH_CONFIG_FILE ausente: la identidad del host debe verificarse antes de conectar"
SSH="${SSH_BIN:-ssh}"

out=$(mktemp)
rc=0
# La espera y la lectura van en una sola sesión. El código de --wait no decide; el de la
# lectura final (código de salida de ssh) sí cuenta: éxito exige JSON de éxito Y código 0
timeout "${TIMEOUT}" "${SSH}" -F "${CONFIG}" "${SSH_USER:-ansible}@${IP}" \
  'cloud-init status --wait > /dev/null 2>&1; cloud-init status --format json' > "${out}" 2> "${out}.err" || rc=$?

if [ "${rc}" -eq 124 ]; then
  fail "[plazo vencido] cloud-init no terminó en ${TIMEOUT}s en ${IP}: inicialización indeterminada; no se configura el servidor (revisar 'cloud-init status --long' con el usuario ops)"
fi
# Forma del JSON, explícita: status (texto) obligatorio; extended_status, errors y
# recoverable_errors pueden faltar (o ser null) en versiones antiguas de cloud-init, pero si
# están deben tener su tipo (texto, lista, objeto de listas). Otra forma es indeterminada.
if ! jq -e '
    type == "object"
    and (.status | type == "string")
    and ((.extended_status // null) | type == "string" or type == "null")
    and ((.errors // null) | type == "array" or type == "null")
    and ((.recoverable_errors // null) |
         type == "null" or (type == "object" and all(.[]; type == "array")))' "${out}" > /dev/null 2>&1; then
  fail "[indeterminado] estado de cloud-init ilegible o con forma inesperada en ${IP} (ssh/cloud-init rc=${rc}: $(tail -c 300 "${out}.err" | tr '\n' ' '); salida: $(head -c 300 "${out}" | tr '\n' ' ')): inicialización indeterminada"
fi

# Con la forma validada, ninguna lectura puede fallar; si falla, el script termina (set -e)
# en vez de convertir el error en "0 avisos"
status=$(jq -er '.status' "${out}")
extended=$(jq -er '.extended_status // .status' "${out}")
errors=$(jq -er '(.errors // []) | length' "${out}")
warnings=$(jq -er '[(.recoverable_errors // {})[][]] | length' "${out}")
[[ "${errors}" =~ ^[0-9]+$ ]] && [[ "${warnings}" =~ ^[0-9]+$ ]] ||
  fail "[indeterminado] no se pudieron contar los errores de cloud-init en ${IP}"

if [ "${status}" != "done" ] || [ "${errors}" != 0 ]; then
  fail "[fallo] cloud-init en ${IP}: status '${status}' (${extended}), errores: $(jq -c '.errors // []' "${out}" | cut -c1-500). Inicialización fallida o incompleta; no se configura el servidor"
fi
if [ "${extended}" = "degraded done" ] || [ "${warnings}" != 0 ]; then
  fail "[degradado] cloud-init en ${IP} terminó (${extended}) con ${warnings} error(es) recuperable(s): $(jq -c '.recoverable_errors // {}' "${out}" | cut -c1-500). Un error recuperable puede ser configuración inicial o paquetes no aplicados; no se configura el servidor. Revisar 'cloud-init status --long' y /var/log/cloud-init.log con el usuario ops; corregir la plantilla o recrear el servidor y volver a lanzar"
fi
[ "${extended}" = "done" ] || fail "[fallo] cloud-init en ${IP}: estado extendido inesperado '${extended}'"
# Un JSON de éxito con un código de salida distinto de 0 (ssh o el comando remoto) no prueba
# nada: la sesión pudo cortarse o el comando fallar; `cloud-init status` sale con 0 en done
if [ "${rc}" -ne 0 ]; then
  fail "[indeterminado] la salida de cloud-init en ${IP} indica éxito pero ssh/el comando remoto terminó con código ${rc} ($(tail -c 300 "${out}.err" | tr '\n' ' ')): no se da por inicializado"
fi

echo "[éxito] cloud-init terminó en ${IP} (done, sin errores ni avisos)" >&2
echo "cloud_init_status=done"
