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
  está habilitado, la cadena falla antes de crear el run y vuelve a comprobarlo justo antes
  de confirmar el Apply.
- Estado: vacío (instalación nueva) o el de una instalación que la cadena gestiona. El
  estado histórico de la cuenta anterior de Cloudflare **no** es un punto de partida: su
  retiro (respaldo, revisión de lo que queda gestionado, workspace nuevo o limpieza del
  estado) es una operación aparte, con su propia autorización, que la cadena nunca hace.
- Variables del workspace: `HCLOUD_TOKEN`, `CLOUDFLARE_API_TOKEN` (cuenta y zona dedicadas:
  Pages, DNS, ajustes de zona y Authenticated Origin Pulls), `ansible_ssh_public_key`,
  `ops_ssh_public_key`. `github_owner`/`github_repo` ya no se usan.
- `TF_API_TOKEN` (token de usuario o de equipo, no de organización) debe poder: leer el
  workspace y su estado, crear configuraciones y runs (`can-queue-run`), confirmar,
  descartar y cancelar runs (`can-queue-apply`) y leer el **JSON del plan**
  (`/plans/<id>/json-output`; según la documentación de HCP exige acceso de administración al
  workspace). Con cambios, la revisión en GitHub se arma con ese JSON: sin él la cadena falla
  cerrado antes de pedir la segunda aprobación. Que el token del plan Free cumpla todo esto se
  confirma en vivo (ver *Pendiente de verificación en vivo*).

**Cloudflare** (cuenta `ab4f90b9b70538731273c3fdf9548f07`, zona `piedrazul.org`):

- Sin proyecto de Pages `piedrazul` (lo crea Terraform, de subida directa) o con uno de
  subida directa ya gestionado por este estado. Un proyecto con integración Git no sirve.
- Sin un registro DNS previo en el apex (`piedrazul.org`) ni en `api`/`auth`: Terraform los
  crea y un registro ajeno con el mismo nombre hace fallar el Apply.

