#!/usr/bin/env bash
# Política de vulnerabilidades de imágenes: decisión (vuln-policy.sh), procedencia de la
# aprobación humana (approval-provenance.sh), procedencia e integridad de evidencia
# histórica (historical-scan-evidence.sh), registro de break-glass (break-glass-record.sh),
# entrada de la política (scan-evidence.sh) y su lugar en el workflow de producción.
#
# Sin red ni GitHub: `gh` es falso (sirve respuestas registradas por método, ruta y
# parámetros, y registra cada llamada) y la historia de main es un repositorio git
# desechable con merges reales. Hallazgos, aprobaciones y evidencia son sintéticos: nada de
# esto aprueba ni activa una excepción real.
#
# Uso: infra/tests/vuln-policy-check.sh
# Requiere: jq, yq (mikefarah v4), git, zip, unzip, sha256sum
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPTS="${ROOT}/.github/scripts"
POLICY="${SCRIPTS}/vuln-policy.sh"
APPROVAL="${SCRIPTS}/approval-provenance.sh"
HISTORICAL="${SCRIPTS}/historical-scan-evidence.sh"
RECORD="${SCRIPTS}/break-glass-record.sh"
SCAN_EVIDENCE="${SCRIPTS}/scan-evidence.sh"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
ACTION="${ROOT}/.github/actions/trivy-scan/action.yml"

for bin in jq yq git zip unzip sha256sum; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "${TMP:?}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

d64() { printf "$1%.0s" {1..64}; }
NOW=$(date -u -d 2026-10-15T12:00:00Z +%s)
REPO=o/r
REPO_ID=555

# ── gh falso ────────────────────────────────────────────────────────────────
mkdir -p "${TMP}/bin" "${TMP}/gh"
export FAKE_GH_DIR="${TMP}/gh" FAKE_GH_LOG="${TMP}/gh.log"
cat > "${TMP}/bin/gh" << 'EOF'
#!/usr/bin/env bash
# Solo `gh api`: responde con el archivo registrado para "<MÉTODO> <ruta>[?<parámetros -f ordenados>]"
[ "${1:-}" = api ] || { echo "gh falso: $*" >&2; exit 99; }
shift
method=GET path="" jqexpr="" input="" params=()
while [ $# -gt 0 ]; do
  case "$1" in
    --paginate) shift ;;
    -X) method="$2"; shift 2 ;;
    -f) params+=("$2"); shift 2 ;;
    --jq) jqexpr="$2"; shift 2 ;;
    --input) input="$2"; shift 2 ;;
    -*) echo "gh falso: opción $1" >&2; exit 99 ;;
    *) path="$1"; shift ;;
  esac
done
key="${method} ${path}"
[ "${#params[@]}" -eq 0 ] || key="${key}?$(printf '%s\n' "${params[@]}" | LC_ALL=C sort | paste -sd'&')"
echo "${key}" >> "${FAKE_GH_LOG}"
[ -z "${input}" ] || cat > "${FAKE_GH_DIR}/last-input.json"
f="${FAKE_GH_DIR}/$(printf '%s' "${key}" | sha256sum | cut -c1-32)"
[ -f "${f}" ] || { echo "HTTP 404: ${key}" >&2; exit 1; }
if [ -n "${jqexpr}" ]; then jq -r "${jqexpr}" "${f}"; else cat "${f}"; fi
# Respuesta siguiente registrada (p. ej. la misma lista antes y después de crear algo)
[ ! -f "${f}.then" ] || mv "${f}.then" "${f}"
EOF
chmod +x "${TMP}/bin/gh"
serve() { printf '%s' "$2" > "${FAKE_GH_DIR}/$(printf '%s' "$1" | sha256sum | cut -c1-32)"; }
serve_file() { cp "$2" "${FAKE_GH_DIR}/$(printf '%s' "$1" | sha256sum | cut -c1-32)"; }
unserve() { rm -f "${FAKE_GH_DIR:?}/$(printf '%s' "$1" | sha256sum | cut -c1-32)"{,.then}; }
serve_then() { printf '%s' "$2" > "${FAKE_GH_DIR}/$(printf '%s' "$1" | sha256sum | cut -c1-32).then"; }

# ── Historia de main (git real) ─────────────────────────────────────────────
G="${TMP}/repo"
git init -q -b main "${G}"
git -C "${G}" config user.email t@example.invalid
git -C "${G}" config user.name t
git -C "${G}" config commit.gpgsign false
mkdir -p "${G}/security/vulnerability-dispositions" "${G}/security/break-glass"
echo base > "${G}/README"
git -C "${G}" add -A && git -C "${G}" commit -qm init
INIT=$(git -C "${G}" rev-parse HEAD)

# merge_pr <rama> <archivo> <contenido>: commit en la rama y merge --no-ff a main.
# Deja en FEAT_COMMIT el commit de la rama, en MERGE el de main y en BLOB el contenido.
merge_pr() {
  git -C "${G}" checkout -q -b "$1" main
  mkdir -p "$(dirname "${G}/$2")"
  printf '%s\n' "$3" > "${G}/$2"
  git -C "${G}" add -A && git -C "${G}" commit -qm "$1"
  FEAT_COMMIT=$(git -C "${G}" rev-parse HEAD)
  git -C "${G}" checkout -q main
  git -C "${G}" merge -q --no-ff "$1" -m "merge $1"
  MERGE=$(git -C "${G}" rev-parse HEAD)
  BLOB=$(git -C "${G}" rev-parse "HEAD:$2")
}
# pr_fixtures <número> <merge> <autor> <archivo> <commit revisado> <blob en ese commit> <revisiones JSON>
pr_fixtures() {
  serve "GET repos/${REPO}/commits/$2/pulls" \
    "$(jq -cn --argjson n "$1" --arg m "$2" --arg a "$3" '[{number: $n, merged_at: "2026-10-10T00:00:00Z", base: {ref: "main"}, merge_commit_sha: $m, user: {login: $a}}]')"
  serve "GET repos/${REPO}/pulls/$1/reviews?per_page=100" "$7"
  serve "GET repos/${REPO}/contents/$4?ref=$5" "$(jq -cn --arg b "$6" '{sha: $b}')"
}
review() { # <id> <login> <user id> <estado> <commit> [tipo]
  jq -cn --argjson id "$1" --arg l "$2" --argjson uid "$3" --arg s "$4" --arg c "$5" --arg t "${6:-User}" \
    '{id: $id, user: {login: $l, id: $uid, type: $t}, state: $s, commit_id: $c, submitted_at: "2026-10-09T00:00:00Z"}'
}

BACKEND="ghcr.io/o/r/backend@sha256:$(d64 1)"
BACKEND_OTHER="ghcr.io/o/r/backend@sha256:$(d64 2)"
POSTGRES="ghcr.io/o/r/postgres@sha256:$(d64 3)"
LAYER="sha256:$(d64 4)"
disposition() { # <id> <surface> <cve> <pkg> <scope JSON> [effective] [expires] [approver]
  jq -n --arg id "$1" --arg s "$2" --arg v "$3" --arg p "$4" --argjson scope "$5" \
    --arg e "${6:-2026-10-01T00:00:00Z}" --arg x "${7:-2026-12-01T00:00:00Z}" --arg a "${8:-alice}" '{
      schema: "piedrazul.vulnerability-disposition/v1", id: $id, surface: $s, vulnerability: $v, package: $p,
      scope: $scope, justification: "No alcanzable: el binario no usa TLS en ningún camino.",
      residual_risk: "Bajo: requiere ejecución local previa dentro del contenedor.",
      approver: $a, effective: $e, expires: $x}'
}

# ── 1. Procedencia de la aprobación humana ──────────────────────────────────
APPROVERS="alice:1001, bob:1002"
prov() { # <archivo> <approver declarado> [VAR=valor...]
  (cd "${G}" && env -i PATH="${TMP}/bin:${PATH}" FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" \
    GITHUB_REPOSITORY="${REPO}" EVAL_COMMIT="$(git rev-parse HEAD)" RISK_APPROVERS="${APPROVERS}" "${@:3}" \
    "${APPROVAL}" "$1" "$2")
}
expect_prov_fail() { # <descripción> <archivo> <approver> [VAR=valor...]
  local out rc=0
  out=$(prov "$2" "$3" "${@:4}" 2> "${TMP}/prov.err") || rc=$?
  if [ "${rc}" -ne 0 ] && [ -z "${out}" ]; then ok "aprobación rechazada: $1"; else ko "aprobación rechazada: $1 (rc=${rc}: ${out})"; fi
}

F1=security/vulnerability-dispositions/VD-2026-001.json
merge_pr pr7 "${F1}" "$(disposition VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}")"
F1_FEAT="${FEAT_COMMIT}" F1_MERGE="${MERGE}" F1_BLOB="${BLOB}"
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${F1_FEAT}")]"

if OUT=$(prov "${F1}" alice 2> /dev/null) &&
   jq -e --arg m "${F1_MERGE}" --arg b "${F1_BLOB}" --arg c "${F1_FEAT}" \
     '.pr == 7 and .merge_commit == $m and .blob == $b and .reviewed_commit == $c and .approver_login == "alice" and .approver_id == 1001' <<< "${OUT}" > /dev/null; then
  ok "aprobación verificada: PR fusionado a main, revisor autorizado (login e id), mismo blob aprobado"
else
  ko "aprobación verificada: PR fusionado a main, revisor autorizado (login e id), mismo blob aprobado (${OUT:-sin salida})"
