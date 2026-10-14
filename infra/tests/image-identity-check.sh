#!/usr/bin/env bash
# Contrato de identidad de imagen: resolución de referencias (resolve-amd64-image.sh),
# referencia fijada de Keycloak de punta a punta en el repositorio y continuidad
# escaneado = desplegado de Keycloak (scan-evidence.sh). Sin red ni contenedores: el
# registry es un `docker` falso que sirve bytes fijos y registra qué referencia se leyó.
# La prueba ejecutable contra Keycloak real es keycloak-lifecycle-harness.sh (IDENT, UPG).
#
# Uso: infra/tests/image-identity-check.sh
# Requiere: jq, yq (mikefarah v4)
#
# Verifica:
#   1. Resolución: <repo>:<tag>, <repo>@sha256:<índice> y <repo>:<tag>@sha256:<índice> válidos;
#      con digest se lee SOLO por digest (el tag nunca llega al registry) y los bytes deben
#      coincidir; tags, digests y repositorios inválidos, índices ambiguos o sin linux/amd64 y
#      manifiestos que no son índice fallan sin salida.
#   2. Repositorio: keycloak.yml declara <tag>@sha256:<índice>; desarrollo usa la misma
#      referencia; producción usa KEYCLOAK_IMAGE (image.env), que solo escribe converge;
#      deploy exige Keycloak ya convergido antes de mutar y nunca lo arranca ni recrea; el workflow resuelve, escanea y pasa a converge esa identidad y
#      verifica keycloak en ejecución; ya no queda ninguna referencia por tag a Keycloak.
#   3. Evidencia de escaneo: keycloak solo se exige cuando corre converge, y su evidencia debe
#      ser exactamente la identidad que converge despliega.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RESOLVE="${ROOT}/.github/scripts/resolve-amd64-image.sh"
SCAN_EVIDENCE="${ROOT}/.github/scripts/scan-evidence.sh"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
TASKS="${ROOT}/infra/ansible/roles/app/tasks"

for bin in jq yq sha256sum; do
  command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }

# ── Registry falso ───────────────────────────────────────────────────────────
mkdir -p "${TMP}/bin" "${TMP}/registry"
cat > "${TMP}/bin/docker" <<'EOF'
#!/usr/bin/env bash
# Solo `docker buildx imagetools inspect --raw <ref>`: sirve los bytes registrados para <ref>
[ "$*" = "buildx imagetools inspect --raw $5" ] || { echo "docker falso: $*" >&2; exit 99; }
echo "$5" >> "${FAKE_LOG}"
f="${FAKE_REGISTRY}/$(printf '%s' "$5" | sha256sum | cut -c1-32)"
[ -f "$f" ] || { echo "manifest unknown: $5" >&2; exit 1; }
cat "$f"
EOF
chmod +x "${TMP}/bin/docker"
export FAKE_LOG="${TMP}/requests.log" FAKE_REGISTRY="${TMP}/registry"

serve() { # <ref> <archivo>
  cp "$2" "${FAKE_REGISTRY}/$(printf '%s' "$1" | sha256sum | cut -c1-32)"
}
digest_of() { echo "sha256:$(sha256sum "$1" | cut -d' ' -f1)"; }
d64() { printf "$1%.0s" {1..64}; }

AMD64="sha256:$(d64 a)"
ARM64="sha256:$(d64 b)"
ATTEST="sha256:$(d64 c)"
index_json() { # <manifests JSON>
  jq -cn --argjson m "$1" '{schemaVersion: 2, mediaType: "application/vnd.oci.image.index.v1+json", manifests: $m}'
}
child() { # <digest> <os> <arch> [attestation]
  jq -cn --arg d "$1" --arg os "$2" --arg arch "$3" --arg att "${4:-}" '
    {mediaType: "application/vnd.oci.image.manifest.v1+json", digest: $d, size: 1, platform: {os: $os, architecture: $arch}}
    + (if $att == "" then {} else {annotations: {"vnd.docker.reference.type": "attestation-manifest"}} end)'
}
index_json "[$(child "${AMD64}" linux amd64),$(child "${ARM64}" linux arm64),$(child "${ATTEST}" unknown unknown att)]" > "${TMP}/good.json"
index_json "[$(child "${AMD64}" linux amd64),$(child "${ARM64}" linux amd64)]" > "${TMP}/two-amd64.json"
index_json "[$(child "${ARM64}" linux arm64),$(child "${AMD64}" unknown unknown att)]" > "${TMP}/no-amd64.json"
jq -cn '{schemaVersion: 2, mediaType: "application/vnd.oci.image.manifest.v1+json", config: {}, layers: []}' > "${TMP}/single.json"
GOOD="$(digest_of "${TMP}/good.json")"

