# Piedra Azul — Sistema de agendamiento de citas médicas

Piedra Azul es una plataforma web para gestionar las citas de una clínica. Está pensada para
sustituir el agendamiento manual por mensajes, que concentraba en los médicos una carga
administrativa considerable, con un sistema donde los pacientes pueden agendar por su cuenta y el
personal de la clínica dispone de un canal asistido para quienes tienen acceso limitado a la
tecnología.

El software es propietario y sus derechos pertenecen a sus autores; consulte [LICENSE](LICENSE)
antes de copiarlo, modificarlo o redistribuirlo.

## Qué permite hacer

El acceso depende del rol del usuario (`PATIENT`, `SCHEDULER`, `DOCTOR`, `ADMIN`, `AUDITOR`). El
frontend y la API incluyen funciones para los siguientes usos:

- **Pacientes**: registrarse, agendar y cancelar citas eligiendo médico o especialidad, consultar su
  historial de citas y editar su perfil. Vincular una cuenta con un paciente existente requiere un código de
  verificación.
- **Agendamiento asistido**: el personal agendador registra citas en nombre de los pacientes y
  consulta el historial de citas, con exportación de reportes.
- **Médicos**: consultar su agenda y sus pacientes, registrar citas, marcar asistencia o
  inasistencia, registrar atenciones sin cita previa y controles médicos, y gestionar sus horarios y
  ausencias.
- **Administración**: gestionar usuarios, médicos y pacientes, consultar estadísticas de citas y
  habilitar o deshabilitar el agendamiento autónomo de los pacientes. Cuando está habilitado, el
  backend rechaza una segunda cita autónoma de un mismo paciente en el mes en curso.
- **Auditoría**: el rol `AUDITOR` consulta los eventos de auditoría.
- **Notificaciones**: el backend implementa códigos de verificación y un recordatorio de cita, y
  puede enviarlos por correo mediante SMTP, con Amazon SES como proveedor previsto. Ese proveedor
  está deshabilitado por defecto y requiere configuración externa. Sin un transporte real habilitado
  ni el diagnóstico por consola (solo para datos sintéticos), las solicitudes de código se rechazan
  con `503` antes de emitir un código. Que el servidor SMTP acepte un mensaje no prueba que llegue al
  buzón. Ver [la arquitectura](docs/architecture/overview.md) para su comportamiento y límites.

## Arquitectura en breve

```text
 1. Inicio de sesión (OIDC con PKCE)     Frontend (SPA) ◄──────► Keycloak
 2. Llamadas a la API (Authorization: Bearer <JWT>)
                                         Frontend (SPA) ───────► Backend (Spring Boot) ──► PostgreSQL
 3. El backend valida cada JWT localmente (firma con las claves publicadas por Keycloak y
    reclamaciones) y aplica los roles del token; Keycloak no recibe ni reenvía las peticiones.
```

| Componente | Responsabilidad | Tecnología |
| --- | --- | --- |
| [`frontend/`](frontend) | Interfaz web por rol (SPA) | Angular, Angular Material, Tailwind CSS, Chart.js |
| [`backend/`](backend) | API REST y reglas de negocio | Java 21, Spring Boot, Spring Security, Spring Data JPA, Spring Modulith |
| Identidad | Autenticación, usuarios y roles; el backend actúa como *resource server* | Keycloak con tema de login propio (Keycloakify) |
| Persistencia | Datos de negocio e identidad; esquema versionado | PostgreSQL y Flyway |
| [`infra/`](infra) | Contenedores, aprovisionamiento y despliegue | Docker Compose, Terraform, Ansible, Traefik, Prometheus/Grafana/Loki |

