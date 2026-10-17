# Instalación nueva de producción (y actualizaciones posteriores)

La misma cadena, `.github/workflows/production-hetzner.yml`, instala desde cero y actualiza
una instalación existente. No hay un modo ni un workflow de bootstrap aparte: una instalación
nueva es un run que aplica Terraform, configura el servidor, construye las imágenes y publica
el frontend, sin depender de un VPS, un proyecto de Pages, un estado de Terraform ni un
último éxito previos.

> **Estado de verificación.** El contrato está implementado y probado sin red
> (`infra/tests/production-bootstrap-check.sh`, `infra/tests/pages-release-check.sh`, con
> respuestas simuladas de HCP, Cloudflare y cloud-init). La base desde un `PGDATA` vacío,
> Flyway desde cero y Keycloak P2 tienen pruebas de runtime aisladas en Docker
> (`infra/tests/db-migration-check.sh`, `infra/tests/keycloak-lifecycle-harness.sh`; este
> último con la colección `community.docker` fijada en `infra/ansible/requirements.yml`, vía
> `ANSIBLE_COLLECTIONS_PATH`). **Nada de esto se ha ejecutado contra el entorno real.** La
> lista de lo que falta verificar en vivo está al final.

## Requisitos externos

La cadena verifica lo que puede (y falla cerrado si no se cumple), pero no los crea.

**HCP Terraform** (workspace `Piedrazul/piedrazul-hetzner`, id en `vars.TF_WORKSPACE_HETZNER_ID`):

- Ejecución `remote` (o `agent`).
- *Apply method* manual: `auto-apply` y `auto-apply-run-trigger` deshabilitados. Si alguno
  está habilitado, la cadena falla antes de crear el run.
- Estado: vacío (instalación nueva) o el de una instalación que la cadena gestiona. El
  estado histórico de la cuenta anterior de Cloudflare **no** es un punto de partida: su
  retiro (respaldo, revisión de lo que queda gestionado, workspace nuevo o limpieza del
  estado) es una operación aparte, con su propia autorización, que la cadena nunca hace.
- Variables del workspace: `HCLOUD_TOKEN`, `CLOUDFLARE_API_TOKEN` (cuenta y zona dedicadas:
  Pages, DNS, ajustes de zona y Authenticated Origin Pulls), `ansible_ssh_public_key`,
  `ops_ssh_public_key`. `github_owner`/`github_repo` ya no se usan.
- `TF_API_TOKEN` debe poder leer el **JSON del plan** (`/plans/<id>/json-output`). Cuando el
  proyecto de Pages falta o está incompleto, la cadena exige ver en ese JSON la creación de lo
  que falta; sin permiso (o con un JSON ilegible) la instalación falla cerrado antes de la
  confirmación. Qué permiso de HCP lo concede se confirma en vivo.

**Cloudflare** (cuenta `ab4f90b9b70538731273c3fdf9548f07`, zona `piedrazul.org`):

- Sin proyecto de Pages `piedrazul` (lo crea Terraform, de subida directa) o con uno de
  subida directa ya gestionado por este estado. Un proyecto con integración Git no sirve.
- Sin un registro DNS previo en el apex (`piedrazul.org`) ni en `api`/`auth`: Terraform los
  crea y un registro ajeno con el mismo nombre hace fallar el Apply.

