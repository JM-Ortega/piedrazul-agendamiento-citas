# Instalación nueva de producción (y actualizaciones posteriores)

La misma cadena, `.github/workflows/production-hetzner.yml`, instala desde cero y actualiza
una instalación existente. Un servidor nuevo o reemplazado se instala en **dos runs explícitos**
(ADR-007), con una autenticación fuera de banda entre ambos; no depende de un VPS, un proyecto
de Pages, un estado de Terraform ni un último éxito previos. Un servidor existente y de
confianza sigue con un solo run, sin pasos manuales.

| etapa | qué hace | qué nunca hace |
|---|---|---|
| **Run P** (`stage=provision`) | comprobaciones previas de la instalación completa, plan revisado, Apply | SSH, configuración, deploy, publicación del frontend, último éxito |
| **Fuera de banda** (operador) | autentica la clave del host por la consola de Hetzner y registra el pin | ningún workflow lo hace |
| **Run I** (`stage=install`) | plan nuevo sin cambios, identidad verificada, converge, deploy, health, frontend | Terraform Apply |

> **Estado de verificación.** El contrato está implementado y probado sin red
> (`infra/tests/production-bootstrap-check.sh`, `infra/tests/pages-release-check.sh`,
> `infra/tests/production-stage-check.sh`, con respuestas simuladas de HCP, Cloudflare, GitHub y
> cloud-init). La verificación SSH estricta tiene pruebas con OpenSSH y Ansible reales contra un
> `sshd` desechable en Docker (`infra/tests/ssh-host-trust-check.sh`). La base desde un `PGDATA` vacío,
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
se valida antes del Apply si el plan crea o reemplaza el servidor: sin ella válida no se
aplica nada; ver [administrador-keycloak.md](administrador-keycloak.md)) y, si se habilita, el primer
administrador de negocio ([primer-admin-negocio.md](primer-admin-negocio.md)).

**GitHub, variables:**

- `PRODUCTION_SSH_HOST_PIN`, solo en el environment `production-hetzner`: la identidad autenticada
  del host (no es un secreto). No se define antes de Run P: la registra un operador después
  (ver *Paso fuera de banda*). Una copia en el repositorio o la organización hace fallar la
  cadena: `vars` no distingue el origen y esa copia podría ocupar el lugar del pin protegido.
- `PRODUCTION_PUSH_DEPLOY`, de repositorio: sin definir o `deferred` difiere los push; `enabled`
  los procesa; otro valor hace fallar el push. Se habilita **después** de instalar, y Run P
  exige que esté retenida si el plan crea o reemplaza el servidor. No definirla en el environment:
  los push la leen sin environment y esa copia no cambiaría su decisión.