El backend es un monolito modular. Sus módulos son `appointment`, `doctors`, `patients`,
`medicalCheckup`, `user`, `verification`, `notifications`, `report`, `statistics` y `audit`. El
frontend se organiza por funcionalidad (`features/`) sobre un sistema de diseño propio
(`designSystem/`). Las decisiones de diseño están registradas como ADR (ver
[Documentación](#documentación)).

### Despliegue

El destino operativo es Hetzner, con el frontend publicado en Cloudflare Pages y DNS en Cloudflare.
La cadena está declarada en
[`production-hetzner.yml`](.github/workflows/production-hetzner.yml) (Terraform, Ansible y Docker
Compose). La configuración de OCI que permanece en el repositorio es histórica y no es el destino
soportado. Lo que el repositorio declara no equivale al estado en vivo. El procedimiento operativo de
instalación está descrito en
[`instalacion-nueva.md`](docs/operations/runbooks/instalacion-nueva.md).

## Estructura del repositorio

| Ruta | Contenido |
| --- | --- |
| `backend/` | API Spring Boot, migraciones Flyway (`src/main/resources/db/migration`) y pruebas |
| `frontend/` | Aplicación Angular |
| `infra/compose/` | Definiciones de Compose por servicio (base de datos, Keycloak, backend, Traefik, logging) |
| `infra/keycloak/` | Realm importable y tema de login |
| `infra/terraform/`, `infra/ansible/` | Aprovisionamiento y configuración de servidores |
| `infra/observability/` | Métricas, logs y alertas |
| `infra/tests/` | Verificaciones de infraestructura (scripts de shell y Python) |
| `loadtests/` | Prueba de carga con k6 ([README](loadtests/README.md)) |
| `security/` | Políticas de seguridad operativa ([break-glass](security/break-glass/README.md), [disposiciones de vulnerabilidades](security/vulnerability-dispositions/README.md)) |
| `docs/` | Arquitectura, decisiones (ADR), convenciones y runbooks |
| `docker-compose.yml`, `.env.example` | Servicios de soporte para desarrollo local y su configuración |

## Desarrollo local

El desarrollo local levanta PostgreSQL y Keycloak con Docker Compose
([ADR-005](docs/decisions/ADR-005%20Orquestaci%C3%B3n%20local%20con%20Docker%20Compose%20para%20servicios%20de%20soporte.MD));
el backend y el frontend se ejecutan en la máquina del desarrollador.

### Requisitos

- Linux, macOS o Windows con WSL, y Bash o Zsh. Los comandos de esta sección no se han adaptado a
  PowerShell.
- Docker con el plugin Compose (`docker compose`) en una versión que admita `include` en
  `docker-compose.yml` (2.20 o posterior).
- JDK 21 (el wrapper `backend/mvnw` descarga Maven).
- Node.js y npm, en la versión indicada en [`frontend/.node-version`](frontend/.node-version), que
  también usan los workflows de CI del frontend y de producción.

### Pasos

Todos los comandos parten de la raíz del repositorio. El backend y el frontend son procesos de larga
duración: use una terminal para cada uno.

1. **Configuración.** Copie la plantilla. `.env` está ignorado por Git; use solo valores de
   desarrollo.

   ```bash
   cp .env.example .env
   ```

2. **Servicios de soporte** (terminal 1). Construye e inicia PostgreSQL y Keycloak, que importa el
   realm `piedrazul`. El servicio de un solo uso `keycloak-dev-secret` aplica a Keycloak el secreto
   del cliente del backend definido en `.env` (y asigna el client scope `kc-admin-api`), y el backend
   lo necesita para operar con Keycloak. **No continúe hasta que ese servicio haya terminado
   correctamente**: debe aparecer como `Exited (0)` y su registro debe terminar con
   `kc-admin-api asignado por defecto a ...`. Si termina con error, revise ese registro antes de
   seguir.

   ```bash
   docker compose up -d --build
   docker compose ps -a keycloak-dev-secret
   docker compose logs keycloak-dev-secret
   ```

3. **Backend** (terminal 2, puerto `8080`). Spring Boot lee variables de entorno del proceso, no el
   archivo `.env`. No use `source .env`: la plantilla contiene valores con espacios sin comillas
   (por ejemplo `NOTIFICATIONS_EMAIL_FROM_NAME`) que Bash interpretaría como comandos. El bucle
   siguiente exporta cada línea `CLAVE=valor` sin interpretar el valor. Con
   `SPRING_PROFILES_ACTIVE=dev` y `APP_DEMO_SEED_ENABLED=true` (los valores de la plantilla) se crean
   datos ficticios de demostración, y Flyway aplica las migraciones al arrancar.

   ```bash
   while IFS='=' read -r key value; do export "$key=$value"; done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' .env)
   (cd backend && ./mvnw spring-boot:run)
   ```

4. **Frontend** (terminal 3, `http://localhost:4200`). Apunta por defecto a la API en
   `localhost:8080` y a Keycloak en `localhost:8180`
   ([`environment.development.ts`](frontend/src/environments/environment.development.ts)).

   ```bash
   npm --prefix frontend ci
   npm --prefix frontend start
   ```

Con el backend en marcha, `/actuator/health` responde sin autenticación en el puerto de la aplicación:

```bash
curl http://localhost:8080/actuator/health
```

Con la configuración de desarrollo no hay proveedor de correo, por lo que las solicitudes de código
de verificación responden `503`. La plantilla permite un diagnóstico por consola solo con datos
sintéticos (ver los comentarios de `.env.example` y
[`correo-ses.md`](docs/operations/runbooks/correo-ses.md)).

Esta secuencia se comprobó solo por inspección de la configuración; el arranque completo no se ha
ejecutado de extremo a extremo.

### Primer administrador de negocio

En desarrollo, los datos de demostración incluyen cuentas con el rol `ADMIN`. En una instalación
limpia de producción no existen: el primer administrador se crea con un bootstrap explícito de un
solo uso, descrito en
[`primer-admin-negocio.md`](docs/operations/runbooks/primer-admin-negocio.md).

### Verificación

| Qué | Comando | Notas |
| --- | --- | --- |
| Pruebas unitarias y de contexto del backend | `cd backend && ./mvnw test` | Ejecuta las clases `*Test` (Surefire). Ninguna usa Testcontainers; las de integración `*IT` solo corren en `verify` |
| Backend completo (como CI) | `cd backend && ./mvnw -B clean verify` | Incluye las pruebas `*IT`, que requieren Docker |
| Frontend: compilación | `cd frontend && npm run build` | Es la comprobación que ejecuta CI |
| Frontend: lint | `cd frontend && npm run lint` | |
| Infraestructura | Scripts en `infra/tests/` | No hay un comando único: cada script verifica un contrato concreto y puede requerir Docker u otras herramientas; léalo antes de ejecutarlo |

CI ([`ci-backend.yml`](.github/workflows/ci-backend.yml), [`ci-frontend.yml`](.github/workflows/ci-frontend.yml))
se ejecuta en GitHub Actions.

## Documentación

Los documentos de cada componente viven junto a su implementación.

| Tema | Documento |
| --- | --- |
| Arquitectura y flujos principales | [`docs/architecture/overview.md`](docs/architecture/overview.md) |
| Decisiones de diseño (ADR) | [`docs/decisions/`](docs/decisions) |
| Convenciones del frontend | [`docs/development/CONVENCIONES_FRONTEND.md`](docs/development/CONVENCIONES_FRONTEND.md) |
| Manejo de excepciones | [`docs/development/MANEJO_DE_EXCEPCIONES.md`](docs/development/MANEJO_DE_EXCEPCIONES.md) |
| Migraciones de base de datos | [`convenio-migraciones-flyway.md`](backend/src/main/resources/db/migration/convenio-migraciones-flyway.md) |
| Operación (instalación, Keycloak, logs, métricas, alertas) | [`docs/operations/runbooks/`](docs/operations/runbooks) |
| Ansible | [`infra/ansible/ANSIBLE.md`](infra/ansible/ANSIBLE.md) |
| Pruebas de carga | [`loadtests/README.md`](loadtests/README.md) |
| Evaluaciones | [`docs/evaluations/`](docs/evaluations) |

## Contribuir

El flujo de trabajo usa `develop` como rama de integración y `main` como rama principal; las ramas
de trabajo siguen `<área>/<tipo>/<descripción>` y los commits usan una convención con emoji. El
detalle está en [CONTRIBUTING.md](CONTRIBUTING.md).
