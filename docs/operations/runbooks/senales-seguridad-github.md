# Señales de seguridad de GitHub: dependencias y SAST

## Propósito

Describir los controles nativos de GitHub que detectan vulnerabilidades en dependencias
(Dependency Graph, Dependabot Alerts, Dependency Review) y en el código propio (CodeQL): qué
está configurado en GitHub, qué hace el workflow del repositorio, qué está verificado, qué falta
comprobar con una ejecución real y qué hace falta antes de exigir algún check.

Dependabot no abre PRs de actualización: los dueños de cada componente (backend, frontend)
actualizan sus dependencias.

## Configuración de GitHub

Estado configurado el 2026-10-14. Para el estado actual, ver **Settings → Advanced Security** y
**Settings → Rules**.

| Ajuste | Estado | Nota |
|---|---|---|
| Dependency graph | activado | requisito de todo lo demás |
| Dependabot alerts | activado | solo describe la rama por defecto (`main`) |
| Automatic dependency submission | **desactivado** | debe seguir así: el workflow ya envía el árbol Maven de `main`; activarlo duplicaría el backend |
| Dependabot security updates | desactivado | sin PRs automáticos |
| Grouped security updates | desactivado | |
| CodeQL default setup | activado, suite `Default`, semanal | lenguajes `actions`, `java-kotlin`, `javascript-typescript` |
| Ruleset `Protect main` | activo | borrado, force-push, PR con aprobaciones |
| Ruleset `Protect develop` | activo | solo borrado y force-push; no exige PR, aprobaciones ni checks |
| Checks requeridos de seguridad | ninguno | ver "Antes de exigir un check" |

## Workflow `dependency-review.yml`

| Job | Cuándo corre | Permisos | Qué hace |
|---|---|---|---|
| `Grafo Maven` | push a `develop` o `main`; PR hacia `develop` o `main` desde una rama de este repositorio; ejecución manual sobre `develop` o `main` (ver Adopción). Nunca en PRs de forks, otras ramas ni tags | `contents: write`; sin secretos, environment ni caché | resuelve el árbol Maven de `backend/` (directas y transitivas) y lo envía como snapshot al Dependency Graph |
| `Dependency Review` | todo PR hacia `develop` o `main`, incluidos los de forks | `contents: read`, `actions: read`, `checks: read`; sin checkout ni caché | comprueba la cobertura Maven, compara el grafo de la base del PR con el de su cabeza y reporta lo que el PR introduce |

El workflow declara `cache-mode: none`: ningún job puede leer ni crear entradas de la caché de
Actions. GitHub lo aplica en el token del job, así que vale para cualquier herramienta que lo
use, no solo para `actions/cache`.

**Por qué hace falta el snapshot Maven.** El análisis estático de GitHub sobre `pom.xml` solo ve
dependencias directas y no resuelve las versiones que gestiona el BOM de Spring Boot (22 de 33
directas no declaran versión); no ve ninguna transitiva (el árbol resuelto tiene 262). Al
activar, Dependabot mostraba 2 alertas Maven en `main` solo con ese análisis. npm no lo necesita:
`package-lock.json` y `yarn.lock` ya traen el árbol completo.

### Cobertura por rama

| | `develop` | `main` |
|---|---|---|
| Snapshot Maven de cada SHA | push (y manual) | push (y manual) |
| Snapshot Maven de la cabeza de un PR hacia la rama | PRs de ramas del repositorio | PRs de ramas del repositorio |
| Dependency Review en PRs hacia la rama | sí | sí |
| Dependabot alerts | no (no es la rama por defecto) | sí |

**De dónde sale cada lado de la comparación.**
- Cabeza del PR: el job `Grafo Maven` del mismo run, antes de la revisión. Se resuelve el commit
  de la cabeza (`pull_request.head.sha`), no el merge commit, porque el snapshot queda ligado a
  ese SHA (ref `refs/pull/<n>/merge`).
- Base del PR: el run de push (o manual) de este workflow sobre la rama base (`develop` o `main`)
  para ese mismo SHA (ref `refs/heads/<rama>`). Esos runs no se cancelan entre sí, así que cada
  SHA de las dos ramas tiene su snapshot. Un run de otra rama con el mismo SHA no cuenta.

### Cobertura Maven completa o incompleta

