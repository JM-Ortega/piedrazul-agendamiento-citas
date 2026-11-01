#!/usr/bin/env bash
# Frescura de producción: solo la punta actual de main puede iniciar una mutación.
#
# Uso: verify-main-tip.sh
# Entorno: GH_TOKEN, GITHUB_REPOSITORY, GITHUB_REF, GITHUB_SHA
#
# Se llama inmediatamente antes de la primera mutación de producción del run. Una vez
# iniciada, la cadena termina aunque main avance. Un run viejo (incluido un re-run de
# un commit que ya no es la punta) falla aquí sin tocar nada.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

: "${GITHUB_REPOSITORY:?}" "${GITHUB_REF:?}" "${GITHUB_SHA:?}"

[ "${GITHUB_REF}" = "refs/heads/main" ] ||
  fail "solo main puede iniciar una mutación de producción (ref: ${GITHUB_REF})"

TIP="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/main" --jq '.object.sha')" ||
  fail "no se pudo leer la punta actual de main"
[[ "${TIP}" =~ ^[0-9a-f]{40}$ ]] || fail "punta de main inválida: '${TIP}'"

[ "${TIP}" = "${GITHUB_SHA}" ] ||
  fail "main avanzó a ${TIP}; este run (${GITHUB_SHA}) ya no puede iniciar una mutación de producción"

echo "Frescura verificada: ${GITHUB_SHA} es la punta actual de main"