REPO=quay.io/keycloak/keycloak
serve "${REPO}:26.8.1" "${TMP}/good.json"
serve "${REPO}@${GOOD}" "${TMP}/good.json"
serve "localhost:5000/ns/img@${GOOD}" "${TMP}/good.json"
serve "docker.io/library/keycloak@${GOOD}" "${TMP}/good.json"
# Bytes distintos bajo un digest que no les corresponde (registry comprometido o error)
OTHER="sha256:$(d64 f)"
serve "${REPO}@${OTHER}" "${TMP}/good.json"
for f in two-amd64 no-amd64 single; do serve "${REPO}@$(digest_of "${TMP}/${f}.json")" "${TMP}/${f}.json"; done

resolve() { PATH="${TMP}/bin:${PATH}" "${RESOLVE}" "$1"; }
expect_ok() { # <descripción> <entrada> <referencia que debe leerse>
  : > "${FAKE_LOG}"
  local out
  if out="$(resolve "$2" 2>/dev/null)" &&
     [ "${out}" = "$(printf 'image_index_digest=%s\nimage_amd64_digest=%s\nimage_ref=%s@%s' "${GOOD}" "${AMD64}" "$4" "${AMD64}")" ] &&
     [ "$(cat "${FAKE_LOG}")" = "$3" ]; then
    ok "resolver: $1"
  else
    ko "resolver: $1 (leído: $(paste -sd' ' "${FAKE_LOG}"); salida: ${out:-ninguna})"
  fi
}
expect_fail() { # <descripción> <entrada>
  local out rc=0
  out="$(resolve "$2" 2>/dev/null)" || rc=$?
  if [ "${rc}" -ne 0 ] && [ -z "${out}" ]; then ok "resolver rechaza: $1"; else ko "resolver rechaza: $1 (rc=${rc}, salida: ${out})"; fi
}

# ── 1. Resolución ────────────────────────────────────────────────────────────
expect_ok "<repo>:<tag> (primera parte, sin cambios)" "${REPO}:26.8.1" "${REPO}:26.8.1" "${REPO}"
expect_ok "<repo>@sha256:<índice>" "${REPO}@${GOOD}" "${REPO}@${GOOD}" "${REPO}"
expect_ok "<repo>:<tag>@sha256:<índice>, leído solo por digest" "${REPO}:26.8.1@${GOOD}" "${REPO}@${GOOD}" "${REPO}"
expect_ok "registry con puerto: <host>:<puerto>/<ns>/<img>:<tag>@sha256" "localhost:5000/ns/img:v1@${GOOD}" "localhost:5000/ns/img@${GOOD}" "localhost:5000/ns/img"
expect_ok "nombre corto de Docker Hub con tag y digest" "keycloak:26.8.1@${GOOD}" "docker.io/library/keycloak@${GOOD}" "docker.io/library/keycloak"
# El tag de una referencia fijada es etiqueta: no se resuelve ni se contrasta en el registry
# (Ansible lo contrasta con la versión de la imagen descargada, keycloak_image.yml)
: > "${FAKE_LOG}"
resolve "${REPO}:0.0.0@${GOOD}" >/dev/null 2>&1 || true
check "resolver: con digest, el tag nunca se lee del registry" bash -c "! grep -q ':0.0.0' '${FAKE_LOG}'"