**GitHub, environment `production-hetzner`**: `TF_API_TOKEN` (lee el estado, sube
configuraciones, encola, confirma y descarta los runs de la cadena), `CLOUDFLARE_PAGES_API_TOKEN` (*Account → Cloudflare Pages → Edit*
en la cuenta dedicada, distinto del de DNS), `CLOUDFLARE_DNS_API_TOKEN` (DNS de la zona
nueva, para el desafío de Let's Encrypt), `ANSIBLE_SSH_KEY`, contraseñas de la base,
secretos de Keycloak, `KC_PLATFORM_ADMIN_INITIAL_PASSWORD` (solo la instalación nueva, P2;
ver [administrador-keycloak.md](administrador-keycloak.md)) y, si se habilita, el primer
administrador de negocio ([primer-admin-negocio.md](primer-admin-negocio.md)).

**Correo.** SendGrid está deshabilitado en producción (`notifications_sendgrid_enabled: false`
en `group_vars`): la cadena no exige ni entrega `PIEDRAZUL_BACKEND_MAIL_SEND`, el `.env`
queda con `SENDGRID_API_KEY` vacía y el backend arranca sin ella. No hay envío externo de
correo: las notificaciones (incluidos los códigos de verificación) caen al proveedor de
consola, que solo registra en el log del backend y oculta el contenido sensible. Los flujos que
dependen de recibir un correo quedan fuera de esta instalación y de su verificación. Si se
habilita SendGrid sin entregar la clave al converge, la configuración del servidor falla antes
de escribir `.env`. `NOTIFICATION_ENCRYPTION_KEY` no es un requisito: ningún componente la usa.

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

Todo ocurre en el run de GitHub; el operador no necesita abrir HCP.

1. **Aprueba el environment** `production-hetzner` del job *Plan de Terraform*. Esta primera
   aprobación solo autoriza usar las credenciales de HCP para planificar este commit.
2. **Espera el plan.** El job sube la configuración como versión **provisional** (no pasa a
   ser la configuración actual del workspace salvo que se aplique), crea un **saved plan**
   (`save-plan`, `auto-apply=false`: HCP nunca lo aplica solo) y liga el plan al estado
   capturado antes de crear el run y a esa configuración: si HCP informa el state version de
   entrada debe ser el capturado, y en todo caso el estado actual, releído después del plan,
   debe seguir siendo el capturado. El resumen del run muestra:
   - la identidad: workspace, commit, run y plan, configuración provisional (con el sha256
     del archivo subido), state version del plan con su ligadura (`relationship`: informado
     por HCP; `bracketed`: capturado antes y releído sin cambios después del plan) e intento
     de GitHub;
   - los **efectos**, primero los destructivos: destruir, reemplazar (con el motivo y las
     rutas que lo fuerzan), actualizar, crear, olvidar, importar, drift y outputs. Por cada
     recurso se ven las rutas de los atributos que cambian; un valor solo se muestra si está
     clasificado en `.github/scripts/plan-review-classes.json` (el repositorio es público);
     lo sensible o no clasificado aparece como ruta sin valor.

   Sin cambios, no hay nada que aprobar para Terraform: el job *Producción* solo corre si el
   run también despliega o publica el frontend.
3. **Revisa los efectos y decide.** En una instalación nueva se esperan solo creaciones:
   servidor, firewall, proyecto de Pages, registros DNS, asociación del dominio, ajustes de
   zona y AOP. Una destrucción, un reemplazo o un cambio sobre recursos ajenos a lo esperado
   (por ejemplo, referencias del estado histórico) es motivo para **rechazar** e investigar:
   el saved plan no se aplica. Si corresponde, **aprueba el environment** del job
   *Producción*: esa segunda aprobación autoriza aplicar **ese** saved plan y seguir con el
   deploy.
4. **Producción revalida y confirma.** Justo antes de confirmar comprueba: workspace sin
   auto-apply y con permiso de confirmar, el run es el de este run de GitHub (mensaje, plan,
   configuración), el plan tiene como mucho una hora, ningún otro run ocupa el workspace,
   `main` no avanzó y, por último, el estado actual (y el de entrada, si HCP lo informa)
   sigue siendo el del plan.
   Envía **una sola** solicitud de Apply (nunca la repite) y espera el resultado real del
   Apply antes de seguir.
5. El resto es automático: state version exacto del Apply → outputs → preflight estricto de
   Pages → espera de cloud-init → servidor (converge con Keycloak P2, deploy con migración) →
   identidad y health → registro del último éxito → publicación del frontend.

Sin Terraform (solo deploy o solo frontend) hay una sola aprobación, la de *Producción*.
Un plan de Terraform sin cambios que además despliega pide las dos; la segunda autoriza el
deploy.

**Confirmación fuera de GitHub.** Un Owner de HCP puede confirmar el run desde la UI de HCP;
la cadena no lo puede impedir. Si lo detecta (en la revisión del plan o al ir a confirmar),
nunca lo atribuye a la aprobación de GitHub: no cancela el Apply ya aceptado, lo observa
hasta que termina, lo informa como `out_of_band` y **falla antes del servidor y de Pages**.
Para seguir, el operador revisa el resultado de ese Apply y vuelve a correr la cadena
completa (*Re-run all jobs*): el plan nuevo parte del estado resultante y pasa por las dos
aprobaciones.

**Respuesta incierta de HCP a la solicitud de Apply** (sin respuesta, 5xx): la cadena no
repite la solicitud; relee el run. Si HCP lo muestra confirmado después de la solicitud,
sigue (`accepted_after_ambiguous`); si no se puede atribuir, lo trata como confirmación fuera
de GitHub; si sigue sin confirmar, falla y la limpieza descarta el saved plan. Aceptar la
solicitud no es aplicar: solo un Apply terminado (`applied`) con su state version enlazado
habilita el deploy.

Las imágenes pasan por el mismo escaneo y la misma política de vulnerabilidades que
cualquier deploy: una instalación nueva no tiene excepciones.

## Fallos, reintentos y cancelación

| dónde falla | estado que deja | cómo seguir |
|---|---|---|
| credenciales, workspace (auto-apply, permisos, identidad), frescura antes del plan | nada mutado | corregir y re-run |
| subida de la configuración (HCP no la registra como provisional, la subida falla o no se procesa) | una configuración sin run (provisional: no pasa a ser la actual) | corregir y re-run; nunca se recurre a una configuración no provisional |
| creación del saved plan (falla o se corta después de crearlo) | el run propio, si existe, se busca por su mensaje y se descarta o cancela | re-run |
| plan (falla, override de políticas, JSON ilegible con cambios, acción desconocida, conteos contradictorios, estado de entrada contradictorio, estado cambiado desde la captura, configuración distinta, `main` avanzó) | sin Apply; el saved plan propio se descarta (o terminó sin cambios) | corregir y re-run |
| segunda aprobación rechazada o vencida | sin Apply; el saved plan queda sin confirmar: no ocupa el workspace y HCP lo descarta si el estado cambia | re-run completo (o el run del commit nuevo) |
| evidencia del plan sobre Pages insuficiente, frescura o revalidación antes de confirmar (estado cambiado, plan de más de una hora, otro run activo, auto-apply) | sin Apply; el saved plan se descarta | re-run completo: un plan nuevo |
| solicitud de Apply rechazada (409) o incierta y sin confirmación | sin Apply; el saved plan se descarta si todavía se puede | re-run completo |
| confirmación fuera de GitHub | Apply aceptado por HCP (observado hasta terminar); servidor y Pages sin cambios | revisar ese Apply; re-run completo |
| Apply terminado con error | estado parcial según el error de HCP | revisar el error; corregir y re-run completo (el plan nuevo parte de ese estado) |
| Apply parcial (p. ej. creó el proyecto de Pages y falló antes del CNAME o de la asociación del dominio) | proyecto con la identidad correcta pero incompleto | re-run completo con Terraform: el sondeo lo admite como `incomplete`, el plan debe mostrar la creación de la asociación, y el preflight estricto exige el proyecto completo antes de publicar. Un run sin Terraform (solo frontend o deploy) falla ante un proyecto incompleto |
| cloud-init falló, quedó degradado (errores recuperables), sigue corriendo, no se pudo leer (salida ilegible, forma inesperada o ssh/comando remoto con código distinto de 0) o venció el plazo | servidor creado, sin configurar (el diagnóstico dice cuál de los casos) | revisar `cloud-init status --long` con el usuario `ops`; corregir y re-run (en un servidor nuevo, recrearlo es también válido) |
| después del Apply (preflight de Pages, SSH, converge, deploy, health) | infraestructura creada; servidor sin último éxito o con el anterior; Pages sin cambios | corregir y re-run completo: el plan nuevo no tiene cambios y el resto converge de nuevo |
| publicación de Pages | release parcial: servidor registrado, frontend anterior (o ninguno) | re-run; republicar el mismo contenido es inocuo |

- **Re-run de solo los jobs fallidos**: si *Producción* falló antes de confirmar por una causa
  transitoria y su limpieza no descartó el saved plan, un *Re-run failed jobs* reutiliza el
  plan revisado (mismo mensaje del run de HCP) siempre que siga vigente (sin cambios de
  estado, menos de una hora). Si no, falla en la revalidación sin aplicar: usar *Re-run all
  jobs*.
- **Cancelación o vencimiento**: si *Plan de Terraform* falla o se cancela después de crear
  el run, su paso de limpieza lo descarta (o lo cancela si sigue planificando). En
  *Producción*, *Liberar el saved plan de este run* corre siempre que había que aplicar: si
  el saved plan sigue sin confirmar, lo descarta; si HCP ya aceptó el Apply (confirmado o
  aplicando), no lo toca y lo informa: cancelar el job no deshace un Apply. El siguiente run
  espera a que el workspace quede libre y planifica sobre el estado resultante.
- **Si GitHub no llegó a registrar el id del run** (la creación falló o se cortó después del
  POST), la limpieza lo busca por el mensaje exacto del intento entre los 20 runs más
  recientes del workspace y solo actúa sobre ese.
- **Run huérfano** (la limpieza no pudo actuar: runner terminado de golpe, HCP rechazó el
  descarte, o la lista de runs no respondió). El resumen lo dice cuando el paso corrió. Un
  saved plan sin confirmar no bloquea al siguiente run (HCP lo descarta cuando el estado
  cambia); un run que sí ocupa el workspace detiene al siguiente en *Esperar a que HCP
  Terraform esté libre*. Recuperación excepcional en HCP:
  1. Workspace `piedrazul-hetzner` → *Runs*, buscar el run con el mensaje
     `Producción <sha> — run <run_id>/<intento>` del run de GitHub afectado.
  2. Si está esperando confirmación o planificando: **Discard run** (o *Cancel run* mientras
     planifica), con un comentario que cite el run de GitHub. **Nunca confirmarlo desde HCP**:
     esa confirmación no pasa por la revisión de GitHub.
  3. Si ya está aplicando o aplicado: **no cancelarlo** (cancelar un Apply en curso puede dejar
     el estado a medias); dejarlo terminar. El siguiente run planifica sobre ese estado.
  4. Volver a lanzar el run de GitHub (re-run completo o el siguiente push).
- **Concurrencia**: *Plan de Terraform* y *Producción* comparten el grupo
  `piedrazul-production-hetzner`, y solo la punta de `main` puede iniciar una mutación. Si un
  commit nuevo planifica mientras un saved plan anterior espera la segunda aprobación, el
  anterior falla en la frescura al confirmar; si otro Apply cambia el estado, HCP descarta el
  saved plan y la revalidación lo detecta.
- El rango de push solo avanza cuando el run termina en éxito; un primer push fallido deja
  el siguiente push también en modo árbol completo. Un job saltado nunca cuenta como hecho:
  con Terraform, *Plan de Terraform* debe terminar bien con un resultado válido.

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
  state versions.
- Que el plan Free de HCP admita configuraciones **provisionales** y **saved plans** vía API, y
  que la respuesta los confirme (`provisional`, `auto-queue-runs`, `save-plan`).
- Que un saved plan con cambios termine en `planned_and_saved` con `is-confirmable`, y uno sin
  cambios en `planned_and_finished` (la cadena falla cerrado ante `planned_and_saved` sin
  cambios); que el run informe `created-at` y, al confirmarse, `status-timestamps.confirmed-at`.
- `input-state-version` es **opcional**: en el ensayo del 2026-10-28 (run
  `run-Y3WYSQ3tawEn5DQQ`, `planned_and_saved`) HCP la omitió, y la cadena se liga al estado
  por captura y relectura (`bracketed`). Queda por observar: si alguna vez aparece (y con qué
  forma) en saved plans, y si aparece con `data: null` en un workspace **con** estado (la
  cadena lo trata como contradicción y falla cerrado).
- Que HCP descarte un saved plan cuando el estado cambia antes de confirmarlo (documentado,
  no ensayado): la cadena lo ve como `discarded` o como `409` al confirmar y falla sin Apply.
- Que `TF_API_TOKEN` tenga `can-queue-run` y `can-queue-apply`, pueda leer el JSON del plan y
  pueda confirmar, descartar y cancelar; y que los conteos del plan de HCP coincidan con las
  acciones del JSON (la cadena falla cerrado si no).
- Que `POST /runs/<id>/actions/apply` sobre un saved plan responda `202` y el run pase a
  `confirmed`/`apply_queued`, y que el paso de limpieza corra al cancelar el job.

Ensayo mínimo recomendado, con autorización propia y sin Apply: un plan con
`run_terraform_apply` (o el job *Plan de Terraform* solo) y **rechazar** la segunda
aprobación; comprobar en el resumen la revisión y la identidad, y en HCP que la configuración
quedó provisional y que el saved plan no ocupa el workspace (luego descartarlo). Antes del
primer Apply real, auto-apply debe seguir deshabilitado y el plan revisado debe afectar solo a
la infraestructura nueva esperada.
- Que `cloud-init status --wait` / `--format json` se comporten así en la imagen
  `ubuntu-24.04` de Hetzner, y que la plantilla de cloud-init termine **sin** errores
  recuperables: un estado degradado (p. ej. por una clave de configuración obsoleta) detiene
  la instalación hasta corregir la plantilla.

## Limitaciones conocidas

- **Confirmación fuera de GitHub**: un Owner de HCP puede confirmar, descartar o aplicar desde
  la UI de HCP; la cadena no lo puede impedir. Lo detecta, no lo atribuye a GitHub y se
  detiene antes del servidor (ver arriba).
- **Revisión pública del plan**: muestra rutas de atributos y solo los valores clasificados.
  Para ver un valor no clasificado hace falta HCP (diagnóstico excepcional); agregar una
  clase publica ese valor en los resúmenes del repositorio.
- **Protección del servidor con datos**: la cadena muestra un reemplazo o una destrucción del
  servidor como efecto destructivo y depende de la decisión del operador en la segunda
  aprobación. Antes de introducir datos clínicos reales hace falta una protección propia
  (nativa de Terraform/Hetzner) y una recuperación de datos verificada; no es parte de esta
  cadena.
- **Identidad del servidor por SSH**: la espera de SSH, la barrera de cloud-init y Ansible
  conectan con `StrictHostKeyChecking=no`: la clave del host no se verifica. Se confía en que
  `server_ip` (output del state version exacto de este run) es el servidor creado por
  Terraform. Es una limitación previa a este trabajo y queda fuera de su alcance.
- Convergencia completa de un servidor limpio (Keycloak P2, identidad inicial) e
  instalación de punta a punta.