fi
expect_prov_fail "sin autoridad configurada (vars.VULNERABILITY_RISK_APPROVERS vacía)" "${F1}" alice RISK_APPROVERS=
expect_prov_fail "autoridad mal formada" "${F1}" alice "RISK_APPROVERS=alice"
expect_prov_fail "el registro nombra a alguien sin autoridad (autodeclarado)" "${F1}" mallory
expect_prov_fail "el registro nombra a un aprobador autorizado que no aprobó el PR" "${F1}" bob
expect_prov_fail "login autorizado con otro id (cuenta renombrada o reciclada)" "${F1}" alice "RISK_APPROVERS=alice:9999"
check "aprobación: sin autoridad, ni siquiera consulta GitHub" \
  bash -c "! grep -q 'pulls' <(: > '${FAKE_GH_LOG}'; cd '${G}' && env -i PATH='${TMP}/bin:${PATH}' FAKE_GH_DIR='${FAKE_GH_DIR}' FAKE_GH_LOG='${FAKE_GH_LOG}' GITHUB_REPOSITORY=${REPO} EVAL_COMMIT=\$(git rev-parse HEAD) RISK_APPROVERS= '${APPROVAL}' '${F1}' alice 2>/dev/null; cat '${FAKE_GH_LOG}')"

# Aprobó un contenido anterior: después del approve se cambió el alcance
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${INIT}")]"
serve "GET repos/${REPO}/contents/${F1}?ref=${INIT}" '{"sha": "0000000000000000000000000000000000000000"}'
expect_prov_fail "la aprobación es de otro contenido del registro (alterado después de aprobar)" "${F1}" alice
unserve "GET repos/${REPO}/contents/${F1}?ref=${INIT}"
expect_prov_fail "el commit aprobado no tiene el registro" "${F1}" alice
for LATER in CHANGES_REQUESTED DISMISSED; do
  pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" \
    "[$(review 1 alice 1001 APPROVED "${F1_FEAT}"),$(review 2 alice 1001 "${LATER}" "${F1_FEAT}")]"
  expect_prov_fail "aprobación anulada después (${LATER})" "${F1}" alice
done
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" \
  "[$(review 1 alice 1001 APPROVED "${F1_FEAT}"),$(review 2 alice 1001 COMMENTED "${F1_FEAT}")]"
check "aprobación: un comentario posterior no anula el APPROVED" prov "${F1}" alice
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${F1_FEAT}" Bot)]"
expect_prov_fail "revisor que no es humano (Bot)" "${F1}" alice
pr_fixtures 7 "${F1_MERGE}" alice "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${F1_FEAT}")]"
expect_prov_fail "el aprobador es el autor del PR" "${F1}" alice
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[]"
expect_prov_fail "PR sin revisiones" "${F1}" alice
serve "GET repos/${REPO}/commits/${F1_MERGE}/pulls" \
  "$(jq -cn --arg m "${F1_MERGE}" '[{number: 7, merged_at: null, base: {ref: "main"}, merge_commit_sha: $m, user: {login: "carol"}}]')"
expect_prov_fail "PR no fusionado" "${F1}" alice
serve "GET repos/${REPO}/commits/${F1_MERGE}/pulls" \
  "$(jq -cn --arg m "${F1_MERGE}" '[{number: 7, merged_at: "2026-10-10T00:00:00Z", base: {ref: "develop"}, merge_commit_sha: $m, user: {login: "carol"}}]')"
expect_prov_fail "PR fusionado hacia otra rama" "${F1}" alice
serve "GET repos/${REPO}/commits/${F1_MERGE}/pulls" '[]'
expect_prov_fail "integrado sin PR (push directo a main)" "${F1}" alice
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${F1_FEAT}")]"
expect_prov_fail "registro inexistente en el commit evaluado" security/vulnerability-dispositions/VD-2026-999.json alice

# Un PR posterior modifica el registro ya aprobado (p. ej. extiende la vigencia) sin aprobación autorizada
F2=security/vulnerability-dispositions/VD-2026-002.json
merge_pr pr8 "${F2}" "$(disposition VD-2026-002 backend CVE-2026-41293 org.apache.tomcat.embed:tomcat-embed-core "{\"image_refs\": [\"${BACKEND}\"]}")"
pr_fixtures 8 "${MERGE}" carol "${F2}" "${FEAT_COMMIT}" "${BLOB}" "[$(review 3 alice 1001 APPROVED "${FEAT_COMMIT}")]"
check "aprobación: segundo registro aprobado" prov "${F2}" alice
merge_pr pr9 "${F2}" "$(disposition VD-2026-002 backend CVE-2026-41293 org.apache.tomcat.embed:tomcat-embed-core "{\"image_refs\": [\"${BACKEND}\"]}" 2026-10-01T00:00:00Z 2026-12-29T00:00:00Z)"
pr_fixtures 9 "${MERGE}" carol "${F2}" "${FEAT_COMMIT}" "${BLOB}" "[$(review 4 dave 2001 APPROVED "${FEAT_COMMIT}")]"
expect_prov_fail "registro modificado por un PR posterior aprobado por alguien sin autoridad" "${F2}" alice
EVAL=$(git -C "${G}" rev-parse HEAD)

# ── 2. Política: matriz de decisión ─────────────────────────────────────────
mkscan() { # <dir> <surface> <ref> <CRITICAL JSON [{id,pkg,version,layer}]> <n HIGH> [CreatedAt] [DB UpdatedAt] [arch]
  mkdir -p "$1"
  jq -n --arg ref "$3" --arg c "${6:-2026-10-15T10:00:00.123456789Z}" --arg arch "${8:-amd64}" \
    --argjson crit "$4" --argjson nh "$5" '{
      SchemaVersion: 2, CreatedAt: $c, ArtifactName: $ref, ArtifactType: "container_image",
      Metadata: {OS: {Family: "alpine", Name: "3.24.2"}, ImageConfig: {os: "linux", architecture: $arch}},
      Results: [{Target: "img (alpine 3.24.2)", Class: "os-pkgs", Type: "alpine",
        Vulnerabilities: ([$crit[] | {VulnerabilityID: .id, PkgName: .pkg, InstalledVersion: .version,
                                      Severity: "CRITICAL", Layer: {DiffID: .layer}}]
          + [range($nh) | {VulnerabilityID: "CVE-2026-1000\(.)", PkgName: "libx", InstalledVersion: "1", Severity: "HIGH"}])}]}' \
    > "$1/trivy.json"
  jq -n --arg db "${7:-2026-10-15T01:00:00Z}" '{Version: "0.74.0", VulnerabilityDB: {Version: 2, UpdatedAt: $db}}' > "$1/trivy-version.json"
  jq -n --arg s "$2" --arg ref "$3" --argjson crit "$4" --argjson nh "$5" '{
      surface: $s, source: "build", label: "t", image_index_digest: "sha256:x", image_ref: $ref,
      platform: "linux/amd64", outcome: "scanned", enforcement: "policy",
      critical: {findings: ($crit | length), unique_ids: ($crit | map(.id) | unique | length)},
      high: {findings: $nh, unique_ids: $nh}}' > "$1/summary.json"
}
mkfail() { # <dir> <surface> <ref>: fallo operativo (sin conteos)
  mkdir -p "$1"
  jq -n --arg s "$2" --arg ref "$3" '{surface: $s, source: "build", label: "t", image_index_digest: "sha256:x",
    image_ref: $ref, platform: "linux/amd64", outcome: "operational_failure", enforcement: "policy",
    critical: null, high: null}' > "$1/summary.json"
}
crit() { jq -cn --arg id "$1" --arg p "$2" --arg l "${3:-${LAYER}}" '[{id: $id, pkg: $p, version: "v1.24.6", layer: $l}]'; }
input() { # <surface> <ref> <dir> [<surface> <ref> <dir> ...]
  local out='[]'
  while [ $# -gt 0 ]; do
    out=$(jq -c --arg s "$1" --arg r "$2" --arg d "$3" '. + [{surface: $s, image_ref: $r, dir: $d}]' <<< "${out}")
    shift 3
  done
  echo "${out}"
}

# Aprobación simulada para la matriz (la verificación real está probada arriba y abajo)
cat > "${TMP}/approve-stub" << 'EOF'
#!/usr/bin/env bash
case " ${STUB_APPROVED:-} " in
  *" $(basename "$1" .json):$2 "*) jq -cn --arg a "$2" '{approver_login: $a, pr: 7, reviewed_commit: "c", merge_commit: ("a" * 40)}' ;;
  *) echo "Error: aprobación humana no verificada (simulada)" >&2; exit 1 ;;
esac
EOF
chmod +x "${TMP}/approve-stub"

