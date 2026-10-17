#!/usr/bin/env bash
# Publicación coordinada del frontend en Cloudflare Pages: intención (classify-production.sh
# e intención manual del workflow), artefacto validado (frontend-artifact.sh), destino,
# preflight y publicación (pages-release.sh) y su lugar en production-hetzner.yml.
#
# Sin red, sin Cloudflare y sin GitHub: `gh`, `curl` y `wrangler` son falsos (sirven
# respuestas registradas y anotan cada llamada) y los rangos de main son un repositorio git
# desechable. Nada de esto publica ni prueba permisos reales de un token: eso queda para la
# verificación formal contra el entorno.
#
# Uso: infra/tests/pages-release-check.sh
# Requiere: jq, yq (mikefarah v4), git, tar (GNU), zip, unzip, sha256sum
# Opcional: frontend/dist/frontend/browser (build real) para empaquetar el sitio real.
# Salida: 0 si todo pasa; 1 si algo falla.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPTS="${ROOT}/.github/scripts"
CLASSIFY="${SCRIPTS}/classify-production.sh"
ARTIFACT="${SCRIPTS}/frontend-artifact.sh"
PAGES="${SCRIPTS}/pages-release.sh"
WORKFLOW="${ROOT}/.github/workflows/production-hetzner.yml"
TF="${ROOT}/infra/terraform/live/prod"

for bin in jq yq git tar zip unzip sha256sum; do
  command -v "$bin" > /dev/null || { echo "falta $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap '[ -n "${KEEP_TMP:-}" ] || rm -rf "${TMP}"' EXIT

FAILS=0
ok() { printf 'PASS  %s\n' "$1"; }
ko() { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ok "${desc}"; else ko "${desc}"; fi; }
fails() { local desc="$1"; shift; if "$@" > /dev/null 2>&1; then ko "${desc} (no falló)"; else ok "${desc}"; fi; }
key() { printf '%s' "$1" | sha256sum | cut -c1-32; }

# ── 1. Intención por rango (classify-production.sh) ─────────────────────────
REPO="${TMP}/repo"
git init -q -b main "${REPO}"
git -C "${REPO}" config user.email t@t
git -C "${REPO}" config user.name t
put() { mkdir -p "$(dirname "${REPO}/$1")"; printf '%s\n' "${2:-x$RANDOM}" > "${REPO}/$1"; }
commit() { git -C "${REPO}" add -A && git -C "${REPO}" commit -q -m "$1" && git -C "${REPO}" rev-parse HEAD; }
put frontend/package.json; put frontend/src/main.ts; put frontend/README.md
put backend/pom.xml; put infra/terraform/live/prod/main.tf; put infra/terraform/live/prod/README.md
put infra/compose/prod.yml; put infra/postgres/Dockerfile; put infra/ansible/site.yml; put docs/x.md
BASE=$(commit base)

# classify_case <descripción> <intención> <terraform/deploy/frontend> <archivos...>
classify_case() {
  local desc="$1" intent="$2" flags="$3" out head
  shift 3
  git -C "${REPO}" checkout -q -B "c$RANDOM" "${BASE}"
  for f in "$@"; do put "$f"; done
  head=$(commit "${desc}")
  if out=$(cd "${REPO}" && "${CLASSIFY}" "${BASE}" "${head}" 2>&1) &&
     grep -qx "intent=${intent}" <<< "${out}" &&
     [ "$(sed -n 's/^terraform_required=//p;s/^deploy_required=//p;s/^frontend_required=//p' <<< "${out}" | paste -sd/)" = "${flags}" ]; then
    ok "rango: ${desc} → ${intent}"
  else
    ko "rango: ${desc} → ${intent} (salida: $(paste -sd' ' <<< "${out}"))"
  fi
}
classify_case "solo frontend" frontend_only false/false/true frontend/src/main.ts
classify_case "solo .node-version del frontend" frontend_only false/false/true frontend/.node-version
classify_case "solo README del frontend" noop false/false/false frontend/README.md
classify_case "solo documentación (sin regresión)" noop false/false/false docs/x.md infra/terraform/live/prod/README.md
classify_case "solo backend (sin regresión)" deploy_only false/true/false backend/pom.xml
classify_case "solo Terraform (sin regresión)" apply_only true/false/false infra/terraform/live/prod/main.tf
classify_case "Terraform y backend (sin regresión)" apply_then_deploy true/true/false infra/terraform/live/prod/main.tf backend/pom.xml
classify_case "backend y frontend" deploy_then_frontend false/true/true backend/pom.xml frontend/src/main.ts
classify_case "configuración del servidor y frontend" deploy_then_frontend false/true/true infra/ansible/site.yml frontend/src/main.ts
classify_case "Terraform y frontend" apply_then_frontend true/false/true infra/terraform/live/prod/main.tf frontend/src/main.ts
classify_case "Terraform, backend y frontend" apply_then_deploy_then_frontend true/true/true \
  infra/terraform/live/prod/main.tf backend/pom.xml frontend/package.json
HEAD_FULL=$(git -C "${REPO}" rev-parse HEAD)
check "rango completo (sin éxito previo): el árbol con frontend/ exige publicarlo" \
  bash -c "cd '${REPO}' && '${CLASSIFY}' '' '${HEAD_FULL}' | grep -qx frontend_required=true"
# Acumulado: un run fallido con frontend no se pierde si el siguiente commit no lo toca
git -C "${REPO}" checkout -q -B acc "${BASE}"
put frontend/src/main.ts; commit "frontend (run fallido)" > /dev/null
put backend/pom.xml; ACC=$(commit "solo backend")
check "rango acumulado desde el último éxito conserva el frontend pendiente" \
  bash -c "cd '${REPO}' && '${CLASSIFY}' '${BASE}' '${ACC}' | grep -qx intent=deploy_then_frontend"

# ── 2. Intención manual (workflow_dispatch) ─────────────────────────────────
INTENT_RUN="$(yq -r '.jobs.classify.steps[] | select(.id == "intent") | .run' "${WORKFLOW}")"
dispatch() { # <terraform> <host> <build> <build_pg> <deploy> <frontend> [break-glass]
  local dir="${TMP}/dispatch"
  rm -rf "${dir}" && mkdir -p "${dir}"
  env -i PATH="${PATH}" HOME="${TMP}" GITHUB_REF=refs/heads/main GITHUB_SHA="${HEAD_FULL}" \
    EVENT_NAME=workflow_dispatch RUNNER_TEMP="${dir}" GITHUB_OUTPUT="${dir}/out" \
    GITHUB_STEP_SUMMARY="${dir}/summary" WORKFLOW_PATH=x \
    IN_TERRAFORM="$1" IN_HOST_CONFIG="$2" IN_BUILD="$3" IN_BUILD_POSTGRES="$4" IN_APP_DEPLOY="$5" \
    IN_FRONTEND="$6" IN_IMAGE_TAG="" IN_KEYCLOAK_RECOVERY=false \
    IN_BREAK_GLASS="${7:-}" IN_BREAK_GLASS_JUSTIFICATION="${7:+justificación suficientemente larga}" \
    bash -c "${INTENT_RUN}" > /dev/null 2>&1 && cat "${dir}/out"
}
check "dispatch: solo frontend → frontend_only, sin Apply ni deploy" \
  bash -c "$(declare -f dispatch); TMP='${TMP}' HEAD_FULL='${HEAD_FULL}' INTENT_RUN=\$1; out=\$(dispatch false false false false false true) && grep -qx intent=frontend_only <<< \"\$out\" && grep -qx deploy_required=false <<< \"\$out\" && grep -qx terraform_required=false <<< \"\$out\"" _ "${INTENT_RUN}"
check "dispatch: valores por defecto (build + deploy) sin frontend → deploy_only (sin regresión)" \
  bash -c "$(declare -f dispatch); TMP='${TMP}' HEAD_FULL='${HEAD_FULL}' INTENT_RUN=\$1; out=\$(dispatch false false true false true false) && grep -qx intent=deploy_only <<< \"\$out\" && grep -qx frontend_required=false <<< \"\$out\"" _ "${INTENT_RUN}"
check "dispatch: Apply + deploy + frontend → apply_then_deploy_then_frontend" \
  bash -c "$(declare -f dispatch); TMP='${TMP}' HEAD_FULL='${HEAD_FULL}' INTENT_RUN=\$1; dispatch true false true false true true | grep -qx intent=apply_then_deploy_then_frontend" _ "${INTENT_RUN}"
fails "dispatch: todos los flags en false (incluido frontend) se rechaza" \
  bash -c "$(declare -f dispatch); TMP='${TMP}' HEAD_FULL='${HEAD_FULL}' INTENT_RUN=\$1; dispatch false false false false false false" _ "${INTENT_RUN}"
fails "dispatch: break-glass con publicación del frontend se rechaza" \
  bash -c "$(declare -f dispatch); TMP='${TMP}' HEAD_FULL='${HEAD_FULL}' INTENT_RUN=\$1; dispatch false false false false true true BG-2026-001" _ "${INTENT_RUN}"
check "dispatch: break-glass sin frontend sigue admitido (sin regresión)" \
  bash -c "$(declare -f dispatch); TMP='${TMP}' HEAD_FULL='${HEAD_FULL}' INTENT_RUN=\$1; dispatch false false false false true false BG-2026-001 | grep -qx intent=deploy_only" _ "${INTENT_RUN}"
check "dispatch: no más de 10 inputs (límite de workflow_dispatch)" \
  test "$(yq '.on.workflow_dispatch.inputs | length' "${WORKFLOW}")" -le 10

# ── 3. Artefacto del frontend (frontend-artifact.sh) ────────────────────────
SITE_REPO="${TMP}/site-repo"
git init -q -b main "${SITE_REPO}"
git -C "${SITE_REPO}" config user.email t@t
git -C "${SITE_REPO}" config user.name t
mkdir -p "${SITE_REPO}/frontend" && echo '{}' > "${SITE_REPO}/frontend/package.json"
git -C "${SITE_REPO}" add -A && git -C "${SITE_REPO}" commit -q -m s
SITE_SHA=$(git -C "${SITE_REPO}" rev-parse HEAD)
RUN_ID=4242
make_site() { # <dir>
  mkdir -p "$1/assets"
  printf '<!doctype html><html><head><base href="/"><link rel="icon" href="favicon.ico"><link rel="stylesheet" href="styles-AB12.css"><link rel="preconnect" href="https://fonts.gstatic.com"></head><body><app-root></app-root><script src="polyfills-CD34.js" type="module"></script><script src="main-EF56.js" type="module"></script></body></html>\n' > "$1/index.html"
  echo 'console.log(1)' > "$1/main-EF56.js"; echo 'p' > "$1/polyfills-CD34.js"; echo 'b{}' > "$1/styles-AB12.css"
  echo i > "$1/favicon.ico"; echo h > "$1/_headers"; echo a > "$1/assets/x.png"
}
package() { # <site> <out>
  (cd "${SITE_REPO}" && GITHUB_SHA="${SITE_SHA}" GITHUB_RUN_ID="${RUN_ID}" GITHUB_RUN_ATTEMPT=1 "${ARTIFACT}" package "$1" "$2")
}
make_site "${TMP}/site"
check "package: sitio válido → tar + manifiesto con commit, sha256 y punto de entrada" \
  bash -c "$(declare -f package); SITE_REPO='${SITE_REPO}' SITE_SHA='${SITE_SHA}' RUN_ID='${RUN_ID}' ARTIFACT='${ARTIFACT}'; package '${TMP}/site' '${TMP}/pkg' > '${TMP}/pkg.env' && grep -qx entry_asset=main-EF56.js '${TMP}/pkg.env' && jq -e --arg c '${SITE_SHA}' '.commit == \$c and .entry_asset == \"main-EF56.js\"' '${TMP}/pkg/frontend-manifest.json'"
DIST_SHA=$(sed -n 's/^dist_sha256=//p' "${TMP}/pkg.env")
check "package: mismo sitio → mismo sha256 (tar determinista)" \
  bash -c "$(declare -f package); SITE_REPO='${SITE_REPO}' SITE_SHA='${SITE_SHA}' RUN_ID='${RUN_ID}' ARTIFACT='${ARTIFACT}'; package '${TMP}/site' '${TMP}/pkg2' | grep -qx 'dist_sha256=${DIST_SHA}'"
package_fails() { # <descripción> <mutación sobre el sitio>
  local d="${TMP}/bad$RANDOM"
  make_site "${d}"
  (cd "${d}" && eval "$2")
  if package "${d}" "${d}-out" > /dev/null 2>&1; then ko "package rechaza: $1 (no falló)"; else ok "package rechaza: $1"; fi
}
package_fails "sin index.html (página en blanco)" 'rm index.html'
package_fails "index.html vacío" ': > index.html'
package_fails "chunk referenciado ausente" 'rm polyfills-CD34.js'
package_fails "dos puntos de entrada main-*.js" 'sed -i "s#</body>#<script src=\"main-ZZ99.js\"></script></body>#" index.html && echo z > main-ZZ99.js'
package_fails "enlace simbólico en el sitio" 'ln -s /etc/passwd leak'
package_fails "archivo de más de 25 MiB" 'truncate -s 26M assets/big.bin'
check "package: el checkout con cambios en frontend/ no se empaqueta como el commit" \
  bash -c "echo dirty >> '${SITE_REPO}/frontend/package.json'; $(declare -f package); SITE_REPO='${SITE_REPO}' SITE_SHA='${SITE_SHA}' RUN_ID='${RUN_ID}' ARTIFACT='${ARTIFACT}'; ! package '${TMP}/site' '${TMP}/pkg-dirty'; rc=\$?; git -C '${SITE_REPO}' checkout -q -- frontend; exit \$rc"
REAL_DIST="${ROOT}/frontend/dist/frontend/browser"
if [ -f "${REAL_DIST}/index.html" ]; then
  check "package: el build real de Angular (frontend/dist) valida y empaqueta" \
    bash -c "$(declare -f package); SITE_REPO='${SITE_REPO}' SITE_SHA='${SITE_SHA}' RUN_ID='${RUN_ID}' ARTIFACT='${ARTIFACT}'; package '${REAL_DIST}' '${TMP}/pkg-real' | grep -q '^entry_asset=main-'"
else
  echo "SKIP  package del build real: frontend/dist no existe (npm run build en frontend/)"
fi

# ── gh falso: artifact del run ──
mkdir -p "${TMP}/bin" "${TMP}/gh"
export FAKE_GH_DIR="${TMP}/gh" FAKE_GH_LOG="${TMP}/gh.log"
cat > "${TMP}/bin/gh" << 'EOF'
#!/usr/bin/env bash
[ "${1:-}" = api ] || { echo "gh falso: $*" >&2; exit 99; }
shift
path="" jqexpr=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jq) jqexpr="$2"; shift 2 ;;
    -*) echo "gh falso: opción $1" >&2; exit 99 ;;
    *) path="$1"; shift ;;
  esac
