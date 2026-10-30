# Arquitectura

## Objetivos

- Brindar a los pacientes la autonomía de agendar sus propias citas, ya sea seleccionando directamente al médico de su preferencia o permitiendo que el sistema asigne automáticamente un médico disponible según la especialidad solicitada, balanceando así la carga laboral entre el personal médico.
- Mantener disponible un canal de agendamiento manual para los pacientes con acceso limitado a la tecnología, sin comprometer el objetivo de agilizar el proceso y reducir la carga de los doctores.
- Centralizar la gestión diaria de citas para los médicos y permitir una configuración dinámica de su disponibilidad y especialidades ante la variabilidad de horarios y roles del personal.
- Garantizar un nivel de seguridad robusto para la información gestionada por el sistema.

## Contexto

Piedra Azul es una clínica cuyo proceso de agendamiento de citas se ha basado históricamente en la gestión manual realizada por los doctores, quienes reciben y organizan las solicitudes de citas enviadas por WhatsApp, lo que genera jornadas de trabajo extensas dedicadas a esta tarea administrativa. El sistema a constribuye digitalizar y automatizar este proceso, administrando información sensible de pacientes, médicos, usuarios administrativos y citas. Además, garantiza consistencia transaccional en operaciones críticas de agenda, como la creación y cancelación de citas, y soportar consultas estructuradas mediante filtros de fecha, médico y estado.

## Componentes principales

- **Backend**: monolito modular construido con Spring Boot y Spring Modulith, organizado en módulos con responsabilidades y límites arquitectónicos bien definidos (ADR-003).
- **Frontend**: aplicación SPA construida con Angular (ADR-004).
- **Identidad y acceso**: Keycloak como proveedor de identidad (IdP), responsable de la autenticación, gestión de usuarios, credenciales y roles (ADR-001). Sus pantallas de autenticación usan un tema propio construido con Keycloakify (ADR-006).
- **Base de datos**: PostgreSQL como motor relacional principal (ADR-002).

## Dependencias

- Spring Data JPA como capa de acceso a datos del backend (ADR-002, ADR-003).
- Spring Security para la validación de JWT en el backend (ADR-001).
- Spring Modulith para la definición y verificación de límites entre módulos (ADR-003).
- Flyway para el versionado de migraciones de esquema de base de datos (ADR-002).
- Docker Compose para la orquestación local de PostgreSQL y Keycloak (ADR-005) y, en producción, para el stack desplegado en el servidor (`infra/compose/prod.yml`).

## Flujos importantes

### Autenticación y autorización

- El frontend autentica al usuario mediante Keycloak.
- Keycloak emite un JWT con la información del usuario y sus roles.
- El backend actúa como un _OAuth2 Resource Server_, validando el token recibido en cada solicitud.
- Las autorizaciones se aplican utilizando los roles contenidos en el JWT (ADR-001).

### Flujo de Manejo de Excepciones

**Propósito:** centralizar el manejo de errores mediante manejadores `@RestControllerAdvice` por módulo, que traducen cualquier excepción (validación, negocio o no prevista) en una respuesta HTTP estandarizada con formato `ProblemDetail` (RFC 9457).

**Flujo de ejecución:**

1. Ocurre un error en la petición (validación, regla de negocio o excepción no prevista).
2. Las excepciones de negocio implementan `BusinessException`, definiendo errorCode, status y module.
3. Un `@RestControllerAdvice` del módulo la captura y arma un ProblemDetail uniforme (título, detalle, código, módulo, timestamp) mediante utilidades compartidas.
4. Si nadie la captura, un manejador global de respaldo la registra y responde con `500 Internal Server Error` genérico.
5. El cliente siempre recibe un error con estructura consistente, sin importar su origen.

### Despacho de notificaciones

