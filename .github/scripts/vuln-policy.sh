#!/usr/bin/env bash
# Política de vulnerabilidades de imágenes: decide si cada imagen que el run aplica puede
# autorizarse, a partir de la evidencia exacta de su escaneo (summary.json, trivy.json y
# trivy-version.json de la acción trivy-scan).
#
# Por imagen:
#   escaneo fresco válido, sin CRITICAL                       → authorized
#   escaneo fresco válido, cada CRITICAL con disposición válida → authorized
#   CRITICAL sin disposición válida                           → BLOCK
#   HIGH                                                      → solo se informa
#   operational_failure (registry, DB, Trivy)                 → BLOCK, salvo break-glass válido
#   evidencia ausente, incoherente, de otra referencia, vencida → BLOCK
# Un fallo operativo nunca equivale a cero hallazgos.
#
# Disposición (security/vulnerability-dispositions/<id>.json): aceptación humana explícita
# de una vulnerabilidad CRITICAL concreta. Solo es válida si su esquema es correcto, está
# en vigencia (máximo 90 días), su alcance cubre el hallazgo (misma imagen lógica, CVE,
# paquete y manifiesto linux/amd64 exacto de la imagen: el mismo invariante de identidad
# que autoriza el despliegue) y approval-provenance.sh verifica en GitHub la aprobación
# humana de ESE contenido.
#
# Break-glass (solo workflow_dispatch desde main): una imagen cuyo escaneo fresco tuvo un
# fallo operativo puede autorizarse con evidencia histórica, solo si
# security/break-glass/<id>.json está aprobado (misma verificación de procedencia), vigente
# (máximo 72 h), su aprobador no es quien dispara el run, describe exactamente la operación
# de este run (sin Terraform Apply: ninguna mutación puede ir antes de esta decisión y del
# registro de auditoría), lista esa referencia exacta con su evidencia, y
# historical-scan-evidence.sh verifica procedencia e integridad de esa evidencia. Sus
# CRITICAL conocidos siguen necesitando disposición. El resultado es break_glass, nunca
# authorized: no hubo escaneo fresco. Una autorización sirve para UNA operación
# (break-glass-record.sh lo exige al registrarla).
#
# Uso: vuln-policy.sh
# Entorno:
#   POLICY_INPUT       JSON [{surface, image_ref, dir}] de las imágenes que el run aplica
#   POLICY_PHASE       preliminar | autoritativa (solo para el resumen)
#   DISPOSITIONS_DIR   por defecto security/vulnerability-dispositions
#   BREAK_GLASS_ID     autorización de break-glass pedida (vacío = ninguna)
#   BREAK_GLASS_JUSTIFICATION  justificación de esta operación
#   BREAK_GLASS_DIR    por defecto security/break-glass
#   EVENT_NAME, GITHUB_REF, ACTOR, TRIGGERING_ACTOR
#   OP_RUN_TERRAFORM_APPLY, OP_RUN_HOST_CONFIG, OP_RUN_APP_DEPLOY, OP_RUN_BUILD,
#   OP_RUN_BUILD_POSTGRES, OP_IMAGE_TAG, OP_KEYCLOAK_RECOVERY  intención de este run
#                      (solo se usan con break-glass)
#   RISK_APPROVERS     autoridad de aprobación (la usa approval-provenance.sh)
#   EXPECTED_APPROVERS_FINGERPRINT  huella de RISK_APPROVERS que vio la evaluación
#                      preliminar; si difiere de la de este job (p. ej. una variable del
#                      environment que ensombrece la del repositorio), bloquea
#   RESULT_FILE        JSON con la decisión (por defecto policy-result.json)
#   POLICY_NOW         epoch de evaluación (por defecto, ahora)
#   (approval-provenance.sh e historical-scan-evidence.sh leen su propio entorno)
# Salida (GITHUB_OUTPUT): decision=authorized|break_glass|blocked, result_file=<ruta>,
#   approvers_fingerprint=<sha256 del conjunto normalizado de aprobadores>
# Código: 0 si authorized o break_glass; 1 si blocked; 2 si la entrada es inválida.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APPROVAL_CHECK="${APPROVAL_CHECK:-${HERE}/approval-provenance.sh}"
HISTORICAL_CHECK="${HISTORICAL_CHECK:-${HERE}/historical-scan-evidence.sh}"
DISPOSITIONS_DIR="${DISPOSITIONS_DIR:-security/vulnerability-dispositions}"
BREAK_GLASS_DIR="${BREAK_GLASS_DIR:-security/break-glass}"
BREAK_GLASS_ID="${BREAK_GLASS_ID:-}"
RESULT_FILE="${RESULT_FILE:-policy-result.json}"
POLICY_PHASE="${POLICY_PHASE:-autoritativa}"
TRIGGERING_ACTOR="${TRIGGERING_ACTOR:-}"
ACTOR="${ACTOR:-}"
NOW="${POLICY_NOW:-$(date -u +%s)}"

