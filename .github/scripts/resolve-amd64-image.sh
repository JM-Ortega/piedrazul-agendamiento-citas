#!/usr/bin/env bash
# Resuelve UNA vez la identidad inmutable linux/amd64 de una imagen multi-plataforma.
#
# Uso: resolve-amd64-image.sh <repositorio>:<tag> | <repositorio>@sha256:<digest>
#
# Lee el índice OCI una sola vez (docker buildx imagetools inspect --raw), calcula su
# digest sobre los bytes exactos recibidos y elige su único hijo linux/amd64 que no sea
# un manifiesto de attestation. Con una referencia por digest, exige que los bytes
# coincidan con ese digest. Falla cerrado ante cualquier ambigüedad.
#
# Salida (formato GITHUB_OUTPUT), solo si todo validó:
#   image_index_digest=sha256:<índice>
#   image_amd64_digest=sha256:<manifiesto linux/amd64>
#   image_ref=<repositorio>@sha256:<manifiesto linux/amd64>
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "uso: $0 <repositorio>:<tag> | <repositorio>@sha256:<digest>"
INPUT="$1"

DIGEST_RE='^sha256:[0-9a-f]{64}$'
TAG_RE='^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'
REPO_RE='^[a-z0-9]+([.-][a-z0-9]+)*(:[0-9]+)?(/[a-z0-9]+([._-]+[a-z0-9]+)*)+$'

if [[ "${INPUT}" == *@* ]]; then
  REPOSITORY="${INPUT%%@*}"
  TAG=""
  EXPECTED_DIGEST="${INPUT#*@}"
  [[ "${EXPECTED_DIGEST}" =~ ${DIGEST_RE} ]] || fail "digest inválido en '${INPUT}'"
else
  # El tag va después del último ':' del último segmento (el registry puede tener puerto)
  LAST_SEGMENT="${INPUT##*/}"
  [[ "${LAST_SEGMENT}" == *:* ]] || fail "'${INPUT}' no tiene tag ni digest explícito"
  REPOSITORY="${INPUT%:*}"
  TAG="${INPUT##*:}"
  EXPECTED_DIGEST=""
  if ! [[ "${TAG}" =~ ${TAG_RE} ]] || [[ "${TAG,,}" =~ ^(none|null|undefined|latest)$ ]]; then
    fail "tag inválido en '${INPUT}'"
  fi
fi

# Nombre completo: sin host de registry explícito es Docker Hub (y library/ si es oficial)
FIRST_COMPONENT="${REPOSITORY%%/*}"
if [[ "${REPOSITORY}" != */* ]]; then
  REPOSITORY="docker.io/library/${REPOSITORY}"
elif ! [[ "${FIRST_COMPONENT}" == *.* || "${FIRST_COMPONENT}" == *:* || "${FIRST_COMPONENT}" == localhost ]]; then
  REPOSITORY="docker.io/${REPOSITORY}"
fi
[[ "${REPOSITORY}" =~ ${REPO_RE} ]] || fail "repositorio inválido: '${REPOSITORY}'"

if [ -n "${TAG}" ]; then
  REFERENCE="${REPOSITORY}:${TAG}"
else
  REFERENCE="${REPOSITORY}@${EXPECTED_DIGEST}"
fi

RAW="$(mktemp)"
trap 'rm -f "${RAW}"' EXIT

docker buildx imagetools inspect --raw "${REFERENCE}" > "${RAW}" ||
  fail "no se pudo leer ${REFERENCE} del registry"
[ -s "${RAW}" ] || fail "el registry devolvió un manifiesto vacío para ${REFERENCE}"

INDEX_DIGEST="sha256:$(sha256sum "${RAW}" | cut -d' ' -f1)"
if [ -n "${EXPECTED_DIGEST}" ] && [ "${INDEX_DIGEST}" != "${EXPECTED_DIGEST}" ]; then
  fail "el contenido leído (${INDEX_DIGEST}) no coincide con ${EXPECTED_DIGEST}"
fi

jq -e '
  type == "object"
  and (.mediaType == "application/vnd.oci.image.index.v1+json"
       or .mediaType == "application/vnd.docker.distribution.manifest.list.v2+json")
  and (.manifests | type == "array")
' "${RAW}" > /dev/null 2>&1 ||
  fail "${REFERENCE} no es un índice OCI multi-plataforma válido"

# Hijos linux/amd64 que son imágenes (no attestations), con cualquier variante
CANDIDATES="$(jq -c '
  [ .manifests[]
    | select(type == "object")
    | select(.platform.os? == "linux" and .platform.architecture? == "amd64")
    | select((.annotations?["vnd.docker.reference.type"] // "") != "attestation-manifest")
  ]
' "${RAW}")" || fail "índice malformado en ${REFERENCE}"

COUNT="$(jq 'length' <<< "${CANDIDATES}")"
[ "${COUNT}" -eq 1 ] ||
  fail "${REFERENCE} (${INDEX_DIGEST}) tiene ${COUNT} manifiestos linux/amd64; se requiere exactamente 1"

jq -e '.[0].mediaType == "application/vnd.oci.image.manifest.v1+json"
       or .[0].mediaType == "application/vnd.docker.distribution.manifest.v2+json"' \
  <<< "${CANDIDATES}" > /dev/null ||
  fail "el hijo linux/amd64 de ${REFERENCE} no es un manifiesto de imagen"

AMD64_DIGEST="$(jq -r '.[0].digest // ""' <<< "${CANDIDATES}")"
[[ "${AMD64_DIGEST}" =~ ${DIGEST_RE} ]] ||
  fail "digest linux/amd64 inválido en ${REFERENCE}: '${AMD64_DIGEST}'"

echo "image_index_digest=${INDEX_DIGEST}"
echo "image_amd64_digest=${AMD64_DIGEST}"
echo "image_ref=${REPOSITORY}@${AMD64_DIGEST}"