done
echo "GET ${path}" >> "${FAKE_GH_LOG}"
f="${FAKE_GH_DIR}/$(printf '%s' "GET ${path}" | sha256sum | cut -c1-32)"
[ -f "${f}" ] || { echo "HTTP 404: ${path}" >&2; exit 1; }
if [ -n "${jqexpr}" ]; then jq -r "${jqexpr}" "${f}"; else cat "${f}"; fi
EOF
chmod +x "${TMP}/bin/gh"
GHREPO=o/r
ART_ID=901
ART_NAME="frontend-dist-${RUN_ID}-1"
serve_file() { cp "$2" "${FAKE_GH_DIR}/$(key "GET $1")"; }
printf '{"id": 555}' > "${TMP}/repo.json"; serve_file "repos/${GHREPO}" "${TMP}/repo.json"
# publish_artifact <dir con frontend-dist.tar y frontend-manifest.json> [jq de ajuste del JSON]
publish_artifact() {
  rm -f "${TMP}/art.zip"
  (cd "$1" && zip -q -X "${TMP}/art.zip" frontend-dist.tar frontend-manifest.json)
  serve_file "repos/${GHREPO}/actions/artifacts/${ART_ID}/zip" "${TMP}/art.zip"
  jq -n --arg d "sha256:$(sha256sum "${TMP}/art.zip" | cut -d' ' -f1)" --arg n "${ART_NAME}" \
    --arg sha "${SITE_SHA}" --argjson run "${RUN_ID}" --argjson id "${ART_ID}" '
    {id: $id, name: $n, digest: $d, expired: false,
     workflow_run: {id: $run, repository_id: 555, head_repository_id: 555, head_sha: $sha}}' |
    jq "${2:-.}" > "${TMP}/art.json"
  serve_file "repos/${GHREPO}/actions/artifacts/${ART_ID}" "${TMP}/art.json"
}
fetch() { # [EXPECTED_DIST_SHA256]
  rm -rf "${TMP}/fetched"
  env PATH="${TMP}/bin:${PATH}" GITHUB_REPOSITORY="${GHREPO}" GITHUB_RUN_ID="${RUN_ID}" GITHUB_SHA="${SITE_SHA}" \
    EXPECTED_ARTIFACT_ID="${ART_ID}" EXPECTED_ARTIFACT_NAME="${ART_NAME}" EXPECTED_DIST_SHA256="${1:-${DIST_SHA}}" \
    "${ARTIFACT}" fetch "${TMP}/fetched"
}
FETCH_DEF="$(declare -f fetch); TMP='${TMP}' GHREPO='${GHREPO}' RUN_ID='${RUN_ID}' SITE_SHA='${SITE_SHA}' ART_ID='${ART_ID}' ART_NAME='${ART_NAME}' DIST_SHA='${DIST_SHA}' ARTIFACT='${ARTIFACT}'"