**Correo.** SendGrid está deshabilitado en producción (`notifications_sendgrid_enabled: false`
en `group_vars`): la cadena no exige ni entrega `PIEDRAZUL_BACKEND_MAIL_SEND`, el `.env`
queda con `SENDGRID_API_KEY` vacía y el backend arranca sin ella. No hay envío externo de
correo. Las notificaciones de citas caen al proveedor de consola, que solo las registra en el
log del backend y las deja `FAILED` (`CONSOLE_DIAGNOSTIC_ONLY`): nadie las recibe. Los códigos de verificación, en cambio, no se emiten: sin un canal apto para
ellos, `POST /api/patients/link-user-account/request-code` responde `503`
(`VERIFICATION_DELIVERY_UNAVAILABLE`) antes de buscar el documento, sin generar código, sin
invalidar el activo, sin consumir el cupo de solicitudes y sin encolar nada; el arranque lo
avisa una sola vez en el log (`[OTP_DELIVERY] Sin canal apto…`); los rechazos no repiten ese
aviso ni dejan traza (solo una línea `DEBUG`). La vinculación de cuentas por código no es operable en esta instalación
hasta que exista un proveedor de correo apto para códigos (SES, pendiente); SendGrid, aunque se
habilite, no cuenta como canal para códigos. La consola es diagnóstico, no entrega:
`NOTIFICATIONS_CONSOLE_SHOW_SENSITIVE=true` (el `.env` no la lleva; vale `false` por defecto)
solo habilita el diagnóstico por consola si no hay ningún proveedor real, y solo para datos
exclusivamente sintéticos, nunca aquí. En ese modo se aceptan las solicitudes (incluso con
contactos ficticios), pero el envío queda `FAILED` (`CONSOLE_DIAGNOSTIC_ONLY`) y no cuenta como
entregado; con un proveedor real habilitado el código nunca llega a la consola. Con un canal de
correo apto, una persona sin correo utilizable (vacío, mal formado o de dominio `.invalid`)
recibe `409` (`VERIFICATION_CONTACT_UNUSABLE`) sin cambios en su código activo; el teléfono no
cuenta mientras no haya SMS ni WhatsApp. Un `200` significa solicitud aceptada y envío
encolado, no entrega; un envío `ACCEPTED` tampoco prueba la entrega, y un intento `UNKNOWN`
(`ACCEPTANCE_UNKNOWN`, evento `NOTIFICATION_ACCEPTANCE_UNKNOWN`) significa que el destinatario pudo
recibirlo y no se reintenta (ver [despacho de notificaciones](../../architecture/overview.md#despacho-de-notificaciones),
que también explica la recuperación del trabajo interrumpido). `LOG_LEVEL_SQL_BIND` debe seguir en `WARN` (`log_level_sql_bind`): en
`TRACE` el log recibe los valores enlazados, incluido el código. Por lo mismo, `LOG_LEVEL_ROOT`
no debe bajar a `DEBUG` (`log_level_root`): con SendGrid habilitado, el log de red de su
cliente HTTP (`org.apache.http.wire`) escribe el cuerpo de cada correo. Los flujos que dependen de
recibir un correo quedan fuera de esta instalación y de su verificación. Si se habilita
SendGrid sin entregar la clave al converge, la configuración del servidor falla antes de
escribir `.env`. `NOTIFICATION_ENCRYPTION_KEY` no es un requisito: ningún componente la usa.

## Cómo se inicia

**Antes de promover a `main`:** dejar `PRODUCTION_PUSH_DEPLOY` sin definir. El push de la
promoción queda entonces registrado como **diferido**: termina en `success`, su título dice
`Push diferido, sin deploy`, no toca nada y no avanza el rango. No hace falta provocar ningún
fallo. Un push nunca instala ni crea un servidor.

La promoción debe ser un merge completo de `develop`: `main` todavía contiene
`deploy-hetzner.yml`, `_deploy-server.yml` y `terraform-hetzner-apply.yml`, que despliegan y
aplican por push y que `develop` eliminó. Un cherry-pick o una promoción parcial los dejaría
activos.

**Run P**, dispatch desde `main` con todos los flags de la instalación:

```bash
gh workflow run production-hetzner.yml --ref main -f stage=provision -f run_terraform_apply=true -f run_host_config=true -f run_build=true -f run_build_postgres=true -f run_app_deploy=true -f run_frontend_publish=true
```

**Run I**, después del paso fuera de banda. Run I rechaza `run_terraform_apply=true`, aunque
siempre planifica:

```bash
gh workflow run production-hetzner.yml --ref main -f stage=install -f run_terraform_apply=false -f run_host_config=true -f run_build=true -f run_build_postgres=true -f run_app_deploy=true -f run_frontend_publish=true
```

Ambas etapas exigen la instalación completa y rechazan `image_tag`, `keycloak_recovery` y
break-glass, que van en un dispatch `ordinary`. Un dispatch `ordinary` cuyo plan cree, reemplace o
destruya el servidor falla antes de la segunda aprobación y el saved plan se descarta.

**Retención de los push.** Si el plan de Run P crea o reemplaza el servidor, `PRODUCTION_PUSH_DEPLOY`
debe estar sin definir o en `deferred`; si no, Run P falla antes de la segunda aprobación y el
saved plan se descarta, sin Apply. La cadena solo lee la variable, no la cambia: el operador la
corrige y vuelve a lanzar Run P. Se comprueba otra vez justo antes del Apply, por si se levantó
mientras el run esperaba la aprobación.

Una recuperación que no crea ni reemplaza el servidor (plan sin cambios, o solo los recursos que
faltaron tras un Apply parcial) no necesita esa retención y sigue aunque los push estén habilitados.

## Qué hace el operador en Run P

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
   *Producción*: esa segunda aprobación autoriza aplicar **ese** saved plan. En Run P no autoriza
   nada más: el run termina sin tocar el servidor.
4. **Producción revalida y confirma.** Justo antes de confirmar comprueba: workspace sin
   auto-apply y con permiso de confirmar, el run es el de este run de GitHub (mensaje, plan,
   configuración), el plan tiene como mucho una hora, ningún otro run ocupa el workspace,
   `main` no avanzó y, por último, el estado actual (y el de entrada, si HCP lo informa)
   sigue siendo el del plan.
   Envía **una sola** solicitud de Apply (nunca la repite) y espera el resultado real del
   Apply antes de seguir.
5. **Fin de Run P.** State version exacto del Apply → outputs → preflight estricto de Pages. El
   resumen muestra la **instancia** y la **dirección** del estado exacto y uno de estos resultados:

   | resultado | cuándo | qué significa |
   |---|---|---|
   | `PROVISIONED / TRUST PENDING / NOT INSTALLED` | el Apply creó o reemplazó el servidor | falta el paso fuera de banda y Run I; este estado no vence y nada lo destruye solo |
   | `INFRASTRUCTURE UNCHANGED / TRUST AND INSTALLATION NOT EVALUATED` | el plan salió sin cambios (p. ej., el Apply ya se había completado) | no se aplicó nada |
   | `INFRASTRUCTURE UPDATED / TRUST AND INSTALLATION NOT EVALUATED` | el Apply terminó sin crear ni reemplazar el servidor (p. ej., recuperación de un Apply parcial) | se aplicaron otros recursos |

   Los dos últimos no evalúan la confianza ni la instalación: solo informan el estado. Si Run P
   quedó pendiente, seguir con el paso fuera de banda y Run I. Un run fallido o cancelado no
   muestra ninguno de estos resultados: nunca informa un aprovisionamiento exitoso.

Sin Terraform (solo deploy o solo frontend) hay una sola aprobación, la de *Producción*.
Un plan de Terraform sin cambios que además despliega pide las dos; la segunda autoriza el
deploy.

## Paso fuera de banda: autenticar el host

Lo hace un operador autorizado, por un canal que no pasa por la conexión SSH. Nunca copiar la
huella de un log de GitHub ni de `ssh-keyscan`: eso solo repite lo que dice quien responde.

1. En la consola de Hetzner, abrir la instancia cuyo id coincide con el resumen de Run P, y
   confirmar que su IP pública es la del resumen.
2. Abrir su **consola** (no SSH). Un `root` con contraseña sirve aquí: SSH lo rechaza, y esa
   contraseña es solo la vía de recuperación por consola. Usar la consola no obliga a bloquear
   `root` después.
3. Comprobar que la inicialización terminó y leer la huella. El servidor debe informar la misma
   instancia:

   ```bash
   cloud-init status --long
   ```

   ```bash
   curl -s http://169.254.169.254/hetzner/v1/metadata/instance-id
   ```

   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
   ```

4. Registrar el pin en el environment `production-hetzner`, en una sola línea con espacios
   simples, por la interfaz de GitHub o con:

   ```bash
   gh variable set PRODUCTION_SSH_HOST_PIN --env production-hetzner --body "<server_id> <server_ip> ssh-ed25519 SHA256:<huella>"
   ```

5. Comprobar `PRODUCTION_PUSH_DEPLOY` y lanzar Run I.

## Qué hace Run I

1. Valida la **forma del pin** sin red, antes de cualquier mutación.
2. **Plan nuevo**, con su primera aprobación. Debe salir sin cambios: con cambios, el run falla
   antes de la segunda aprobación y el saved plan se descarta. Si el cambio es esperado, va en
   Run P. Un plan sin cambios no prueba nada sobre el sistema operativo del host, así que
   Run I configura el servidor igual.
3. Segunda aprobación: autoriza configurar y desplegar. Sin Apply.
4. **Identidad**, antes de escribir la clave SSH y de leer nada del servidor: pin ↔ instancia y
   dirección del state version exacto ↔ clave ofrecida (leída sin credenciales). Solo si coincide
   se crea la configuración SSH de ese run; todos los pasos siguientes usan verificación estricta.
5. SSH → cloud-init → **sshd efectivo** antes de los secretos del converge (sin `root` ni
   contraseñas por SSH) → último éxito → imágenes, escaneo y política autoritativa, evaluados de
   nuevo → frescura → converge con Keycloak P2 → deploy con migración → identidad y health →
   registro del último éxito → publicación del frontend.

Los datos que vencen no se heredan de Run P. Si algo falla después de la verificación, **se vuelve
a lanzar Run I** sobre la misma instancia, sin otra autenticación fuera de banda: el pin sigue
vigente.

## Después de instalar: habilitar los push

Cuando Run I termine bien, definir `PRODUCTION_PUSH_DEPLOY=enabled` en el repositorio. El primer
push procesado cubre todo el rango pendiente: si no hay un rango procesado previo, clasifica el
árbol completo (Terraform y deploy, con sus aprobaciones). Dispatches y pushes diferidos nunca
avanzan el rango. Para detener de nuevo los push, volver a `deferred`.

## Ciclo de vida de la identidad

| situación | qué ocurre | cómo seguir |
|---|---|---|
| Servidor reemplazado (Run P), o creado de nuevo tras una destrucción | el pin es de otra instancia: `[pin obsoleto]`, sin contacto de red | autenticar y registrar otro pin; Run I |
| Servidor borrado fuera de banda | un deploy sin Terraform falla al leer la clave (`[sin respuesta]`); con Terraform, el plan crea el servidor y falla fuera de Run P | Run P, autenticar, Run I |
| Misma IP en una instancia nueva | `[pin obsoleto]`: una dirección o un nombre reutilizados no heredan confianza | autenticar y registrar otro pin |
| Reinstalación de la misma instancia, o rotación de claves | `[identidad distinta]` en el primer intento | autenticar la nueva huella por la consola; registrar otro pin. Si no hubo una reinstalación conocida, tratarlo como incidente |
| Pin borrado o corrupto | falla sin red, antes de mutar | volver a autenticarlo por la consola; nunca recuperarlo de un log |
| Copia del pin en repositorio u organización | falla sin conectar | eliminar esa copia |
| Servidor apagado o firewall | `[sin respuesta]`: conectividad, no identidad | corregir; el pin no cambia |
| Converge o deploy interrumpido | misma instancia, mismo pin | relanzar Run I |
| `sshd` efectivo inseguro | falla antes de entregar secretos | corregir desde `ops` o la consola; relanzar |

Antes de un Run P que cree o reemplace el servidor de una instalación activa, volver a
`PRODUCTION_PUSH_DEPLOY=deferred`: Run P lo exige. Mientras la variable siga retenida, los push
quedan diferidos y el rango pendiente se cubre al habilitarlos después de Run I. Si alguien la
habilita antes de terminar Run I, un push con el pin anterior falla contra el pin (sin efectos y
sin avanzar el rango), pero un push con el pin nuevo ya registrado sí puede tocar el servidor sin
instalar: por eso solo se habilita al final.

**Retirar el servidor** no es una operación de esta cadena: un plan que lo destruye sin crear otro
falla en todas las etapas antes de la segunda aprobación y el saved plan se descarta. Un retiro
deliberado es una decisión operativa aparte, sin procedimiento en este repositorio.

**Reemplazar un servidor con datos** queda fuera de este procedimiento: destruye sus volúmenes y
requiere una recuperación verificada aparte (ver *Limitaciones conocidas*).

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
| Run P: Apply interrumpido o ya completado | según lo que HCP haya aplicado | volver a lanzar Run P: aplica lo que falte o, sin cambios, informa la identidad |
| el plan de Run I tiene cambios | sin Apply; el saved plan se descarta | revisar el plan; si corresponde, Run P y después Run I |
| el plan de un run ordinario crea o reemplaza el servidor | sin Apply; el saved plan se descarta | Run P, fuera de banda, Run I |
| el plan destruye el servidor sin crear otro (cualquier etapa) | sin Apply; el saved plan se descarta | revisar por qué el plan lo destruye; un retiro es una decisión aparte |
| Run P crea o reemplaza sin los push retenidos, o se levantaron antes del Apply | sin Apply; el saved plan se descarta | `PRODUCTION_PUSH_DEPLOY=deferred` y volver a lanzar Run P |
| identidad del host (pin ausente, corrupto u obsoleto, clave distinta) | nada leído ni mutado en el servidor; el pin no cambia | ver *Ciclo de vida de la identidad* |
| después de la identidad (`sshd`, SSH, converge, deploy, health) | infraestructura creada; servidor sin último éxito o con el anterior; Pages sin cambios | corregir y relanzar Run I: el pin sigue vigente |
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
- El rango de push solo avanza si el run es un push ordinario, termina en éxito y deja el job
  *Rango procesado*. `success` no basta: un push diferido también termina así. Un fallo, una
  cancelación, un run histórico sin ese job o un dispatch no lo avanzan; un primer push fallido
  deja el siguiente también en modo árbol completo. Si GitHub no responde al leer los jobs, el
  run falla: la falta de datos nunca se lee como rango procesado. Un job saltado nunca cuenta
  como hecho: con Terraform, *Plan de Terraform* debe terminar bien con un resultado válido.

## Qué significa "instalado"

Un Run P exitoso **no** es una instalación: ninguno de sus resultados dice que el servidor esté
configurado. Después de Run I, el resumen separa cuatro resultados que no
deben confundirse:

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

- Las etapas y el diferido no se han ejecutado en GitHub Actions: `run-name` con `vars`, el input
  `stage` de tipo `choice` (11 inputs; el límite actual documentado es 25), el job *Rango
  procesado* con dependencias saltadas, su nombre en `/actions/runs/<id>/jobs?filter=latest`, y que
  `vars` en `classify` no vea las variables del environment.
- Que el primer push procesado después de la instalación cubra el rango y deje la señal.
- En Hetzner: el acceso real a la consola, el acceso de `root` por consola en un servidor sin clave
  SSH asignada, `metadata/instance-id` y que la huella leída coincida con la que recibe el runner.
- Que `ssh-keyscan` desde un runner de GitHub alcance el puerto 22 a través del firewall real, y
  que sus conexiones no cuenten para `fail2ban`.
- Que el output `server_id` de HCP sea numérico y coincida con el id de la consola.
- Que `vars.PRODUCTION_PUSH_DEPLOY` en un job con environment se resuelva con el valor vigente al
  empezar ese job, después de la aprobación (la segunda lectura de la retención depende de eso).
- Que `sudo -n sshd -T -C …` funcione para `ansible` en la imagen `ubuntu-24.04` de Hetzner (probado
  en un contenedor Ubuntu 24.04). Lee la configuración, no el proceso en ejecución.
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
- **Identidad del servidor por SSH**: la confianza inicial depende del operador que lee la consola
  y de quien puede escribir en el environment. La cadena verifica el pin, no a quien lo registró.
- **Pin en `vars`**: GitHub no informa el origen de una variable. La cadena detecta una copia de
  repositorio u organización desde `classify`; el resto depende de la protección del environment.
- Convergencia completa de un servidor limpio (Keycloak P2, identidad inicial) e
  instalación de punta a punta.