# Contrato de la política (cambiarlo es cambiar la política)
MAX_SCAN_AGE_HOURS=24          # escaneo fresco: antigüedad máxima al autorizar
MAX_DB_AGE_HOURS=48            # DB de vulnerabilidades: antigüedad máxima al escanear
MAX_DISPOSITION_DAYS=90        # vigencia máxima de una disposición CRITICAL
MAX_BREAK_GLASS_HOURS=72       # vigencia máxima de una autorización de break-glass (una operación)
CLOCK_SKEW_SECONDS=300
SURFACES_RE='^(backend|postgres|keycloak|traefik)$'
REF_RE='^[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}$'
ISO_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'

usage_error() {
  echo "Error: $*" >&2
  exit 2
}

[[ "${NOW}" =~ ^[0-9]+$ ]] || usage_error "POLICY_NOW inválido"
POLICY_INPUT="${POLICY_INPUT:-}"
jq -e 'type == "array" and all(.[]; type == "object" and (.surface | type == "string")
        and (.image_ref | type == "string") and (.dir | type == "string"))' \
  <<< "${POLICY_INPUT:-null}" > /dev/null 2>&1 || usage_error "POLICY_INPUT no es un arreglo [{surface, image_ref, dir}]"
[ "$(jq '[.[].surface] | length == (unique | length)' <<< "${POLICY_INPUT}")" = true ] ||
  usage_error "POLICY_INPUT repite una imagen"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

epoch() { # <iso8601 UTC> → segundos; falla si no es una fecha UTC válida
  [[ "$1" =~ ${ISO_RE} ]] || return 1
  date -u -d "$1" +%s 2> /dev/null
}