expect_fail "bytes que no corresponden al digest fijado" "${REPO}:26.8.1@${OTHER}"
expect_fail "tag latest junto al digest" "${REPO}:latest@${GOOD}"
expect_fail "tag 'none' junto al digest" "${REPO}:None@${GOOD}"
expect_fail "tag vacío junto al digest" "${REPO}:@${GOOD}"
expect_fail "tag con caracteres inválidos" "${REPO}:26.8.1!@${GOOD}"
expect_fail "digest truncado" "${REPO}:26.8.1@sha256:$(printf 'a%.0s' {1..63})"
expect_fail "digest en mayúsculas" "${REPO}:26.8.1@sha256:$(d64 A)"
expect_fail "algoritmo distinto de sha256" "${REPO}:26.8.1@sha512:$(d64 a)"
expect_fail "dos digests" "${REPO}:26.8.1@${GOOD}@${GOOD}"
expect_fail "repositorio en mayúsculas" "quay.io/Keycloak/keycloak:26.8.1@${GOOD}"
expect_fail "sin tag ni digest" "${REPO}"
expect_fail "tag latest sin digest" "${REPO}:latest"
expect_fail "digest inexistente en el registry" "${REPO}:26.8.1@sha256:$(d64 e)"
expect_fail "índice con dos manifiestos linux/amd64" "${REPO}:x@$(digest_of "${TMP}/two-amd64.json")"
expect_fail "índice sin manifiesto linux/amd64 de imagen (solo attestation)" "${REPO}:x@$(digest_of "${TMP}/no-amd64.json")"
expect_fail "manifiesto único (no es índice)" "${REPO}:x@$(digest_of "${TMP}/single.json")"

# ── 2. Referencia fijada de Keycloak en el repositorio ──────────────────────
DECLARED="$(yq -r '.services.keycloak.image' "${ROOT}/infra/compose/keycloak.yml")"
check "keycloak.yml declara ${REPO}:<tag>@sha256:<índice OCI>" \
  bash -c "[[ '${DECLARED}' =~ ^quay\\.io/keycloak/keycloak:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}@sha256:[0-9a-f]{64}$ ]]"
check "desarrollo: keycloak-dev-secret usa la misma referencia fijada" \
  test "$(yq -r '.services["keycloak-dev-secret"].image' "${ROOT}/docker-compose.yml")" = "${DECLARED}"
check "producción: prod.yml reemplaza la referencia por KEYCLOAK_IMAGE obligatoria" \
  bash -c "yq -r '.services.keycloak.image' '${ROOT}/infra/compose/prod.yml' | grep -q '^\${KEYCLOAK_IMAGE:?'"
check "ninguna referencia a Keycloak por tag solo (compose, Ansible, workflow)" \
  bash -c "! grep -rhoE 'quay\\.io/keycloak/keycloak:[A-Za-z0-9_.-]+([^@A-Za-z0-9_.-]|$)' '${ROOT}/infra/compose' '${ROOT}/docker-compose.yml' '${ROOT}/infra/ansible' '${WORKFLOW}'"
check "setup.yml escribe KEYCLOAK_IMAGE en image.env después de validar keycloak_image.yml" \
  bash -c "grep -q 'KEYCLOAK_IMAGE={{ keycloak_image_ref }}' '${TASKS}/setup.yml' && test \$(grep -n 'include_tasks: keycloak_image.yml' '${TASKS}/setup.yml' | cut -d: -f1) -lt \$(grep -n 'KEYCLOAK_IMAGE=' '${TASKS}/setup.yml' | cut -d: -f1)"
check "deploy.yml no selecciona Keycloak: exige exactamente una KEYCLOAK_IMAGE exacta en image.env" \
  bash -c "! grep -q 'keycloak_image_ref' '${TASKS}/deploy.yml' && grep -q 'app_deploy_keycloak_image | length == 1' '${TASKS}/deploy.yml'"