publish_artifact "${TMP}/pkg"
check "fetch: artifact de este run, commit y digest → sitio extraído idéntico al validado" \
  bash -c "${FETCH_DEF}; fetch > '${TMP}/fetch.env' && grep -qx entry_asset=main-EF56.js '${TMP}/fetch.env' && diff -r '${TMP}/site' '${TMP}/fetched/site'"
fetch_fails() { # <descripción> [jq sobre el JSON del artifact] [sha esperado]
  publish_artifact "${TMP}/pkg" "${2:-.}"
  if bash -c "${FETCH_DEF}; fetch '${3:-}'" > /dev/null 2>&1; then ko "fetch rechaza: $1 (no falló)"; else ok "fetch rechaza: $1"; fi
}
fetch_fails "artifact de otro run" '.workflow_run.id = 1'
fetch_fails "artifact de otro commit (re-run viejo)" '.workflow_run.head_sha = "'"$(printf 'a%.0s' {1..40})"'"'
fetch_fails "artifact de un fork" '.workflow_run.head_repository_id = 9'
fetch_fails "artifact vencido" '.expired = true'
fetch_fails "artifact con otro nombre" '.name = "frontend-dist-1-1"'
fetch_fails "zip que no tiene el digest registrado por GitHub" '.digest = "sha256:'"$(printf 'b%.0s' {1..64})"'"'
fetch_fails "tar distinto del que validó frontend_build (output de job)" '.' "sha256:$(printf 'c%.0s' {1..64})"
# Contenido coherente con su digest pero ajeno al build validado o inseguro
tamper() { # <descripción> <comando en el dir del artefacto> [sha esperado = el del tar resultante]
  local d="${TMP}/tamper$RANDOM" sha
  cp -r "${TMP}/pkg" "${d}"
  (cd "${d}" && eval "$2")
  sha="sha256:$(sha256sum "${d}/frontend-dist.tar" | cut -d' ' -f1)"
  publish_artifact "${d}"
  if bash -c "${FETCH_DEF}; fetch '${sha}'" > /dev/null 2>&1; then ko "fetch rechaza: $1 (no falló)"; else ok "fetch rechaza: $1"; fi
}
tamper "manifiesto de otro commit" 'jq --arg s "$(sha256sum frontend-dist.tar | cut -d" " -f1)" ".commit = \"$(printf "d%.0s" {1..40})\" | .dist_sha256 = \"sha256:\" + \$s" frontend-manifest.json > m && mv m frontend-manifest.json'
tamper "tar con ruta '..'" 'mkdir -p x && echo e > x/evil && tar --format=gnu -cf frontend-dist.tar -C x --transform "s#^#./../#" evil && jq --arg s "$(sha256sum frontend-dist.tar | cut -d" " -f1)" ".dist_sha256 = \"sha256:\" + \$s" frontend-manifest.json > m && mv m frontend-manifest.json'
tamper "tar con enlace simbólico" 'mkdir -p x && tar -xf frontend-dist.tar -C x && ln -s /etc/passwd x/leak && tar --format=gnu -cf frontend-dist.tar -C x . && jq --arg s "$(sha256sum frontend-dist.tar | cut -d" " -f1)" ".dist_sha256 = \"sha256:\" + \$s" frontend-manifest.json > m && mv m frontend-manifest.json'
publish_artifact "${TMP}/pkg"
fails "fetch: zip con archivos de más" \
  bash -c "cp '${TMP}/pkg/frontend-manifest.json' '${TMP}/extra.json'; cd '${TMP}/pkg' && zip -q -X '${TMP}/art2.zip' frontend-dist.tar frontend-manifest.json && cd '${TMP}' && zip -q '${TMP}/art2.zip' extra.json && cp '${TMP}/art2.zip' '${FAKE_GH_DIR}/$(key "GET repos/${GHREPO}/actions/artifacts/${ART_ID}/zip")' && jq --arg d \"sha256:\$(sha256sum '${TMP}/art2.zip' | cut -d' ' -f1)\" '.digest = \$d' '${TMP}/art.json' > '${FAKE_GH_DIR}/$(key "GET repos/${GHREPO}/actions/artifacts/${ART_ID}")'; ${FETCH_DEF}; fetch"
publish_artifact "${TMP}/pkg"

# ── 4. Destino, preflight y publicación (pages-release.sh) ──────────────────
check "target: proyecto, cuenta, rama y dominio salen de la declaración de Terraform" \
  bash -c "cd '${ROOT}' && out=\$('${PAGES}' target) && grep -qx project_name=piedrazul <<< \"\$out\" && grep -qx account_id=ab4f90b9b70538731273c3fdf9548f07 <<< \"\$out\" && grep -qx production_branch=main <<< \"\$out\" && grep -qx custom_domain=piedrazul.org <<< \"\$out\""
printf 'project = "a"\nproject = "b"\ncloudflare_account_id = "ab4f90b9b70538731273c3fdf9548f07"\nbase_domain = "x.org"\n' > "${TMP}/dup.tfvars"
fails "target: variable declarada dos veces" env PAGES_TFVARS="${TMP}/dup.tfvars" "${PAGES}" target
printf 'project = "a"\nbase_domain = "x.org"\n' > "${TMP}/missing.tfvars"
fails "target: cuenta ausente" env PAGES_TFVARS="${TMP}/missing.tfvars" "${PAGES}" target
check "Terraform: el proyecto de Pages es var.project, rama main, dominio base_domain" \
  bash -c "grep -qE '^[[:space:]]*pages_name[[:space:]]*=[[:space:]]*var\\.project\$' '${TF}/locals.tf' && grep -qE '^[[:space:]]*frontend_domain[[:space:]]*=[[:space:]]*var\\.base_domain\$' '${TF}/locals.tf' && sed -n '/^module \"pages\"/,/^}/p' '${TF}/main.tf' | grep -qE 'production_branch[[:space:]]*=[[:space:]]*\"main\"' && sed -n '/^module \"pages\"/,/^}/p' '${TF}/main.tf' | grep -qE 'project_name[[:space:]]*=[[:space:]]*local\\.pages_name' && sed -n '/^module \"pages\"/,/^}/p' '${TF}/main.tf' | grep -qE 'custom_domain[[:space:]]*=[[:space:]]*local\\.frontend_domain'"
PAGES_MOD="${ROOT}/infra/terraform/modules/cloudflare/pages"
# Sin comentarios: solo lo que Terraform interpreta
tf_code() { sed -E 's/#.*$//' "$@"; }
check "Terraform: proyecto de Pages de subida directa (sin source, sin build_config, sin lifecycle que oculte el origen)" \
  bash -c "$(declare -f tf_code); ! tf_code '${PAGES_MOD}/main.tf' | grep -qE '^[[:space:]]*(source|build_config|ignore_changes|deployment_configs|preview_deployment_setting|production_deployments_enabled|pr_comments_enabled)\\b' && tf_code '${PAGES_MOD}/main.tf' | sed -n '/resource \"cloudflare_pages_project\" \"main\"/,/^}/p' | grep -q account_id && ! tf_code '${PAGES_MOD}/main.tf' | sed -n '/resource \"cloudflare_pages_project\" \"main\"/,/^}/p' | grep -q lifecycle"
check "Terraform: sin variables de integración Git (github_*, build_command, output_dir, root_dir)" \
  bash -c "$(declare -f tf_code); ! tf_code '${PAGES_MOD}'/*.tf '${TF}'/*.tf | grep -qE 'github_owner|github_repo|build_command|output_dir|root_dir' && ! grep -qE '^[[:space:]]*github_(owner|repo)[[:space:]]*=' '${TF}/piedrazul.auto.tfvars'"
check "Terraform: cuenta y zona dedicadas declaradas" \
  bash -c "grep -qx 'cloudflare_account_id = \"ab4f90b9b70538731273c3fdf9548f07\"' '${TF}/piedrazul.auto.tfvars' && grep -qx 'cloudflare_zone_id = \"b663ee4c0c9adb151120634cb7b84e7b\"' '${TF}/piedrazul.auto.tfvars' && grep -qx 'base_domain = \"piedrazul.org\"' '${TF}/piedrazul.auto.tfvars' && grep -qx 'project = \"piedrazul\"' '${TF}/piedrazul.auto.tfvars'"
