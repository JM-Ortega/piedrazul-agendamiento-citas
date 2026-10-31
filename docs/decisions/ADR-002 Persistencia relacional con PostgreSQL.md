# ADR-002: Persistencia relacional con PostgreSQL

- Estado: Aceptado
- Fecha: 2026-08-01
- Impacto: Datos, Backend, Infraestructura

## Contexto

El dominio de agendamiento requiere consistencia transaccional para la gestión de citas, pacientes y estados de atención, dado que operaciones como la creación o cancelación de una cita no pueden dejar el sistema en un estado inconsistente. Adicionalmente, el sistema demanda consultas estructuradas mediante filtros por fecha, médico y estado, lo que exige un modelo de datos capaz de soportar consultas complejas de forma eficiente.

El backend del sistema se apoya en Spring Data JPA como capa de acceso a datos, por lo que la selección del motor de base de datos debía ser coherente con esta tecnología. De forma paralela, se definía también la estrategia de orquestación de los servicios de soporte del entorno de desarrollo mediante contenedores. Por ello, era necesario evaluar qué motor de base de datos resultaba más adecuado para sostener los requisitos de consistencia transaccional y de consulta del dominio de agendamiento.

## Opciones consideradas

### Opción 1: Base de datos NoSQL documental

**Ventajas**

- Flexibilidad de esquema.
- Facilita el manejo de datos semi-estructurados o con esquemas cambiantes.
- Buen soporte nativo para escalado horizontal.

**Desventajas**

- Menor ajuste para transacciones relacionales críticas.
- Soporte transaccional multi-documento históricamente más limitado que en bases de datos relacionales.
- Requeriría replantear el modelo de datos actualmente construido sobre Spring Data JPA.

---

### Opción 2: Motor relacional alternativo (MySQL/MariaDB)

**Ventajas**

- Paradigma relacional maduro y ampliamente utilizado.
- Amplia adopción y comunidad extensa.

**Desventajas**

- Soporte históricamente más limitado que PostgreSQL para tipos de datos avanzados (JSON, arrays) e índices especializados.

---

### Opción 3: PostgreSQL

**Ventajas**

- Consistencia fuerte para operaciones concurrentes mediante transacciones ACID.
- Ecosistema robusto y maduro para aplicaciones Java/Spring.
- Soporte de índices y consultas complejas.
- Integración directa con Spring Data JPA, ya utilizado por el backend.

**Desventajas**

- Requiere administración de esquema y migraciones.
- Escalado horizontal más complejo que enfoques NoSQL puros.

## Decisión

Se decidió mantener **PostgreSQL** como base de datos principal del sistema.

La solución contempla:

- Modelo relacional normalizado para las entidades de negocio.
- Transacciones ACID para las operaciones críticas de agenda, como la creación y cancelación de citas.
- Integración mediante Spring Data JPA como capa de persistencia.
- Definición de la conexión a la base de datos mediante variables de entorno.
- Uso de **Flyway** para el versionado de migraciones de esquema, permitiendo mantener trazabilidad y consistencia de los cambios de base de datos entre los distintos ambientes.
- Definición de políticas de backup y restore por ambiente.

Esta decisión responde directamente a la necesidad de consistencia transaccional y de consulta del dominio de agendamiento, y es coherente con el uso de Spring Data JPA como capa de acceso a datos del backend y con la orquestación de los servicios de soporte mediante Docker Compose, definidos de forma conjunta como parte de la arquitectura del sistema.

## Consecuencias

### Beneficios

- Consistencia fuerte para operaciones concurrentes.
- Ecosistema robusto y maduro para Java/Spring.
- Soporte de índices y consultas complejas.
- Trazabilidad de los cambios de esquema mediante migraciones versionadas.

### Costos y limitaciones

- Requiere administración de esquema y migraciones.
- Escalado horizontal más complejo que enfoques NoSQL puros.

## Referencias

No aplica.