D_GATE="$(yq -r 'to_entries[] | select(.value.name == "Verificar que Keycloak ya está convergido con esa identidad") | .key' "${TASKS}/deploy.yml")"
D_FIRST_MUTATION="$(yq -r '[to_entries[] | select(.value.name == "Actualizar image.env" or .value.name == "Converger postgres y reconciliar autoridades de la base" or .value.name == "Desplegar postgres y backend con identidades exactas") | .key] | min' "${TASKS}/deploy.yml")"
check "deploy.yml: la precondición de Keycloak (existe, corre healthy, creado desde esa referencia) va antes de toda mutación" \
  bash -c "[ -n '${D_GATE}' ] && [ '${D_GATE}' -lt '${D_FIRST_MUTATION}' ]"
check "deploy.yml: Compose levanta postgres y backend sin dependencias (nunca arranca ni recrea keycloak)" \
  test "$(yq -r '.[] | select(.name == "Desplegar postgres y backend con identidades exactas") | .["community.docker.docker_compose_v2"].dependencies' "${TASKS}/deploy.yml")" = false
check "deploy.yml: después verifica que keycloak es el mismo contenedor (Id e inicio)" \
  bash -c "grep -q 'app_deploy_keycloak_before.id' '${TASKS}/deploy.yml' && grep -q 'app_deploy_keycloak_before.started_at' '${TASKS}/deploy.yml'"
check "inspección de contenedores sin mostrar su entorno (no_log en todo docker_container_info nuevo o compartido)" \
  bash -c "for f in deploy.yml verify_running_images.yml verify_health.yml; do test \"\$(yq -r '[.[] | select(has(\"community.docker.docker_container_info\")) | select(.no_log != true)] | length' '${TASKS}'/\$f)\" = 0 || exit 1; done"
check "keycloak_lifecycle.yml: versión registrada desconocida falla antes de la vuelta atrás y de arrancar" \
  bash -c "a=\$(grep -n 'Fallar cerrado ante una versión de la base desconocida' '${TASKS}/keycloak_lifecycle.yml' | cut -d: -f1); b=\$(grep -n 'Fallar cerrado ante una imagen anterior a la base' '${TASKS}/keycloak_lifecycle.yml' | cut -d: -f1); c=\$(grep -n 'Levantar Keycloak sobre la base ya inicializada' '${TASKS}/keycloak_lifecycle.yml' | cut -d: -f1); [ \"\$a\" -lt \"\$b\" ] && [ \"\$b\" -lt \"\$c\" ]"
check "keycloak_lifecycle.yml exige la identidad antes de arrancar y la verifica en ejecución" \
  bash -c "grep -q 'no arrancaría exactamente' '${TASKS}/keycloak_lifecycle.yml' && grep -q 'Volver' '${TASKS}/keycloak_lifecycle.yml' && grep -q 'app_release_services: \\[keycloak\\]' '${TASKS}/keycloak_lifecycle.yml'"
check "workflow: image_targets exige la referencia fijada y la resuelve con resolve-amd64-image.sh" \
  bash -c "yq -r '.jobs.image_targets.steps[] | select(.id == \"targets\") | .run' '${WORKFLOW}' | grep -q 'resolve-amd64-image.sh \"\${KEYCLOAK_LABEL}\"'"
check "workflow: una referencia de Keycloak sin resolver falla el job (no es fallo operativo del escáner)" \
  bash -c "yq -r '.jobs.image_targets.steps[] | select(.id == \"targets\") | .run' '${WORKFLOW}' | grep -A2 'resolve-amd64-image.sh \"\${KEYCLOAK_LABEL}\"' | grep -q 'exit 1'"
check "workflow: Keycloak solo se escanea y aplica con converge" \
  bash -c "yq -r '.jobs.image_targets.steps[] | select(.id == \"targets\") | .run' '${WORKFLOW}' | grep -q 'if \\[ \"\${RUN_HOST_CONFIG}\" = \"true\" \\]; then'"