check "Terraform: orden proyecto → CNAME (módulo dns) → asociación del dominio, un solo dueño del registro" \
  bash -c "$(declare -f tf_code); sed -n '/^module \"pages\"/,/^}/p' '${TF}/main.tf' | grep -qE 'custom_domain_dns_record_id[[:space:]]*=[[:space:]]*module\\.dns\\.frontend_record_id' && sed -n '/resource \"cloudflare_pages_domain\"/,/^}/p' '${PAGES_MOD}/main.tf' | grep -q 'var.custom_domain_dns_record_id' && grep -q 'cloudflare_dns_record.frontend.id' '${ROOT}/infra/terraform/modules/cloudflare/dns/outputs.tf' && sed -n '/^module \"dns\"/,/^}/p' '${TF}/main.tf' | grep -qE 'pages_hostname[[:space:]]*=[[:space:]]*module\\.pages\\.pages_hostname' && [ \"\$(tf_code '${ROOT}'/infra/terraform/modules/*/*/*.tf | grep -c 'resource \"cloudflare_dns_record\"')\" = 3 ] && ! tf_code '${PAGES_MOD}'/*.tf | grep -q cloudflare_dns_record"

# ── curl y wrangler falsos ──
# curl: sirve, por URL, una secuencia de respuestas (<clave>.1, .2, …; la última se repite)
# y anota URL, cabecera Authorization y argumentos. wrangler: anota argumentos y entorno y
# escribe en WRANGLER_OUTPUT_FILE_PATH lo que el caso registre (como el wrangler real).
mkdir -p "${TMP}/cf"
export FAKE_CF_DIR="${TMP}/cf" FAKE_CF_LOG="${TMP}/cf.log" FAKE_WRANGLER_LOG="${TMP}/wrangler.log" \
  FAKE_WRANGLER_OUT="${TMP}/wrangler-out.ndjson"
# Una respuesta puede empezar con una línea "@status NNN" (código HTTP; si no, 200). Sin
# respuesta registrada: con -w imprime 000 y sale 7 (sin conexión); sin -w sale 22.
cat > "${TMP}/bin/curl" << 'EOF'
#!/usr/bin/env bash
url="" auth="" out="" fmt="" failflag="" method=GET
while [ $# -gt 0 ]; do
  case "$1" in
    -H) [[ "$2" == @* ]] && auth=$(cat "${2#@}"); shift 2 ;;
    -o) out="$2"; shift 2 ;;
    -w) fmt="$2"; shift 2 ;;
    -X) method="$2"; shift 2 ;;
    --max-time | --data-binary) shift 2 ;;
    --fail | --fail-with-body | -sSf | -f) failflag=1; shift ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s|%s|%s\n' "${url}" "${auth}" "${method}" >> "${FAKE_CF_LOG}"
k="${FAKE_CF_DIR:?}/$(printf '%s' "${url}" | sha256sum | cut -c1-32)"
f=$(ls "${k}".* 2> /dev/null | sort -V | head -1)
if [ -z "${f}" ]; then
  [ -z "${fmt}" ] || { printf '000'; exit 7; }
  exit 22
fi
code=200 body="${f}"
if head -1 "${f}" | grep -qE '^@status [0-9]{3}$'; then
  code=$(head -1 "${f}" | cut -d' ' -f2)
  body=$(mktemp); tail -n +2 "${f}" > "${body}"
fi
if [ -n "${out}" ]; then cat "${body}" > "${out}"; else cat "${body}"; fi
[ "$(ls "${k}".* | wc -l)" -le 1 ] || rm -f -- "${f:?}"
[ -z "${fmt}" ] || printf '%s' "${code}"
if [ -n "${failflag}" ] && [ "${code}" -ge 400 ]; then exit 22; fi
exit 0
EOF
cat > "${TMP}/bin/wrangler" << 'EOF'
#!/usr/bin/env bash
printf '%s|%s|token=%s|out=%s\n' "$*" "${CLOUDFLARE_ACCOUNT_ID}" "${CLOUDFLARE_API_TOKEN:+set}" \
  "${WRANGLER_OUTPUT_FILE_PATH:-}" >> "${FAKE_WRANGLER_LOG}"
[ -z "${FAKE_WRANGLER_FAIL:-}" ] || { echo "wrangler falso: fallo" >&2; exit 1; }
if [ -n "${WRANGLER_OUTPUT_FILE_PATH:-}" ] && [ -s "${FAKE_WRANGLER_OUT}" ]; then
  mkdir -p "$(dirname "${WRANGLER_OUTPUT_FILE_PATH}")"
  echo '{"type":"wrangler-session","version":1}' >> "${WRANGLER_OUTPUT_FILE_PATH}"
  cat "${FAKE_WRANGLER_OUT}" >> "${WRANGLER_OUTPUT_FILE_PATH}"
fi
echo "✨ Deployment complete!"
EOF
chmod +x "${TMP}/bin/curl" "${TMP}/bin/wrangler"
CF=https://cf.test/client/v4
ACC=ab4f90b9b70538731273c3fdf9548f07
PROJECTS_URL="${CF}/accounts/${ACC}/pages/projects"
PROJ_URL="${PROJECTS_URL}/piedrazul"
DOMAIN_URL="${PROJ_URL}/domains/piedrazul.org"
LIST_URL="${PROJ_URL}/deployments?env=production&per_page=25"
PROD_URL=https://piedrazul.pages.dev
PREV=0a000000-0000-4000-8000-000000000000
OURS=0b1c2d3e-0000-4000-8000-000000000001
OTHER=0c000000-0000-4000-8000-000000000002
SHA_OTHER=$(printf 'f%.0s' {1..40})
dep_url() { echo "https://${1:0:8}.piedrazul.pages.dev"; }
cf_clear() { find "${FAKE_CF_DIR:?}" -maxdepth 1 -name "$(key "$1").*" -delete; }
# cf_seq <url> <respuesta>...: respuestas sucesivas para esa URL (la última se repite)
cf_seq() {
  local url="$1" n=1
  shift
  cf_clear "${url}"
  for r in "$@"; do printf '%s' "${r}" > "${FAKE_CF_DIR:?}/$(key "${url}").${n}"; n=$((n + 1)); done
}
cf_file() { cf_clear "$1"; cp "$2" "${FAKE_CF_DIR:?}/$(key "$1").1"; }
# project_json <canónico> [origen: direct|null|github-off|github-on|gitlab|other] [rama] [dominios]
project_json() {
  jq -cn --arg c "$1" --arg g "${2:-direct}" --arg b "${3:-main}" --arg other "${SHA_OTHER}" \
    --argjson d "${4:-[\"piedrazul.org\",\"piedrazul.pages.dev\"]}" '
    {success: true, result: ({name: "piedrazul", subdomain: "piedrazul.pages.dev", production_branch: $b, domains: $d,
      canonical_deployment: (if $c == "" then null else {id: $c, deployment_trigger: {metadata: {commit_hash: $other}}} end)}
      + ({direct: {},
          null: {source: null},
          "github-off": {source: {type: "github", config: {production_deployments_enabled: false, preview_deployment_setting: "none"}}},
          "github-on": {source: {type: "github", config: {production_deployments_enabled: true, preview_deployment_setting: "all"}}},
          gitlab: {source: {type: "gitlab", config: {production_deployments_enabled: false}}},
          other: {source: {type: "bitbucket"}}}[$g]))}'
}
# Respuestas de error de la API de Cloudflare
NOT_FOUND=$'@status 404\n{"success":false,"errors":[{"code":8000007,"message":"Project not found. The specified project name does not match any of your existing projects."}],"result":null}'
cf_error() { printf '@status %s\n{"success":false,"errors":[{"code":%s,"message":"x"}],"result":null}' "$1" "$2"; }
projects_list() { # [nombres...]
  jq -cn '$ARGS.positional | {success: true, result: [.[] | {name: .}], result_info: {page: 1, total_pages: 1}}' --args "$@"
}
domain_json() { jq -cn --arg s "$1" '{success: true, result: {name: "piedrazul.org", status: $s}}'; }
# deployment <id> [jq de ajuste]: deployment de producción ad_hoc de este intento, terminado
deployment() {
  jq -cn --arg id "$1" --arg url "$(dep_url "$1")" --arg sha "${SITE_SHA}" --arg m "production-hetzner ${RUN_ID}/${ATTEMPT:-1}" '
    {id: $id, short_id: $id[0:8], url: $url, environment: "production", created_on: "2026-10-16T10:00:00Z",
     latest_stage: {name: "deploy", status: "success"},
     deployment_trigger: {type: "ad_hoc", metadata: {branch: "main", commit_hash: $sha, commit_message: $m, commit_dirty: false}}}' |
    jq -c "${2:-.}"
}
# Build Git de Pages del mismo commit, anterior al nuestro
GIT_SAME_SHA=$(deployment "${OTHER}" '.deployment_trigger = {type: "github:push", metadata: {branch: "main", commit_hash: .deployment_trigger.metadata.commit_hash, commit_message: "feat: x"}} | .created_on = "2026-10-16T09:00:00Z"')
envelope() { jq -cn --argjson r "$1" '{success: true, result: $r}'; }
wrangler_reports() { # <id>... (sin ids = no registra nada)
  : > "${FAKE_WRANGLER_OUT}"
  for id in "$@"; do
    jq -cn --arg id "${id}" --arg url "$(dep_url "${id}")" --arg sha "${SITE_SHA}" '
      {type: "pages-deploy-detailed", version: 1, pages_project: "piedrazul", deployment_id: $id, url: $url,
       environment: "production", production_branch: "main", deployment_trigger: {metadata: {commit_hash: $sha}}}' \
      >> "${FAKE_WRANGLER_OUT}"
  done
}
pages_env() {
  env PATH="${TMP}/bin:${PATH}" CF_API_URL="${CF}" CLOUDFLARE_API_TOKEN=tok-pages-SECRET \
    PAGES_ACCOUNT_ID="${ACC}" PAGES_PROJECT=piedrazul PAGES_BRANCH=main PAGES_DOMAIN=piedrazul.org \
    PAGES_POLL_ATTEMPTS=2 PAGES_POLL_INTERVAL=0 "$@"
}
PAGES_DEF="$(declare -f pages_env); TMP='${TMP}' CF='${CF}' ACC='${ACC}'"

