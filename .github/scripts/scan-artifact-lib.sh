# shellcheck shell=bash
# Descarga verificada de un artifact del workflow de producción: evidencia de escaneo
# (image-scan-*), del run actual (current-scan-evidence.sh) o histórica
# (historical-scan-evidence.sh), y el artefacto del frontend (frontend-artifact.sh).
#
# La integridad se verifica contra el digest SHA-256 que GitHub registró para el artifact
# al subirlo (metadatos de la API, no un checksum que traiga el propio artifact): el zip
# descargado debe tener exactamente ese digest y contener exactamente los archivos
# esperados (por defecto summary.json, trivy.json y trivy-version.json), sin rutas.
# Cualquier diferencia falla cerrado; no hay modo "advertencia". El origen (qué run, qué
# workflow) lo verifica quien llama.
#
# Requiere: gh (GH_TOKEN con actions: read), GITHUB_REPOSITORY, sha256sum, unzip.

# fetch_verified_artifact <artifact JSON de la API> <dir_salida> [archivos esperados]
#   archivos esperados: nombres separados por coma en orden C (sort), sin rutas.
# Devuelve 1 con el motivo en stderr si el artifact no es íntegro.
fetch_verified_artifact() {
  local artifact="$1" out="$2" expected="${3:-summary.json,trivy-version.json,trivy.json}"
  local id digest tmp files names
  id=$(jq -r '.id // ""' <<< "${artifact}")
  digest=$(jq -r '.digest // ""' <<< "${artifact}")
  [[ "${id}" =~ ^[1-9][0-9]*$ ]] || { echo "artifact sin id" >&2; return 1; }
  [[ "${digest}" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    { echo "artifact ${id}: GitHub no registró un digest verificable" >&2; return 1; }
  [ ! -e "${out}" ] || { echo "${out} ya existe" >&2; return 1; }
  tmp=$(mktemp -d)
  if ! gh api "repos/${GITHUB_REPOSITORY}/actions/artifacts/${id}/zip" > "${tmp}/artifact.zip"; then
    rm -rf "${tmp}"
    echo "no se pudo descargar el artifact ${id}" >&2
    return 1
  fi
  if [ "sha256:$(sha256sum "${tmp}/artifact.zip" | cut -d' ' -f1)" != "${digest}" ]; then
    rm -rf "${tmp}"
    echo "artifact ${id}: el contenido descargado no tiene el digest registrado (alterado o incompleto)" >&2
    return 1
  fi
  # Datos no confiables hasta validarlos: lista exacta antes de extraer, sin rutas
  files=$(unzip -Z1 "${tmp}/artifact.zip" 2> /dev/null | LC_ALL=C sort | paste -sd,)
  if [ "${files}" != "${expected}" ]; then
    rm -rf "${tmp}"
    echo "artifact ${id}: no contiene exactamente ${expected//,/, }" >&2
    return 1
  fi
  IFS=, read -r -a names <<< "${expected}"
  mkdir -p "${out}"
  if ! unzip -q -o "${tmp}/artifact.zip" "${names[@]}" -d "${out}"; then
    rm -rf "${tmp}"
    echo "artifact ${id}: no se pudo extraer" >&2
    return 1
  fi
  rm -rf "${tmp}"
}