check "workflow: converge recibe keycloak_image_ref y keycloak_image_index_digest de image_targets" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"converge\") | .run' '${WORKFLOW}' | grep -q 'keycloak_image_ref=\${KEYCLOAK_IMAGE_REF}' && yq -r '.jobs.production.env.KEYCLOAK_IMAGE_REF' '${WORKFLOW}' | grep -q 'needs.image_targets.outputs.keycloak_image_ref'"
check "workflow: tras converge verifica keycloak en ejecución con esa misma identidad" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"converge_identity\") | .run' '${WORKFLOW}' | grep -q '\"keycloak\"' && yq -r '.jobs.production.steps[] | select(.id == \"converge_identity\") | .run' '${WORKFLOW}' | grep -q 'keycloak_image_ref=\${KEYCLOAK_IMAGE_REF}'"
check "workflow: deploy no recibe identidad de Keycloak" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.id == \"deploy\") | .run' '${WORKFLOW}' | grep -q keycloak_image"

# ── 3. Evidencia de escaneo: escaneado = desplegado ─────────────────────────
KC_REF="${REPO}@${AMD64}"
evidence() { # <dir> <surface> <ref>
  mkdir -p "$1/image-scan-selected-$2-1"
  jq -n --arg s "$2" --arg r "$3" '{surface: $s, image_ref: $r, outcome: "scanned", critical: {findings: 0}, high: {findings: 3}}' \
    > "$1/image-scan-selected-$2-1/summary.json"
}
evidence "${TMP}/ev" keycloak "${KC_REF}"
evidence "${TMP}/ev" traefik "docker.io/library/traefik@sha256:$(d64 d)"
TARGETS_KC="$(jq -cn --arg r "${KC_REF}" --arg t "docker.io/library/traefik@sha256:$(d64 d)" \
  '[{surface: "keycloak", image_ref: $r}, {surface: "traefik", image_ref: $t}]')"
run_evidence() { # <RUN_HOST_CONFIG> <RUN_APP_DEPLOY> <TARGETS> <KEYCLOAK_IMAGE_REF>
  env -i PATH="${PATH}" TARGETS="$3" UNRESOLVED='[]' SCAN_RESULT=success EVIDENCE_DIR="${TMP}/ev" \
    RUN_HOST_CONFIG="$1" RUN_APP_DEPLOY="$2" KEYCLOAK_IMAGE_REF="$4" \
    BACKEND_SOURCE=reuse BACKEND_IMAGE_REF="ghcr.io/x/backend@sha256:$(d64 1)" \
    POSTGRES_SOURCE=reuse POSTGRES_IMAGE_REF="ghcr.io/x/postgres@sha256:$(d64 2)" \
    "${SCAN_EVIDENCE}"
}
check "evidencia: converge con la identidad escaneada de keycloak: pasa" \
  bash -c "$(declare -f run_evidence d64); TMP='${TMP}' SCAN_EVIDENCE='${SCAN_EVIDENCE}'; run_evidence true true '${TARGETS_KC}' '${KC_REF}' | grep -q 'keycloak'"
check "evidencia: converge desplegaría otra identidad que la escaneada: falla" \
  bash -c "$(declare -f run_evidence d64); TMP='${TMP}' SCAN_EVIDENCE='${SCAN_EVIDENCE}'; ! run_evidence true true '${TARGETS_KC}' '${REPO}@${ARM64}'"
check "evidencia: converge sin identidad exacta de keycloak: falla" \
  bash -c "$(declare -f run_evidence d64); TMP='${TMP}' SCAN_EVIDENCE='${SCAN_EVIDENCE}'; ! run_evidence true true '${TARGETS_KC}' '${REPO}:26.8.1'"
check "evidencia: converge sin intento de escaneo de keycloak: falla" \
  bash -c "$(declare -f run_evidence d64); TMP='${TMP}' SCAN_EVIDENCE='${SCAN_EVIDENCE}'; ! run_evidence true true \"\$(jq -c 'map(select(.surface != \"keycloak\"))' <<< '${TARGETS_KC}')\" '${KC_REF}'"
check "evidencia: deploy sin converge no exige ni registra keycloak (no lo aplica)" \
  bash -c "$(declare -f run_evidence d64); TMP='${TMP}' SCAN_EVIDENCE='${SCAN_EVIDENCE}'; run_evidence false true '[]' '' | grep -q '^evidence=\\[\\]$'"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "image-identity-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "image-identity-check: todo PASS"