# ── Autoridad única (preflight estricto, tras Terraform) ──
cf_seq "${DOMAIN_URL}" "$(domain_json pending)"
preflight_case() { # <descripción> <pasa|falla> <origen>
  local rc=0
  cf_seq "${PROJ_URL}" "$(project_json "${PREV}" "$3")"
  bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight" > "${TMP}/pf.env" 2> /dev/null || rc=$?
  if { [ "$2" = pasa ] && [ "${rc}" -eq 0 ]; } || { [ "$2" = falla ] && [ "${rc}" -ne 0 ]; }; then
    ok "preflight: $1"
  else
    ko "preflight: $1 (rc=${rc})"
  fi
}
preflight_case "proyecto de subida directa (sin source) → autoridad única" pasa direct
check "preflight: informa origen, producción vigente, alias *.pages.dev y estado del dominio" \
  bash -c "grep -qx project_state=present '${TMP}/pf.env' && grep -qx pages_source=direct_upload '${TMP}/pf.env' && grep -qx 'previous_deployment_id=${PREV}' '${TMP}/pf.env' && grep -qx 'production_url=${PROD_URL}' '${TMP}/pf.env' && grep -qx custom_domain_status=pending '${TMP}/pf.env'"
preflight_case "source: null (subida directa) → autoridad única" pasa null
preflight_case "integración GitHub aunque no publique producción → falla (la arquitectura es sin Git)" falla github-off
preflight_case "integración GitHub con deploys automáticos → falla (dos autoridades)" falla github-on
preflight_case "integración GitLab → falla" falla gitlab
preflight_case "origen no reconocido → falla (desconocido)" falla other
check "preflight: el token va en una cabecera, nunca en la línea de comandos" \
  bash -c "grep -q '|Authorization: Bearer tok-pages-SECRET|' '${FAKE_CF_LOG}' && ! cut -d'|' -f1,3 '${FAKE_CF_LOG}' | grep -q SECRET"
cf_seq "${PROJ_URL}" "$(project_json "${PREV}" direct develop)"
fails "preflight: rama de producción distinta de main" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_seq "${PROJ_URL}" "$(project_json "${PREV}" direct main '["otro.org"]')"
fails "preflight: el proyecto no tiene asociado el dominio declarado" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_seq "${PROJ_URL}" "$(project_json "${PREV}" | jq -c '.result.name = "otro"')"
fails "preflight: el proyecto leído no es el declarado" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_seq "${PROJ_URL}" "$(project_json "${PREV}" | jq -c '.result.subdomain = "piedrazul.example.com"')"
fails "preflight: subdominio que no es *.pages.dev" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_seq "${PROJ_URL}" "${NOT_FOUND}"
cf_seq "${PROJECTS_URL}" "$(projects_list)"
fails "preflight: proyecto inexistente (tras Terraform debe existir)" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_seq "${PROJ_URL}" "$(cf_error 403 10000)"
fails "preflight: token sin permiso (HTTP 403)" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_seq "${PROJ_URL}" '{"success":true,"result":[]}'
fails "preflight: respuesta malformada (result no es un objeto)" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
cf_clear "${PROJ_URL}"
fails "preflight: sin respuesta de la API" bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight"
fails "preflight: sin token" bash -c "${PAGES_DEF}; pages_env env -u CLOUDFLARE_API_TOKEN '${PAGES}' preflight"
# Estado del dominio propio: informativo, nunca bloquea ni se toma por activación
cf_seq "${PROJ_URL}" "$(project_json "")"
cf_seq "${DOMAIN_URL}" "$(cf_error 500 1)"
check "preflight: dominio ilegible → custom_domain_status=unreadable, sin fallar; proyecto sin publicaciones → previous vacío" \
  bash -c "${PAGES_DEF}; out=\$(pages_env '${PAGES}' preflight) && grep -qx custom_domain_status=unreadable <<< \"\$out\" && grep -qx previous_deployment_id= <<< \"\$out\""
cf_seq "${DOMAIN_URL}" "$(domain_json active)"
check "preflight: dominio activo se informa como active" \
  bash -c "${PAGES_DEF}; pages_env '${PAGES}' preflight | grep -qx custom_domain_status=active"
cf_seq "${DOMAIN_URL}" "$(domain_json pending)"

# ── Sondeo antes de Terraform (probe) ──
probe_case() { # <descripción> <pasa|falla> <PAGES_ALLOW_PROVISIONING> [estado esperado]
  local rc=0
  bash -c "${PAGES_DEF}; pages_env PAGES_ALLOW_PROVISIONING='$3' '${PAGES}' probe" > "${TMP}/probe.env" 2> /dev/null || rc=$?
  if { [ "$2" = pasa ] && [ "${rc}" -eq 0 ] && { [ -z "${4:-}" ] || grep -qx "project_state=$4" "${TMP}/probe.env"; }; } ||
     { [ "$2" = falla ] && [ "${rc}" -ne 0 ]; }; then
    ok "probe: $1"
  else
    ko "probe: $1 (rc=${rc}; $(paste -sd' ' "${TMP}/probe.env"))"
  fi
}
cf_seq "${PROJ_URL}" "${NOT_FOUND}"
cf_seq "${PROJECTS_URL}" "$(projects_list otro-proyecto)"
probe_case "ausente con Terraform en este run → project_state=absent (instalación nueva)" pasa true absent
probe_case "ausente sin Terraform (p. ej. solo frontend) → falla" falla false
probe_case "PAGES_ALLOW_PROVISIONING ausente o inválido → falla" falla ""
cf_seq "${PROJECTS_URL}" "$(projects_list piedrazul)"
probe_case "404 pero el proyecto aparece en la lista de la cuenta → falla (contradicción)" falla true
cf_seq "${PROJECTS_URL}" "$(cf_error 403 10000)"
probe_case "404 y la cuenta no lista sus proyectos (token/cuenta) → falla, nunca 'ausente'" falla true
cf_seq "${PROJECTS_URL}" "$(projects_list | jq -c '.result_info.total_pages = 2')"
probe_case "404 con más de una página de proyectos (ausencia no verificable) → falla" falla true
cf_seq "${PROJECTS_URL}" "$(projects_list)"
cf_seq "${PROJ_URL}" "$(cf_error 404 7003)"
probe_case "404 sin el error de proyecto inexistente (cuenta o ruta incorrectas) → falla" falla true
cf_seq "${PROJ_URL}" "$(cf_error 401 10000)"
probe_case "token inválido (HTTP 401) → falla" falla true
cf_seq "${PROJ_URL}" "$(cf_error 403 10000)"
probe_case "cuenta equivocada o token sin permiso (HTTP 403) → falla" falla true
cf_seq "${PROJ_URL}" "$(cf_error 500 8000000)"
probe_case "error de la API (HTTP 500) → falla" falla true
cf_seq "${PROJ_URL}" '@status 404
<html>not json</html>'
probe_case "404 con cuerpo ilegible → falla" falla true
cf_clear "${PROJ_URL}"
probe_case "sin respuesta (red) → falla" falla true
cf_seq "${PROJ_URL}" "$(project_json "${PREV}")"
probe_case "presente y correcto → project_state=present con las comprobaciones estrictas" pasa true present
cf_seq "${PROJ_URL}" "$(project_json "${PREV}" github-on)"
probe_case "presente con integración Git → falla antes de cualquier mutación, aunque haya Terraform" falla true
# Proyecto existente pero incompleto (sin el dominio asociado): p. ej. un Apply anterior creó
# el proyecto y falló antes del CNAME o de la asociación. La identidad se exige siempre.
INCOMPLETE="$(project_json "" direct main '["piedrazul.pages.dev"]')"
cf_seq "${PROJ_URL}" "${INCOMPLETE}"
probe_case "incompleto (sin dominio) con Terraform en este run → project_state=incomplete (Terraform lo completa)" pasa true incomplete
check "probe: incompleto no informa salidas de publicación (la publicación exige el preflight estricto)" \
  bash -c "! grep -q '^previous_deployment_id=\|^production_url=' '${TMP}/probe.env'"
