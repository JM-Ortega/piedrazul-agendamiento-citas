# ADR-006: Personalización de la interfaz de autenticación de Keycloak con Keycloakify

* **Estado:** Aceptado
* **Fecha:** 2026-10-15
* **Impacto:** Frontend, Despliegue

## Contexto

Según el ADR-001, la autenticación de Piedra Azul está delegada en Keycloak: el frontend Angular redirige al usuario a Keycloak (flujo de código con PKCE; el cliente `piedrazul-frontend` es público y no admite *direct access grants*) y Keycloak presenta las pantallas de inicio de sesión, cambio obligatorio de contraseña y recuperación de acceso. Esas pantallas forman parte de la experiencia del producto, por lo que deben mostrar la identidad de Piedra Azul y no la apariencia genérica de Keycloak.

Keycloak permite personalizar estas pantallas mediante *temas de login*: paquetes con plantillas FreeMarker (`.ftl`), recursos estáticos y un `theme.properties`, que se cargan como proveedores (`/opt/keycloak/providers`) y se seleccionan por realm (`loginTheme`).

**Origen de la tecnología.** Keycloakify ya formaba parte del repositorio antes de este ADR:

* 2026-04-03 (`79dc327f`): se agregó `infra/keycloak/themes/piedrazul/keycloakify-starter`, un proyecto basado en el *starter* oficial de Keycloakify, inicialmente como submódulo.
* 2026-04-11 (`8dc962b5`) y 2026-04-17 (`59b214ad`): se versionaron los JAR compilados del tema.
* 2026-04-15 (`efde573c`) y 2026-05-09 (`4fafd348`): el starter pasó a ser un directorio regular del repositorio.

El repositorio no conserva un registro de las alternativas evaluadas al adoptarlo. Por eso este ADR **no reconstruye** aquella elección: es una **ratificación actual** de la tecnología existente, basada en el estado verificado del proyecto y en la decisión vigente de seguir personalizando sobre ella las pantallas de autenticación con la identidad visual del frontend Angular.

## Opciones consideradas

### Opción 1: Tema estándar de Keycloak

Usar el tema incluido en Keycloak, ajustando solo lo que el realm permite configurar sin un tema propio: nombre visible, idiomas y textos (localización del realm).

**Ventajas**

* Sin código ni artefactos propios que mantener.
* Compatibilidad garantizada con cada versión de Keycloak.

**Desventajas**

* No refleja la identidad de Piedra Azul; las pantallas se perciben como un sistema ajeno a la aplicación.
* Los textos pueden adaptarse con la localización del realm, pero la estructura, los estilos y los elementos de la página (marca, enlaces propios) requieren un tema propio.

---

### Opción 2: Tema propio con plantillas FreeMarker

Crear un tema que herede del tema base de Keycloak y sobrescriba las plantillas `.ftl` y hojas de estilo necesarias.

**Ventajas**

* No requiere herramientas de compilación adicionales; el tema se copia como directorio o JAR.
* Es el mecanismo nativo y documentado de Keycloak.
* Admite reutilización: herencia de temas (`parent`), el layout común de las páginas (`template.ftl`) y macros de FreeMarker.

**Desventajas**

* Las páginas sobrescritas se mantienen en FreeMarker, sin tipado del contexto de cada página ni componentes con estado.
* Las plantillas sobrescritas deben revisarse frente a los cambios de las plantillas base en cada actualización de Keycloak.
* No hay vista previa de las páginas con datos simulados; revisarlas requiere un servidor Keycloak en ejecución.

---

### Opción 3: Pantallas de login dentro del frontend Angular

Implementar el formulario de credenciales en la SPA y que la aplicación las envíe a Keycloak.

**Ventajas**

* Un único proyecto y framework de interfaz.

**Desventajas**

* Contradice el modelo del ADR-001: las credenciales dejarían de ingresarse solo en Keycloak. Que la aplicación intercambie credenciales por tokens exige el *grant* de contraseña (*direct access grants*), deshabilitado en el cliente `piedrazul-frontend` y desaconsejado para clientes públicos.
* Obligaría a reimplementar en la aplicación pasos que Keycloak resuelve en sus propias páginas (acciones requeridas, cambio de contraseña, recuperación, sesión SSO).

---

### Opción 4: Keycloakify (React)