DISP="${TMP}/dispositions"
mkdir -p "${DISP}"
policy() { # <POLICY_INPUT> [VAR=valor...] → código; resultado en ${TMP}/result.json
  (cd "${G}" && env -i PATH="${TMP}/bin:${PATH}" FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" \
    POLICY_NOW="${NOW}" POLICY_INPUT="$1" RESULT_FILE="${TMP}/result.json" GITHUB_REPOSITORY="${REPO}" \
    EVAL_COMMIT="${EVAL}" RISK_APPROVERS="${APPROVERS}" WORKFLOW_PATH=.github/workflows/production-hetzner.yml \
    APPROVAL_CHECK="${TMP}/approve-stub" DISPOSITIONS_DIR="${DISP}" BREAK_GLASS_DIR="${TMP}/bg" \
    "${@:2}" "${POLICY}") > "${TMP}/policy.out" 2> "${TMP}/policy.err"
}
# expect <descripción> <decisión> <código esperado> <filtro jq extra sobre result.json> <POLICY_INPUT> [VAR=valor...]
expect() {
  local rc=0
  rm -f "${TMP:?}/result.json"
  policy "$5" "${@:6}" || rc=$?
  if [ "${rc}" -eq "$3" ] && grep -qx "decision=$2" "${TMP}/policy.out" &&
     jq -e --arg d "$2" ".decision == \$d and ($4)" "${TMP}/result.json" > /dev/null 2>&1; then
    ok "política: $1"
  else
    ko "política: $1 (rc=${rc}, $(grep '^decision=' "${TMP}/policy.out" 2> /dev/null || echo sin decisión); $(jq -c '[.global_errors, [.surfaces[]? | {surface, verdict, reason_code, reasons}]]' "${TMP}/result.json" 2> /dev/null))"
  fi
}

S="${TMP}/scans"
mkscan "${S}/high" backend "${BACKEND}" '[]' 4
expect "solo HIGH → authorized, HIGH visible" authorized 0 \
  '.surfaces[0].verdict == "authorized" and .surfaces[0].evaluation == "fresh" and .surfaces[0].scan.high_findings == 4 and (.surfaces[0].scan.high | length) == 4' \
  "$(input backend "${BACKEND}" "${S}/high")"
check "política: el resumen del operador muestra los HIGH como informativos" grep -q 'HIGH: CVE-2026-10000' "${TMP}/policy.err"
mkscan "${S}/zero" traefik "docker.io/library/traefik@sha256:$(d64 5)" '[]' 0
expect "cero hallazgos → authorized, con la advertencia de cobertura" authorized 0 '.surfaces[0].scan.critical == []' \
  "$(input traefik "docker.io/library/traefik@sha256:$(d64 5)" "${S}/zero")"
check "política: cero hallazgos no se presenta como ausencia de vulnerabilidades" grep -q 'Cero hallazgos no prueba ausencia' "${TMP}/policy.err"
expect "sin imágenes aplicadas → authorized" authorized 0 '.surfaces == []' '[]'

mkscan "${S}/crit" postgres "${POSTGRES}" "$(crit CVE-2025-68121 stdlib)" 2
PG_INPUT="$(input postgres "${POSTGRES}" "${S}/crit")"
expect "CRITICAL sin disposición → BLOCK" blocked 1 \
  '.surfaces[0].verdict == "blocked" and .surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}"

put() { disposition "$@" > "${DISP}/$1.json"; }
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}"
expect "CRITICAL con disposición aprobada y vigente (digest exacto) → authorized" authorized 0 \
  '.surfaces[0].scan.critical[0].disposition.id == "VD-2026-001" and .surfaces[0].scan.critical[0].disposition.approver == "alice"' \
  "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
expect "disposición sin aprobación humana verificada → BLOCK" blocked 1 \
  '.surfaces[0].reason_code == "undisposed_critical" and (.surfaces[0].scan.critical[0].undisposed[0] | test("aprobación humana no verificada"))' \
  "${PG_INPUT}"
expect "aprobación verificada de otra persona que la nombrada en el registro → BLOCK" blocked 1 \
  '.surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:bob
mkscan "${S}/crit-other" postgres "ghcr.io/o/r/postgres@sha256:$(d64 6)" "$(crit CVE-2025-68121 stdlib)" 0
expect "disposición de otro digest (rollback o rebuild) → BLOCK" blocked 1 '.surfaces[0].reason_code == "undisposed_critical"' \
  "$(input postgres "ghcr.io/o/r/postgres@sha256:$(d64 6)" "${S}/crit-other")" STUB_APPROVED=VD-2026-001:alice
