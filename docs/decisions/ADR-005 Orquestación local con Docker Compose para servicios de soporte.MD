# ADR-005: Orquestación local con Docker Compose para servicios de soporte

- Estado: aceptado
- Fecha: 2026-07-28
- Impacto: Infraestructura, Desarrollo

## Contexto

Se requiere que cualquier desarrollador pueda levantar las dependencias compartidas del proyecto (base de datos e identidad) de forma rápida, consistente y con una configuración uniforme entre los diferentes equipos de desarrollo.

Actualmente el repositorio incluye un archivo `docker-compose.yml` que define los servicios de PostgreSQL y Keycloak, junto con un archivo `.env` para parametrizar la configuración del entorno.

## Opciones consideradas

### Opción 1: Instalación manual de PostgreSQL y Keycloak
- **Ventajas:** No requiere una capa adicional de virtualización.
- **Desventajas:** Mayor cantidad de pasos de configuración y diferencias entre los entornos de cada desarrollador.

### Opción 2: Utilizar un entorno remoto compartido
- **Ventajas:** Elimina la necesidad de instalar dependencias localmente.
- **Desventajas:** Dependencia de la conectividad de red y posible interferencia entre desarrolladores al compartir recursos.

### Opción 3: Utilizar Docker Compose
- **Ventajas:** Configuración reproducible, rápida y consistente para todos los desarrolladores.
- **Desventajas:** Requiere tener Docker Desktop o Docker Engine instalado y puede generar conflictos de puertos locales.

## Decisión

Se adopta Docker Compose como mecanismo estándar para levantar los servicios de soporte del entorno de desarrollo local.

La solución contempla:
- Ejecutar PostgreSQL y Keycloak como contenedores.
- Configurar puertos, usuarios y credenciales mediante variables de entorno.
- Mantener el backend y el frontend ejecutándose localmente durante el desarrollo, utilizando los servicios proporcionados por Docker Compose.

## Consecuencias

### Beneficios
- Reduce el tiempo de incorporación de nuevos desarrolladores.
- Proporciona un entorno de desarrollo reproducible y consistente.
- Facilita la reconstrucción del entorno con un único comando.
- Disminuye las diferencias de configuración entre equipos.

### Costos y riesgos
- Dependencia de Docker Desktop o Docker Engine en los equipos de desarrollo.
- Posibles conflictos de puertos con otros servicios locales.
- Consumo adicional de recursos del sistema al ejecutar contenedores.

## Referencias

- `docker-compose.yml`
- `infra/compose/`
- `.env.example`