probe_case "incompleto sin Terraform (solo frontend o deploy) → falla: nunca un destino incompleto" falla false
cf_seq "${PROJ_URL}" "$(project_json "" direct main '["otro.org"]')"
probe_case "con otro dominio asociado (no el declarado) y sin Terraform → falla" falla false
cf_seq "${PROJ_URL}" "$(project_json "" github-off main '["piedrazul.pages.dev"]')"
probe_case "incompleto con integración Git → falla aunque haya Terraform (identidad primero)" falla true
cf_seq "${PROJ_URL}" "$(project_json "" direct develop '["piedrazul.pages.dev"]')"
probe_case "incompleto con otra rama de producción → falla aunque haya Terraform" falla true
cf_seq "${PROJ_URL}" "$(project_json "" | jq -c '.result.name = "otro" | .result.domains = []')"
probe_case "incompleto con otro nombre → falla aunque haya Terraform" falla true
cf_seq "${PROJ_URL}" "$(project_json "" | jq -c '.result.subdomain = "x.example.com" | .result.domains = []')"
probe_case "incompleto con subdominio que no es *.pages.dev → falla aunque haya Terraform" falla true
cf_seq "${PROJ_URL}" "$(project_json "" | jq -c '.result.domains = "piedrazul.org"')"
probe_case "domains malformado (no es una lista) sin Terraform → falla" falla false

# Apply parcial y reintento: 1) el Apply anterior creó el proyecto y falló antes del
# dominio; 2) el reintento lo sondea como incompleto; 3) el preflight tras el Apply exige el
# proyecto completo (si el Apply del reintento tampoco asoció el dominio, falla antes de tocar
# el servidor y de publicar)
cf_seq "${PROJ_URL}" "${INCOMPLETE}" "$(project_json "")"
check "reintento tras Apply parcial: sondeo incompleto → Apply completa → preflight estricto pasa (primera publicación)" \
  bash -c "${PAGES_DEF}; pages_env PAGES_ALLOW_PROVISIONING=true '${PAGES}' probe | grep -qx project_state=incomplete && out=\$(pages_env '${PAGES}' preflight) && grep -qx project_state=present <<< \"\$out\" && grep -qx previous_deployment_id= <<< \"\$out\""
cf_seq "${PROJ_URL}" "${INCOMPLETE}" "${INCOMPLETE}"
fails "reintento tras Apply parcial: si el dominio sigue sin asociar, el preflight estricto falla" \
  bash -c "${PAGES_DEF}; pages_env PAGES_ALLOW_PROVISIONING=true '${PAGES}' probe > /dev/null && pages_env '${PAGES}' preflight"

# ── Publicación ligada al deployment de esta invocación ──
# El sitio validado (fetch ya demostró que el extraído es idéntico)
SITE="${TMP}/site"
# publish_setup: estado de una publicación correcta; cada caso altera una pieza
publish_setup() {
  : > "${FAKE_WRANGLER_LOG}"
  wrangler_reports "${OURS}"
  cf_seq "${PROJ_URL}" "$(project_json "${PREV}")" "$(project_json "${OURS}")" "$(project_json "${OURS}")"
  cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}")")"
  cf_seq "${LIST_URL}" "$(envelope "[$(deployment "${OURS}"), ${GIT_SAME_SHA}]")"
  for base in "$(dep_url "${OURS}")" "${PROD_URL}"; do
    cf_file "${base}/main-EF56.js" "${SITE}/main-EF56.js"
    cf_file "${base}/" "${SITE}/index.html"
  done
}
publish() {
  pages_env GITHUB_SHA="${SITE_SHA}" GITHUB_RUN_ID="${RUN_ID}" GITHUB_RUN_ATTEMPT="${ATTEMPT:-1}" ENTRY_ASSET=main-EF56.js \
    PREVIOUS_DEPLOYMENT_ID="${PREV?}" PRODUCTION_URL="${PROD_URL}" WRANGLER_BIN="${TMP}/bin/wrangler" "${PAGES}" publish "${SITE}"
}
PUBLISH_DEF="${PAGES_DEF}; $(declare -f publish); SITE='${SITE}' SITE_SHA='${SITE_SHA}' RUN_ID='${RUN_ID}' PAGES='${PAGES}' PREV='${PREV}' PROD_URL='${PROD_URL}'"
publish_ok() {
  if bash -c "${PUBLISH_DEF}; ATTEMPT='${ATTEMPT:-1}'; publish" > "${TMP}/pub.env" 2> "${TMP}/pub.err"; then
    ok "publish: $1"
  else
    ko "publish: $1 ($(tail -1 "${TMP}/pub.err"))"
  fi
}
publish_fails() {
  if bash -c "${PUBLISH_DEF}; ATTEMPT='${ATTEMPT:-1}'; publish" > /dev/null 2>&1; then
    ko "publish rechaza: $1 (no falló)"
  else
    ok "publish rechaza: $1"
  fi
}

publish_setup
publish_ok "id de la salida de wrangler, confirmado por la API, vigente y sirviendo el artefacto (con un build Git anterior del mismo commit)"
check "publish: informa el deployment de esta invocación" \
  bash -c "grep -qx 'deployment_id=${OURS}' '${TMP}/pub.env' && grep -qx 'deployment_url=$(dep_url "${OURS}")' '${TMP}/pub.env'"
check "publish: wrangler recibe proyecto, rama main, commit, mensaje del intento y archivo de salida; token por entorno" \
  bash -c "grep -q '^pages deploy ${SITE} --project-name=piedrazul --branch=main --commit-hash=${SITE_SHA} --commit-message=production-hetzner ${RUN_ID}/1 --commit-dirty=false|${ACC}|token=set|out=/' '${FAKE_WRANGLER_LOG}' && ! grep -q SECRET '${FAKE_WRANGLER_LOG}'"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.latest_stage = {name: "deploy", status: "active"}')")" "$(envelope "$(deployment "${OURS}")")"
publish_ok "espera a que el deployment de esta invocación termine"
# Re-run: el intento 1 ya publicó el mismo commit (es la producción anterior)
ATTEMPT=2
publish_setup
cf_seq "${LIST_URL}" "$(envelope "[$(deployment "${OURS}"), $(ATTEMPT=1 deployment "${PREV}" '.created_on = "2026-10-16T09:30:00Z"'), ${GIT_SAME_SHA}]")"
publish_ok "re-run (intento 2) con el intento 1 del mismo commit ya publicado: se identifica por su propio id y mensaje"
ATTEMPT=1

# Primera publicación: proyecto recién creado, sin producción anterior
publish_setup
cf_seq "${PROJ_URL}" "$(project_json "")" "$(project_json "${OURS}")" "$(project_json "${OURS}")"
cf_seq "${LIST_URL}" "$(envelope "[$(deployment "${OURS}")]")"
if bash -c "${PUBLISH_DEF}; ATTEMPT=1; PREV=''; publish" > "${TMP}/pub-first.env" 2> "${TMP}/pub-first.err" &&
   grep -qx "deployment_id=${OURS}" "${TMP}/pub-first.env" && grep -qx custom_domain_status=pending "${TMP}/pub-first.env"; then
  ok "publish: primera publicación en un proyecto sin producción (PREVIOUS_DEPLOYMENT_ID vacío) e informa el dominio sin bloquear"
else
  ko "publish: primera publicación ($(tail -1 "${TMP}/pub-first.err"))"
fi
publish_setup
cf_seq "${PROJ_URL}" "$(project_json "${OTHER}")"
if bash -c "${PUBLISH_DEF}; ATTEMPT=1; PREV=''; publish" > /dev/null 2>&1; then
  ko "publish rechaza: proyecto que se esperaba vacío ya tiene otra producción (no falló)"
else
  ok "publish rechaza: proyecto que se esperaba vacío ya tiene otra producción"
fi

# Autoridad única y estado previo, antes de wrangler
publish_setup
cf_seq "${PROJ_URL}" "$(project_json "${PREV}" github-on)"
publish_fails "el proyecto pasó a tener integración Git durante el run"
check "publish: con dos autoridades no se invoca wrangler" test ! -s "${FAKE_WRANGLER_LOG}"
publish_setup
cf_seq "${PROJ_URL}" "$(project_json "${OTHER}")"
publish_fails "otro deployment pasó a producción desde el preflight"
check "publish: con la producción cambiada no se invoca wrangler" test ! -s "${FAKE_WRANGLER_LOG}"
publish_setup
export FAKE_WRANGLER_FAIL=1
publish_fails "wrangler falla"
unset FAKE_WRANGLER_FAIL