Antes de revisar, el job exige evidencia positiva de los dos envíos. Que existan los manifiestos
o el workflow no cuenta como evidencia; una cabecera de advertencias vacía tampoco: GitHub la
devuelve vacía incluso cuando ningún lado tiene snapshot.

- Un envío está confirmado cuando un job `Grafo Maven` terminó en `success`, sus anotaciones
  incluyen el aviso de la acción `Snapshot successfully created at …` y no tienen ninguna de
  nivel `failure`. Hace falta porque la acción solo registra un error, sin fallar, si la API
  rechaza el snapshot.
- Cabeza: cualquier intento de este run (vale tras re-ejecutar).
- Base: un run de este workflow (`path`, con o sin `@<ref>`), de push o manual, sobre la rama base
  para `base.sha`. Mientras siga en curso, espera con backoff hasta 5 minutos. Si no existe
  ninguno, espera solo 1 minuto (el run de un push recién hecho puede tardar en aparecer).
- Con los dos envíos confirmados, la API de comparación además no debe informar snapshots
  faltantes (cabecera `x-github-dependency-graph-snapshot-warnings`).
- Si algo falta, la revisión corre en modo **solo advertencia** y deja el aviso "Cobertura Maven
  incompleta: <motivo>" en el job y en su resumen. Así no se clasifican como nuevas
  vulnerabilidades que ya estaban en la base.
- Un fallo de cualquier API hace fallar el job: nunca cuenta como completo ni como incompleto.

| Situación | Motivo informado |
|---|---|
| PR de fork | la cabeza no envía snapshot Maven |
| envío de la cabeza fallido, cancelado, omitido o rechazado | envío Maven de la cabeza sin confirmar |
| ningún run de la rama base para ese SHA (p. ej. antes de que el workflow llegue a esa rama) | no hay envío Maven de `<rama>` para la base |
| run de la base aún en curso al agotar la espera | el envío de la base no terminó a tiempo |
| run de la base terminado sin envío confirmado | el envío de la base no se confirmó |
| ambos confirmados, pero la API sigue informando faltantes | la API sigue informando snapshots faltantes |

**Resultado.**
- Cobertura completa: falla con vulnerabilidades nuevas `high` o `critical` en dependencias de
  runtime (Maven `test` y `devDependencies` de npm cuentan como desarrollo); el resto se informa
  en el resumen del job.
- Cobertura incompleta: todo se informa como advertencia y el job termina en verde. Afecta
  también a los cambios npm de ese PR, que entonces solo se advierten.
- No es un check requerido.

**Repetir una revisión.** Re-ejecutar el job usa los mismos SHA de base y cabeza del evento
original. Sirve cuando el envío faltante terminó después. Si el envío de la base falló, primero
re-ejecutar ese run de push (**Re-run jobs**). Si cambió la rama base del PR, hace falta un push
nuevo al PR.

## Riesgo aceptado del job `Grafo Maven`

El job ejecuta Maven en el mismo job que tiene `contents: write`, y la acción le pasa el token a
Maven por el entorno. Se acepta con estas condiciones, todas presentes en el workflow:

- runners alojados por GitHub; sin secretos de producción ni environments;
- solo eventos autorizados del propio repositorio (push y manual sobre `develop`/`main`, PRs de
  ramas del repositorio); nunca código de forks con token de escritura;
- acciones fijadas por SHA completo; `contents: write` solo en este job;
- `cache-mode: none`: sin lectura ni creación de caché, tampoco en `main`;
- el wrapper de Maven verifica la distribución que descarga, y el job no ejecuta Maven si el commit
  resuelto no declara ese checksum (sección siguiente).

Quien dispara el job ya tiene escritura en el repositorio. El riesgo es que un artefacto de
terceros comprometido (la distribución de Maven que baja el wrapper, `depgraph-maven-plugin` y
sus dependencias) use el token: puede empujar a ramas sin regla de PR (incluida `develop`),
manejar tags y releases, y falsear snapshots. No puede empujar a `main`, cambiar workflows, leer
secretos ni usar environments. Los pushes hechos con ese token no disparan workflows.

### Integridad del wrapper de Maven

`backend/.mvn/wrapper/maven-wrapper.properties` declara el SHA-256 de la distribución que indica
`distributionUrl` (`apache-maven-3.9.12-bin.zip`):

```properties
distributionSha256Sum=305773a68d6ddfd413df58c82b3f8050e89778e777f3a745c8e5b8cbea4018ef
```