Construir el tema de login con React y TypeScript; Keycloakify genera las plantillas FreeMarker que entregan a la aplicación React el contexto de cada página (`kcContext`) y empaqueta el resultado como JAR de tema.

**Ventajas**

* Keycloak conserva toda la lógica de autenticación: acciones de formulario, sesión, validaciones, mensajes y flujos. React solo presenta.
* Componentes tipados y estilos compartidos entre páginas.
* Las páginas no personalizadas usan automáticamente las implementaciones por defecto de Keycloakify.
* Contexto tipado por página y vista previa con datos simulados (Storybook) sin levantar Keycloak.
* Genera JAR para distintas versiones de Keycloak a partir del mismo código.

**Desventajas**

* Introduce un segundo stack de interfaz (React, Vite, Node) además de Angular, y requiere Maven y Java para empaquetar.
* Agrega una dependencia cuya versión debe ser compatible con la versión de Keycloak desplegada.
* El artefacto final es un JAR generado que debe reconstruirse cuando cambia el código del tema.

## Decisión

Se decide **mantener Keycloakify** como tecnología para personalizar la interfaz de autenticación de Keycloak en Piedra Azul.

En esta arquitectura:

* Keycloak sigue siendo la autoridad de autenticación (ADR-001); el tema no implementa lógica de autenticación propia y usa las URL, campos y mensajes que Keycloak entrega en `kcContext`.
* Solo se personalizan las páginas que lo necesitan; el resto conserva la implementación por defecto de Keycloakify.
* React se usa únicamente dentro del proyecto del tema y no forma parte de la aplicación Angular (ADR-004). La coherencia visual con Angular se logra reproduciendo sus valores de diseño en el tema, no compartiendo código entre ambos proyectos.
* El tema se entrega como JAR versionado en `infra/keycloak/themes/keycloak-theme/`, montado en Keycloak como proveedor y seleccionado por el realm (`loginTheme: keycloakify-starter`).

Razones para mantenerlo:

* Ya existe una base funcional integrada con el despliegue (Compose, Ansible y el workflow de producción reconocen la ruta del JAR).
* Permite compartir la estructura y los estilos entre varias páginas de autenticación sin duplicarlos en plantillas FreeMarker.
* Preserva la separación del ADR-001: la interfaz cambia sin tocar flujos, políticas ni credenciales de Keycloak.
* Reemplazarlo por plantillas FreeMarker implicaría reescribir las páginas existentes sin una ganancia que lo justifique para las necesidades actuales.

## Consecuencias

### Beneficios

* Las pantallas de autenticación presentan la identidad de Piedra Azul manteniendo intacta la lógica de Keycloak.
* Las páginas personalizadas comparten una misma estructura y hoja de estilos.
* Las páginas pueden revisarse con Storybook y datos simulados antes de construir el JAR.
* Agregar una nueva página personalizada no requiere cambios en Keycloak, en el realm ni en la infraestructura.

### Costos y limitaciones

* El equipo debe mantener conocimientos de React y del modelo de Keycloakify, además de Angular.
* Construir el tema requiere Node, Maven y Java; la integración continua del repositorio no construye ni valida el tema.
* Los JAR generados se versionan junto al código: todo cambio del tema debe incluir su JAR reconstruido. El workflow de producción detecta cambios en los JAR, no en el código fuente del tema.
* Keycloakify 11.15.0 genera dos variantes (`keycloak-theme-for-kc-22-to-25.jar` y `keycloak-theme-for-kc-all-other-versions.jar`); el stack actual (Keycloak 26.8.1) usa la segunda. Actualizar Keycloak exige confirmar la compatibilidad de la versión de Keycloakify.
* Un Keycloak en ejecución no toma un JAR reconstruido hasta que su contenedor se recrea.
* Las páginas personalizadas reemplazan el `Template` de Keycloakify, por lo que deben preservar explícitamente su contrato (inicialización de scripts de sesión, mensajes, enlaces de reinicio).

## Referencias

* ADR-001: Autenticación y autorización centralizada mediante Keycloak y JWT.
* ADR-004: Adopción de Angular como framework para el frontend.
* [Tema de autenticación de Piedra Azul](../../infra/keycloak/themes/piedrazul/keycloakify-starter/README.md)
* `infra/compose/keycloak.yml`, `infra/keycloak/realm/piedrazul-realm.json`
* Documentación de Keycloakify: https://docs.keycloakify.dev