# Identidad del deployment
publish_setup; wrangler_reports
publish_fails "wrangler no registra el deployment que creó"
publish_setup; wrangler_reports "${OURS}" "${OTHER}"
publish_fails "wrangler registra dos deployments en una invocación"
publish_setup; wrangler_reports "${PREV}"
cf_seq "${PROJ_URL}/deployments/${PREV}" "$(envelope "$(deployment "${PREV}")")"
publish_fails "wrangler informa el deployment anterior (aunque la API lo confirme)"
publish_setup
sed -i 's/"pages_project":"piedrazul"/"pages_project":"otro"/' "${FAKE_WRANGLER_OUT}"
publish_fails "la salida de wrangler es de otro proyecto"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.deployment_trigger.type = "github:push" | .deployment_trigger.metadata.commit_message = "feat: x"')")"
publish_fails "el id informado es un build Git del mismo commit, no esta publicación"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.deployment_trigger.type = "github:push"')")"
publish_fails "el id informado no es una subida directa (ad_hoc) aunque el mensaje coincida"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.deployment_trigger.metadata.commit_message = "production-hetzner 1/1"')")"
publish_fails "el id informado es una publicación de otro run del mismo commit"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.environment = "preview"')")"
publish_fails "el deployment de esta invocación quedó como preview"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.latest_stage.status = "failure"')")"
cf_seq "${LIST_URL}" "$(envelope "[${GIT_SAME_SHA}]")"
publish_fails "el deployment de esta invocación falla aunque otro del mismo commit terminó bien"
publish_setup
cf_seq "${PROJ_URL}/deployments/${OURS}" "$(envelope "$(deployment "${OURS}" '.latest_stage.status = "active"')")"
publish_fails "el deployment de esta invocación no termina dentro del plazo"
publish_setup
cf_seq "${LIST_URL}" "$(envelope "[$(deployment "${OURS}"), $(deployment "${OTHER}" '.created_on = "2026-10-16T09:59:00Z"')]")"
publish_fails "dos deployments con el mensaje de este intento (publicación ambigua)"
publish_setup
cf_seq "${LIST_URL}" "$(envelope "[$(deployment "${OTHER}" '.deployment_trigger.type = "github:push" | .deployment_trigger.metadata.commit_message = "feat: x" | .created_on = "2026-10-16T10:05:00Z"'), $(deployment "${OURS}")]")"
publish_fails "un build Git del mismo commit, posterior, reemplazó a esta publicación"

# Vigencia: un deployment ajeno pasa a ser el canónico
publish_setup
cf_seq "${PROJ_URL}" "$(project_json "${PREV}")" "$(project_json "${OTHER}")" "$(project_json "${OURS}")"
publish_fails "un deployment ajeno es el vigente al verificar (aunque luego vuelva a ser el nuestro)"
publish_setup
cf_seq "${PROJ_URL}" "$(project_json "${PREV}")" "$(project_json "${OURS}")" "$(project_json "${OTHER}")"
publish_fails "un deployment ajeno pasa a ser el vigente mientras se verifica el contenido"

# Contenido servido
printf 'otro' > "${TMP}/other.js"
publish_setup
cf_file "$(dep_url "${OURS}")/main-EF56.js" "${TMP}/other.js"
publish_fails "la URL del deployment sirve otros bytes que el artefacto validado"
publish_setup
cf_file "${PROD_URL}/main-EF56.js" "${TMP}/other.js"
publish_fails "el alias de producción sirve otros bytes (otro deployment con el mismo nombre de entrada)"
publish_setup
printf '<html><script src="main-OTRO.js"></script></html>' > "${TMP}/other.html"
cf_file "${PROD_URL}/" "${TMP}/other.html"
publish_fails "el alias de producción sirve otro index.html"

# ── 5. Lugar en el workflow ─────────────────────────────────────────────────
PROD_STEPS="$(yq -r '.jobs.production.steps[].id // ""' "${WORKFLOW}")"
idx() { grep -nx "$1" <<< "${PROD_STEPS}" | cut -d: -f1; }
check "frontend_build: sin environment, solo contents: read y sin secretos" \
  bash -c "[ \"\$(yq '.jobs.frontend_build.environment // \"\"' '${WORKFLOW}')\" = '' ] && [ \"\$(yq -o=json -I=0 '.jobs.frontend_build.permissions' '${WORKFLOW}')\" = '{\"contents\":\"read\"}' ] && ! yq '.jobs.frontend_build' '${WORKFLOW}' | grep -q 'secrets\\.'"
check "frontend_build: mismo build que CI Frontend y que Pages (frontend/.node-version, npm ci, npm run build)" \
  bash -c "yq '.jobs.frontend_build' '${WORKFLOW}' | grep -q 'node-version-file: frontend/.node-version' && yq -r '.jobs.frontend_build.steps[].run // \"\"' '${WORKFLOW}' | grep -qx 'npm run build' && grep -q 'run: npm run build' '${ROOT}/.github/workflows/ci-frontend.yml'"
check "frontend_build: id, nombre y sha256 del artefacto son outputs de job" \
  bash -c "yq -r '.jobs.frontend_build.outputs | keys | .[]' '${WORKFLOW}' | sort | paste -sd, | grep -q 'artifact_id,artifact_name,dist_sha256,entry_asset'"
check "production: nunca construye el frontend (wrangler solo sube el artefacto)" \
  bash -c "! yq -r '.jobs.production.steps[].run // \"\"' '${WORKFLOW}' | grep -qE 'npm run build|ng build' && [ \"\$(grep -c 'pages deploy' '${PAGES}')\" -ge 1 ] && ! yq -r '.jobs[].steps[].run // \"\"' '${WORKFLOW}' | grep -q 'pages deploy'"
check "production: espera a frontend_build y exige su éxito cuando hay frontend" \
  bash -c "yq -r '.jobs.production.needs[]' '${WORKFLOW}' | grep -qx frontend_build && yq -r '.jobs.production.if' '${WORKFLOW}' | grep -q \"needs.classify.outputs.frontend_required != 'true' || needs.frontend_build.result == 'success'\""
check "production: environment y concurrencia sin cambios" \
  test "$(yq -r '.jobs.production | .environment + "/" + .concurrency.group + "/" + (.concurrency."cancel-in-progress" | tostring) + "/" + .concurrency.queue' "${WORKFLOW}")" = "production-hetzner/piedrazul-production-hetzner/false/max"
check "credencial: CLOUDFLARE_PAGES_API_TOKEN solo en production (credenciales, sondeo, preflight, publicación)" \
  bash -c "[ \"\$(yq -r '.jobs | to_entries[] | select(.key != \"production\") | .value' '${WORKFLOW}' | grep -c CLOUDFLARE_PAGES_API_TOKEN)\" = 0 ] && [ \"\$(yq -r '.jobs.production.steps[] | select(.env.CLOUDFLARE_API_TOKEN != null or .env.CLOUDFLARE_PAGES_API_TOKEN != null) | .id' '${WORKFLOW}' | paste -sd,)\" = credentials,pages_probe,pages_preflight,pages_publish ] && ! grep -q 'secrets.CLOUDFLARE_PAGES_API_TOKEN' <<< \"\$(yq '.env' '${WORKFLOW}')\""
check "credencial: Pages nunca usa el token de DNS" \
  bash -c "! yq -r '.jobs.production.steps[] | select(.id == \"pages_probe\" or .id == \"pages_preflight\" or .id == \"pages_publish\") | .env.CLOUDFLARE_API_TOKEN' '${WORKFLOW}' | grep -v -x '\${{ secrets.CLOUDFLARE_PAGES_API_TOKEN }}' | grep -q . && yq -r '.jobs.production.steps[] | select(.id == \"credentials\") | .run' '${WORKFLOW}' | grep -q 'no puede ser el token de DNS'"
check "credencial: el token de Pages nunca llega a Ansible ni al servidor" \
  bash -c "! grep -rq --exclude='*.md' CLOUDFLARE_PAGES_API_TOKEN '${ROOT}/infra/ansible' '${ROOT}/infra/compose' && ! yq -r '.jobs.production.steps[] | select(.run // \"\" | test(\"(^|[[:space:]])ansible(-playbook)?[[:space:]]\")) | .env // {} | keys[]' '${WORKFLOW}' | grep -qE '^(CLOUDFLARE_API_TOKEN|CLOUDFLARE_PAGES_API_TOKEN)\$'"
check "credencial: alcance declarado solo en production-hetzner (auditoría de confianza)" \
  grep -q '^  \[CLOUDFLARE_PAGES_API_TOKEN\]="production-hetzner"$' "${ROOT}/.github/scripts/audit-github-trust.sh"
check "orden: destino, artefacto, wrangler y sondeo de Pages antes de cualquier mutación (frescura/plan/Apply/servidor)" \
  bash -c "[ $(idx pages_probe) -gt $(idx pages_tooling) ] && [ $(idx pages_tooling) -gt $(idx pages_artifact) ] && [ $(idx pages_artifact) -gt $(idx pages_target) ] && for s in hcp_workspace hcp_idle fresh_apply hcp_upload hcp_run fresh_deploy converge deploy; do [ $(idx pages_probe) -lt \$(grep -nx \"\$s\" <<< \"\$1\" | cut -d: -f1) ] || exit 1; done" _ "${PROD_STEPS}"