rm "${DISP}"/*.json
# Alcance por digest de capa (HR-03): autorizaría cualquier imagen que contenga esa capa,
# con otras capas arbitrarias. No se admite: el alcance es el manifiesto exacto.
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"layer_diff_id\": \"${LAYER}\"}"
expect "disposición por digest de capa (alcance más amplio que la identidad exacta) → BLOCK, esquema inválido" blocked 1 \
  '(.global_errors[0] | test("esquema inválido")) and .surfaces[0].reason_code == "undisposed_critical"' \
  "$(input postgres "ghcr.io/o/r/postgres@sha256:$(d64 6)" "${S}/crit-other")" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"], \"layer_diff_id\": \"${LAYER}\"}"
expect "disposición que mezcla digest exacto y capa → BLOCK, esquema inválido" blocked 1 '(.global_errors[0] | test("esquema inválido"))' \
  "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
rm "${DISP}"/*.json
put VD-2026-001 backend CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}"
expect "disposición de otra imagen lógica → BLOCK" blocked 1 '.surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-99999 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}"
expect "disposición de otro CVE → BLOCK" blocked 1 '.surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-68121 openssl "{\"image_refs\": [\"${POSTGRES}\"]}"
expect "disposición de otro paquete → BLOCK" blocked 1 '.surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}" 2026-08-01T00:00:00Z 2026-10-15T11:59:59Z
expect "disposición vencida → BLOCK" blocked 1 '(.surfaces[0].scan.critical[0].undisposed[0] | test("vencida"))' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}" 2026-10-16T00:00:00Z 2026-11-01T00:00:00Z
expect "disposición todavía no vigente → BLOCK" blocked 1 '(.surfaces[0].scan.critical[0].undisposed[0] | test("vigencia"))' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}" 2026-10-01T00:00:00Z 2026-12-30T00:00:01Z
expect "disposición de más de 90 días → BLOCK (registro inválido)" blocked 1 '(.global_errors[0] | test("vigencia inválida"))' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
put VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}" 2026-11-01T00:00:00Z 2026-10-01T00:00:00Z
expect "disposición con vigencia negativa → BLOCK" blocked 1 '(.global_errors | length) == 1' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
rm "${DISP}"/*.json
disposition VD-2026-001 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}" | jq '. + {approved: true, approved_by: "alice"}' > "${DISP}/VD-2026-001.json"
expect "disposición con aprobación autodeclarada (campos extra) → BLOCK, esquema inválido" blocked 1 '(.global_errors[0] | test("esquema inválido"))' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
disposition VD-2026-001 postgres CVE-2025-68121 stdlib '{"image_refs": ["ghcr.io/o/r/postgres:16-alpine"]}' > "${DISP}/VD-2026-001.json"
expect "disposición con alcance por tag mutable → BLOCK" blocked 1 '(.global_errors[0] | test("esquema inválido"))' "${PG_INPUT}" STUB_APPROVED=VD-2026-001:alice
disposition VD-2026-002 postgres CVE-2025-68121 stdlib "{\"image_refs\": [\"${POSTGRES}\"]}" > "${DISP}/VD-2026-001.json"
expect "disposición cuyo id no coincide con el archivo → BLOCK" blocked 1 '(.global_errors | length) == 1' "${PG_INPUT}" STUB_APPROVED=VD-2026-002:alice
echo '{' > "${DISP}/VD-2026-001.json"
expect "disposición malformada → BLOCK" blocked 1 '(.global_errors | length) == 1' "$(input backend "${BACKEND}" "${S}/high")"
rm "${DISP}"/*.json

mkfail "${S}/opfail" backend "${BACKEND}"
expect "fallo operativo del escáner → BLOCK (nunca cero hallazgos)" blocked 1 \
  '.surfaces[0].reason_code == "operational_failure" and .surfaces[0].scan == null' "$(input backend "${BACKEND}" "${S}/opfail")"
expect "evidencia ausente → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/nada")"
mkdir -p "${S}/no-trivy" && cp "${S}/high/summary.json" "${S}/no-trivy/"
expect "summary.json sin trivy.json → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/no-trivy")"
expect "evidencia de otra referencia → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND_OTHER}" "${S}/high")"
cp -r "${S}/high" "${S}/artifact-mismatch" && jq --arg r "${BACKEND_OTHER}" '.ArtifactName = $r' "${S}/high/trivy.json" > "${S}/artifact-mismatch/trivy.json"
expect "trivy.json de otra referencia que su summary.json → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/artifact-mismatch")"
cp -r "${S}/crit" "${S}/hidden" && jq '.critical.findings = 0' "${S}/crit/summary.json" > "${S}/hidden/summary.json"
expect "summary.json oculta un CRITICAL del reporte → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input postgres "${POSTGRES}" "${S}/hidden")"
mkscan "${S}/stale" backend "${BACKEND}" '[]' 0 2026-10-14T11:59:00Z 2026-10-14T06:00:00Z
expect "escaneo de más de 24 h → BLOCK (vencido)" blocked 1 '.surfaces[0].reason_code == "evidence_stale"' "$(input backend "${BACKEND}" "${S}/stale")"
mkscan "${S}/olddb" backend "${BACKEND}" '[]' 0 2026-10-15T10:00:00Z 2026-10-13T09:00:00Z
expect "DB de vulnerabilidades de más de 48 h al escanear → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/olddb")"
mkscan "${S}/future" backend "${BACKEND}" '[]' 0 2026-10-15T13:00:00Z
expect "escaneo con fecha futura → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/future")"
mkscan "${S}/arm" backend "${BACKEND}" '[]' 0 "" "" arm64
expect "reporte de otra plataforma → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/arm")"
cp -r "${S}/high" "${S}/weird" && jq '.outcome = "skipped"' "${S}/high/summary.json" > "${S}/weird/summary.json"
expect "resultado de escaneo desconocido → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend "${BACKEND}" "${S}/weird")"
expect "referencia por tag → BLOCK" blocked 1 '.surfaces[0].reason_code == "evidence_invalid"' "$(input backend ghcr.io/o/r/backend:latest "${S}/high")"
expect "una imagen bloqueada bloquea el run aunque otras estén autorizadas" blocked 1 \
  '[.surfaces[].verdict] == ["authorized", "blocked"]' "$(input backend "${BACKEND}" "${S}/high" postgres "${POSTGRES}" "${S}/crit")"
RC=0; policy 'no-json' || RC=$?
check "política: entrada inválida → código 2, sin decisión" bash -c "[ ${RC} -eq 2 ] && ! grep -q '^decision=' '${TMP}/policy.out'"
RC=0; policy "$(input backend "${BACKEND}" "${S}/high" backend "${BACKEND}" "${S}/high")" || RC=$?
check "política: imagen repetida en la entrada → código 2" test "${RC}" -eq 2

# Autoridad ensombrecida (HR-05): la evaluación autoritativa exige el mismo conjunto de
# aprobadores que vio la preliminar (sin environment)
policy "$(input backend "${BACKEND}" "${S}/high")" || true
FP=$(sed -n 's/^approvers_fingerprint=//p' "${TMP}/policy.out")
check "política: publica la huella del conjunto de aprobadores" bash -c "[[ '${FP}' =~ ^[0-9a-f]{64}$ ]]"
expect "mismo conjunto de aprobadores (otro orden, mayúsculas, separadores) → authorized" authorized 0 'true' \
  "$(input backend "${BACKEND}" "${S}/high")" "RISK_APPROVERS=BOB:1002 alice:1001" EXPECTED_APPROVERS_FINGERPRINT="${FP}"
expect "aprobadores distintos a los de la evaluación preliminar (variable ensombrecida) → BLOCK" blocked 1 \
  '(.global_errors[0] | test("ensombrece"))' "$(input backend "${BACKEND}" "${S}/high")" \
  "RISK_APPROVERS=alice:1001, bob:1002, mallory:666" EXPECTED_APPROVERS_FINGERPRINT="${FP}"
expect "aprobadores quitados en el environment (conjunto vacío) → BLOCK" blocked 1 '(.global_errors[0] | test("ensombrece"))' \
  "$(input backend "${BACKEND}" "${S}/high")" RISK_APPROVERS= EXPECTED_APPROVERS_FINGERPRINT="${FP}"

# ── 3. Política con la verificación real de procedencia (git + gh falso) ────
# El registro aprobado en la sección 1 (VD-2026-001) cubre ${POSTGRES}; el resto de la
# historia sigue igual. Sin stub: approval-provenance.sh contra el PR y sus revisiones.
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${F1_FEAT}")]"
git -C "${G}" rm -q "${F2}" && git -C "${G}" commit -qm "retirar VD-2026-002"
EVAL=$(git -C "${G}" rev-parse HEAD)
expect "de punta a punta: disposición aprobada en un PR fusionado → authorized" authorized 0 \
  '.surfaces[0].scan.critical[0].disposition.pr == 7 and .surfaces[0].scan.critical[0].disposition.approved_commit == "'"${F1_FEAT}"'"' \
  "${PG_INPUT}" APPROVAL_CHECK="${APPROVAL}" DISPOSITIONS_DIR=security/vulnerability-dispositions
expect "de punta a punta: sin vars.VULNERABILITY_RISK_APPROVERS ninguna disposición es efectiva" blocked 1 \
  '.surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}" APPROVAL_CHECK="${APPROVAL}" \
  DISPOSITIONS_DIR=security/vulnerability-dispositions RISK_APPROVERS=
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 dave 2001 APPROVED "${F1_FEAT}")]"
expect "de punta a punta: aprobada solo por alguien sin autoridad → BLOCK" blocked 1 \
  '.surfaces[0].reason_code == "undisposed_critical"' "${PG_INPUT}" APPROVAL_CHECK="${APPROVAL}" DISPOSITIONS_DIR=security/vulnerability-dispositions
pr_fixtures 7 "${F1_MERGE}" carol "${F1}" "${F1_FEAT}" "${F1_BLOB}" "[$(review 1 alice 1001 APPROVED "${F1_FEAT}")]"

# El árbol real no trae disposiciones ni autorizaciones de break-glass efectivas
check "repositorio: ninguna disposición CRITICAL real versionada (las propuestas no están aprobadas)" \
  bash -c "! ls '${ROOT}'/security/vulnerability-dispositions/*.json 2>/dev/null | grep -q ."
check "repositorio: ninguna autorización de break-glass versionada" \
  bash -c "! ls '${ROOT}'/security/break-glass/*.json 2>/dev/null | grep -q ."
check "workflow: la autoridad de aprobación solo viene de vars.VULNERABILITY_RISK_APPROVERS (sin valores por defecto)" \
  bash -c "[ \"\$(yq -r '.. | select(has(\"RISK_APPROVERS\")) | .RISK_APPROVERS' '${WORKFLOW}' | sort -u)\" = '\${{ vars.VULNERABILITY_RISK_APPROVERS }}' ] && ! grep -qE 'RISK_APPROVERS:-[^}]' '${APPROVAL}' '${POLICY}'"

# ── 4. Evidencia histórica: procedencia e integridad ────────────────────────
H="${TMP}/hist"
WF=.github/workflows/production-hetzner.yml
RUN_ID=4242 ART_ID=9001
mkscan "${H}/src" backend "${BACKEND}" '[]' 3 2026-10-01T08:00:00Z 2026-10-01T01:00:00Z
(cd "${H}/src" && zip -q -X ../good.zip summary.json trivy.json trivy-version.json)
GOOD_DIGEST="sha256:$(sha256sum "${H}/good.zip" | cut -d' ' -f1)"
hist_fixtures() { # estado bueno de referencia
  serve "GET repos/${REPO}" "{\"id\": ${REPO_ID}}"
  serve "GET repos/${REPO}/actions/artifacts/${ART_ID}" "$(jq -cn --arg d "${GOOD_DIGEST}" --arg sha "${INIT}" \
    --argjson id "${ART_ID}" --argjson run "${RUN_ID}" --argjson repo "${REPO_ID}" '{
      id: $id, name: "image-scan-selected-backend-\($run)", expired: false, digest: $d, created_at: "2026-10-01T08:01:00Z",
      workflow_run: {id: $run, repository_id: $repo, head_repository_id: $repo, head_branch: "main", head_sha: $sha}}')"
  serve "GET repos/${REPO}/actions/runs/${RUN_ID}" "$(jq -cn --arg sha "${INIT}" --arg p "${WF}" \
    --argjson run "${RUN_ID}" --argjson repo "${REPO_ID}" '{
      id: $run, workflow_id: 77, path: $p, event: "push", head_branch: "main", head_sha: $sha,
      repository: {id: $repo}, head_repository: {id: $repo}, run_attempt: 1, html_url: "https://example.invalid/run"}')"
  serve "GET repos/${REPO}/actions/workflows/production-hetzner.yml" '{"id": 77}'
  serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/good.zip"
}
hist() { # [VAR=valor...] → corre historical-scan-evidence.sh con los argumentos de HARGS
  rm -rf "${H:?}/out"
  (cd "${G}" && env -i PATH="${TMP}/bin:${PATH}" FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" \
    GITHUB_REPOSITORY="${REPO}" EVAL_COMMIT="${EVAL}" WORKFLOW_PATH="${WF}" "$@" \
    "${HISTORICAL}" "${HARGS[@]}" "${H}/out")
}
HARGS=(backend "${BACKEND}" "${RUN_ID}" "${ART_ID}" "${GOOD_DIGEST}")
expect_hist_fail() { # <descripción> [VAR=valor...]
  local out rc=0
  out=$(hist "${@:2}" 2> "${H}/err") || rc=$?
  if [ "${rc}" -ne 0 ] && [ -z "${out}" ]; then ok "evidencia histórica rechazada: $1"; else ko "evidencia histórica rechazada: $1 (rc=${rc}: ${out})"; fi
  hist_fixtures
  HARGS=(backend "${BACKEND}" "${RUN_ID}" "${ART_ID}" "${GOOD_DIGEST}")
}
hist_fixtures
if OUT=$(hist 2> /dev/null) && jq -e --arg d "${GOOD_DIGEST}" --arg sha "${INIT}" \
     '.run_id == 4242 and .run_commit == $sha and .artifact_digest == $d and .artifact_name == "image-scan-selected-backend-4242"' <<< "${OUT}" > /dev/null &&
   cmp -s "${H}/out/trivy.json" "${H}/src/trivy.json"; then
  ok "evidencia histórica verificada: run de main del workflow, digest registrado por GitHub, contenido íntegro"
else
  ko "evidencia histórica verificada (${OUT:-sin salida}; $(cat "${H}/err" 2> /dev/null))"
fi
HARGS=(backend "${BACKEND}" "${RUN_ID}" "${ART_ID}" "sha256:$(d64 9)")
expect_hist_fail "la autorización declara otro digest de artifact"
cp "${H}/good.zip" "${H}/tampered.zip" && printf 'x' >> "${H}/tampered.zip"
serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/tampered.zip"
expect_hist_fail "contenido descargado alterado (no coincide con el digest registrado)"
# Un artifact fabricado que trae su propio checksum: GitHub registró otro digest
mkdir -p "${H}/fab" && cp "${H}/src/"*.json "${H}/fab/" && sha256sum "${H}/fab/trivy.json" > "${H}/fab/trivy.json.sha256"
(cd "${H}/fab" && zip -q -X ../fab.zip summary.json trivy.json trivy-version.json trivy.json.sha256)
FAB_DIGEST="sha256:$(sha256sum "${H}/fab.zip" | cut -d' ' -f1)"
serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/fab.zip"
HARGS=(backend "${BACKEND}" "${RUN_ID}" "${ART_ID}" "${FAB_DIGEST}")
expect_hist_fail "artifact fabricado con checksum propio (GitHub registró otro digest)"
serve "GET repos/${REPO}/actions/artifacts/${ART_ID}" "$(jq -c --arg d "${FAB_DIGEST}" '.digest = $d' "${FAKE_GH_DIR}/$(printf '%s' "GET repos/${REPO}/actions/artifacts/${ART_ID}" | sha256sum | cut -c1-32)")"
serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/fab.zip"
HARGS=(backend "${BACKEND}" "${RUN_ID}" "${ART_ID}" "${FAB_DIGEST}")
expect_hist_fail "artifact con archivos adicionales"
art_patch() { # <filtro jq> → modifica el artifact registrado
  local key="GET repos/${REPO}/actions/artifacts/${ART_ID}"
  serve "${key}" "$(jq -c "$1" "${FAKE_GH_DIR}/$(printf '%s' "${key}" | sha256sum | cut -c1-32)")"
}
run_patch() {
  local key="GET repos/${REPO}/actions/runs/${RUN_ID}"
  serve "${key}" "$(jq -c "$1" "${FAKE_GH_DIR}/$(printf '%s' "${key}" | sha256sum | cut -c1-32)")"
}
art_patch '.expired = true';                          expect_hist_fail "artifact vencido"
art_patch '.workflow_run.head_branch = "feature"';    expect_hist_fail "artifact de un run de otra rama"
art_patch '.workflow_run.head_repository_id = 666';   expect_hist_fail "artifact de un run de un fork"
art_patch '.workflow_run.id = 1';                     expect_hist_fail "artifact de otro run"
art_patch '.name = "image-scan-selected-postgres-4242"'; expect_hist_fail "artifact de otra imagen"
art_patch 'del(.digest)';                             expect_hist_fail "artifact sin digest registrado"
run_patch '.path = ".github/workflows/otro.yml"';    expect_hist_fail "run de otro workflow (ruta)"
run_patch '.workflow_id = 78';                        expect_hist_fail "run de otro workflow (id)"
run_patch '.event = "pull_request"';                  expect_hist_fail "run de un pull_request"
run_patch '.head_branch = "feature"';                 expect_hist_fail "run de otra rama"
run_patch '.head_sha = "'"$(printf 'e%.0s' {1..40})"'"'; expect_hist_fail "run cuyo commit no está en la historia de main"
unserve "GET repos/${REPO}/actions/artifacts/${ART_ID}"; expect_hist_fail "artifact inexistente"
HARGS=(backend "${BACKEND_OTHER}" "${RUN_ID}" "${ART_ID}" "${GOOD_DIGEST}")
expect_hist_fail "evidencia de otro digest de imagen"
mkfail "${H}/opsrc" backend "${BACKEND}" && cp "${H}/src/trivy.json" "${H}/src/trivy-version.json" "${H}/opsrc/"
(cd "${H}/opsrc" && zip -q -X ../op.zip summary.json trivy.json trivy-version.json)
OP_DIGEST="sha256:$(sha256sum "${H}/op.zip" | cut -d' ' -f1)"
art_patch ".digest = \"${OP_DIGEST}\""
serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/op.zip"
HARGS=(backend "${BACKEND}" "${RUN_ID}" "${ART_ID}" "${OP_DIGEST}")
expect_hist_fail "evidencia histórica de un fallo operativo (no es un escaneo)"
hist_fixtures

# ── 4b. Evidencia del run actual: origen y digest (reemplaza download-artifact) ──
CUR="${TMP}/cur"
CUR_RUN=4400
mkdir -p "${CUR}"
cp "${H}/good.zip" "${CUR}/backend.zip"
mkscan "${CUR}/pg" postgres "${POSTGRES}" '[]' 0
(cd "${CUR}/pg" && zip -q -X ../postgres.zip summary.json trivy.json trivy-version.json)
cur_artifact() { # <id> <imagen> <zip>
  jq -cn --argjson id "$1" --arg n "image-scan-selected-$2-${CUR_RUN}" --arg d "sha256:$(sha256sum "$3" | cut -d' ' -f1)" \
    --argjson run "${CUR_RUN}" --argjson repo "${REPO_ID}" \
    '{id: $id, name: $n, expired: false, digest: $d, workflow_run: {id: $run, repository_id: $repo, head_repository_id: $repo}}'
}
cur_fixtures() { # [filtro jq sobre la lista]
  serve "GET repos/${REPO}/actions/runs/${CUR_RUN}/artifacts?per_page=100" \
    "$(jq -cn --argjson a "$(cur_artifact 7001 backend "${CUR}/backend.zip")" --argjson b "$(cur_artifact 7002 postgres "${CUR}/postgres.zip")" \
      '{total_count: 3, artifacts: [$a, $b, {id: 7003, name: "otro-artifact", expired: false, digest: null, workflow_run: {id: 4400}}]}' | jq -c "${1:-.}")"
  serve_file "GET repos/${REPO}/actions/artifacts/7001/zip" "${CUR}/backend.zip"
  serve_file "GET repos/${REPO}/actions/artifacts/7002/zip" "${CUR}/postgres.zip"
}
cur() { # [VAR=valor...]
  rm -rf "${CUR:?}/out"
  env -i PATH="${TMP}/bin:${PATH}" FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" \
    GITHUB_REPOSITORY="${REPO}" GITHUB_RUN_ID="${CUR_RUN}" "$@" "${SCRIPTS}/current-scan-evidence.sh" "${CUR}/out"
}
expect_cur_fail() { # <descripción> [VAR=valor...]
  local rc=0
  cur "${@:2}" > /dev/null 2> "${CUR}/err" || rc=$?
  if [ "${rc}" -ne 0 ]; then ok "evidencia del run rechazada: $1"; else ko "evidencia del run rechazada: $1"; fi
  cur_fixtures
}
cur_fixtures
if OUT=$(cur 2> /dev/null) && grep -qx 'artifacts=2' <<< "${OUT}" &&
   cmp -s "${CUR}/out/image-scan-selected-backend-${CUR_RUN}/trivy.json" "${H}/src/trivy.json" &&
   [ -f "${CUR}/out/image-scan-selected-postgres-${CUR_RUN}/summary.json" ]; then
  ok "evidencia del run: artifacts de este run con el digest registrado por GitHub, en el formato de scan-evidence"
else
  ko "evidencia del run: artifacts de este run con el digest registrado por GitHub (${OUT:-sin salida})"
fi
serve_file "GET repos/${REPO}/actions/artifacts/7001/zip" "${H}/tampered.zip"
expect_cur_fail "contenido descargado alterado (download-artifact solo advertía)"
cur_fixtures '.artifacts[0].digest = null';                 expect_cur_fail "artifact sin digest registrado"
cur_fixtures ".artifacts[0].digest = \"sha256:$(d64 9)\"";  expect_cur_fail "digest registrado distinto del contenido"
cur_fixtures '.artifacts[0].workflow_run.id = 1';           expect_cur_fail "artifact atribuido a otro run"
cur_fixtures '.artifacts[0].workflow_run.head_repository_id = 666'; expect_cur_fail "artifact de un fork"
cur_fixtures '.artifacts[0].expired = true';                expect_cur_fail "artifact vencido"
cur_fixtures '.artifacts += [.artifacts[0] | .id = 7009]';  expect_cur_fail "dos artifacts con el mismo nombre (otro intento): ambiguo"
serve_file "GET repos/${REPO}/actions/artifacts/7001/zip" "${H}/fab.zip"
cur_fixtures ".artifacts[0].digest = \"${FAB_DIGEST}\""
serve_file "GET repos/${REPO}/actions/artifacts/7001/zip" "${H}/fab.zip"
expect_cur_fail "artifact con archivos adicionales"
unserve "GET repos/${REPO}/actions/runs/${CUR_RUN}/artifacts?per_page=100"; expect_cur_fail "artifacts del run ilegibles"
cur_fixtures '.artifacts = [.artifacts[1]]'
check "evidencia del run: un artifact ausente no se inventa (scan-evidence.sh lo exige después)" \
  bash -c "$(declare -f cur); TMP='${TMP}' CUR='${CUR}' FAKE_GH_DIR='${FAKE_GH_DIR}' FAKE_GH_LOG='${FAKE_GH_LOG}' REPO='${REPO}' CUR_RUN='${CUR_RUN}' SCRIPTS='${SCRIPTS}'; cur 2>/dev/null | grep -qx 'artifacts=1' && [ ! -e '${CUR}/out/image-scan-selected-backend-${CUR_RUN}' ]"
hist_fixtures

# ── 5. Break-glass en la política ───────────────────────────────────────────
mkdir -p "${TMP}/bg"
bg_auth() { # <id> [filtro jq]
  jq -n --arg id "$1" --arg ref "${BACKEND}" --arg d "${GOOD_DIGEST}" '{
      schema: "piedrazul.break-glass-authorization/v2", id: $id,
      justification: "Mirror de la DB de Trivy caído; rollback urgente del backend.",
      approver: "bob", effective: "2026-10-15T08:00:00Z", expires: "2026-10-16T08:00:00Z",
      operation: {run_host_config: false, run_app_deploy: true, run_build: false, run_build_postgres: false,
                  image_tag: "565928f763", keycloak_recovery: false},
      images: [{surface: "backend", image_ref: $ref, known_critical: [],
        coverage_limitations: "Solo paquetes del SO y jars detectados por Trivy 0.74.0.",
        evidence: {run_id: 4242, artifact_id: 9001, artifact_digest: $d, scanned_at: "2026-10-01T08:00:00Z"}}]}' |
    jq "${2:-.}" > "${TMP}/bg/$1.json"
}
BG_ENV=(EVENT_NAME=workflow_dispatch GITHUB_REF=refs/heads/main ACTOR=carol TRIGGERING_ACTOR=carol
  BREAK_GLASS_ID=BG-2026-001 "BREAK_GLASS_JUSTIFICATION=Trivy DB no disponible; rollback por incidente 42."
  STUB_APPROVED=BG-2026-001:bob
  OP_RUN_TERRAFORM_APPLY=false OP_RUN_HOST_CONFIG=false OP_RUN_APP_DEPLOY=true OP_RUN_BUILD=false
  OP_RUN_BUILD_POSTGRES=false OP_IMAGE_TAG=565928f763 OP_KEYCLOAK_RECOVERY=false)
OP_INPUT="$(input backend "${BACKEND}" "${S}/opfail")"
bg_auth BG-2026-001
expect "break-glass válido con evidencia histórica verificada → break_glass (no authorized)" break_glass 0 \
  '.surfaces[0].verdict == "break_glass" and .surfaces[0].evaluation == "historical" and .surfaces[0].historical_evidence.artifact_digest == "'"${GOOD_DIGEST}"'" and .surfaces[0].scan.age_hours == 340 and .break_glass.approver == "bob" and .break_glass.operation.image_tag == "565928f763"' \
  "${OP_INPUT}" "${BG_ENV[@]}"
check "break-glass: el resumen dice que no hubo escaneo fresco" grep -q 'NO tuvieron un escaneo fresco' "${TMP}/policy.err"
expect "break-glass pedido por un push → BLOCK" blocked 1 '(.global_errors[0] | test("workflow_dispatch"))' "${OP_INPUT}" "${BG_ENV[@]}" EVENT_NAME=push
expect "break-glass desde otra rama → BLOCK" blocked 1 '(.global_errors | length) == 1' "${OP_INPUT}" "${BG_ENV[@]}" GITHUB_REF=refs/heads/feature
expect "break-glass sin justificación de la operación → BLOCK" blocked 1 '(.global_errors[0] | test("justificación"))' "${OP_INPUT}" "${BG_ENV[@]}" "BREAK_GLASS_JUSTIFICATION=   "
expect "break-glass sin autorización versionada → BLOCK" blocked 1 '(.global_errors[0] | test("no existe"))' "${OP_INPUT}" "${BG_ENV[@]}" BREAK_GLASS_ID=BG-2026-002
expect "break-glass sin aprobación humana verificada → BLOCK" blocked 1 '(.global_errors[0] | test("aprobación humana no verificada"))' "${OP_INPUT}" "${BG_ENV[@]}" STUB_APPROVED=
expect "break-glass aprobado por quien dispara el run → BLOCK" blocked 1 '(.global_errors[0] | test("otra persona"))' "${OP_INPUT}" "${BG_ENV[@]}" TRIGGERING_ACTOR=bob
bg_auth BG-2026-001 '.expires = "2026-10-15T11:00:00Z"'
expect "break-glass vencido → BLOCK" blocked 1 '(.global_errors[0] | test("fuera de vigencia"))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 '.expires = "2026-10-18T08:00:01Z"'
expect "break-glass de más de 72 h → BLOCK" blocked 1 '(.global_errors[0] | test("vigencia inválida"))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 '. + {max_operations: 3}'
expect "break-glass con varias operaciones declaradas (esquema anterior) → BLOCK" blocked 1 '(.global_errors[0] | test("esquema"))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 '.schema = "piedrazul.break-glass-authorization/v1"'
expect "break-glass con el esquema v1 (sin operación) → BLOCK" blocked 1 '(.global_errors[0] | test("esquema"))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001
expect "break-glass combinado con Terraform Apply → BLOCK (el Apply mutaría antes de autorizar y registrar)" blocked 1 \
  '(.global_errors[0] | test("Terraform Apply"))' "${OP_INPUT}" "${BG_ENV[@]}" OP_RUN_TERRAFORM_APPLY=true
expect "break-glass para otra operación (otro tag de rollback) → BLOCK" blocked 1 '(.global_errors[0] | test("otra operación"))' \
  "${OP_INPUT}" "${BG_ENV[@]}" OP_IMAGE_TAG=38993db195
expect "break-glass para otra operación (agrega configuración del servidor) → BLOCK" blocked 1 '(.global_errors[0] | test("otra operación"))' \
  "${OP_INPUT}" "${BG_ENV[@]}" OP_RUN_HOST_CONFIG=true
expect "break-glass para otra operación (recuperación de Keycloak) → BLOCK" blocked 1 '(.global_errors[0] | test("otra operación"))' \
  "${OP_INPUT}" "${BG_ENV[@]}" OP_KEYCLOAK_RECOVERY=true
expect "break-glass sin la intención del run → BLOCK" blocked 1 '(.global_errors[0] | test("intención del run"))' \
  "${OP_INPUT}" "${BG_ENV[@]}" OP_RUN_BUILD=
bg_auth BG-2026-001 '.operation.run_build = "false"'
expect "break-glass con operación mal tipada → BLOCK" blocked 1 '(.global_errors[0] | test("esquema"))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 '. + {approved: true}'
expect "break-glass con aprobación autodeclarada → BLOCK" blocked 1 '(.global_errors[0] | test("esquema"))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 ".images[0].image_ref = \"${BACKEND_OTHER}\""
expect "break-glass que no autoriza esa referencia exacta → BLOCK" blocked 1 '(.surfaces[0].reasons | any(test("no autoriza")))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 '.images[0].evidence.scanned_at = "2026-10-14T08:00:00Z"'
expect "break-glass cuya fecha declarada no es la de la evidencia → BLOCK" blocked 1 '.surfaces[0].reason_code == "operational_failure"' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 ".images[0].evidence.artifact_digest = \"sha256:$(d64 9)\""
expect "break-glass con evidencia histórica no verificable → BLOCK" blocked 1 '(.surfaces[0].reasons | any(test("no verificable")))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001
serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/tampered.zip"
expect "break-glass con evidencia alterada → BLOCK" blocked 1 '(.surfaces[0].reasons | any(test("no verificable")))' "${OP_INPUT}" "${BG_ENV[@]}"
hist_fixtures
# Evidencia histórica con un CRITICAL conocido
mkscan "${H}/csrc" backend "${BACKEND}" "$(crit CVE-2026-41293 org.apache.tomcat.embed:tomcat-embed-core)" 0 2026-10-01T08:00:00Z 2026-10-01T01:00:00Z
(cd "${H}/csrc" && zip -q -X ../crit.zip summary.json trivy.json trivy-version.json)
CRIT_DIGEST="sha256:$(sha256sum "${H}/crit.zip" | cut -d' ' -f1)"
art_patch ".digest = \"${CRIT_DIGEST}\""
serve_file "GET repos/${REPO}/actions/artifacts/${ART_ID}/zip" "${H}/crit.zip"
bg_auth BG-2026-001 ".images[0].evidence.artifact_digest = \"${CRIT_DIGEST}\""
expect "break-glass que oculta un CRITICAL conocido de la evidencia → BLOCK" blocked 1 '(.surfaces[0].reasons | any(test("known_critical")))' "${OP_INPUT}" "${BG_ENV[@]}"
bg_auth BG-2026-001 ".images[0].evidence.artifact_digest = \"${CRIT_DIGEST}\" | .images[0].known_critical = [\"CVE-2026-41293\"]"
expect "break-glass no exime un CRITICAL conocido sin disposición → BLOCK" blocked 1 '.surfaces[0].reason_code == "undisposed_critical"' "${OP_INPUT}" "${BG_ENV[@]}"
put VD-2026-003 backend CVE-2026-41293 org.apache.tomcat.embed:tomcat-embed-core "{\"image_refs\": [\"${BACKEND}\"]}"
expect "break-glass con el CRITICAL conocido dispuesto por separado → break_glass" break_glass 0 \
  '.surfaces[0].scan.critical[0].disposition.id == "VD-2026-003"' "${OP_INPUT}" "${BG_ENV[@]}" "STUB_APPROVED=BG-2026-001:bob VD-2026-003:alice"
rm "${DISP}"/*.json
hist_fixtures
bg_auth BG-2026-001
expect "break-glass pedido pero el escaneo fresco funcionó → authorized por la vía normal" authorized 0 \
  '.surfaces[0].evaluation == "fresh" and .surfaces[0].historical_evidence == null' "$(input backend "${BACKEND}" "${S}/high")" "${BG_ENV[@]}"
mkscan "${S}/bcrit" backend "${BACKEND}" "$(crit CVE-2026-41293 org.apache.tomcat.embed:tomcat-embed-core)" 0
expect "break-glass no exime un CRITICAL de un escaneo fresco → BLOCK" blocked 1 \
  '.surfaces[0].reason_code == "undisposed_critical" and .surfaces[0].evaluation == "fresh"' "$(input backend "${BACKEND}" "${S}/bcrit")" "${BG_ENV[@]}"
expect "fallo operativo sin break-glass sigue bloqueando aunque exista una autorización versionada" blocked 1 \
  '.surfaces[0].reason_code == "operational_failure" and .break_glass_requested == false' "${OP_INPUT}"

# ── 6. Registro de break-glass ──────────────────────────────────────────────
policy "${OP_INPUT}" "${BG_ENV[@]}" || true
cp "${TMP}/result.json" "${TMP}/bg-result.json"
AC=$(printf 'a%.0s' {1..40})   # merge commit de main que integró la autorización
CTX="security/break-glass-consumption/BG-2026-001"
RUN_URL_4300="https://github.com/${REPO}/actions/runs/4300/attempts/1"
APPROVALS_KEY="GET repos/${REPO}/actions/runs/4300/approvals"
STATUSES_KEY="GET repos/${REPO}/commits/${AC}/statuses?per_page=100"
status_json() { # <id> <contexto> <target_url> [creador]
  jq -cn --argjson id "$1" --arg c "$2" --arg u "$3" --arg who "${4:-github-actions[bot]}" \
    '{id: $id, state: "success", context: $c, target_url: $u, creator: {login: $who}, created_at: "2026-10-15T09:00:00Z"}'
}
record_fixtures() {
  serve "${APPROVALS_KEY}" '[{"state": "approved", "user": {"login": "erin", "type": "User"}, "environments": [{"name": "production-hetzner"}]}]'
  serve "${STATUSES_KEY}" '[]'
  serve_then "${STATUSES_KEY}" "[$(status_json 501 "${CTX}" "${RUN_URL_4300}")]"
  serve "POST repos/${REPO}/statuses/${AC}" "$(status_json 501 "${CTX}" "${RUN_URL_4300}")"
  serve "POST repos/${REPO}/issues" '{"number": 12, "html_url": "https://example.invalid/issues/12", "labels": [{"name": "security-break-glass"}]}'
}
rec() { # [VAR=valor...]
  env -i PATH="${TMP}/bin:${PATH}" FAKE_GH_DIR="${FAKE_GH_DIR}" FAKE_GH_LOG="${FAKE_GH_LOG}" \
    POLICY_RESULT="${TMP}/bg-result.json" ENVIRONMENT_NAME=production-hetzner GITHUB_REPOSITORY="${REPO}" \
    GITHUB_RUN_ID=4300 GITHUB_RUN_ATTEMPT=1 GITHUB_SHA="${EVAL}" GITHUB_ACTOR=carol GITHUB_TRIGGERING_ACTOR=carol \
    "$@" "${RECORD}"
}
expect_rec_fail() { # <descripción> [VAR=valor...]
  local rc=0
  : > "${FAKE_GH_LOG}"
  rec "${@:2}" > /dev/null 2>&1 || rc=$?
  if [ "${rc}" -ne 0 ]; then ok "registro de break-glass falla cerrado: $1"; else ko "registro de break-glass falla cerrado: $1"; fi
  cp "${FAKE_GH_LOG}" "${TMP}/last-rec.log"
  record_fixtures
}
no_issue_created() { ! grep -q "^POST repos/${REPO}/issues" "${TMP}/last-rec.log"; }

check "política: el break-glass verificado lleva el merge commit de su autorización (ancla del consumo)" \
  jq -e --arg ac "${AC}" '.break_glass.authorization_commit == $ac' "${TMP}/bg-result.json"
record_fixtures
: > "${FAKE_GH_DIR}/last-input.json"
: > "${FAKE_GH_LOG}"
if OUT=$(rec 2> /dev/null) && grep -qx 'issue_number=12' <<< "${OUT}" && grep -qx 'consumption_status=501' <<< "${OUT}" &&
   jq -e --arg ref "${BACKEND}" --arg ctx "${CTX}" '.title == "[break-glass][BG-2026-001] run 4300/1"
       and .labels == ["security-break-glass"]
       and (.body | test("SIN escaneo fresco") and test($ref) and test("histórica: run 4242") and test("incidente 42")
            and test("erin") and test("carol") and test("PR #7") and test("una sola operación") and test("565928f763")
            and test($ctx))' "${FAKE_GH_DIR}/last-input.json" > /dev/null; then
  ok "registro de break-glass: consumo inmutable + issue con actor, run, digests, evidencia, CVE, justificación y ausencia de escaneo fresco"
else
  ko "registro de break-glass: consumo inmutable + issue (${OUT:-sin salida})"
fi
check "registro de break-glass: comprueba el consumo, lo registra y lo relee ANTES de crear el issue" \
  bash -c "grep -E 'statuses|POST repos/${REPO}/issues' '${FAKE_GH_LOG}' | paste -sd'|' | grep -qxF 'GET repos/${REPO}/commits/${AC}/statuses?per_page=100|POST repos/${REPO}/statuses/${AC}|GET repos/${REPO}/commits/${AC}/statuses?per_page=100|POST repos/${REPO}/issues'"
check "registro de break-glass: el consumo no depende de issues (no consulta título, etiquetas ni autor)" \
  bash -c "! grep -q '^GET repos/${REPO}/issues' '${FAKE_GH_LOG}'"

jq '.decision = "authorized"' "${TMP}/bg-result.json" > "${TMP}/authorized-result.json"
expect_rec_fail "la política no decidió break_glass" POLICY_RESULT="${TMP}/authorized-result.json"
jq 'del(.break_glass.authorization_commit)' "${TMP}/bg-result.json" > "${TMP}/no-anchor-result.json"
expect_rec_fail "autorización sin merge commit verificado (sin ancla de consumo)" POLICY_RESULT="${TMP}/no-anchor-result.json"
serve "${APPROVALS_KEY}" '[]';                         expect_rec_fail "sin aprobación del environment en este run"
serve "${APPROVALS_KEY}" '[{"state": "rejected", "user": {"login": "erin", "type": "User"}, "environments": [{"name": "production-hetzner"}]}]'
expect_rec_fail "aprobación del environment rechazada"
serve "${APPROVALS_KEY}" '[{"state": "approved", "user": {"login": "erin", "type": "User"}, "environments": [{"name": "otro"}]}]'
expect_rec_fail "aprobación de otro environment"
unserve "${APPROVALS_KEY}";                            expect_rec_fail "aprobaciones ilegibles"

# Uso previo (HR-06): el consumo vive en un commit status, no en metadatos editables del issue.
# El issue de ese uso puede haber perdido la etiqueta, cambiado de título, transferirse o
# borrarse: no se consulta, y el status sigue ahí.
PRIOR_STATUS="[$(status_json 400 "${CTX}" "https://github.com/${REPO}/actions/runs/4299/attempts/1")]"
serve "${STATUSES_KEY}" "${PRIOR_STATUS}"
expect_rec_fail "autorización ya consumida por otro run, aunque su issue perdió la etiqueta security-break-glass"
serve "${STATUSES_KEY}" "${PRIOR_STATUS}"
expect_rec_fail "autorización ya consumida, aunque el título de su issue se cambió (ya no tiene el id)"
serve "${STATUSES_KEY}" "${PRIOR_STATUS}"
expect_rec_fail "autorización ya consumida, aunque su issue se transfirió o borró (no aparece en el repositorio)"
check "registro de break-glass: con un consumo previo no registra otro ni crea issue" \
  bash -c "! grep -q -e '^POST repos/${REPO}/statuses' -e '^POST repos/${REPO}/issues' '${TMP}/last-rec.log'"
serve "${STATUSES_KEY}" "[$(status_json 400 "${CTX}" "https://github.com/${REPO}/actions/runs/4300/attempts/1")]"
expect_rec_fail "autorización consumida por un intento anterior de este run (re-run)"
serve "${STATUSES_KEY}" "[$(status_json 400 "${CTX}" "https://example.invalid/x" octocat)]"
expect_rec_fail "status de consumo creado por otra persona (cuenta como consumo: falla cerrado)"
serve "${STATUSES_KEY}" "[$(status_json 400 "${CTX}" "https://example.invalid/x"),$(status_json 399 "${CTX}" "https://example.invalid/y")]"
expect_rec_fail "varios consumos previos"
unserve "${STATUSES_KEY}";                             expect_rec_fail "registro de consumo ilegible (no prueba que no se usó)"
check "registro de break-glass: con el registro de consumo ilegible no crea issue" no_issue_created
# HR-07: "cero consumos" solo con una enumeración completa y válida
expect_status_list_fail() { # <descripción> <respuesta cruda de la lista previa>
  serve "${STATUSES_KEY}" "$2"
  expect_rec_fail "$1"
  if no_issue_created && ! grep -q "^POST repos/${REPO}/statuses" "${TMP}/last-rec.log"; then
    ok "registro de break-glass: $1 → no registra consumo ni crea issue"
  else
    ko "registro de break-glass: $1 → no registra consumo ni crea issue"
  fi
}
expect_status_list_fail "lista de statuses vacía (sin salida)" ''
expect_status_list_fail "lista de statuses solo con espacios" $'  \n'
expect_status_list_fail "lista de statuses con JSON malformado" '[{"id": 1, "context":'
expect_status_list_fail "lista de statuses con un objeto en vez de arreglo (p. ej. error de la API)" '{"message": "Not Found"}'
expect_status_list_fail "lista de statuses con null" 'null'
expect_status_list_fail "lista de statuses con elementos que no son statuses" '["x", 1]'
expect_status_list_fail "status sin contexto" '[{"id": 1, "state": "success"}]'
expect_status_list_fail "status sin id" "[{\"context\": \"${CTX}\"}]"
expect_status_list_fail "segunda página malformada tras una válida" '[] [{"id": 1'
expect_status_list_fail "segunda página que no es arreglo" '[] {"message": "error"}'
serve_then "${STATUSES_KEY}" ''
expect_rec_fail "relectura del consumo vacía (sin salida)"
check "registro de break-glass: con la relectura vacía no crea issue" no_issue_created
serve_then "${STATUSES_KEY}" '{"message": "error"}'
expect_rec_fail "relectura del consumo con otra estructura"
for VALID in '[]' $'[]\n' "[] [$(status_json 300 security/break-glass-consumption/BG-2026-002 https://example.invalid/z)]"; do
  serve "${STATUSES_KEY}" "${VALID}"
  if OUT=$(rec 2> /dev/null) && grep -qx 'consumption_status=501' <<< "${OUT}"; then
    ok "registro de break-glass: lista válida sin consumos de esta autorización ($(tr '\n' ' ' <<< "${VALID}" | cut -c1-40)) → registra"
  else
    ko "registro de break-glass: lista válida sin consumos de esta autorización ($(tr '\n' ' ' <<< "${VALID}" | cut -c1-40)) (${OUT:-sin salida})"
  fi
  record_fixtures
done
unserve "POST repos/${REPO}/statuses/${AC}";           expect_rec_fail "no se pudo registrar el consumo"
check "registro de break-glass: sin consumo registrado no crea issue" no_issue_created
serve "POST repos/${REPO}/statuses/${AC}" "$(status_json 501 security/break-glass-consumption/BG-2026-002 "${RUN_URL_4300}")"
expect_rec_fail "el consumo registrado no es el pedido (otro contexto)"
serve_then "${STATUSES_KEY}" "[$(status_json 501 "${CTX}" "${RUN_URL_4300}"),$(status_json 502 "${CTX}" "https://example.invalid/otro")]"
expect_rec_fail "consumo ambiguo al releer (otro registro simultáneo)"
check "registro de break-glass: con un consumo ambiguo no crea issue" no_issue_created
serve_then "${STATUSES_KEY}" "[]"
expect_rec_fail "el consumo registrado no aparece al releer"
unserve "POST repos/${REPO}/issues";                   expect_rec_fail "no se pudo crear el issue (el consumo ya quedó: un re-run también bloquea)"
serve "POST repos/${REPO}/issues" '{"number": 13, "html_url": "x", "labels": []}'
expect_rec_fail "issue creado sin la etiqueta security-break-glass"
# El consumo de OTRA autorización sobre el mismo commit no bloquea esta
serve "${STATUSES_KEY}" "[$(status_json 300 security/break-glass-consumption/BG-2026-002 https://example.invalid/z)]"
serve_then "${STATUSES_KEY}" "[$(status_json 300 security/break-glass-consumption/BG-2026-002 https://example.invalid/z),$(status_json 501 "${CTX}" "${RUN_URL_4300}")]"
if OUT=$(rec 2> /dev/null) && grep -qx 'issue_number=12' <<< "${OUT}"; then
  ok "registro de break-glass: el consumo de otra autorización no bloquea esta"
else
  ko "registro de break-glass: el consumo de otra autorización no bloquea esta (${OUT:-sin salida})"
fi
record_fixtures

# ── 7. Entrada de la política (scan-evidence.sh) ────────────────────────────
EV="${TMP}/ev"
mkscan "${EV}/image-scan-selected-backend-1" backend "${BACKEND}" '[]' 0
SE_OUT=$(env -i PATH="${PATH}" TARGETS="$(jq -cn --arg r "${BACKEND}" '[{surface: "backend", image_ref: $r, applied: true}]')" \
  SCAN_RESULT=success EVIDENCE_DIR="${EV}" RUN_HOST_CONFIG=false RUN_APP_DEPLOY=true \
  BACKEND_SOURCE=build BACKEND_IMAGE_REF="${BACKEND}" POSTGRES_SOURCE=reuse POSTGRES_IMAGE_REF="${POSTGRES}" \
  REUSE_SCAN_ROOT=/ws "${SCAN_EVIDENCE}" 2> /dev/null) || SE_OUT=""
check "scan-evidence: policy_input lleva TODA imagen aplicada (build con su artifact, reuso con su escaneo en production)" \
  jq -e --arg b "${BACKEND}" --arg p "${POSTGRES}" --arg d "${EV}/image-scan-selected-backend-1" \
    '. == [{surface: "backend", image_ref: $b, dir: $d}, {surface: "postgres", image_ref: $p, dir: "/ws/scan-production-postgres"}]' \
    <<< "$(sed -n 's/^policy_input=//p' <<< "${SE_OUT}")"

# ── 8. Lugar de la política en el workflow ──────────────────────────────────
step_index() { yq -r ".jobs.production.steps | to_entries[] | select(.value.id == \"$1\") | .key" "${WORKFLOW}"; }
check "workflow: production espera a image_policy y exige su éxito con deploy" \
  bash -c "yq -r '.jobs.production.needs[]' '${WORKFLOW}' | grep -qx image_policy && yq -r '.jobs.production.if' '${WORKFLOW}' | grep -q \"needs.image_policy.result == 'success'\""
check "workflow: image_policy corre con todo deploy (también sin imágenes conocidas antes del gate)" \
  bash -c "yq -r '.jobs.image_policy.if' '${WORKFLOW}' | grep -q \"deploy_required == 'true'\" && yq -r '.jobs.image_policy.if' '${WORKFLOW}' | grep -q \"needs.image_targets.outputs.targets == '\\[\\]'\""
check "workflow: image_policy solo evalúa lo que el run aplica (applied) y solo lee" \
  bash -c "yq -r '.jobs.image_policy.steps[] | select(.id == \"policy\") | .run' '${WORKFLOW}' | grep -q 'select(.applied == true)' && [ \"\$(yq -o=json -I=0 '.jobs.image_policy.permissions' '${WORKFLOW}')\" = '{\"contents\":\"read\",\"actions\":\"read\",\"pull-requests\":\"read\"}' ]"
check "workflow: image_targets marca applied=false solo en la imagen construida y no desplegada" \
  bash -c "yq -r '.jobs.image_targets.steps[] | select(.id == \"targets\") | .run' '${WORKFLOW}' | grep -q 'build (no desplegada en este run)\" \"\$6\" \"\$7\" \"\$8\" false'"
POLICY_I=$(step_index policy) BG_I=$(step_index break_glass) AUTH_I=$(step_index authorization)
FRESH_I=$(step_index fresh_deploy) CONVERGE_I=$(step_index converge) DEPLOY_I=$(step_index deploy) RECORD_I=$(step_index record)
REUSE_I=$(step_index scan_postgres) EVIDENCE_I=$(step_index scan_evidence)
check "workflow: evidencia → escaneo del reuso → política → break-glass → exigencia → frescura → converge/deploy → registro" \
  bash -c "[ ${EVIDENCE_I} -lt ${REUSE_I} ] && [ ${REUSE_I} -lt ${POLICY_I} ] && [ ${POLICY_I} -lt ${BG_I} ] && [ ${BG_I} -lt ${AUTH_I} ] && [ ${AUTH_I} -lt ${FRESH_I} ] && [ ${FRESH_I} -lt ${CONVERGE_I} ] && [ ${CONVERGE_I} -lt ${DEPLOY_I} ] && [ ${DEPLOY_I} -lt ${RECORD_I} ]"
check "workflow: converge y deploy exigen la decisión (authorized, o break_glass con su registro)" \
  bash -c "for s in converge deploy; do yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .if\" '${WORKFLOW}' | tr -d '\n ' | grep -q \"steps.policy.outputs.decision=='authorized'||(steps.policy.outputs.decision=='break_glass'&&steps.break_glass.outcome=='success')\" || exit 1; done"
check "workflow: una decisión ausente o desconocida falla el job antes de mutar" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"authorization\") | .run' '${WORKFLOW}' | grep -q 'exit 1'"
check "workflow: la política corre en todo deploy y recibe policy_input de scan-evidence" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"policy\") | .if' '${WORKFLOW}' | grep -qx \"env.DEPLOY_REQUIRED == 'true'\" && yq -r '.jobs.production.steps[] | select(.id == \"policy\") | .env.POLICY_INPUT' '${WORKFLOW}' | grep -q 'steps.scan_evidence.outputs.policy_input'"
check "workflow: un bloqueo falla el paso (no avanza el último éxito ni la base del rango)" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"policy\") | .run' '${WORKFLOW}' | grep -q 'exit \"\${RC}\"' && yq -r '.jobs.conclude.steps[0].run' '${WORKFLOW}' | grep -q 'PRODUCTION}\" = \"success\"'"
check "workflow: el break-glass solo se registra si la política lo decidió" \
  test "$(yq -r '.jobs.production.steps[] | select(.id == "break_glass") | .if' "${WORKFLOW}")" = "env.DEPLOY_REQUIRED == 'true' && steps.policy.outputs.decision == 'break_glass'"
check "workflow: break-glass solo por workflow_dispatch (un push lo deja vacío) y con justificación" \
  bash -c "yq -r '.on.workflow_dispatch.inputs | keys | .[]' '${WORKFLOW}' | grep -qx break_glass_authorization && yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .run' '${WORKFLOW}' | grep -q 'echo \"break_glass_authorization=\"' && yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .run' '${WORKFLOW}' | grep -q 'requiere break_glass_justification'"
check "workflow: permisos de production = lectura + statuses e issues (consumo y auditoría de break-glass), sin escritura de contenido" \
  test "$(yq -o=json -I=0 '.jobs.production.permissions' "${WORKFLOW}")" = '{"contents":"read","actions":"read","pull-requests":"read","statuses":"write","issues":"write"}'
check "workflow: production e image_policy leen la historia completa (procedencia en main)" \
  bash -c "[ \"\$(yq -r '.jobs.production.steps[0].with.fetch-depth' '${WORKFLOW}')\" = 0 ] && [ \"\$(yq -r '.jobs.image_policy.steps[0].with.fetch-depth' '${WORKFLOW}')\" = 0 ]"
check "workflow: el escaneo sigue sin permisos más allá de contents: read" \
  test "$(yq -o=json -I=0 '.jobs.image_scan.permissions' "${WORKFLOW}")" = '{"contents":"read"}'
check "workflow: el resumen distingue el bloqueo de la política y el break-glass" \
  bash -c "grep -q 'first_failure \"Política de vulnerabilidades (bloqueo)\"' '${WORKFLOW}' && grep -q 'evidencia HISTÓRICA, sin escaneo fresco' '${WORKFLOW}'"
check "workflow y acción: ya no hay comportamiento ni texto de 'solo reporte'" \
  bash -c "! grep -qi -e 'solo reporte' -e 'report-only' '${WORKFLOW}' '${ACTION}' '${SCAN_EVIDENCE}'"
check "acción: Trivy fijado por versión y digest, sin socket de Docker" \
  bash -c "grep -q 'aquasec/trivy:0.74.0@sha256:[0-9a-f]\{64\}' '${ACTION}' && ! grep -q 'docker.sock' '${ACTION}'"
check "acción: summary.json registra la fecha del escaneo y los CRITICAL" \
  bash -c "grep -q 'scanned_at: \$report\[0\].CreatedAt' '${ACTION}' && grep -q 'ids: (\[\$v\[\] | select(.Severity == \"CRITICAL\")' '${ACTION}'"
check "workflow: ninguna evidencia de escaneo llega por actions/download-artifact (solo advierte ante un digest distinto)" \
  bash -c "! grep -q 'actions/download-artifact' '${WORKFLOW}' && [ \"\$(grep -c 'current-scan-evidence.sh' '${WORKFLOW}')\" = 2 ]"
check "workflow: break-glass con run_terraform_apply se rechaza al clasificar" \
  bash -c "yq -r '.jobs.classify.steps[] | select(.id == \"intent\") | .run' '${WORKFLOW}' | grep -A2 'IN_TERRAFORM}\" != \"false\"' | grep -q 'exit 1'"
check "workflow: ambas evaluaciones reciben la intención completa del run (OP_*)" \
  bash -c "for j in image_policy production; do [ \"\$(yq -r \".jobs.\$j.steps[] | select(.id == \\\"policy\\\") | .env | keys | map(select(test(\\\"^OP_\\\"))) | length\" '${WORKFLOW}')\" = 7 ] || exit 1; done"
check "workflow: la evaluación autoritativa exige la huella de aprobadores de la preliminar" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"policy\") | .env.EXPECTED_APPROVERS_FINGERPRINT' '${WORKFLOW}' | grep -q 'needs.image_policy.outputs.approvers_fingerprint' && yq -r '.jobs.image_policy.outputs.approvers_fingerprint' '${WORKFLOW}' | grep -q 'steps.policy.outputs.approvers_fingerprint'"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "vuln-policy-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "vuln-policy-check: todo PASS"