El valor coincide con los SHA-512 publicados por Maven Central y por Apache, y la firma de Apache
del archivo es válida con una clave del archivo `KEYS` de Maven. `mvnw` descarga la distribución,
comprueba el SHA-256 con `sha256sum` (o `shasum`) y se detiene sin instalarla si no coincide o si
no hay ninguna de esas herramientas. Solo comprueba al descargar: si la distribución ya está
instalada en `~/.m2/wrapper` (por ejemplo, en una máquina de desarrollo), la reutiliza sin
volver a comprobarla.

Dónde aplica:

| Uso | Descarga en cada ejecución | Comprobación |
|---|---|---|
| `Grafo Maven` (este workflow) | sí: runner nuevo y `cache-mode: none` | sí |
| `ci-backend.yml` | sí: `setup-java` solo guarda `~/.m2/repository`, no `~/.m2/wrapper` | sí |
| build de la imagen (`backend/Dockerfile`) | depende del cache mount de BuildKit en `/root/.m2`; en un runner nuevo empieza vacío | sí, con `sha256sum` de coreutils de la imagen |
| máquina de desarrollo | solo la primera vez | solo la primera vez |

**Guarda en el workflow.** En un PR, GitHub ejecuta el workflow del merge commit pero `Grafo
Maven` resuelve la cabeza del PR, que puede ser una rama creada antes del checksum. El paso
"Exigir checksum del wrapper de Maven" corre antes de Java y de Maven y falla si el archivo del
commit resuelto no tiene la línea `distributionSha256Sum=` con 64 caracteres hexadecimales. Así
ese commit no envía snapshot y la revisión informa la cobertura Maven como incompleta. La
solución es actualizar la rama con la base.

**Al cambiar de versión de Maven** hay que actualizar `distributionUrl` y `distributionSha256Sum`
juntos. Con solo la URL nueva, `mvnw` falla en todas partes. Un cambio en `backend/**` que llega a
`main` dispara el build y despliegue del backend (`classify-production.sh`).

## Límites conocidos

- **Dependabot alerts solo refleja `main`.** Mientras `main` tenga la versión heredada, las
  alertas describen `main`, no `develop`. El estado de `develop` y de las ramas de trabajo se ve
  en el Dependency Review de cada PR.
- **PRs de forks:** no envían snapshot (su token es de solo lectura); siempre solo advierten.
- **PRs entre `develop` y `main`:** con `main` heredado la diferencia es enorme; lo que esté en
  `develop` y no en `main` aparece como nuevo, que es correcto respecto de `main`.
- **La evidencia de envío sale del propio job.** Código que se ejecuta en Maven (del autor del
  PR o de un push a la rama) podría imprimir el mismo aviso. Eso no da más de lo que ya tiene
  quien puede escribir en el repositorio.

## CodeQL

CodeQL usa *default setup*, configurado en GitHub. No se agrega un workflow propio de CodeQL
(*advanced setup*): entra en conflicto con default setup.

Default setup analiza los pushes a la rama por defecto y a ramas protegidas, los PRs hacia ellas
(excepto los de forks) y una ejecución semanal.

| Objetivo | Estado |
|---|---|
| push a `main` | verificado: primer análisis de los tres lenguajes sobre `main` al activar |
| PR hacia `main` | esperado por documentación; pendiente de observar |
| push a `develop` y PR hacia `develop` | **pendiente**: `develop` solo tiene el ruleset `Protect develop`, y la documentación de GitHub no aclara si una protección solo por ruleset cuenta como rama protegida |

Para cerrar lo pendiente: después del próximo push o PR hacia `develop`, comprobar que existe un
análisis con `ref` de `develop`:

```bash
gh api "repos/JM-Ortega/piedrazul-agendamiento-citas/code-scanning/analyses?per_page=20" \
  --jq '.[] | {ref, category, commit_sha, created_at}'
```

Si no aparece, `develop` no está cubierta; la decisión sobre su protección es del equipo dueño
de `develop`.

## Adopción del workflow

Los eventos `push` y `pull_request` usan el workflow tal como está en el commit del evento: no
necesitan que exista en `main`. La ejecución manual (`workflow_dispatch`) sí: GitHub solo la
ofrece cuando el archivo existe en la rama por defecto. Hasta entonces, la línea base y su
recuperación dependen solo de pushes y re-ejecuciones.

Antes de empezar: la configuración de GitHub de la tabla inicial. El checksum del wrapper va en
el mismo cambio que el workflow, así que cualquier commit que tenga el workflow también lo tiene.