check "sondeo: un proyecto ausente o incompleto solo se admite cuando este run aplica Terraform" \
  test "$(yq -r '.jobs.production.steps[] | select(.id == "pages_probe") | .env.PAGES_ALLOW_PROVISIONING' "${WORKFLOW}")" = "\${{ env.TERRAFORM_REQUIRED == 'true' }}"
check "sondeo: ausente o incompleto exige que el plan observe la creación de proyecto y dominio (o no se aplica)" \
  bash -c "w=\$(yq -r '.jobs.production.steps[] | select(.id == \"hcp_plan\") | .env.PLAN_WATCH' '${WORKFLOW}'); grep -q 'pages_project=module.pages.cloudflare_pages_project.main' <<< \"\$w\" && grep -q 'pages_domain=module.pages.cloudflare_pages_domain.main' <<< \"\$w\" && yq -r '.jobs.production.steps[] | select(.id == \"hcp_plan\") | .run' '${WORKFLOW}' | grep -q 'incomplete) need=\"pages_domain\"' && [ $(idx hcp_plan) -lt $(idx hcp_confirm) ]"
check "orden: preflight estricto de Pages después de Terraform (plan, confirmación, estado, outputs) y antes del servidor y de wrangler" \
  bash -c "for s in hcp_plan hcp_confirm hcp_state tf_outputs; do [ $(idx pages_preflight) -gt \$(grep -nx \"\$s\" <<< \"\$1\" | cut -d: -f1) ] || exit 1; done; for s in access readiness host_state fresh_deploy converge deploy record pages_publish; do [ $(idx pages_preflight) -lt \$(grep -nx \"\$s\" <<< \"\$1\" | cut -d: -f1) ] || exit 1; done" _ "${PROD_STEPS}"
check "orden: la publicación va después de Terraform, el deploy, la identidad, el health y el registro" \
  bash -c "for s in hcp_confirm hcp_state converge deploy running_identity container_health public_health record; do [ $(idx pages_publish) -gt \$(grep -nx \"\$s\" <<< \"\$1\" | cut -d: -f1) ] || exit 1; done" _ "${PROD_STEPS}"
check "frescura: solo frontend → verify-main-tip.sh justo antes de publicar" \
  bash -c "[ $(idx fresh_frontend) -eq $(( $(idx pages_publish) - 1 )) ] && yq -r '.jobs.production.steps[] | select(.id == \"fresh_frontend\") | .if' '${WORKFLOW}' | grep -q \"env.TERRAFORM_REQUIRED != 'true' && env.DEPLOY_REQUIRED != 'true'\" && yq -r '.jobs.production.steps[] | select(.id == \"fresh_frontend\") | .run' '${WORKFLOW}' | grep -q verify-main-tip.sh"
check "frescura previa sin cambios: Apply y servidor siguen comprobándola (sin regresión)" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"fresh_apply\") | .if' '${WORKFLOW}' | grep -qx \"env.TERRAFORM_REQUIRED == 'true'\" && yq -r '.jobs.production.steps[] | select(.id == \"fresh_deploy\") | .if' '${WORKFLOW}' | grep -qx \"env.DEPLOY_REQUIRED == 'true' && env.TERRAFORM_REQUIRED != 'true'\""
check "autoridad única: sondeo y preflight fallan (sin continue-on-error ni advertencia que deje seguir) antes de mutar el servidor" \
  bash -c "for s in pages_probe pages_preflight; do [ \"\$(yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .[\\\"continue-on-error\\\"] // false\" '${WORKFLOW}')\" = false ] && ! yq -r \".jobs.production.steps[] | select(.id == \\\"\$s\\\") | .run\" '${WORKFLOW}' | grep -q '::warning' || exit 1; done && [ $(idx pages_preflight) -lt $(idx fresh_deploy) ] && [ $(idx pages_preflight) -lt $(idx converge) ]"
check "identidad: publish recibe del preflight la producción anterior y el alias de producción" \
  bash -c "yq -r '.jobs.production.steps[] | select(.id == \"pages_publish\") | .env.PREVIOUS_DEPLOYMENT_ID + \" \" + .env.PRODUCTION_URL' '${WORKFLOW}' | grep -qx '\${{ steps.pages_preflight.outputs.previous_deployment_id }} \${{ steps.pages_preflight.outputs.production_url }}'"
check "publicación: solo si todo lo anterior pasó (sin always/failure) y con el preflight estricto en success" \
  test "$(yq -r '.jobs.production.steps[] | select(.id == "pages_publish") | .if' "${WORKFLOW}")" = "env.FRONTEND_REQUIRED == 'true' && steps.pages_preflight.outcome == 'success'"
check "artefacto: production lo descarga con frontend-artifact.sh (verificado), no con download-artifact" \
  bash -c "! grep -q 'actions/download-artifact' '${WORKFLOW}' && yq -r '.jobs.production.steps[] | select(.id == \"pages_artifact\") | .run' '${WORKFLOW}' | grep -q 'frontend-artifact.sh fetch' && yq -r '.jobs.production.steps[] | select(.id == \"pages_artifact\") | .env.EXPECTED_ARTIFACT_ID' '${WORKFLOW}' | grep -q 'needs.frontend_build.outputs.artifact_id'"
check "wrangler: versión exacta fijada, lockfile con integridad, instalado sin scripts" \
  bash -c "v=\$(jq -r '.dependencies.wrangler' '${ROOT}/.github/pages-publish/package.json'); [[ \"\$v\" =~ ^[0-9]+\\.[0-9]+\\.[0-9]+\$ ]] && jq -e --arg v \"\$v\" '.packages[\"node_modules/wrangler\"] | .version == \$v and (.integrity | startswith(\"sha512-\"))' '${ROOT}/.github/pages-publish/package-lock.json' && yq -r '.jobs.production.steps[] | select(.id == \"pages_tooling\") | .run' '${WORKFLOW}' | grep -q 'npm ci --ignore-scripts'"
check "conclude: con frontend requerido, solo es success si frontend_build y production lo son" \
  bash -c "yq -r '.jobs.conclude.needs[]' '${WORKFLOW}' | grep -qx frontend_build && yq -r '.jobs.conclude.steps[0].run' '${WORKFLOW}' | grep -q 'FRONTEND_REQUIRED}\" = \"true\" \\]; then' && yq -r '.jobs.conclude.steps[0].run' '${WORKFLOW}' | grep -q '\\[ \"\${DEPLOY_REQUIRED}\" = \"true\" \\] || \\[ \"\${FRONTEND_REQUIRED}\" = \"true\" \\]'"

# conclude ejecutado: la conclusión es la base del rango procesado
CONCLUDE_RUN="$(yq -r '.jobs.conclude.steps[0].run' "${WORKFLOW}")"
conclude() { # <tf> <deploy> <frontend> <frontend_build> <production>
  env -i PATH="${PATH}" CLASSIFY=success SELECT=success TERRAFORM_REQUIRED="$1" DEPLOY_REQUIRED="$2" \
    FRONTEND_REQUIRED="$3" FRONTEND_BUILD="$4" PRODUCTION="$5" bash -c "${CONCLUDE_RUN}" > /dev/null 2>&1
}
CONCLUDE_DEF="$(declare -f conclude); CONCLUDE_RUN=\$1"
check "conclude: solo frontend publicado → success (el rango avanza)" bash -c "${CONCLUDE_DEF}; conclude false false true success success" _ "${CONCLUDE_RUN}"
fails "conclude: deploy bien pero Pages falló (production failure) → el rango no avanza" bash -c "${CONCLUDE_DEF}; conclude false true true success failure" _ "${CONCLUDE_RUN}"
fails "conclude: build del frontend fallido → production no corre y el rango no avanza" bash -c "${CONCLUDE_DEF}; conclude false true true failure skipped" _ "${CONCLUDE_RUN}"
fails "conclude: frontend requerido y production cancelado → el rango no avanza" bash -c "${CONCLUDE_DEF}; conclude false false true success cancelled" _ "${CONCLUDE_RUN}"
check "conclude: no-op limpio sin frontend (sin regresión)" bash -c "${CONCLUDE_DEF}; conclude false false false skipped skipped" _ "${CONCLUDE_RUN}"
check "conclude: solo deploy sin frontend (sin regresión)" bash -c "${CONCLUDE_DEF}; conclude false true false skipped success" _ "${CONCLUDE_RUN}"

echo
if [ "${FAILS}" -gt 0 ]; then
  echo "pages-release-check: ${FAILS} fallo(s)"
  exit 1
fi
echo "pages-release-check: todo PASS"
