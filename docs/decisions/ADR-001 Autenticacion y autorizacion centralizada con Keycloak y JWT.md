# ADR-001: Autenticación y autorización centralizada mediante Keycloak y JWT

* **Estado:** Aceptado
* **Fecha:** 2026-07-28
* **Impacto:** Backend, Frontend, Despliegue

## Contexto

El sistema de agendamiento médico administra información sensible, como datos de pacientes, médicos, usuarios administrativos y citas. Por ello, era necesario implementar un mecanismo que permitiera autenticar usuarios de forma segura, controlar el acceso según el rol de cada usuario y evitar que la lógica de autenticación estuviera distribuida dentro del backend de negocio.

Inicialmente, el proyecto incorporó Keycloak como requisito académico durante su desarrollo. Sin embargo, al evolucionar el proyecto hacia un contexto de pasantía, se evaluó la conveniencia de mantener esta solución frente a implementar un mecanismo propio de autenticación y autorización.

Se concluyó que separar la gestión de identidad de la lógica del negocio mejora la mantenibilidad del sistema y reduce la complejidad de desarrollar y mantener funcionalidades relacionadas con la seguridad.

## Opciones consideradas

### Opción 1: Implementar autenticación y autorización directamente en el backend

Consistía en desarrollar internamente toda la gestión de usuarios, autenticación, almacenamiento seguro de credenciales, recuperación de contraseñas, emisión y validación de tokens, administración de sesiones y control de permisos.

**Ventajas**

* Menor cantidad de componentes desplegados.
* Control total sobre la implementación.

**Desventajas**

* Mayor complejidad de desarrollo.
* Mayor responsabilidad en aspectos de seguridad.
* Mayor esfuerzo de mantenimiento y evolución.
* Mayor riesgo de introducir vulnerabilidades al implementar funcionalidades críticas de seguridad.

---

### Opción 2: Utilizar Keycloak como proveedor de identidad (IdP)

Consiste en delegar la autenticación y la administración de identidades a Keycloak, mientras el backend únicamente valida los JWT emitidos y aplica las reglas de autorización según los roles incluidos en el token.

**Ventajas**

* Separación entre la lógica del negocio y la gestión de identidad.
* Administración centralizada de usuarios, credenciales y roles.
* Implementación basada en estándares ampliamente utilizados como OAuth 2.0, OpenID Connect y JWT.
* Reducción del código relacionado con autenticación dentro del backend.
* Facilita la incorporación de nuevos roles o clientes sin modificar significativamente la lógica de negocio.

**Desventajas**

* Introduce un componente adicional en la infraestructura.
* Requiere aprendizaje y configuración inicial.
* Incrementa el consumo de recursos durante el despliegue.
* El tiempo de inicio de Keycloak puede afectar el levantamiento completo del entorno de desarrollo.

## Decisión

Se decidió utilizar **Keycloak** como proveedor central de identidad y emplear **JSON Web Tokens (JWT)** para la autorización de las peticiones al backend.

En esta arquitectura:

* El frontend autentica al usuario mediante Keycloak.
* Keycloak emite un JWT con la información del usuario y sus roles.
* El backend actúa como un *OAuth2 Resource Server*, validando el token recibido en cada solicitud.
* Las autorizaciones se realizan utilizando los roles contenidos en el JWT.
* Las credenciales de los usuarios son administradas exclusivamente por Keycloak y no por el backend del sistema.

Esta decisión permite que el backend permanezca enfocado en la lógica del dominio mientras la autenticación y autorización son gestionadas por una plataforma especializada.

## Consecuencias

### Beneficios

* La autenticación queda desacoplada de la lógica del negocio.
* Se centraliza la administración de usuarios, credenciales y roles.
* Se reduce la cantidad de código propio relacionado con seguridad.
* Se utilizan estándares ampliamente adoptados en la industria para autenticación y autorización.
* La incorporación de nuevos roles o cambios en permisos resulta más sencilla de administrar.
* El backend no necesita almacenar ni gestionar contraseñas de los usuarios.

### Costos y limitaciones

* El despliegue requiere ejecutar un servicio adicional.
* Keycloak demanda una cantidad considerable de recursos en comparación con una solución desarrollada directamente en el backend.
* El tiempo de arranque del entorno puede aumentar debido al inicio de Keycloak.
* La curva de aprendizaje inicial fue mayor, ya que la tecnología era nueva para el equipo y fue necesario investigar su funcionamiento, configuración de *realms*, clientes, roles y mapeo de permisos.
* El correcto funcionamiento del sistema depende de la disponibilidad del servidor de Keycloak.

## Referencias

No aplica.