**`develop`:**
1. El PR que lleva el workflow a `develop` envía el snapshot de su cabeza; su base todavía no
   tiene envío, así que informa cobertura incompleta y solo advierte. Es lo esperado.
2. El push que lo integra corre `Grafo Maven` sobre `develop` y crea la línea base de ese SHA.
   Comprobar en ese run el aviso `Snapshot successfully created at …`.
3. Desde ahí, cada push a `develop` envía su snapshot y los PRs de ramas del repositorio hacia
   `develop` deberían informar "Cobertura Maven: completa".

**`main`:**
1. Mientras `main` no tenga el workflow, los PRs hacia `main` informan cobertura incompleta (no
   hay envío de la base).
2. El push que lo integra en `main` crea la línea base de `main`. Ese snapshot es el que usa
   Dependabot alerts para Maven: deberían aparecer alertas de dependencias transitivas del
   backend que el análisis estático no veía.
3. Desde ahí, la ejecución manual queda disponible para `develop` y `main` (no envía nada en otras
   ramas ni tags). Sirve para volver a sembrar la cabeza actual de una rama.

Si un envío de la base falla, re-ejecutar ese run de push (**Re-run jobs**) y después la revisión
del PR.

## Verificación

**Verificado sin GitHub** (simulación y lectura del código de las acciones fijadas):
- qué evento ejecuta qué job, con qué permisos, y que nunca hay token de escritura en PRs de
  forks;
- que el commit resuelto coincide con el SHA del snapshot (cabeza del PR; SHA del push);
- la regla de cobertura completa/incompleta, incluidos fallos de API, reintentos y re-ejecuciones;
- la clasificación directa/transitiva y de ámbito (runtime/desarrollo) que envía la acción;
- el checksum de la distribución de Maven (SHA-512 de Central y Apache, firma de Apache);
- que `mvnw` lo comprueba al descargar y se detiene si no coincide o si falta la herramienta;
- que el `sha256sum` de la imagen de build (`eclipse-temurin:21-jdk-alpine` fijada por digest,
  coreutils) acepta el valor y rechaza uno incorrecto;
- la guarda del checksum en el workflow, con casos de archivo ausente o mal formado.

**Pendiente de una ejecución real:**
- que GitHub acepte y aplique `cache-mode: none`;
- un build real de la imagen del backend con el checksum (aquí no se ejecutó Docker);
- que el aviso de la acción aparezca como anotación y que la API acepte el snapshot;
- que la cabecera de advertencias informe una base sin snapshot cuando la cabeza sí tiene;
- cómo muestra el Dependency Graph el manifiesto enviado junto al análisis estático de
  `backend/pom.xml` (posibles duplicados de dependencias directas);
- la cobertura de CodeQL en `develop`.

## Antes de exigir un check

Ningún check de dependencias ni de CodeQL es requerido. Para exigir `Dependency Review` en una
rama hace falta observar, en PRs reales hacia esa rama:

- el nombre estable del check (`Dependency Review`) presente en todo PR;
- "Cobertura Maven: completa" en PRs de ramas del repositorio; avisos de cobertura incompleta
  solo en forks;
- un PR cuya base no tiene envío informa cobertura incompleta;
- un PR que agrega una dependencia de runtime con vulnerabilidad `high` conocida falla, y un PR
  sin cambios de dependencias pasa;
- que las vulnerabilidades ya presentes en la base no se reporten;
- la espera de snapshots, sin agotar los 5 minutos de forma habitual.

Además, antes de exigirlo hay que cambiar el tratamiento de la cobertura incompleta. Hoy termina
en verde con advertencia, lo que es correcto para una señal informativa. Como check requerido
dejaría pasar un PR interno sin evaluar Maven. Para PRs de ramas del repositorio debe fallar (o
quedar pendiente) en vez de advertir; los forks necesitan una regla propia.

Para CodeQL, además: análisis exitosos por lenguaje en la rama exigida y la línea base de
alertas revisada.

## Triage de la línea base

La línea base (alertas existentes de Dependabot y CodeQL) se trata aparte de la exigencia y no
bloquea el desarrollo:

- revisar con el dueño del componente;
- descartar con motivo (*won't fix*, *false positive*, *used in tests*) o planificar la
  corrección;
- las actualizaciones las hace el equipo dueño (backend o frontend) en su rama.