**GitHub, environment `production-hetzner`**: `TF_API_TOKEN` (lee el estado y encola runs;
no necesita confirmar), `CLOUDFLARE_PAGES_API_TOKEN` (*Account → Cloudflare Pages → Edit*
en la cuenta dedicada, distinto del de DNS), `CLOUDFLARE_DNS_API_TOKEN` (DNS de la zona
nueva, para el desafío de Let's Encrypt), `ANSIBLE_SSH_KEY`, contraseñas de la base,
secretos de Keycloak, `KC_PLATFORM_ADMIN_INITIAL_PASSWORD` (solo la instalación nueva, P2;
ver [administrador-keycloak.md](administrador-keycloak.md)) y, si se habilita, el primer
administrador de negocio ([primer-admin-negocio.md](primer-admin-negocio.md)).
`PIEDRAZUL_BACKEND_MAIL_SEND` debe existir y no estar vacío: la verificación de credenciales
lo exige y el backend no arranca con el envío de correo habilitado y la clave vacía. Solo se
exige su presencia; el proveedor de correo (SendGrid, en retiro) no forma parte de esta
instalación ni de su verificación.

## Cómo se inicia

Cualquiera de los dos:

- **Primer push a `main` con esta cadena.** Sin un run push exitoso previo del workflow, el
  rango se clasifica contra el árbol completo: Terraform, configuración del servidor, build
  de backend y de postgres, deploy y frontend.
- **Dispatch manual completo** desde `main`: `run_terraform_apply`, `run_host_config`,
  `run_build`, `run_build_postgres`, `run_app_deploy` y `run_frontend_publish` en `true`
  (sin `image_tag`, sin break-glass). No depende de ningún diff. Es también el camino de
  recuperación si se pierde la infraestructura: un push normal solo cubre su rango.

## Qué hace el operador

1. **Aprueba el environment** `production-hetzner` (autoriza la cadena; el plan todavía no
   existe).
2. **Espera a que GitHub termine de revisar el plan.** El paso *Revisar plan de Terraform*
   comprueba la identidad del run, la frescura del commit y, si el proyecto de Pages faltaba
   o estaba incompleto, la creación observada en el plan; deja en el log y en el resumen el
   workspace, el commit, el run, el plan, los conteos crear/cambiar/destruir/importar y la
   lista de cambios. Solo cuando ese paso **termina en éxito** emite el aviso
   **"Plan revisado por GitHub"** con el run, el plan y el commit, y empieza el paso
   *Esperar confirmación humana en HCP*.
3. **Revisa y confirma (o descarta) ese run en HCP**, solo después de ese aviso y dentro de
   30 minutos: el run debe ser el del aviso (mensaje `Producción <sha> — run <id>/<intento>`,
   mismo commit) y el plan completo en HCP es la revisión autoritativa. En una instalación
   nueva se esperan solo creaciones: servidor, firewall, proyecto de Pages, registros DNS,
   asociación del dominio, ajustes de zona y AOP. Nada en GitHub confirma. Sin confirmación a
   tiempo, o si `main` avanza antes de la confirmación, la cadena descarta el run y falla sin
   Apply. Un plan sin cambios no pide confirmación (y, si `main` avanzó, la cadena no sigue:
   nada mutó).
4. El resto es automático: estado del Apply → outputs → preflight estricto de Pages →
   espera de cloud-init → servidor (converge con Keycloak P2, deploy con migración) →
   identidad y health → registro del último éxito → publicación del frontend.

**Confirmación antes de tiempo: garantía operativa, no técnica.** HCP deja confirmar un run
en cuanto su plan es confirmable, y eso ocurre **antes** de que el paso *Revisar plan de
Terraform* termine. Ni la cadena ni HCP pueden impedir esa confirmación temprana: el token de
la cadena no controla quién confirma ni cuándo, y no hay una operación atómica "confirmar solo
si GitHub ya revisó y `main` no avanzó". Por eso la regla "confirmar solo después del aviso
*Plan revisado por GitHub*" es **operativa** (procedimiento del operador). Si se incumple:

- la cadena lo detecta (`already_confirmed`), lo anota como confirmado fuera del
  procedimiento y **no** interrumpe un Apply que HCP ya aceptó;
- las comprobaciones de GitHub igual corren, pero ya no pueden evitar el Apply. Si fallan
  (p. ej. Pages faltaba y el plan no mostraba su creación, o el JSON del plan no se pudo
  leer), la cadena se detiene antes del servidor y de Pages e informa que el Apply ya fue
  aceptado;
- la frescura comprobada por última vez fue la de antes de subir la configuración; si `main`
  avanzó desde entonces, el run queda marcado `confirmed_while_stale`. La ventana es todo el
  tiempo de subida y plan (minutos), no un intervalo de sondeo.

Siguiendo el procedimiento (confirmar después del aviso), la cadena comprueba la frescura al
terminar el plan y en cada sondeo de la espera; entre la última comprobación y el clic queda
una ventana de, como mucho, un intervalo de sondeo (15 s). Si la confirmación cae ahí y `main`
ya avanzó, el Apply empezó y la cadena **no** lo interrumpe: termina coherentemente con ese
commit, lo marca como *confirmado cuando main ya había avanzado*, y el run del commit nuevo
(en cola) planifica y despliega después.

Las imágenes pasan por el mismo escaneo y la misma política de vulnerabilidades que
cualquier deploy: una instalación nueva no tiene excepciones.

## Fallos, reintentos y cancelación

| dónde falla | estado que deja | cómo seguir |
|---|---|---|
| sondeo de Pages, credenciales, workspace (auto-apply, permisos, identidad) | nada mutado | corregir y re-run |
| creación del run en HCP (falla o se corta después de crearlo) | el run propio, si existe, se busca por su mensaje y se descarta o cancela | re-run |
| plan (falla, override de políticas, JSON ilegible o sin la creación de Pages que falta) | sin Apply; el run propio queda terminado o se descarta | corregir y re-run |
| confirmación vencida, descartada en HCP o `main` avanzó | sin Apply; el run propio se descarta | re-run (o el run del commit nuevo) |
| Apply parcial (p. ej. creó el proyecto de Pages y falló antes del CNAME o de la asociación del dominio) | proyecto con la identidad correcta pero incompleto | re-run completo con Terraform: el sondeo lo admite como `incomplete`, el plan debe mostrar la creación de la asociación, y el preflight estricto exige el proyecto completo antes de publicar. Un run sin Terraform (solo frontend o deploy) falla ante un proyecto incompleto |
| cloud-init falló, quedó degradado (errores recuperables), sigue corriendo, no se pudo leer (salida ilegible, forma inesperada o ssh/comando remoto con código distinto de 0) o venció el plazo | servidor creado, sin configurar (el diagnóstico dice cuál de los casos) | revisar `cloud-init status --long` con el usuario `ops`; corregir y re-run (en un servidor nuevo, recrearlo es también válido) |
| después del Apply (preflight de Pages, SSH, converge, deploy, health) | infraestructura creada; servidor sin último éxito o con el anterior; Pages sin cambios | corregir y re-run completo: el plan nuevo no tiene cambios (sin confirmación) y el resto converge de nuevo |
| publicación de Pages | release parcial: servidor registrado, frontend anterior (o ninguno) | re-run; republicar el mismo contenido es inocuo |

- **Cancelación o vencimiento del job**: el paso *Liberar el run de Terraform de este
  intento* corre siempre. Si el run de este intento espera confirmación, lo descarta; si
  está planificando, lo cancela; si HCP ya aceptó el Apply (confirmado o aplicando), no lo
  toca y lo informa: cancelar el job no deshace un Apply. El siguiente run espera a que el
  workspace quede libre y planifica sobre el estado resultante.
- **Si GitHub no llegó a registrar el id del run** (la creación falló o se cortó después del
  POST), la limpieza lo busca por el mensaje exacto del intento entre los 20 runs más
  recientes del workspace y solo actúa sobre ese.
- **Run huérfano** (la limpieza no pudo actuar: runner terminado de golpe, HCP rechazó el
  descarte, o la lista de runs no respondió). El resumen lo dice cuando el paso corrió; el
  siguiente run se detiene en *Esperar a que HCP Terraform esté libre*. Procedimiento:
  1. En HCP, workspace `piedrazul-hetzner` → *Runs*, buscar el run con el mensaje
     `Producción <sha> — run <run_id>/<intento>` del run de GitHub afectado.
  2. Si está esperando confirmación (*Needs confirmation*) o planificando: **Discard run**
     (o *Cancel run* mientras planifica), con un comentario que cite el run de GitHub.
  3. Si ya está aplicando o aplicado: **no cancelarlo** (cancelar un Apply en curso puede dejar
     el estado a medias); dejarlo terminar. El siguiente run planifica sobre ese estado.
  4. Volver a lanzar el run de GitHub (re-run o el siguiente push).
- **Concurrencia**: el job `production` está serializado (`piedrazul-production-hetzner`) y
  solo la punta de `main` puede iniciar una mutación; mientras un run espera confirmación,
  HCP encola cualquier otro run del workspace detrás de él.
- El rango de push solo avanza cuando el run termina en éxito; un primer push fallido deja
  el siguiente push también en modo árbol completo.

## Qué significa "instalado"

El resumen del run separa cuatro resultados que no deben confundirse:

1. **Proyecto de Pages creado** (Terraform) y verificado por el preflight estricto.
2. **Frontend publicado** y verificado por `*.pages.dev` (deployment de este intento,
   vigente, sirviendo el artefacto validado).
3. **Dominio propio activo**: `custom_domain_status=active` según la API. Mientras no lo
   esté, el resumen marca la instalación pública como **no verificada**.
4. **Aplicación verificada de punta a punta** (login, flujos de negocio): nunca la verifica
   la cadena; es parte de la verificación formal.

## Cloudflare Access y alias públicos

Access protege temporalmente el apex y lo administra el operador fuera de la cadena; la
cadena no lo usa ni lo necesita. La publicación se verifica por `piedrazul.pages.dev`, que
—como las URLs propias de cada deployment— es público aunque el apex esté detrás de Access.
El estado del dominio propio (`custom_domain_status`) se informa sin bloquear: `pending` no
es ni activación ni fallo. Si el dominio no se activa y se sospecha de Access, el operador
revisa la aplicación de Access del apex por su cuenta (sin cambiar tokens ni crear recursos
desde la cadena) y vuelve a comprobar el estado en el siguiente run o en el dashboard.

## Pendiente de verificación en vivo

- Que la API de Pages represente un proyecto de subida directa sin `source` (o `null`) y
  responda `404` con el código `8000007` a un proyecto inexistente.
- Que `cloudflare_pages_project` sin `source` (provider 5.18.0) cree un proyecto de subida
  directa, y que un segundo plan tras la instalación salga sin cambios (incluidos el
  proyecto, el CNAME y la asociación del dominio).
- Que la asociación del dominio con el CNAME ya creado se active (y cuánto tarda con Access
  delante).
- Que HCP responda `404` a `current-state-version` de un workspace vacío y lista vacía de
  state versions, y que el token tenga los permisos que la cadena exige.
- Que la confirmación en HCP, el descarte y la cancelación se comporten como en las
  pruebas, que el paso de limpieza corra al cancelar el job y que el token pueda leer el
  JSON del plan.
- Que `cloud-init status --wait` / `--format json` se comporten así en la imagen
  `ubuntu-24.04` de Hetzner, y que la plantilla de cloud-init termine **sin** errores
  recuperables: un estado degradado (p. ej. por una clave de configuración obsoleta) detiene
  la instalación hasta corregir la plantilla.

## Limitaciones conocidas

- **Confirmación temprana en HCP**: la regla "confirmar después del aviso *Plan revisado por
  GitHub*" es operativa; HCP no permite que la cadena la imponga (ver arriba).
- **Identidad del servidor por SSH**: la espera de SSH, la barrera de cloud-init y Ansible
  conectan con `StrictHostKeyChecking=no`: la clave del host no se verifica. Se confía en que
  `server_ip` (output del state version exacto de este run) es el servidor creado por
  Terraform. Es una limitación previa a este trabajo y queda fuera de su alcance.
- Convergencia completa de un servidor limpio (Keycloak P2, identidad inicial) e
  instalación de punta a punta.
