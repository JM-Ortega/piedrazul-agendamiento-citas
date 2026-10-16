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

El proyecto de Pages `piedrazul` (declarado en `infra/terraform/live/prod`) está conectado al repositorio. La integración Git construye las **previews** de las ramas que no son de producción; esas previews no forman parte de este contrato. Para **producción**, el repositorio define la publicación desde la cadena de producción con `wrangler pages deploy`. Requisito operativo: la integración Git del proyecto debe tener deshabilitada la publicación automática de producción; si no es así, la cadena no publica (ver *Autoridad única*). Qué mecanismo publica efectivamente producción depende de esa configuración externa, que este documento no describe.

**Artefacto.** El job `frontend_build` (antes del gate, solo `contents: read`) hace el único build de producción del commit, con el mismo comando y la misma versión de Node que CI Frontend (`frontend/.node-version`, `npm ci`, `npm run build`). `frontend-artifact.sh package` valida el sitio (`index.html`, recursos que referencia, exactamente un `main-<hash>.js`, sin enlaces simbólicos, límites de Pages) y lo sube como artifact `frontend-dist-<run_id>-<intento>`; su id y su sha256 son outputs del job. El job `production` lo descarga con `frontend-artifact.sh fetch`: mismo run, repositorio y commit, digest registrado por GitHub y sha256 validado. Pages no vuelve a construir.

**Destino.** `pages-release.sh target` toma el proyecto (`var.project`), la cuenta (`cloudflare_account_id`) y el dominio (`base_domain`) de `infra/terraform/live/prod/piedrazul.auto.tfvars`; la rama de producción es `main`.

**Autoridad única.** Antes de la primera mutación del run (también del backend), `pages-release.sh preflight` lee el proyecto con la credencial de Pages y exige que sea el declarado y que la publicación automática de producción desde Git esté deshabilitada (`production_deployments_enabled = false`, *Branch control → automatic production branch deployments*). Habilitada, ausente o ilegible: el run falla antes de cualquier mutación de producción (Apply, servidor y Pages); los jobs previos sin privilegios del run pueden haber producido ya artefactos (imágenes en GHCR, el artifact del frontend, evidencia de escaneo). Inmediatamente antes de publicar se repite la comprobación y se exige que el deployment de producción vigente siga siendo el que leyó el preflight.

**Orden.** Apply → converge/deploy → identidad → health → registro del último éxito del servidor → publicación del frontend. Si el run solo publica el frontend, la frescura (`verify-main-tip.sh`) se comprueba justo antes de publicar.

**Identidad de la publicación.** wrangler sube el sitio con el commit y un mensaje propio del intento (`production-hetzner <run_id>/<intento>`) y escribe en `WRANGLER_OUTPUT_FILE_PATH` el id que devolvió la creación del deployment. La publicación solo termina bien si hay exactamente uno, la API lo confirma como deployment de producción `ad_hoc` de `main` con ese commit y ese mensaje, terminó en `deploy: success`, es el único con ese mensaje, ningún deployment de producción posterior lo reemplazó, es el `canonical_deployment` del proyecto antes y después de comprobar el contenido, y tanto su URL propia como el alias de producción (`piedrazul.pages.dev`) sirven el `main-<hash>.js` validado. Coincidir solo en el commit no basta. El dominio propio no se usa para verificar: puede estar detrás de Cloudflare Access.

**Credencial.** `CLOUDFLARE_PAGES_API_TOKEN`, secreto del environment `production-hetzner` (no de repositorio; alcance declarado en `.github/scripts/audit-github-trust.sh`). Token de API dedicado con **Account → Cloudflare Pages → Edit** sobre la cuenta de Piedrazul y nada más; no se reutilizan el token de DNS ni el de Terraform. Lo reciben solo los pasos de credenciales, preflight y publicación del job `production`; nunca Ansible ni el servidor. La verificación de credenciales rechaza que coincida con `CLOUDFLARE_DNS_API_TOKEN`. wrangler se instala desde `.github/pages-publish/package-lock.json` (versión exacta, `npm ci --ignore-scripts`).

**Resultado y reintentos.** El resumen del job muestra el artefacto, la producción anterior y el deployment publicado. El rango procesado avanza solo si el run termina en `success`, incluida la publicación cuando hacía falta.

| caso | efecto | recuperación |
|---|---|---|
| build/validación del frontend, artefacto, wrangler, credencial o autoridad única fallan | ninguna mutación de producción (ni Apply, ni servidor, ni Pages); pueden existir artefactos de jobs previos (p. ej. imágenes en GHCR) | corregir y re-run, o nuevo push |
| Apply, deploy o health fallan | no se publica; Pages conserva la producción anterior | como cualquier fallo de la cadena |
| backend ok y la publicación falla | release parcial: backend nuevo registrado en el servidor, frontend anterior; el run falla y el rango no avanza | re-run del job o siguiente push (rango acumulado); republicar el mismo contenido es inocuo |
| otro deployment cambió la producción durante el run | la publicación falla antes de subir | revisar quién publicó; re-run |

No hay atomicidad ni vuelta atrás automática entre Cloudflare y Hetzner: un cambio del backend debe tolerar el frontend anterior durante esa ventana, y viceversa. Volver atrás el frontend es explícito: *Deployments → Rollback* en el dashboard de Pages (el resumen muestra la producción anterior) o un revert en `main`. Un dispatch con `run_frontend_publish` publica la punta de `main`. La verificación local es `infra/tests/pages-release-check.sh` (sin red ni Cloudflare).

## Riesgos conocidos

- El correcto funcionamiento del sistema depende de la disponibilidad del servidor de Keycloak (ADR-001).
- Keycloak demanda una cantidad considerable de recursos y puede aumentar el tiempo de arranque del entorno (ADR-001).
- La modularidad del backend no reemplaza un buen diseño del dominio; sigue dependiendo de la disciplina del equipo para mantener los límites entre módulos (ADR-003).
- El backend permanece como una única aplicación desplegable, por lo que no es posible escalar módulos de forma independiente como en una arquitectura de microservicios (ADR-003).
- PostgreSQL presenta un escalado horizontal más complejo que enfoques NoSQL puros (ADR-002).
- Dependencia de Docker Desktop o Docker Engine en los equipos de desarrollo, con posibles conflictos de puertos locales (ADR-005).

## Decisiones relacionadas

- ADR-001: Autenticación y autorización centralizada mediante Keycloak y JWT.
- ADR-002: Persistencia relacional con PostgreSQL.
- ADR-003: Implementar el backend como un monolito modular utilizando Spring Boot y Spring Modulith.
- ADR-004: Adopción de Angular como framework para el frontend.
- ADR-005: Orquestación local con Docker Compose para servicios de soporte.
- ADR-006: Personalización de la interfaz de autenticación de Keycloak con Keycloakify.