# ── Evidencia de un escaneo ──────────────────────────────────────────────────
# scan_facts <surface> <ref> <dir> <out.json>: valida la evidencia y extrae los hechos.
# Devuelve 1 (con el motivo en stdout) si la evidencia no es válida para esa referencia.
scan_facts() {
  local surface="$1" ref="$2" dir="$3" out="$4" f
  for f in summary.json trivy.json trivy-version.json; do
    [ -f "${dir}/${f}" ] || { echo "falta ${f}"; return 1; }
    jq -e 'type == "object"' "${dir}/${f}" > /dev/null 2>&1 || { echo "${f} malformado"; return 1; }
  done
  jq -e --arg s "${surface}" --arg r "${ref}" \
    '.surface == $s and .image_ref == $r and .platform == "linux/amd64" and .outcome == "scanned"' \
    "${dir}/summary.json" > /dev/null || { echo "summary.json no es un escaneo de ${ref} (${surface}, linux/amd64)"; return 1; }
  jq -e --arg r "${ref}" '
      .SchemaVersion == 2 and .ArtifactName == $r and .ArtifactType == "container_image"
      and .Metadata.ImageConfig.os == "linux" and .Metadata.ImageConfig.architecture == "amd64"
      and (.CreatedAt | type == "string") and ((.Results // []) | type == "array")' \
    "${dir}/trivy.json" > /dev/null || { echo "trivy.json no corresponde a ${ref} linux/amd64 o está incompleto"; return 1; }
  jq -e '.VulnerabilityDB.UpdatedAt | type == "string"' "${dir}/trivy-version.json" > /dev/null ||
    { echo "trivy-version.json sin metadatos de la DB"; return 1; }

  jq -n --slurpfile r "${dir}/trivy.json" --slurpfile v "${dir}/trivy-version.json" '
    $r[0] as $r | $v[0] as $v
    | [$r.Results[]? as $res | $res.Vulnerabilities[]? | . + {_target: $res.Target}] as $all
    | {
        scanned_at: $r.CreatedAt,
        trivy_version: ($v.Version // null),
        db_updated_at: $v.VulnerabilityDB.UpdatedAt,
        java_db_updated_at: ($v.JavaDB.UpdatedAt // null),
        os: (if $r.Metadata.OS.Family then "\($r.Metadata.OS.Family) \($r.Metadata.OS.Name // "")" else null end),
        eosl: ($r.Metadata.OS.EOSL // false),
        classes: ([$r.Results[]? | "\(.Class)/\(.Type)"] | unique),
        critical: [$all[] | select(.Severity == "CRITICAL") | {
          id: .VulnerabilityID, pkg: .PkgName, version: .InstalledVersion,
          target: (.PkgPath // ._target), layer: (.Layer.DiffID // null), fixed: (.FixedVersion // null)}],
        high_findings: ([$all[] | select(.Severity == "HIGH")] | length),
        high: ([$all[] | select(.Severity == "HIGH") | .VulnerabilityID] | unique),
        critical_findings: ([$all[] | select(.Severity == "CRITICAL")] | length)
      }' > "${out}"

  # El resumen publicado debe coincidir con el reporte del que sale
  jq -e --slurpfile s "${dir}/summary.json" \
    '.critical_findings == $s[0].critical.findings and .high_findings == $s[0].high.findings' \
    "${out}" > /dev/null || { echo "summary.json no coincide con trivy.json (conteos)"; return 1; }
  jq -e 'all(.critical[]; (.id | type == "string") and (.pkg | type == "string"))' "${out}" > /dev/null ||
    { echo "trivy.json con hallazgos CRITICAL sin identificador o paquete"; return 1; }

  local scanned db
  scanned=$(epoch "$(jq -r '.scanned_at' "${out}")") || { echo "fecha de escaneo inválida"; return 1; }
  db=$(epoch "$(jq -r '.db_updated_at' "${out}")") || { echo "fecha de la DB inválida"; return 1; }
  [ "${scanned}" -le $((NOW + CLOCK_SKEW_SECONDS)) ] || { echo "fecha de escaneo en el futuro"; return 1; }
  [ $((scanned - db)) -le $((MAX_DB_AGE_HOURS * 3600)) ] ||
    { echo "DB de vulnerabilidades con más de ${MAX_DB_AGE_HOURS} h al escanear: evaluación no confiable"; return 1; }
  jq --argjson age $(((NOW - scanned) / 3600)) '. + {age_hours: $age}' "${out}" > "${out}.tmp" && mv "${out}.tmp" "${out}"
}

# ── Disposiciones ────────────────────────────────────────────────────────────
GLOBAL_ERRORS='[]'
global_error() { GLOBAL_ERRORS=$(jq -c --arg m "$1" '. + [$m]' <<< "${GLOBAL_ERRORS}"); }

DISPOSITIONS='[]'
if [ -d "${DISPOSITIONS_DIR}" ]; then
  shopt -s nullglob
  for F in "${DISPOSITIONS_DIR}"/*.json; do
    ID="$(basename "${F}" .json)"
    if ! jq -e --arg id "${ID}" --arg ref_re "${REF_RE}" --arg iso "${ISO_RE}" '
        (keys == ["approver","effective","expires","id","justification","package","residual_risk","schema","scope","surface","vulnerability"])
        and .schema == "piedrazul.vulnerability-disposition/v1"
        and .id == $id and (.id | test("^VD-[0-9]{4}-[0-9]{3}$"))
        and (.surface | type == "string" and test("^(backend|postgres|keycloak|traefik)$"))
        and (.vulnerability | type == "string" and test("^(CVE-[0-9]{4}-[0-9]{4,}|GHSA(-[23456789cfghjmpqrvwx]{4}){3})$"))
        and (.package | type == "string" and length > 0 and length <= 214)
        and (.scope | type == "object" and keys == ["image_refs"]
              and (.image_refs | type == "array" and length >= 1 and all(.[]; type == "string" and test($ref_re))))
        and (.justification | type == "string" and length >= 20)
        and (.residual_risk | type == "string" and length >= 20)
        and (.approver | type == "string")
        and (.effective | type == "string" and test($iso)) and (.expires | type == "string" and test($iso))
      ' "${F}" > /dev/null 2>&1; then
      global_error "disposición ${F} con esquema inválido"
      continue
    fi
    EFF=$(epoch "$(jq -r '.effective' "${F}")") || { global_error "disposición ${F}: effective inválido"; continue; }
    EXP=$(epoch "$(jq -r '.expires' "${F}")") || { global_error "disposición ${F}: expires inválido"; continue; }
    if [ "${EXP}" -le "${EFF}" ] || [ $((EXP - EFF)) -gt $((MAX_DISPOSITION_DAYS * 86400)) ]; then
      global_error "disposición ${F}: vigencia inválida (debe ser positiva y de máximo ${MAX_DISPOSITION_DAYS} días)"
      continue
    fi
    DISPOSITIONS=$(jq -c --slurpfile d "${F}" --arg f "${F}" --argjson eff "${EFF}" --argjson exp "${EXP}" \
      '. + [$d[0] + {_file: $f, _eff: $eff, _exp: $exp}]' <<< "${DISPOSITIONS}")
  done
  shopt -u nullglob
fi

declare -A PROVENANCE=()
provenance() { # <archivo> <approver> → 0 si la aprobación humana está verificada
  local key="$1|$2" out
  if [ -z "${PROVENANCE[${key}]:-}" ]; then
    if out=$("${APPROVAL_CHECK}" "$1" "$2" 2> "${WORK}/prov.err"); then
      PROVENANCE[${key}]="ok:${out}"
    else
      PROVENANCE[${key}]="fail:$(tail -n1 "${WORK}/prov.err")"
    fi
  fi
  [[ "${PROVENANCE[${key}]}" == ok:* ]]
}

# dispose <surface> <ref> <facts.json>: decide cada CRITICAL; escribe .critical con
# .disposition (id/aprobador/PR/vence) o .undisposed (motivos)
dispose() {
  local surface="$1" ref="$2" facts="$3" n i finding cands c file approver why disp
  n=$(jq '.critical | length' "${facts}")
  for ((i = 0; i < n; i++)); do
    finding=$(jq -c ".critical[${i}]" "${facts}")
    cands=$(jq -c --arg s "${surface}" --arg r "${ref}" --argjson f "${finding}" '
      [.[] | select(.surface == $s and .vulnerability == $f.id and .package == $f.pkg
        and (.scope.image_refs | index($r)) != null)]
      | sort_by(.id)' <<< "${DISPOSITIONS}")
    why='[]'
    disp=null
    while read -r c; do
      [ -n "${c}" ] || continue
      file=$(jq -r '._file' <<< "${c}")
      approver=$(jq -r '.approver' <<< "${c}")
      if [ "$(jq '._eff' <<< "${c}")" -gt "${NOW}" ]; then
        why=$(jq -c --arg m "$(jq -r '.id' <<< "${c}"): todavía no entra en vigencia" '. + [$m]' <<< "${why}")
      elif [ "$(jq '._exp' <<< "${c}")" -le "${NOW}" ]; then
        why=$(jq -c --arg m "$(jq -r '.id' <<< "${c}"): vencida ($(jq -r '.expires' <<< "${c}"))" '. + [$m]' <<< "${why}")
      elif ! provenance "${file}" "${approver}"; then
        why=$(jq -c --arg m "$(jq -r '.id' <<< "${c}"): aprobación humana no verificada (${PROVENANCE[${file}|${approver}]#fail:})" '. + [$m]' <<< "${why}")
      else
        disp=$(jq -c --argjson p "${PROVENANCE[${file}|${approver}]#ok:}" \
          '{id, approver: $p.approver_login, pr: $p.pr, approved_commit: $p.reviewed_commit, expires,
            scope: "digest de imagen"}' <<< "${c}")
        break
      fi
    done < <(jq -c '.[]' <<< "${cands}")
    if [ "${disp}" = null ] && [ "$(jq 'length' <<< "${why}")" -eq 0 ]; then
      why='["sin disposición que cubra esta imagen, CVE y paquete"]'
    fi
    jq --argjson i "${i}" --argjson d "${disp}" --argjson w "${why}" \
      'if $d != null then .critical[$i].disposition = $d else .critical[$i].undisposed = $w end' \
      "${facts}" > "${facts}.tmp" && mv "${facts}.tmp" "${facts}"
  done
}

# ── Autoridad: mismo conjunto de aprobadores en todas las evaluaciones del run ──
# vars.* de un environment ensombrece la del repositorio solo en el job con ese
# environment. La huella del conjunto normalizado liga la evaluación autoritativa a la
# preliminar: si difieren, nadie amplía (ni cambia) la autoridad en silencio.
APPROVERS_FINGERPRINT=$(tr -s ',[:space:]' '\n' <<< "${RISK_APPROVERS:-}" | tr '[:upper:]' '[:lower:]' | sed '/^$/d' |
  LC_ALL=C sort -u | sha256sum | cut -d' ' -f1)
if [ -n "${EXPECTED_APPROVERS_FINGERPRINT:-}" ] &&
   [ "${EXPECTED_APPROVERS_FINGERPRINT}" != "${APPROVERS_FINGERPRINT}" ]; then
  global_error "el conjunto de aprobadores de riesgo de este job no es el de la evaluación preliminar (¿variable de environment que ensombrece vars.VULNERABILITY_RISK_APPROVERS?)"
fi

# ── Intención de este run (para ligar un break-glass a una operación) ────────
RUN_OPERATION=$(jq -cn --arg tf "${OP_RUN_TERRAFORM_APPLY:-}" --arg hc "${OP_RUN_HOST_CONFIG:-}" \
  --arg ad "${OP_RUN_APP_DEPLOY:-}" --arg b "${OP_RUN_BUILD:-}" --arg bp "${OP_RUN_BUILD_POSTGRES:-}" \
  --arg tag "${OP_IMAGE_TAG:-}" --arg kr "${OP_KEYCLOAK_RECOVERY:-}" '
  def flag: if . == "true" then true elif . == "false" then false else error("flag") end;
  try {run_terraform_apply: ($tf | flag), run_host_config: ($hc | flag), run_app_deploy: ($ad | flag),
       run_build: ($b | flag), run_build_postgres: ($bp | flag), image_tag: $tag,
       keycloak_recovery: ($kr | flag)} catch null')

# ── Autorización de break-glass ──────────────────────────────────────────────
BG='null'        # autorización verificada
BG_ERROR=''
if [ -n "${BREAK_GLASS_ID}" ]; then
  BG_FILE="${BREAK_GLASS_DIR}/${BREAK_GLASS_ID}.json"
  JUSTIFICATION="${BREAK_GLASS_JUSTIFICATION:-}"
  JUSTIFICATION_TRIMMED="${JUSTIFICATION//[[:space:]]/}"
  if [ "${EVENT_NAME:-}" != workflow_dispatch ] || [ "${GITHUB_REF:-}" != refs/heads/main ]; then
    BG_ERROR="break-glass solo se admite en workflow_dispatch desde main (evento '${EVENT_NAME:-}', ref '${GITHUB_REF:-}')"
  elif ! [[ "${BREAK_GLASS_ID}" =~ ^BG-[0-9]{4}-[0-9]{3}$ ]]; then
    BG_ERROR="identificador de break-glass inválido"
  elif [ "${#JUSTIFICATION_TRIMMED}" -lt 20 ]; then
    BG_ERROR="break-glass sin justificación de la operación (mínimo 20 caracteres)"
  elif [ ! -f "${BG_FILE}" ]; then
    BG_ERROR="no existe ${BG_FILE} en este commit"
  elif [ "${RUN_OPERATION}" = null ]; then
    BG_ERROR="intención del run incompleta o inválida (OP_*): no se puede ligar la autorización a esta operación"
  elif [ "$(jq -r '.run_terraform_apply' <<< "${RUN_OPERATION}")" != false ]; then
    BG_ERROR="break-glass no admite Terraform Apply: el Apply mutaría antes de la decisión autoritativa y del registro de auditoría"
  elif ! jq -e --arg id "${BREAK_GLASS_ID}" --arg ref_re "${REF_RE}" --arg iso "${ISO_RE}" '
      (keys == ["approver","effective","expires","id","images","justification","operation","schema"])
      and .schema == "piedrazul.break-glass-authorization/v2" and .id == $id
      and (.justification | type == "string" and length >= 20)
      and (.approver | type == "string")
      and (.operation | type == "object"
        and keys == ["image_tag","keycloak_recovery","run_app_deploy","run_build","run_build_postgres","run_host_config"]
        and (.image_tag | type == "string")
        and ([.keycloak_recovery, .run_app_deploy, .run_build, .run_build_postgres, .run_host_config] | all(type == "boolean")))
      and (.effective | type == "string" and test($iso)) and (.expires | type == "string" and test($iso))
      and (.images | type == "array" and length >= 1 and ([.[].surface] | length == (unique | length))
        and all(.[]; type == "object"
          and keys == ["coverage_limitations","evidence","image_ref","known_critical","surface"]
          and (.surface | type == "string" and test("^(backend|postgres|keycloak|traefik)$"))
          and (.image_ref | type == "string" and test($ref_re))
          and (.coverage_limitations | type == "string" and length >= 10)
          and (.known_critical | type == "array" and all(.[]; type == "string"))
          and (.evidence | type == "object" and keys == ["artifact_digest","artifact_id","run_id","scanned_at"]
            and (.artifact_digest | type == "string" and test("^sha256:[0-9a-f]{64}$"))
            and (.artifact_id | type == "number" and . == floor and . > 0)
            and (.run_id | type == "number" and . == floor and . > 0)
            and (.scanned_at | type == "string" and test($iso)))))
    ' "${BG_FILE}" > /dev/null 2>&1; then
    BG_ERROR="${BG_FILE} con esquema inválido"
  else
    BG_APPROVER=$(jq -r '.approver' "${BG_FILE}")
    BG_EFF=$(epoch "$(jq -r '.effective' "${BG_FILE}")") || BG_EFF=''
    BG_EXP=$(epoch "$(jq -r '.expires' "${BG_FILE}")") || BG_EXP=''
    if [ -z "${BG_EFF}" ] || [ -z "${BG_EXP}" ] || [ "${BG_EXP}" -le "${BG_EFF}" ] ||
       [ $((BG_EXP - BG_EFF)) -gt $((MAX_BREAK_GLASS_HOURS * 3600)) ]; then
      BG_ERROR="${BG_FILE}: vigencia inválida (positiva y de máximo ${MAX_BREAK_GLASS_HOURS} h)"
    elif [ "${BG_EFF}" -gt "${NOW}" ] || [ "${BG_EXP}" -le "${NOW}" ]; then
      BG_ERROR="${BG_FILE}: fuera de vigencia ($(jq -r '"\(.effective) → \(.expires)"' "${BG_FILE}"))"
    elif ! jq -e --argjson op "${RUN_OPERATION}" '.operation == ($op | del(.run_terraform_apply))' "${BG_FILE}" > /dev/null; then
      BG_ERROR="${BG_FILE} autoriza otra operación ($(jq -c '.operation' "${BG_FILE}")) que la de este run ($(jq -c 'del(.run_terraform_apply)' <<< "${RUN_OPERATION}"))"
    elif [ -z "${TRIGGERING_ACTOR}" ] || [ "${BG_APPROVER,,}" = "${TRIGGERING_ACTOR,,}" ] ||
         [ "${BG_APPROVER,,}" = "${ACTOR,,}" ]; then
      BG_ERROR="la autorización de break-glass debe venir de otra persona que quien dispara el run (${TRIGGERING_ACTOR:-desconocido})"
    elif ! provenance "${BG_FILE}" "${BG_APPROVER}"; then
      BG_ERROR="${BG_FILE}: aprobación humana no verificada (${PROVENANCE[${BG_FILE}|${BG_APPROVER}]#fail:})"
    else
      BG=$(jq -c --argjson p "${PROVENANCE[${BG_FILE}|${BG_APPROVER}]#ok:}" --arg j "${JUSTIFICATION}" \
        '{id, approver: $p.approver_login, pr: $p.pr, approved_commit: $p.reviewed_commit,
          authorization_commit: $p.merge_commit, effective, expires,
          operation, authorization_justification: .justification, operation_justification: $j, images}' "${BG_FILE}")
    fi
  fi
  [ -z "${BG_ERROR}" ] || global_error "break-glass ${BREAK_GLASS_ID}: ${BG_ERROR}"
fi

# ── Evaluación por imagen ────────────────────────────────────────────────────
reason() { REASONS=$(jq -c --arg m "$1" '. + [$m]' <<< "${REASONS}"); }
SURFACES='[]'
COUNT=$(jq 'length' <<< "${POLICY_INPUT}")
for ((k = 0; k < COUNT; k++)); do
  SURFACE=$(jq -r ".[${k}].surface" <<< "${POLICY_INPUT}")
  REF=$(jq -r ".[${k}].image_ref" <<< "${POLICY_INPUT}")
  DIR=$(jq -r ".[${k}].dir" <<< "${POLICY_INPUT}")
  FACTS="${WORK}/${k}.facts.json"
  VERDICT=blocked
  CODE=""
  REASONS='[]'
  SOURCE=fresh
  HISTORICAL=null

  if ! [[ "${SURFACE}" =~ ${SURFACES_RE} ]] || ! [[ "${REF}" =~ ${REF_RE} ]]; then
    CODE=evidence_invalid
    reason "imagen o referencia inválida ('${SURFACE}', '${REF}')"
  elif [ ! -f "${DIR}/summary.json" ] || ! jq -e 'type == "object"' "${DIR}/summary.json" > /dev/null 2>&1; then
    CODE=evidence_invalid
    reason "sin summary.json válido del escaneo de ${REF}"
  elif ! jq -e --arg s "${SURFACE}" --arg r "${REF}" '.surface == $s and .image_ref == $r' "${DIR}/summary.json" > /dev/null; then
    CODE=evidence_invalid
    reason "la evidencia es de otra imagen ($(jq -c '{surface, image_ref}' "${DIR}/summary.json"))"
  elif jq -e '.outcome == "scanned"' "${DIR}/summary.json" > /dev/null; then
    if WHY=$(scan_facts "${SURFACE}" "${REF}" "${DIR}" "${FACTS}"); then
      if [ "$(jq '.age_hours' "${FACTS}")" -ge "${MAX_SCAN_AGE_HOURS}" ]; then
        CODE=evidence_stale
        reason "escaneo de hace $(jq '.age_hours' "${FACTS}") h (máximo ${MAX_SCAN_AGE_HOURS} h): volver a escanear (re-ejecutar todos los jobs)"
      else
        VERDICT=authorized
      fi
    else
      CODE=evidence_invalid
      reason "${WHY}"
      rm -f "${FACTS}"
    fi
  elif jq -e '.outcome == "operational_failure" and .critical == null and .high == null' "${DIR}/summary.json" > /dev/null; then
    CODE=operational_failure
    reason "fallo operativo del escáner (registry, DB o Trivy): sin evaluación, no equivale a cero hallazgos"
    if [ "${BG}" != null ]; then
      ENTRY=$(jq -c --arg s "${SURFACE}" --arg r "${REF}" '[.images[] | select(.surface == $s and .image_ref == $r)]' <<< "${BG}")
      if [ "$(jq 'length' <<< "${ENTRY}")" -ne 1 ]; then
        reason "break-glass ${BREAK_GLASS_ID} no autoriza ${REF}"
      else
        ENTRY=$(jq -c '.[0]' <<< "${ENTRY}")
        HDIR="${WORK}/${k}.historical"
        if ! HISTORICAL=$("${HISTORICAL_CHECK}" "${SURFACE}" "${REF}" \
            "$(jq -r '.evidence.run_id' <<< "${ENTRY}")" "$(jq -r '.evidence.artifact_id' <<< "${ENTRY}")" \
            "$(jq -r '.evidence.artifact_digest' <<< "${ENTRY}")" "${HDIR}" 2> "${WORK}/hist.err"); then
          HISTORICAL=null
          reason "evidencia histórica no verificable: $(tail -n1 "${WORK}/hist.err")"
        elif ! WHY=$(scan_facts "${SURFACE}" "${REF}" "${HDIR}" "${FACTS}"); then
          HISTORICAL=null
          rm -f "${FACTS}"
          reason "evidencia histórica inválida: ${WHY}"
        elif [ "$(jq -r '.scanned_at' "${FACTS}")" != "$(jq -r '.evidence.scanned_at' <<< "${ENTRY}")" ]; then
          reason "la autorización declara un escaneo de $(jq -r '.evidence.scanned_at' <<< "${ENTRY}") y la evidencia es de $(jq -r '.scanned_at' "${FACTS}")"
        elif [ "$(jq -c '[.critical[].id] | unique' "${FACTS}")" != "$(jq -c '.known_critical | unique' <<< "${ENTRY}")" ]; then
          reason "known_critical de la autorización no coincide con los CRITICAL de la evidencia ($(jq -c '[.critical[].id] | unique' "${FACTS}"))"
        else
          SOURCE=historical
          VERDICT=break_glass
          CODE=""
          REASONS='[]'
        fi
      fi
    elif [ -n "${BREAK_GLASS_ID}" ]; then
      reason "break-glass ${BREAK_GLASS_ID} no es válido"
    fi
  else
    CODE=evidence_invalid
    reason "summary.json con resultado inválido ($(jq -c '.outcome' "${DIR}/summary.json"))"
  fi

  # CRITICAL: cada hallazgo necesita disposición válida (también con evidencia histórica)
  if [ "${VERDICT}" != blocked ] && [ -f "${FACTS}" ]; then
    dispose "${SURFACE}" "${REF}" "${FACTS}"
    UNDISPOSED=$(jq '[.critical[] | select(.disposition == null)] | length' "${FACTS}")
    if [ "${UNDISPOSED}" -gt 0 ]; then
      VERDICT=blocked
      CODE=undisposed_critical
      reason "${UNDISPOSED} hallazgo(s) CRITICAL sin disposición válida: $(jq -r '[.critical[] | select(.disposition == null) | "\(.id) (\(.pkg) \(.version))"] | unique | join(", ")' "${FACTS}")"
    fi
  fi

  if [ -f "${FACTS}" ]; then F_JSON=$(cat "${FACTS}"); else F_JSON=null; fi
  SURFACES=$(jq -c --arg s "${SURFACE}" --arg r "${REF}" --arg v "${VERDICT}" --arg c "${CODE}" \
    --arg src "${SOURCE}" --argjson reasons "${REASONS}" --argjson facts "${F_JSON}" --argjson h "${HISTORICAL}" '
    . + [{surface: $s, image_ref: $r, verdict: $v, reason_code: (if $c == "" then null else $c end),
          reasons: $reasons, evaluation: (if $facts == null then null else $src end),
          scan: $facts, historical_evidence: $h}]' <<< "${SURFACES}")
done

# ── Decisión ─────────────────────────────────────────────────────────────────
DECISION=$(jq -r --argjson g "${GLOBAL_ERRORS}" '
  if ($g | length) > 0 or any(.[]; .verdict == "blocked") then "blocked"
  elif any(.[]; .verdict == "break_glass") then "break_glass"
  else "authorized" end' <<< "${SURFACES}")

jq -n --arg d "${DECISION}" --arg phase "${POLICY_PHASE}" --argjson now "${NOW}" \
  --argjson g "${GLOBAL_ERRORS}" --argjson bg "${BG}" --argjson s "${SURFACES}" \
  --arg bgid "${BREAK_GLASS_ID}" '{
    decision: $d, phase: $phase, evaluated_at: ($now | todate),
    global_errors: $g, break_glass_requested: ($bgid != ""), break_glass: $bg, surfaces: $s
  }' > "${RESULT_FILE}"

# Resumen para el operador
summary() {
  jq -r '
    def scanline: if .scan == null then "—" else
      "\(.evaluation) · \(.scan.scanned_at) (hace \(.scan.age_hours) h) · Trivy \(.scan.trivy_version) · DB \(.scan.db_updated_at)" end;
    "## Política de vulnerabilidades (\(.phase)): **\(.decision | ascii_upcase)**",
    "",
    (if (.global_errors | length) > 0 then (.global_errors[] | "- **BLOQUEO:** \(.)"), "" else empty end),
    (if .break_glass != null then
      "> **BREAK-GLASS \(.break_glass.id)** aprobado por \(.break_glass.approver) (PR #\(.break_glass.pr)), vigente hasta \(.break_glass.expires). Las imágenes marcadas `break_glass` NO tuvieron un escaneo fresco: se autorizan con evidencia histórica, sin la garantía escaneado = autorizado = desplegado de este run.", "" else empty end),
    "| imagen | veredicto | evaluación | CRITICAL | HIGH (informativo) |",
    "|---|---|---|---|---|",
    (.surfaces[] | "| \(.surface) `\(.image_ref)` | **\(.verdict)**\(if .reason_code then " (\(.reason_code))" else "" end) | \(scanline) | \(if .scan == null then "—" else (.scan.critical | length | tostring) end) | \(if .scan == null then "—" else "\(.scan.high_findings) (\(.scan.high | length) CVE)" end) |"),
    "",
    (.surfaces[] | select((.reasons | length) > 0) | "- \(.surface): \(.reasons | join("; "))"),
    (.surfaces[] | select(.scan != null) | .surface as $s | .scan.critical[] |
      if .disposition then "- \($s): \(.id) en \(.pkg) \(.version) — disposición \(.disposition.id) (\(.disposition.approver), PR #\(.disposition.pr), vence \(.disposition.expires))"
      else "- \($s): **\(.id) en \(.pkg) \(.version) sin disposición** — \(.undisposed // ["no evaluada: la imagen ya está bloqueada"] | join("; "))" end),
    (.surfaces[] | select(.scan != null and (.scan.high | length) > 0) | "- \(.surface) HIGH: \(.scan.high | join(", "))"),
    (.surfaces[] | select(.scan != null) | "- \(.surface) cobertura: SO \(.scan.os // "no detectado (paquetes del sistema sin evaluar)")\(if .scan.eosl then " (fuera de soporte)" else "" end); clases \(.scan.classes | join(", ")). Cero hallazgos no prueba ausencia de vulnerabilidades."),
    (.surfaces[] | select(.historical_evidence != null) | "- \(.surface) evidencia histórica: run \(.historical_evidence.run_id) (commit \(.historical_evidence.run_commit[0:10])), artifact \(.historical_evidence.artifact_id) `\(.historical_evidence.artifact_digest)`, escaneo de hace \(.scan.age_hours) h")
  ' "${RESULT_FILE}"
}
# El resumen solo informa: si no se puede generar, la decisión no cambia
summary >&2 || echo "Error: no se pudo generar el resumen; ver ${RESULT_FILE}" >&2
[ -z "${GITHUB_STEP_SUMMARY:-}" ] || summary >> "${GITHUB_STEP_SUMMARY}" || true
echo "decision=${DECISION}"
echo "result_file=${RESULT_FILE}"
echo "approvers_fingerprint=${APPROVERS_FINGERPRINT}"
[ "${DECISION}" != blocked ]