`DueNotificationJob` reclama los schedules vencidos (`FOR UPDATE SKIP LOCKED`, `PENDING` →
`PROCESSING`) y anota cuántos intentos tenía cada notificación. `NotificationDispatcherService` los
despacha uno a uno; un error en uno no abandona el resto del lote. Toda escritura del despacho pasa
por `NotificationDispatchStore`, que en una sola transacción bloquea la fila del schedule y exige que
siga en `PROCESSING` y que el número de intentos sea el esperado (cerca de propiedad); el estado se
lee fresco dentro de la transacción. Si la cerca falla, no se invoca ningún proveedor más ni se
modifican notificación ni schedule.

Por canal: proveedor registrado y restricciones de consola → destinatario (correo vacío, mal formado
o de dominio `.invalid`, con la misma regla que la admisión de códigos) y contenido (plantilla,
marcadores sin resolver) → intento `PROCESSING` confirmado → vigencia del código con reloj fresco →
proveedor, fuera de toda transacción → intento, notificación y schedule escritos juntos. Un fallo
local se registra como intento `FAILED`/`PERMANENT` sin pasar por `PROCESSING` y nunca llega al
proveedor ni a su circuit breaker.

| Resultado del proveedor | Significado | Intento | Notificación / schedule | Reintento / otro canal |
|---|---|---|---|---|
| `ACCEPTED` | El proveedor lo aceptó; **no** prueba que llegara al buzón | `ACCEPTED` | `ACCEPTED` / `SENT` | No / no |
| `UNKNOWN` | No se puede descartar que lo aceptara (excepción, tiempo agotado, respuesta ambigua) | `UNKNOWN`, `ACCEPTANCE_UNKNOWN` | `FAILED` / `FAILED` | No / no |
| `RETRYABLE` | No aceptación transitoria probada; incluye circuit breaker abierto (no se invocó) | `FAILED`, `TEMPORARY` o `CIRCUIT_OPEN` | sigue / `PENDING` con `nextRetryAt` | Sí, dentro de la política; agotada, otro canal |
| `REJECTED` | Rechazo permanente de destinatario o contenido | `FAILED`, `PERMANENT` | `FAILED` si no queda canal | No / siguiente canal elegible |
| `PROVIDER_ERROR` | No aceptación probada que requiere intervención (configuración, consola) | `FAILED`, `PERMANENT` | `FAILED` si no queda canal | No / siguiente canal elegible |

Política de reintentos: correo 60 s y 300 s (3 intentos), SMS y WhatsApp 60 s (2), consola ninguno.
Un canal rechazado o con los reintentos agotados no se vuelve a intentar en ejecuciones posteriores.
Antes de cada reintento se revalida la elegibilidad; no se programa uno que llegaría con el código
vencido. `DELIVERED` solo podría venir de una confirmación posterior del proveedor (no implementada):
un envío síncrono nunca queda como entregado.

**Aceptación desconocida.** Notificación `FAILED` significa "terminó sin aceptación confirmada"; el
intento `UNKNOWN` con `error_code = ACCEPTANCE_UNKNOWN` (el código original del proveedor queda en
`error_message`) distingue el caso, y el log emite `NOTIFICATION_ACCEPTANCE_UNKNOWN` (sin contenido
ni mensajes de excepción) para revisión del operador: el destinatario pudo haber recibido el
mensaje. Las excepciones de SendGrid no aportan evidencia de no aceptación y se tratan así, salvo el
circuit breaker abierto. Para un proveedor que no declara su resultado, la no aceptación solo se
deduce de `FAILED` con `TEMPORARY`/`CIRCUIT_OPEN` (reintentable) o `PERMANENT` (rechazo); un
`FAILED` sin tipo o con tipo `UNKNOWN`, `BOUNCED`, `UNDELIVERED` o `CANCELLED` cuenta como
aceptación desconocida. `PROVIDER_ERROR` nunca se deduce: solo lo declara el proveedor.

