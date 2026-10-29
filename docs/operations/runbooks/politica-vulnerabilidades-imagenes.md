# Política de vulnerabilidades de imágenes

## Propósito

Qué decide la política de vulnerabilidades antes de que producción aplique una imagen, cómo se
acepta un riesgo CRITICAL concreto (disposición), cómo se opera ante una caída del escáner
(break-glass) y en qué se confía para cada cosa.

Código: `.github/scripts/vuln-policy.sh` (decisión), `approval-provenance.sh` (aprobación
humana), `historical-scan-evidence.sh` (evidencia histórica), `break-glass-record.sh`
(auditoría). Pruebas: `infra/tests/vuln-policy-check.sh`. Cadena de producción:
[`ANSIBLE.md`](../../../infra/ansible/ANSIBLE.md#escaneo-y-política-de-vulnerabilidades-antes-de-mutar).

## Decisión

Para cada imagen que el run aplica (backend, postgres, keycloak, traefik), sobre su manifiesto
`linux/amd64` exacto:

| Evidencia | Veredicto |
|---|---|
| escaneo fresco válido sin CRITICAL | `authorized` |
| escaneo fresco válido; cada CRITICAL con disposición válida | `authorized` |
| algún CRITICAL sin disposición válida | **BLOCK** (`undisposed_critical`) |
| HIGH | solo se informa (resumen del job) |
| `operational_failure` (registry, DB o Trivy) | **BLOCK** (`operational_failure`); nunca equivale a cero hallazgos |
| `operational_failure` + break-glass válido + evidencia histórica verificada | `break_glass` (no `authorized`) |
| evidencia ausente, malformada, de otra referencia o plataforma, conteos incoherentes, fecha futura, DB de más de 48 h al escanear | **BLOCK** (`evidence_invalid`) |
| escaneo de más de 24 h al autorizar | **BLOCK** (`evidence_stale`) |
| registro de disposición o de break-glass inválido en el commit | **BLOCK** (todo el run) |

Un solo BLOCK bloquea el run. El código lee `trivy.json` hallazgo por hallazgo; el resumen
(`summary.json`) solo se acepta si sus conteos coinciden con el reporte.

**Cero hallazgos no prueba ausencia de vulnerabilidades.** El resumen muestra la cobertura de
cada escaneo (SO detectado, clases de paquetes). Limitación conocida: en Keycloak (Quarkus)
Trivy detecta un solo archivo de lenguaje; sus dependencias Java quedan prácticamente sin
evaluar.

## Dónde se evalúa

1. `image_policy` (antes del gate, solo lectura): las identidades conocidas antes del gate que
   el run aplica (build, rollback, keycloak, traefik). Un bloqueo aquí no pide la aprobación
   del environment y no llega al Apply.
2. `production`, paso **Autorizar imágenes**: evaluación autoritativa de todas las imágenes
   aplicadas, incluido el reuso (que solo se conoce y escanea tras el gate). Va después del
   escaneo del reuso y antes de la frescura, converge y deploy. Un bloqueo aquí ocurre después
   de la aprobación del environment (y del Apply, si lo hubo); queda en el resumen y en una
   anotación de error.

Converge y deploy exigen además la decisión en su condición, y un paso intermedio falla el job
si la decisión falta. Un run bloqueado no registra último éxito y su conclusión no es
`success`: la base del rango de push no avanza.

Rollback y reuso no tienen excepciones implícitas: se escanea la identidad exacta en ese run y
se evalúa igual que un build. Haber estado desplegada antes no autoriza una imagen.

### Escaneo vencido

Si pasan más de 24 h entre el escaneo previo al gate y la autorización (por ejemplo, una
aprobación del environment demorada), el run bloquea con `evidence_stale`. "Re-run failed jobs"
no vuelve a escanear: hay que usar **Re-run all jobs** (si el commit sigue siendo la punta de
`main`) o lanzar un run nuevo. Si un re-run deja en el mismo run dos artifacts de escaneo con el
mismo nombre, la evidencia es ambigua y el run bloquea: hace falta un run nuevo.

### Evidencia del run: origen e integridad

La evidencia de escaneo pasa entre jobs como artifact. No se descarga con
`actions/download-artifact`: la versión fijada (v4.3.0) solo **advierte** si el digest no
coincide y sigue, y entre artifacts del mismo nombre elige el más reciente sin avisar. En su
lugar, `current-scan-evidence.sh` (en `image_policy` y en `production`) lista los artifacts de
**este** run por la API de Actions y, para cada `image-scan-selected-*`, exige que la API lo
atribuya a este run y repositorio (no a un fork), que no esté vencido, que su nombre no se
repita, que GitHub haya registrado un digest y que el zip descargado tenga exactamente ese
digest y los tres archivos esperados. Cualquier diferencia falla el paso y no hay
autorización. Lo que garantiza: los bytes evaluados son los que subió un job de este run (el
digest lo calcula quien sube y GitHub lo guarda con el artifact). No es una firma: confía en el
código del workflow en `main` y en la acción de subida fijada por SHA.

## Disposición de un CRITICAL

Una disposición es la aceptación humana explícita de un CRITICAL concreto. Va en
`security/vulnerability-dispositions/<id>.json`, un archivo por disposición:

```json
{
  "schema": "piedrazul.vulnerability-disposition/v1",
  "id": "VD-2026-001",
  "surface": "postgres",
  "vulnerability": "CVE-2025-68121",
  "package": "stdlib",
  "scope": { "image_refs": ["ghcr.io/jm-ortega/piedrazul-agendamiento-citas/postgres@sha256:<linux/amd64>"] },
  "justification": "Por qué se acepta (al menos 20 caracteres).",
  "residual_risk": "Qué riesgo queda y qué lo limita (al menos 20 caracteres).",
  "approver": "<login de GitHub>",
  "effective": "2026-10-16T00:00:00Z",
  "expires": "2026-11-15T00:00:00Z"
}
```

- Exactamente esas claves. Una clave extra (por ejemplo `"approved": true`) invalida el registro.
- `package` es el `PkgName` de Trivy.
- `scope.image_refs`: lista de manifiestos exactos `<repo>@sha256:<linux/amd64>`, el mismo
  invariante de identidad que autoriza el despliegue. No hay alcance por capa ni por tag: un
  digest de capa autorizaría cualquier imagen que la contenga, con otras capas arbitrarias.
- Consecuencia: una imagen propia **recién construida** con un CRITICAL no puede tener
  disposición en el mismo run (su digest se conoce al construirla) y hoy el workflow no tiene
  cómo desplegar después ese digest exacto de postgres (solo el backend tiene `image_tag`).
  Una disposición sí cubre el reuso de un digest ya desplegado y el rollback del backend.
- Vigencia de 90 días como máximo; usar la más corta justificable. No vale antes de `effective`
  ni desde `expires`.

**Qué la hace válida.** El registro no prueba nada sobre sí mismo. `approver` solo nombra a
quién buscar, y `approval-provenance.sh` verifica en GitHub y en la historia de git que el
commit de `main` (primer padre) que dejó el archivo con su contenido actual es el merge de
exactamente un PR fusionado hacia `main`, y que `approver` figura en
`vars.VULNERABILITY_RISK_APPROVERS` con su login **y su id numérico**. Después exige **una** de
dos vías de aceptación; cuál se evalúa lo decide el autor real del PR, no el registro:

| | `independent_review` | `self_merge` |
|---|---|---|
| cuándo | el autor del PR **no** es `approver` | el autor del PR **es** `approver` |
| quién acepta | `approver`, con su último estado de revisión `APPROVED` (usuario humano, distinto del autor) | `approver`, que abrió el PR **y lo fusionó él mismo**: `user` y `merged_by` de GitHub son la misma cuenta humana (login e id) |
| momento | revisión enviada **a más tardar en el momento del merge** (`submitted_at` ≤ `merged_at`); una aprobación posterior no acepta retroactivamente | el merge mismo |
| contenido | el commit aprobado tiene **el mismo blob** del archivo evaluado | merge commit de dos padres; el segundo es la cabeza del PR y trae **el mismo blob** del archivo evaluado (no cambió al fusionar) |
| qué más puede traer el PR | cualquier cosa (la aprobación cubre el blob del registro, no el resto) | **solo** registros de seguridad: el cambio que el merge introdujo en `main` agrega, modifica o borra únicamente archivos regulares `security/vulnerability-dispositions/VD-AAAA-NNN.json` o `security/break-glass/BG-AAAA-NNN.json`; código, workflows, scripts o documentación (incluidos los README de `security/`) lo invalidan |
| revisiones y comentarios | `COMMENTED` no cuenta; `CHANGES_REQUESTED` o `DISMISSED` posteriores la anulan | no se consultan |

Fechas ausentes, mal formadas o contradictorias, un PR sin fusionar, hacia otra rama, con otro
merge commit, fusionado por otra cuenta o por un Bot, un squash o rebase (un solo padre) en la
vía `self_merge`, o cualquier dato de GitHub que no coincide con git: falla cerrado y la
disposición no es efectiva. El resultado de la política y el resumen del job muestran la vía
(`method`). Un PR posterior que toque el archivo necesita una aceptación válida propia: la
anterior no se hereda.

**Por qué no basta un merge ordinario ni la aprobación del environment.** Un merge común de un
PR de código no acepta riesgo: si el autor no es el aprobador, hace falta su revisión `APPROVED`
previa al merge; si lo es, el PR no puede traer nada más que registros. El texto de una revisión
`COMMENTED` o de un comentario puede editarse después y nunca autoriza. La aprobación del
environment `production-hetzner` autoriza un run, no un riesgo: no reemplaza ninguna de las dos
vías.

`Protect main` solo admite merge commits, la forma que exige `self_merge`. La vía
`independent_review` también reconoce un squash (un commit de un solo padre asociado al PR);
un rebase merge no deja un merge identificable y falla cerrado en ambas.

**Autoridad.** La variable de repositorio `VULNERABILITY_RISK_APPROVERS` (Settings → Secrets
and variables → Actions → Variables) lista quién puede aceptar riesgo, como `login:id`
separados por espacios o comas. El id sale de `gh api users/<login> --jq .id`. Vacía o
ausente: ninguna disposición ni break-glass es efectiva, y todo CRITICAL bloquea.

La lista es una **entrada de la política, no una frontera de seguridad independiente**. Según
el modelo de acceso de GitHub, la pueden cambiar no solo los administradores sino también los
colaboradores o usuarios con el acceso de escritura/configuración que permite gestionar
variables del repositorio. Quien tenga ese permiso puede agregarse como aprobador y aceptar
riesgo por sí mismo (por ejemplo, con `self_merge`). Lo que el código sí impide: que una
variable del environment cambie la autoridad en silencio (huella de aprobadores) y que una
cuenta figure con un login reciclado (id inmutable). Quién tiene ese permiso se controla con
los accesos del repositorio, fuera de este código.

**Flujo con otro aprobador disponible (`independent_review`).**
1. El resumen del run bloqueado muestra el CVE, el paquete, la versión y la imagen.
2. Quien propone abre un PR con el registro (`approver` = el aprobador que lo revisará).
3. Ese aprobador revisa la justificación, el alcance y la vigencia, y aprueba el PR **antes**
   de que se fusione. Si el registro cambia después, hay que volver a aprobar.
4. Se fusiona con merge commit.

**Flujo unipersonal (`self_merge`).**
1. El ingeniero autorizado crea una rama desde `main` con **solo** el registro (uno o varios
   `VD-*.json` / `BG-*.json`; puede retirar otros registros en el mismo PR), con
   `"approver": "<su login>"`.
2. Abre el PR desde **su** cuenta, relee el diff (que sea solo `security/…/*.json`) y lo
   fusiona **él mismo** con *Create a merge commit*. Ese merge es el acto deliberado de
   aceptación: no lo delega, no lo programa (auto-merge) ni lo encarga a otra cuenta.
3. Si el PR necesita otro cambio (código, workflow, documentación), va en un PR aparte.

En ambos casos el merge a `main` no despliega por sí mismo: `security/` no es una ruta de
producción. Hay que lanzar el deploy (ejecución manual, o el próximo push que lo requiera).

**Agentes automatizados.** Un agente (asistente de código, bot, workflow) puede redactar un
registro o un PR de propuesta, pero **nunca** aprueba, fusiona ni dispara una aceptación de
riesgo o un break-glass: no envía revisiones `APPROVED`, no fusiona PR de `security/`, no habilita
auto-merge en ellos, no aprueba el environment ni lanza el `workflow_dispatch` de break-glass.
Las cuentas `Bot` se rechazan por código como autor, quien fusiona o revisor; que una persona no
use su propia cuenta a través de un agente es una regla operativa, **no** una garantía técnica
(ver supuestos de confianza).

## Break-glass de escaneo

Es solo para una caída del escáner, del registry o de la DB de vulnerabilidades, cuando una
mutación no puede esperar. No desactiva la política ni exime un CRITICAL conocido. Una imagen
cuyo escaneo fresco funcionó se evalúa por la vía normal aunque se pida break-glass.

**Condiciones (todas):**
- `workflow_dispatch` desde la punta actual de `main`, con `break_glass_authorization`
  (`BG-AAAA-NNN`) y `break_glass_justification` propia de esta operación (al menos 20
  caracteres). Un push nunca lo obtiene.
- `security/break-glass/<id>.json` en ese commit, aceptado con la misma verificación de
  procedencia que una disposición (`independent_review` o `self_merge`) y vigente (72 h como
  máximo). El aprobador **puede** ser quien dispara el run y quien aprueba el environment; el
  run solo exige que quien lo dispara esté identificado (login válido), para la auditoría.
- **Una autorización, una operación, un id.** `operation` describe exactamente la intención
  del run (`run_host_config`, `run_app_deploy`, `run_build`, `run_build_postgres`, `image_tag`,
  `keycloak_recovery`); otra combinación de inputs bloquea. El primer intento la consume aunque
  después falle o se cancele: un re-run u otra operación necesitan una autorización nueva **con
  otro id**.
- **Sin Terraform Apply.** El Apply es la primera mutación del job `production` y ocurre antes
  de la decisión autoritativa y del issue de auditoría; por eso `run_terraform_apply=true` con
  break-glass se rechaza al clasificar la intención (y la política lo vuelve a rechazar). Si
  hace falta un Apply, va en un run normal aparte.
- La autorización lista la referencia exacta de cada imagen y la evidencia histórica que
  aprobó: run, artifact, digest del artifact, fecha del escaneo (debe coincidir con la
  evidencia) y los CRITICAL conocidos (deben coincidir exactamente). Cada CRITICAL conocido
  sigue necesitando una disposición válida.
- La evidencia histórica pasa `historical-scan-evidence.sh` (ver abajo).
- El run tiene una aprobación humana registrada del environment `production-hetzner`.
- Se crea un issue con la etiqueta `security-break-glass` **antes** de mutar: actor, quien
  disparó, run, aprobación del environment, autorización, justificaciones, digests, evidencia
  usada (run, artifact, antigüedad, DB) y CVE conocidos. Si el issue no se crea, no hay
  mutación.
- **Consumo inmutable, antes del issue.** El consumo se registra como un *commit status* con
  contexto `security/break-glass-consumption/<id>` sobre el merge commit de `main` que integró
  la autorización (el que verificó la procedencia). Antes de registrarlo se listan los statuses
  de ese contexto en **todos** los commits de `main` (primer padre) que alguna vez tocaron
  `security/break-glass/<id>.json` (`consumption_anchors`, de la historia completa del commit
  evaluado): si hay alguno, de quien sea, el id ya se usó y no hay mutación. Así editar,
  borrar y recrear o re-fusionar el registro con el mismo id no lo renueva, aunque cada merge
  nuevo sea un commit distinto. Después se relee el registro, que debe ser exactamente el de
  este run, sobre el merge vigente. Si leer (cualquier commit), registrar o releer falla, no hay
  mutación. El issue **no** decide el consumo: su título y etiquetas son editables y se puede
  transferir o borrar.

  Por qué un commit status: la API REST no tiene ninguna operación para editarlo ni borrarlo
  (solo crear y listar), la lista devuelve todos, no solo el último, y no se conoce forma de
  borrarlo desde la interfaz (pendiente de confirmar, ver abajo). Alguien con escritura puede *agregar*
  uno con ese contexto, pero eso solo hace que la autorización parezca usada (falla cerrado).
  Comprobar y registrar es atómico frente a otros runs porque el job `production` está
  serializado. Para una nueva operación después de un consumo hace falta un registro con un id
  nuevo (`BG-AAAA-NNN` siguiente), aceptado por una de las dos vías.

**Cadena completa.** registro `BG-*.json` → merge verificado (`independent_review` o
`self_merge`) → `workflow_dispatch` desde `main` con el id y la justificación →
`image_policy` (preliminar) → aprobación humana del environment → evaluación autoritativa
(misma huella de aprobadores, vigencia, operación, evidencia histórica, CRITICAL conocidos
dispuestos) → consumo único → issue `security-break-glass` (con la vía de autorización) →
operación. Cualquier eslabón que falla deja producción sin mutar; si el consumo ya quedó y
después falla el issue o la operación, el id está gastado y hace falta otro.

```json
{
  "schema": "piedrazul.break-glass-authorization/v2",
  "id": "BG-2026-001",
  "justification": "Motivo y alcance de la excepción.",
  "approver": "<login>",
  "effective": "2026-10-20T08:00:00Z",
  "expires": "2026-10-21T08:00:00Z",
  "operation": { "run_host_config": false, "run_app_deploy": true, "run_build": false,
                 "run_build_postgres": false, "image_tag": "<tag del rollback>", "keycloak_recovery": false },
  "images": [{
    "surface": "backend",
    "image_ref": "ghcr.io/jm-ortega/piedrazul-agendamiento-citas/backend@sha256:<linux/amd64>",
    "known_critical": [],
    "coverage_limitations": "Qué no cubre ese escaneo (y su antigüedad).",
    "evidence": { "run_id": 0, "artifact_id": 0, "artifact_digest": "sha256:<64 hex>",
                  "scanned_at": "<CreatedAt de trivy.json>" }
  }]
}
```

**Evidencia histórica elegible.** Un artifact `image-scan-<fase>-<imagen>-<run_id>` que la
acción `trivy-scan` subió en un run anterior de `production-hetzner.yml` (push o dispatch, rama
`main`, este repositorio, commit en la historia de `main`). Se verifica contra la API de
GitHub, no contra el propio artifact:
- origen (artifact ↔ run ↔ workflow ↔ rama ↔ repositorio), no vencido;
- integridad: el digest que GitHub registró al subirlo es el aprobado y el zip descargado lo
  tiene (un checksum dentro del artifact no cuenta);
- contenido: exactamente `summary.json`, `trivy.json` y `trivy-version.json`, un escaneo
  exitoso de esa referencia `linux/amd64`.

Los artifacts duran 30 días: la evidencia histórica nunca es más vieja que eso, y una imagen sin
un escaneo exitoso propio dentro de ese plazo no tiene break-glass posible. Una imagen recién
construida no tiene evidencia histórica.

Para encontrar la evidencia de un digest:

```bash
gh api "repos/JM-Ortega/piedrazul-agendamiento-citas/actions/artifacts?per_page=100" \
  --jq '.artifacts[] | select(.name | startswith("image-scan-")) | {id, name, digest, created_at, run: .workflow_run.id}'
```

Después, descargar el artifact y confirmar en `summary.json` el `image_ref`, el `outcome`
(`scanned`), `scanned_at` y `critical.ids`.

**Antes de necesitarlo** (configuración externa, la hace un administrador; no la hace este
código): crear la etiqueta `security-break-glass` en el repositorio, definir
`VULNERABILITY_RISK_APPROVERS` (`login:id` del ingeniero autorizado) como variable **del
repositorio**, y confirmar que `production-hetzner` exige revisores. Hasta entonces ninguna
disposición ni break-glass es efectiva (falla cerrado).

## Supuestos de confianza

- `main` solo cambia por PR (ruleset `Protect main`: merge commits, sin aprobaciones
  obligatorias, sin bypass); el código de la política es el de ese commit.
- **Riesgo residual aceptado de la operación unipersonal.** Con `self_merge`, una sola cuenta
  redacta, fusiona (acepta), dispara el run y aprueba el environment. La separación humana es
  operativa, no técnica: si esa cuenta se compromete, o si su dueño la usa a través de un
  agente o automatización, se puede aceptar riesgo sin un acto humano deliberado. Lo que el
  código sí garantiza: la cuenta debe ser la autorizada (login e id), humana según GitHub, el PR
  no puede traer nada más que registros, el contenido aceptado es exactamente el evaluado, y un
  break-glass sigue limitado a una operación, 72 h, sin Terraform Apply ni frontend y sin eximir
  CRITICAL conocidos. Los merges de autorización los hace la persona, no un agente.
- `VULNERABILITY_RISK_APPROVERS` la controla cualquiera con permiso para gestionar variables
  del repositorio (no solo administradores): la autoridad efectiva de aceptar riesgo incluye a
  todas esas cuentas. El environment lo controlan los administradores.
- **Variable ensombrecida.** Si el environment `production-hetzner` definiera una variable con el
  mismo nombre, solo el job `production` la vería. La evaluación autoritativa compara la huella
  del conjunto de aprobadores con la de `image_policy` (sin environment) y bloquea si difieren;
  así nadie amplía la autoridad desde el environment sin que se note. La autoridad válida es la
  variable del **repositorio**; no definir una copia en el environment.
- En `independent_review`, la aprobación de un PR cubre el contenido del registro (blob exacto),
  no el resto del PR. Que otros archivos del mismo PR (por ejemplo, el workflow) no cambien
  después de aprobar depende de la configuración de revisiones de `main`. En `self_merge` no
  hay "resto del PR": solo puede traer registros.
- Los artifacts de un run de `main` solo los suben jobs de ese run. Trivy y la acción de subida
  están fijados por digest/SHA.
- Permisos: `image_policy` solo lee (`contents`, `actions`, `pull-requests`); `production` suma
  `statuses: write` (consumo de break-glass) e `issues: write` (auditoría de break-glass).
- El registro de consumo depende de que los merges del registro sigan en la historia de
  `main` y de que el job tenga la historia completa (`fetch-depth: 0`; un clon superficial falla
  cerrado). Reescribirla requiere saltarse el ruleset `Protect main` (borrado y force-push
  prohibidos), algo que solo puede hacer un administrador. Un id nuevo con el mismo contenido
  es una autorización nueva: necesita su propio merge deliberado.

## Qué está demostrado y qué falta

**Demostrado localmente** (`infra/tests/vuln-policy-check.sh`, GitHub simulado con un `gh` falso
y un repositorio git real con merges; reportes reales de Trivy 0.74.0): la lógica de decisión,
la verificación de procedencia sobre las respuestas de la API tal como se modelaron, la
comprobación de digest y contenido de artifacts, el uso único y la operación del break-glass, la
huella de aprobadores y el orden de los pasos del workflow; ambas vías de aceptación
(`independent_review` con aprobación previa al merge y `self_merge` con merge commit propio,
solo de registros) y el rechazo de un id de break-glass consumido aunque se re-fusione o se
borre y recree.

**Requiere una ejecución real en GitHub** (no verificado):
- que las API devuelvan los campos con la forma modelada: revisiones de PR (`state`,
  `commit_id`, `user.id`, `user.type`, `submitted_at`), `commits/{sha}/pulls` (`merged_at`,
  `user.login`), `pulls/{n}` (`merged`, `merged_at`, `merge_commit_sha`, `head.sha`,
  `user.id`/`type`, `merged_by.login`/`id`/`type`), `contents?ref=` (`sha` del blob),
  artifacts (`digest`, `workflow_run.*`), runs (`path`, `workflow_id`, `event`), aprobaciones del
  run (`state`, `environments`, `user.type`), commit statuses (`GET commits/{sha}/statuses`
  devuelve todos, con `context`, `target_url` e `id`; `POST statuses/{sha}`) e issues
  (creación con etiqueta);
- que el `GITHUB_TOKEN` con estos permisos pueda leer todo eso y crear el issue;
- que el zip de `actions/artifacts/{id}/zip` tenga exactamente el `digest` registrado, y que los
  artifacts de un run en curso aparezcan en la API antes de que el run termine;
- qué pasa con artifacts del mismo nombre en **Re-run all jobs** (aquí: ambigüedad = bloqueo);
- que no haya forma de borrar o editar commit statuses en este repositorio: la API REST no
  tiene operación para eso, pero no se confirmó contra la interfaz ni contra otras API;
- que el environment exija revisores (si no, el break-glass bloquea por falta de aprobación) y
  que no exista una variable `VULNERABILITY_RISK_APPROVERS` en el environment;
- un `self_merge` real: que el merge commit de GitHub tenga como segundo padre `head.sha` y que
  `merged_by` sea el autor cuando el propio autor fusiona su PR sin aprobaciones obligatorias;
- que una revisión enviada después del merge conserve un `submitted_at` posterior a `merged_at`.
