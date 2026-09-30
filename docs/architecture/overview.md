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
- **Identidad y acceso**: Keycloak como proveedor de identidad (IdP), responsable de la autenticación, gestión de usuarios, credenciales y roles (ADR-001).
- **Base de datos**: PostgreSQL como motor relacional principal (ADR-002).

## Dependencias

- Spring Data JPA como capa de acceso a datos del backend (ADR-002, ADR-003).
- Spring Security para la validación de JWT en el backend (ADR-001).
- Spring Modulith para la definición y verificación de límites entre módulos (ADR-003).
- Flyway para el versionado de migraciones de esquema de base de datos (ADR-002).
- Docker Compose para la orquestación local de PostgreSQL y Keycloak (ADR-005).

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

> **Pendiente:** documentar la estrategia de despliegue en ambientes distintos al local (staging, producción).

## Riesgos conocidos

- El correcto funcionamiento del sistema depende de la disponibilidad del servidor de Keycloak (ADR-001).
- Keycloak demanda una cantidad considerable de recursos y puede aumentar el tiempo de arranque del entorno (ADR-001).
- La modularidad del backend no reemplaza un buen diseño del dominio; sigue dependiendo de la disciplina del equipo para mantener los límites entre módulos (ADR-003).
- El backend permanece como una única aplicación desplegable, por lo que no es posible escalar módulos de forma independiente como en una arquitectura de microservicios (ADR-003).
- Una futura migración del frontend fuera de Angular requeriría un esfuerzo considerable debido al tamaño de la base de código existente (ADR-004).
- PostgreSQL presenta un escalado horizontal más complejo que enfoques NoSQL puros (ADR-002).
- Dependencia de Docker Desktop o Docker Engine en los equipos de desarrollo, con posibles conflictos de puertos locales (ADR-005).

## Decisiones relacionadas

- ADR-001: Autenticación y autorización centralizada mediante Keycloak y JWT.
- ADR-002: Persistencia relacional con PostgreSQL.
- ADR-003: Implementar el backend como un monolito modular utilizando Spring Boot y Spring Modulith.
- ADR-004: Adopción de Angular como framework para el frontend.
- ADR-005: Orquestación local con Docker Compose para servicios de soporte.