**Diagnósticos del proveedor.** Lo que devuelve el proveedor no es confiable (puede repetir el
mensaje enviado). Antes de guardarlo en el intento o escribirlo en el log: un código de error que no
tenga forma de código o contenga un valor secreto de la notificación pasa a
`PROVIDER_DIAGNOSTIC_REDACTED`; con contenido sensible, el texto del proveedor se sustituye por una
descripción controlada (clasificación y código seguro), y sin él se conserva salvo que contenga un
secreto; un id de mensaje con un secreto se descarta. La clasificación, el reintento y el cambio de
canal no dependen de ese texto. Los logs propios de SendGrid (cuerpo de respuestas de error) quedan
fuera de esta frontera; SendGrid no es apto para códigos y, con él registrado, no se emiten (W0b).

**Códigos de verificación.** Verification pasa el vencimiento real del código (`expiresAt`, 5 min);
el despachador no entrega nada al proveedor a partir de ese instante (sin margen adicional) y cierra
como `EXPIRED`. Un código sin vencimiento legible cuenta como vencido. Cuando el despacho termina
(`ACCEPTED`, `FAILED` o `EXPIRED`), la misma transacción elimina `code` de `variables_json` y conserva
las variables no secretas; mientras quede un reintento, el código se conserva. Limitación aceptada
(D15-A1): si el proceso cae entre el intento en curso y su resolución, el código queda en claro
hasta que la recuperación resuelva ese trabajo. Las filas anteriores no se limpian (entorno
desechable).

**Consola.** Es diagnóstico, nunca entrega: todo mensaje impreso deja el intento `FAILED`
(`CONSOLE_DIAGNOSTIC_ONLY`, `PROVIDER_ERROR`) y, si es el último canal, la notificación `FAILED`.

**Trabajo interrumpido.** Un error antes de invocar al proveedor cierra como `FAILED`; uno después,
o una caída del proceso, deja el trabajo en `PROCESSING`. `StaleNotificationAttemptJob` lo recupera:
cada 5 min (`NOTIFICATIONS_STALE_ATTEMPT_FIXED_DELAY`) toma hasta 50 schedules, los más antiguos
primero, con un intento en curso sin actualizar desde hace 15 min
(`NOTIFICATIONS_STALE_DEFAULT_THRESHOLD`) o, sin intentos en curso, reclamados desde hace ese tiempo.
Cada uno se resuelve en su propia transacción, bajo el bloqueo del schedule y sobre el historial
completo de intentos, en la misma frontera que las escrituras del despacho; un schedule que no se
puede resolver no detiene a los demás y se reintenta en la siguiente pasada. Reclamos tardíos o en
paralelo son seguros porque el intento se inserta bajo ese bloqueo con la cerca de intentos: de dos
trabajadores con la misma cerca solo uno llega a invocar al proveedor.

Ninguna invocación ocurre sin un intento `PROCESSING` confirmado antes. Por eso la decisión se basa
en los intentos y nunca en comparar marcas de tiempo del reclamo: un intento en curso sin resultado
pudo llegar al proveedor, aunque se interrumpiera justo antes.

| Estado encontrado | Intento | Notificación / schedule | Código | Evento (`NOTIFICATION_STALE reason=`) |
|---|---|---|---|---|
| Intento en curso reciente, o reclamo reciente sin intentos en curso | sin cambio | sin cambio | se conserva | ninguno |
| Algún intento aceptado | aceptado intacto; uno en curso pasa a `UNKNOWN` | `ACCEPTED` / `SENT` | se elimina | `ACCEPTED` |
| Intento en curso antiguo, o un `UNKNOWN` en el historial | `UNKNOWN`, `ACCEPTANCE_UNKNOWN` | `FAILED` / `FAILED` | se elimina | `ACCEPTANCE_UNKNOWN`, más `NOTIFICATION_ACCEPTANCE_UNKNOWN` (`DISPATCH_INTERRUPTED`) |
| Sin aceptación posible y contenido vencido | sin cambio | `EXPIRED` / `EXPIRED` | se elimina | `EXPIRED` |
| Sin aceptación posible, vigente | sin cambio | sigue / `PENDING` sin demora | se conserva | `REQUEUED` |
| Lo anterior, tras 3 reencolados | sin cambio | `FAILED` / `FAILED` | se elimina | `REQUEUES_EXHAUSTED`, más `NOTIFICATION_FAILED` |
| Notificación ya terminada, schedule reclamado | en curso pasa a `UNKNOWN` | notificación intacta / schedule alineado | se elimina | `SETTLED` |
| Schedule ya terminado con un intento en curso antiguo | `UNKNOWN` | sin cambio | se elimina si la notificación terminó | `ATTEMPT_CLOSED` |
| Schedule ya terminado, sin intentos en curso | sin cambio | sin cambio | sin cambio | ninguno |

Reencolar no crea intentos ni consume reintentos: al reclamarse de nuevo, el despachador revalida
canales, presupuesto y vigencia, y es él quien cierra como `FAILED` un trabajo sin canales
disponibles. Los reencolados se cuentan en `dispatchRecoveries` dentro de `variables_json`, igual que
`expiresAt` es un dato del despacho y no del texto; no hay ventana de entrega tardía nueva. Un intento
en curso nunca se reenvía: es el riesgo aceptado D8-A, un mensaje puede perderse pero no se duplica
por una recuperación. El umbral debe superar lo que puede tardar una llamada real al proveedor, porque
un intento cerrado por antigüedad queda `UNKNOWN`: si después el proveedor responde, el estado no
cambia y el log emite `NOTIFICATION_LATE_ACCEPTANCE` con el id de mensaje, la evidencia para el
operador de que ese `UNKNOWN` sí se aceptó. `UNKNOWN` sigue siendo cierto: no se pudo descartar.

**Planificación compartida.** Todos los `@Scheduled` de la aplicación comparten un hilo (no se
configura `spring.task.scheduling.pool.size`) y el lote de 50 se envía en serie. Medido con 50
notificaciones sobre PostgreSQL real y proveedor sintético (`NotificationSchedulerLoadIT`): entre 18 y
29 ms de sobrecarga propia por envío; recuperación y demás jobs esperan al lote completo (unos 3,5 s con
50 ms por envío, unos 11,5 s con envíos de 100 ms y tres llamadas de 2 s); y un código que llega con el
lote en marcha espera lo mismo. Dos hilos eliminan la espera de los demás jobs (unos 55 ms), pero no la
del código (unos 11,5 s), que depende del envío en serie. A 0,3 s por envío serían unos 16 s (estimación). El lote queda acotado
solo si cada llamada al proveedor tiene un tiempo máximo: una llamada sin él detiene todo `@Scheduled`,
incluida la recuperación, mientras dure.

## Persistencia

- Modelo relacional normalizado para las entidades de negocio.
- Transacciones ACID para las operaciones críticas de agenda, como la creación y cancelación de citas.
- Integración mediante Spring Data JPA como capa de persistencia.
- Migraciones de esquema versionadas mediante Flyway (ADR-002).

## Seguridad

- La autenticación y la administración de identidades están delegadas en Keycloak.
- El backend no almacena ni gestiona contraseñas de los usuarios.
- Las autorizaciones se aplican en el backend según los roles incluidos en el JWT emitido por Keycloak (ADR-001).

## Despliegue

### Entorno local

- PostgreSQL y Keycloak se ejecutan como contenedores mediante Docker Compose.
- Puertos, usuarios y credenciales se configuran mediante variables de entorno.
- El backend y el frontend se ejecutan localmente durante el desarrollo, consumiendo los servicios levantados por Docker Compose (ADR-005).

### Producción

El repositorio define un modelo de despliegue de producción compuesto por:

- **Terraform** (ejecutado mediante HCP Terraform) para aprovisionar la infraestructura: servidor y firewall en **Hetzner**, y DNS, ajustes de zona, protección de origen y proyecto de Pages (frontend) en **Cloudflare**.
- **Ansible** para configurar el servidor y desplegar la aplicación.
- **Docker Compose** en el servidor de producción, que ejecuta Traefik (proxy inverso), el backend, Keycloak y PostgreSQL.
- **GitHub Actions** (`.github/workflows/production-hetzner.yml`) como cadena de producción detrás del environment `production-hetzner`: Apply, deploy del servidor y, con sus requisitos operativos cumplidos, publicación del frontend en **Cloudflare Pages** (ver [Publicación del frontend](#publicación-del-frontend-cloudflare-pages)).

Este documento no describe el estado en vivo de esa infraestructura. Los detalles están en:

- [`infra/terraform/live/prod/README.md`](../../infra/terraform/live/prod/README.md) — Terraform y postura de red declarada.
- [`infra/ansible/ANSIBLE.md`](../../infra/ansible/ANSIBLE.md) — configuración y despliegue con Ansible.
- [`infra/compose/prod.yml`](../../infra/compose/prod.yml) — stack Compose de producción.
- [`infra/ansible/ANSIBLE.md` — Cadena de producción](../../infra/ansible/ANSIBLE.md#cadena-de-producción-workflow) — intención, autoridad, escaneo y orden de la cadena.

El entorno OCI (`infra/terraform/live/oci/`) es histórico e inactivo y no es un destino de despliegue.

### Publicación del frontend (Cloudflare Pages)

El proyecto de Pages `piedrazul` (declarado en `infra/terraform/live/prod`, cuenta dedicada de Cloudflare) es de **subida directa** (*Direct Upload*): Terraform lo crea sin integración Git, sin builds de Cloudflare y sin previews automáticas, y el único publicador es la cadena de producción con `wrangler pages deploy`. Cloudflare no convierte un proyecto de subida directa en uno con Git ni al revés; un proyecto con Git no es un destino válido (ver *Autoridad única*). El estado real del proyecto vive fuera del repositorio; la cadena lo verifica en cada publicación.

**Artefacto.** El job `frontend_build` (antes del gate, solo `contents: read`) hace el único build de producción del commit, con el mismo comando y la misma versión de Node que CI Frontend (`frontend/.node-version`, `npm ci`, `npm run build`). `frontend-artifact.sh package` valida el sitio (`index.html`, recursos que referencia, exactamente un `main-<hash>.js`, sin enlaces simbólicos, límites de Pages) y lo sube como artifact `frontend-dist-<run_id>-<intento>`; su id y su sha256 son outputs del job. El job `production` lo descarga con `frontend-artifact.sh fetch`: mismo run, repositorio y commit, digest registrado por GitHub y sha256 validado. Pages no vuelve a construir.

**Destino.** `pages-release.sh target` toma el proyecto (`var.project`), la cuenta (`cloudflare_account_id`) y el dominio (`base_domain`) de `infra/terraform/live/prod/piedrazul.auto.tfvars`; la rama de producción es `main`.

**Autoridad única.** El proyecto debe ser de subida directa: sin `source` en la API (ausente o `null`). Cualquier `source` (GitHub, GitLab u otro) falla cerrado, también una integración Git con la publicación automática de producción deshabilitada: la arquitectura no tiene Git. Inmediatamente antes de publicar se repite la comprobación y se exige que el deployment de producción vigente siga siendo el que leyó el preflight (vacío en un proyecto nuevo, que debe seguir sin producción).

**Lectura del proyecto.** `pages-release.sh` lee el proyecto con el código HTTP explícito. Ausente solo es un 404 con el error de Pages "proyecto inexistente" (`8000007`) **y** una lista de proyectos de la cuenta que el token sí puede leer y que no lo contiene. Un 401/403, otro 404, un 5xx, sin respuesta, un cuerpo ilegible o una contradicción fallan cerrado: un token sin permiso o una cuenta equivocada nunca se leen como "instalación nueva".

**Sondeo y preflight.** Dos niveles: **identidad y autoridad** (nombre, rama `main`, subdominio `*.pages.dev`, subida directa) se exigen siempre y fallan cerrado; **completitud** (dominio propio asociado) es lo que Terraform reconcilia. Antes de la primera mutación del run, `pages-release.sh probe`: un proyecto completo con la identidad correcta pasa; uno ausente o incompleto (p. ej. un Apply anterior creó el proyecto y falló antes del dominio) solo se admite cuando el run aplica Terraform (`PAGES_ALLOW_PROVISIONING`), y entonces el plan de ese run debe **mostrar** la creación de lo que falta (`module.pages.cloudflare_pages_project.main` y `module.pages.cloudflare_pages_domain.main`, o solo la asociación si el proyecto existe): un JSON del plan ilegible, ambiguo o sin esa acción falla antes de confirmar el Apply, nunca se infiere. Un run sin Terraform (solo frontend o deploy) nunca acepta un destino incompleto. Después de Terraform y antes de tocar el servidor, `pages-release.sh preflight` exige estricto el proyecto real (también el recién creado o completado); la publicación solo corre con ese preflight en éxito.

**Dominio propio.** Terraform crea el CNAME del dominio al subdominio `*.pages.dev` (módulo `dns`, único dueño del registro) y después asocia el dominio al proyecto (`cloudflare_pages_domain`). El estado de activación del dominio (`custom_domain_status`: `active`, `pending`, …, o `unreadable`) se informa en el preflight y tras publicar, sin bloquear: un dominio pendiente (certificado en emisión, Cloudflare Access delante) no prueba ni activación ni fallo permanente, y la publicación se verifica por `*.pages.dev`, no por el dominio propio.

**Orden.** Saved plan revisado en GitHub (job `terraform_plan`) → sondeo de Pages → segunda aprobación y confirmación del saved plan → resultado del Apply → preflight estricto de Pages → converge/deploy → identidad → health → registro del último éxito del servidor → publicación del frontend. Si el run solo publica el frontend, no lee HCP ni toca el servidor, y la frescura (`verify-main-tip.sh`) se comprueba justo antes de publicar.

**Identidad de la publicación.** wrangler sube el sitio con el commit y un mensaje propio del intento (`production-hetzner <run_id>/<intento>`) y escribe en `WRANGLER_OUTPUT_FILE_PATH` el id que devolvió la creación del deployment. La publicación solo termina bien si hay exactamente uno, la API lo confirma como deployment de producción `ad_hoc` de `main` con ese commit y ese mensaje, terminó en `deploy: success`, es el único con ese mensaje, ningún deployment de producción posterior lo reemplazó, es el `canonical_deployment` del proyecto antes y después de comprobar el contenido, y tanto su URL propia como el alias de producción (`piedrazul.pages.dev`) sirven el `main-<hash>.js` validado. Coincidir solo en el commit no basta. El dominio propio no se usa para verificar: puede estar detrás de Cloudflare Access.

**Credencial.** `CLOUDFLARE_PAGES_API_TOKEN`, secreto del environment `production-hetzner` (no de repositorio; alcance declarado en `.github/scripts/audit-github-trust.sh`). Token de API dedicado con **Account → Cloudflare Pages → Edit** sobre la cuenta de Piedrazul y nada más; no se reutilizan el token de DNS ni el de Terraform. Lo reciben solo los pasos de credenciales, sondeo, preflight y publicación del job `production`; nunca Ansible ni el servidor. La verificación de credenciales rechaza que coincida con `CLOUDFLARE_DNS_API_TOKEN`. wrangler se instala desde `.github/pages-publish/package-lock.json` (versión exacta, `npm ci --ignore-scripts`).

**Resultado y reintentos.** El resumen del job muestra el artefacto, la producción anterior y el deployment publicado. El rango procesado avanza solo si el run termina en `success`, incluida la publicación cuando hacía falta.

| caso | efecto | recuperación |
|---|---|---|
| build/validación del frontend, artefacto, wrangler, credencial, sondeo o autoridad única fallan | ninguna mutación de producción (ni plan, ni Apply, ni servidor, ni Pages); pueden existir artefactos de jobs previos (p. ej. imágenes en GHCR) | corregir y re-run, o nuevo push |
| proyecto ausente y el run no aplica Terraform | el sondeo falla antes de mutar | dispatch con `run_terraform_apply` (o el push que cambie Terraform) |
| proyecto incompleto (Apply parcial anterior) | con Terraform: el plan debe crear lo que falta; sin Terraform: el sondeo falla antes de mutar | re-run completo con `run_terraform_apply` |
| el plan no muestra la creación de lo que falta (o su JSON no se puede leer), o el saved plan se descarta, vence o se rechaza la segunda aprobación | sin Apply (el saved plan propio se descarta o queda sin confirmar) | revisar la configuración de Terraform o el permiso del token para leer el JSON del plan; re-run |
| el proyecto real no pasa el preflight tras el Apply | Apply hecho; ni servidor ni Pages cambian | corregir el proyecto (o la configuración) y re-run: el plan nuevo no tiene cambios |
| Apply, deploy o health fallan | no se publica; Pages conserva la producción anterior | como cualquier fallo de la cadena |
| backend ok y la publicación falla | release parcial: backend nuevo registrado en el servidor, frontend anterior; el run falla y el rango no avanza | re-run del job o siguiente push (rango acumulado); republicar el mismo contenido es inocuo |
| otro deployment cambió la producción durante el run | la publicación falla antes de subir | revisar quién publicó; re-run |

No hay atomicidad ni vuelta atrás automática entre Cloudflare y Hetzner: un cambio del backend debe tolerar el frontend anterior durante esa ventana, y viceversa. Volver atrás el frontend es explícito: *Deployments → Rollback* en el dashboard de Pages (el resumen muestra la producción anterior) o un revert en `main`. Un dispatch con `run_frontend_publish` publica la punta de `main`. La verificación local es `infra/tests/pages-release-check.sh` y `infra/tests/production-bootstrap-check.sh` (sin red ni Cloudflare): prueban el contrato con respuestas simuladas, no el comportamiento real de la API (forma real de un proyecto de subida directa, código de error de proyecto inexistente, activación del dominio), que queda para la verificación contra el entorno.

**Resultados distintos.** Crear el proyecto, publicar y verificar por `*.pages.dev`, activar el dominio propio (y su certificado) y verificar la aplicación de punta a punta son resultados distintos: el resumen del run marca la instalación pública como **no verificada** mientras el dominio propio no esté `active`, y la cadena nunca verifica la aplicación de punta a punta.

**Alias públicos.** El alias de producción (`<proyecto>.pages.dev`) y las URLs propias de cada deployment son públicos aunque el dominio propio esté detrás de Cloudflare Access, y la verificación de la publicación depende de que lo sean. Access es externo y temporal y no es una dependencia de la cadena.

## Riesgos conocidos

- El correcto funcionamiento del sistema depende de la disponibilidad del servidor de Keycloak (ADR-001).
- Keycloak demanda una cantidad considerable de recursos y puede aumentar el tiempo de arranque del entorno (ADR-001).
- La modularidad del backend no reemplaza un buen diseño del dominio; sigue dependiendo de la disciplina del equipo para mantener los límites entre módulos (ADR-003).
- El backend permanece como una única aplicación desplegable, por lo que no es posible escalar módulos de forma independiente como en una arquitectura de microservicios (ADR-003).
- PostgreSQL presenta un escalado horizontal más complejo que enfoques NoSQL puros (ADR-002).
- Dependencia de Docker Desktop o Docker Engine en los equipos de desarrollo, con posibles conflictos de puertos locales (ADR-005).
- El despacho de notificaciones envía en serie y comparte hilo con el resto de `@Scheduled`: una llamada al proveedor sin tiempo máximo los detiene a todos; ver [Despacho de notificaciones](#despacho-de-notificaciones).

## Decisiones relacionadas

- ADR-001: Autenticación y autorización centralizada mediante Keycloak y JWT.
- ADR-002: Persistencia relacional con PostgreSQL.
- ADR-003: Implementar el backend como un monolito modular utilizando Spring Boot y Spring Modulith.
- ADR-004: Adopción de Angular como framework para el frontend.
- ADR-005: Orquestación local con Docker Compose para servicios de soporte.
- ADR-006: Personalización de la interfaz de autenticación de Keycloak con Keycloakify.
