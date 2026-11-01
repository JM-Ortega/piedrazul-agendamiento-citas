# Tema de autenticación de Piedra Azul (Keycloakify)

Tema de _login_ de Keycloak para Piedra Azul, construido con [Keycloakify](https://docs.keycloakify.dev) 11.15.0, React 18 y Vite. Da a las pantallas de autenticación la identidad visual del frontend Angular sin cambiar la lógica de Keycloak. La decisión de usar Keycloakify está en el [ADR-006](../../../../../docs/decisions/ADR-006%20Interfaz%20de%20autenticacion%20de%20Keycloak%20con%20Keycloakify.md).

El proyecto parte del _starter_ oficial de Keycloakify; por eso el nombre del directorio y del tema es `keycloakify-starter`.

## Alcance

Solo cubre el tema de tipo `login` (`accountThemeImplementation: "none"` en `vite.config.ts`). No hay tema de cuenta ni de correo.

| Página de Keycloak          | Implementación                            | Cuándo aparece                                                                                                       |
| --------------------------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `login.ftl`                 | `src/login/pages/Login.tsx`               | Inicio de sesión                                                                                                     |
| `login-update-password.ftl` | `src/login/pages/LoginUpdatePassword.tsx` | Contraseña temporal (acción requerida `UPDATE_PASSWORD`), enlace de recuperación y cambio iniciado por la aplicación |
| `login-reset-password.ftl`  | `src/login/pages/LoginResetPassword.tsx`  | "¿Has olvidado tu contraseña?"                                                                                       |
| `info.ftl`                  | `src/login/pages/Info.tsx`                | Avisos de Keycloak, p. ej. "Cuenta actualizada" al terminar la recuperación                                          |
| `error.ftl`                 | `src/login/pages/Error.tsx`               | Errores de Keycloak, p. ej. enlace de recuperación ya usado, alterado o vencido                                      |
| `login-page-expired.ftl`    | `src/login/pages/LoginPageExpired.tsx`    | Formulario vencido tras volver atrás o reenviar                                                                      |
| Cualquier otra              | `DefaultPage` de Keycloakify              | OTP, verificación de correo, etc. conservan la apariencia por defecto de Keycloakify                                 |

## Cómo funciona

Keycloak sigue ejecutando la autenticación: valida credenciales y contraseñas, aplica políticas, gestiona sesiones y decide qué página mostrar. Keycloakify genera las plantillas FreeMarker que cargan la aplicación React y le entregan el contexto de la página en `window.kcContext`; React solo presenta ese contexto.

Reglas que siguen las páginas propias:

-   Los formularios envían a las URL que entrega Keycloak (`url.loginAction`, `url.loginRestartFlowUrl`, `url.loginUrl`…) sin modificarlas, con los nombres de campo que espera Keycloak (`username`, `password`, `password-new`, `password-confirm`, `logout-sessions`, `cancel-aia`…).
-   Los errores y mensajes vienen de Keycloak (`messagesPerField`, `message`) y se insertan siempre a través de `kcSanitize`.
-   Los textos usan las claves de mensajes de Keycloak; los textos propios del tema son claves `pz*` en `src/login/i18n.ts` (el realm solo habilita `es`; `en` es el idioma de respaldo de Keycloakify).

### Estructura

```
src/
├── main.tsx                     Punto de entrada; renderiza KcPage con window.kcContext
├── kc.gen.tsx                   Generado por Keycloakify (no editar)
└── login/
    ├── KcPage.tsx               Enrutamiento por pageId: páginas propias o DefaultPage
    ├── KcContext.ts             Tipo de kcContext del tema
    ├── KcPageStory.tsx          Mocks de kcContext para Storybook
    ├── i18n.ts                  Textos pz* y etiqueta del identificador según el realm
    ├── frontendUrl.ts           URL del frontend (FRONTEND_PUBLIC_URL) y ruta de registro de pacientes
    ├── components/
    │   ├── AuthLayout.tsx       Estructura común (panel de marca + columna del formulario)
    │   ├── Alert.tsx            Mensaje de Keycloak (success, error, warning, info)
    │   ├── FormField.tsx        Etiqueta + control + error del campo
    │   ├── fieldErrorId.ts      Id del error de un campo (input-error-<id>)
    │   ├── PasswordInput.tsx    Campo de contraseña con botón mostrar/ocultar
    │   └── icons.tsx            Íconos SVG con la geometría de Lucide
    ├── pages/                   Una página por pageId, cada una con sus *.stories.tsx
    └── styles/auth.css          Hoja de estilos única del tema
```

### `AuthLayout`

Todas las páginas propias se construyen sobre `AuthLayout`, que reemplaza al `Template` de Keycloakify y conserva su contrato:

-   Llama a `useInitialize` de Keycloakify, que inserta `authChecker.js` (detecta inicios de sesión completados en otra pestaña) y los `kcContext.scripts`. No debe duplicarse en las páginas.
-   Muestra el mensaje global de Keycloak; los de tipo `warning` se ocultan solo en acciones iniciadas por la aplicación (`isAppInitiatedAction`). Las páginas pasan `displayMessage={false}` cuando el error ya se muestra junto a su campo.
-   Muestra el usuario identificado con el enlace "Reiniciar el inicio de sesión" cuando Keycloak lo indica (`auth.showUsername`) y el formulario "Prueba de otra manera" cuando `auth.showTryAnotherWayLink` es verdadero.
-   Fija el título del documento como `<título> · Piedra Azul Salud`.

### Navegación hacia el frontend (`FRONTEND_PUBLIC_URL`)

El tema no compila ninguna URL del frontend. `vite.config.ts` declara la propiedad `FRONTEND_PUBLIC_URL` y Keycloakify la escribe en `theme.properties` como `FRONTEND_PUBLIC_URL=${env.FRONTEND_PUBLIC_URL:}`: Keycloak la resuelve en ejecución desde la variable de entorno de su contenedor y la entrega en `kcContext.properties`. El mismo JAR sirve en desarrollo y producción.

`src/login/frontendUrl.ts` la valida y deriva los destinos:

-   Acepta solo una URL absoluta `http` o `https`, sin credenciales, consulta ni fragmento. Admite una ruta base: `https://dominio/app` y `https://dominio/app/` producen el inicio `https://dominio/app/` y el registro `https://dominio/app/registro`.
-   Si falta o es inválida, no se inventa un destino: se ocultan los enlaces al frontend y se registra un error `[piedrazul-theme]` en la consola del navegador.

Dónde se usa:

| Elemento                                           | Destino               | Condiciones                                                                                                                                                                                                         |
| -------------------------------------------------- | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| "Volver al inicio" (`AuthLayout`)                  | Inicio del frontend   | URL válida                                                                                                                                                                                                          |
| "¿Eres paciente nuevo? Regístrate" (login)         | `<frontend>/registro` | URL válida, el realm permite contraseña, Keycloak no suprime el registro (`registrationDisabled`) y el login lo pidió el cliente del frontend (`piedrazul-frontend`). **No depende de `realm.registrationAllowed`** |
| "Ir a Piedra Azul Salud" (`info.ftl`, `error.ftl`) | Inicio del frontend   | Solo si Keycloak no entrega su propia continuación (`pageRedirectUri`, `actionUri`, `client.baseUrl`) y no la suprime con `skipLink`                                                                                |

El registro de pacientes es el flujo del frontend Angular y del backend (`/registro`). El registro nativo de Keycloak no se usa: el realm del repositorio lo mantiene deshabilitado (`registrationAllowed: false`) y el enlace del tema nunca apunta a él.

Origen del valor:

-   `infra/compose/keycloak.yml` entrega `FRONTEND_PUBLIC_URL` al contenedor de Keycloak y la exige (`${FRONTEND_PUBLIC_URL:?}`): sin ella Compose no renderiza el proyecto.
-   Desarrollo: `.env` en la raíz del repositorio (copia de `.env.example`, `http://localhost:4200`).
-   Producción: `infra/ansible/roles/app/templates/env.j2` la escribe como `https://<frontend_public_domain>` (inventario de Ansible). `setup.yml` valida el dominio antes de copiar Compose o escribir `.env`.

### Agregar o modificar una página

1. Crear `src/login/pages/<Página>.tsx` usando `AuthLayout` y, si tiene formulario, `FormField`, `PasswordInput` y las clases de `auth.css`. Tomar como referencia la página por defecto de Keycloakify (`node_modules/keycloakify/src/login/pages/`) para no perder campos, enlaces ni condiciones.
2. Registrar el `pageId` en `src/login/KcPage.tsx` con `doUseDefaultCss={false}`.
3. Agregar sus historias en `<Página>.stories.tsx`.
4. Reconstruir y versionar los JAR (ver abajo).

## Desarrollo

Requisitos: Node `^18` o `>=20` (según `package.json`) y Yarn 1 (el lockfile versionado es `yarn.lock`, formato v1). Para empaquetar el tema también se necesitan Maven (`mvn` en el `PATH`) y Java.

Desde este directorio:

| Comando                          | Uso                                                                                        |
| -------------------------------- | ------------------------------------------------------------------------------------------ |
| `yarn install --frozen-lockfile` | Instala exactamente las versiones de `yarn.lock` (falla si habría que modificarlo)         |
| `yarn storybook`                 | Storybook en `http://localhost:6006` con todas las páginas y sus estados (datos simulados) |
| `yarn build`                     | `tsc` + `vite build` (verificación de tipos y bundle, sin JAR)                             |
| `yarn eslint src`                | Lint                                                                                       |
| `yarn prettier --check src`      | Formato (`.prettierrc.json`)                                                               |
| `yarn build-keycloak-theme`      | Bundle + empaquetado de los JAR del tema                                                   |

Sin Yarn instalado globalmente, `npx yarn@1 <comando>` ejecuta la misma versión mayor. Los scripts de `package.json` también corren con `npm run <script>` sobre una instalación hecha con Yarn.

Storybook es la forma principal de revisar las páginas y sus estados (errores, mensajes, variantes por realm) sin un Keycloak en ejecución. Para probar el comportamiento real (envío de formularios, redirecciones, correos) se necesita un Keycloak con el JAR reconstruido.

## Artefactos y entrega

`yarn build-keycloak-theme` escribe en `infra/keycloak/themes/keycloak-theme/` (opción `keycloakifyBuildDirPath` de `vite.config.ts`):

| JAR                                            | Uso                                                                   |
| ---------------------------------------------- | --------------------------------------------------------------------- |
| `keycloak-theme-for-kc-all-other-versions.jar` | Keycloak 26 o posterior. **Es el que usa el stack** (Keycloak 26.8.1) |
| `keycloak-theme-for-kc-22-to-25.jar`           | Keycloak 22 a 25. Se genera, pero el stack actual no lo monta         |

Ambos JAR declaran un único tema de login llamado `keycloakify-starter`.

-   **Versionado:** los JAR están versionados en el repositorio y deben reconstruirse y confirmarse junto con cualquier cambio del código del tema. Keycloakify crea en ese directorio un `.gitignore` con `*`: los JAR existentes siguen rastreados, pero un archivo nuevo en esa ruta requeriría `git add -f`.
-   **Carga en Keycloak:** `infra/compose/keycloak.yml` monta el JAR como `/opt/keycloak/providers/keycloak-theme.jar`, le entrega `FRONTEND_PUBLIC_URL` y el realm selecciona el tema con `"loginTheme": "keycloakify-starter"` (`infra/keycloak/realm/piedrazul-realm.json`). Agregar o personalizar páginas no requiere cambios en Compose, el realm ni la infraestructura.
-   **Producción:** Ansible copia `infra/keycloak/` al servidor, y el workflow de producción considera cambios de la aplicación los que afectan a `infra/keycloak/themes/keycloak-theme`, no al código fuente del tema.
-   **Recarga:** un Keycloak en ejecución sigue sirviendo el JAR con el que arrancó: el JAR se reemplaza por un archivo nuevo y el contenedor conserva el montado al crearse. `docker compose up -d` recrea el contenedor cuando cambia su configuración (por ejemplo, el valor de `FRONTEND_PUBLIC_URL`), y el contenedor nuevo monta el JAR vigente; un cambio solo del JAR no lo recrea. Tras reconstruir el JAR, en desarrollo local:

    ```bash
    docker compose up -d --force-recreate keycloak
    ```

    En producción, `infra/ansible/roles/app/tasks/keycloak_lifecycle.yml` levanta Keycloak con `state: present`, que no recrea el contenedor solo porque cambió el JAR.

-   El workflow `.github/workflows/ci.yaml` de este directorio viene del starter y no se ejecuta: GitHub solo usa los workflows de la raíz del repositorio, y ninguno de ellos construye el tema.

## Diseño visual

El tema reproduce la identidad del frontend Angular. Los valores provienen de sus componentes de diseño (`frontend/src/app/designSystem/atoms/button`, `atoms/input`) y del navbar (`frontend/src/app/shared/components/navbar`); no se comparte código entre ambos proyectos, así que un cambio de identidad en Angular debe replicarse aquí.

Todos los estilos viven en `src/login/styles/auth.css`, bajo la clase raíz `.pz-auth` y con variables en `:root`. Para cambiar un valor visual se modifica la variable o la clase compartida en ese archivo; las páginas no definen estilos propios.

### Marca

-   Nombre "Piedra Azul Salud" y descriptor "Centro Médico" (claves `pzBrandName` y `pzBrandDescriptor`), como en el navbar de la aplicación.
-   Símbolo: ícono de calendario de Lucide en azul institucional dentro de un círculo blanco (`.pz-brand-mark`), el mismo tratamiento del navbar.
-   Panel de marca con degradado de `--pz-primary` a `--pz-primary-dark` y los círculos decorativos del diseño original del login. No usa fotografía.
-   Favicon: el mismo del frontend Angular, `clinica.ico` (`frontend/src/index.html`). `public/clinica.ico` es una copia sin cambios de `frontend/public/clinica.ico`, necesaria para que el JAR sea autocontenido; si el frontend cambia su favicon, se vuelve a copiar el archivo. `index.html` la referencia y Keycloakify la copia a `dist/` y la incluye en el `<head>` de todas las páginas generadas, también las que usan `DefaultPage`. En producción Keycloak sirve los recursos del tema con `Cache-Control: max-age=2592000`: si cambia el contenido del ícono, conviene cambiar también el nombre del archivo para que los navegadores no conserven el anterior.

### Colores

| Variable                                                              | Valor                                      | Uso                                         | Referencia en Angular                           |
| --------------------------------------------------------------------- | ------------------------------------------ | ------------------------------------------- | ----------------------------------------------- |
| `--pz-primary`                                                        | `#215c98`                                  | Botón principal, enlaces, eyebrow, marca    | Variante `primary` del botón y fondo del navbar |
| `--pz-primary-dark`                                                   | `#163c63`                                  | Hover del botón, títulos, fin del degradado | Hover del botón `primary`                       |
| `--pz-accent`                                                         | `#4e92d9`                                  | Borde y anillo de foco                      | Foco del input                                  |
| `--pz-border`                                                         | `#c2d8f0`                                  | Borde de los campos                         | Borde por defecto del input                     |
| `--pz-tint`, `--pz-tint-border`                                       | `#e6f0fa`, `#a7c9ec`                       | Alerta informativa, usuario identificado    | Tintes azules de la aplicación                  |
| `--pz-text`, `--pz-text-label`, `--pz-text-muted`, `--pz-placeholder` | `#111827`, `#374151`, `#6b7280`, `#9ca3af` | Texto                                       | Escala gris de Tailwind                         |
| `--pz-danger`, `--pz-success`, `--pz-warning` (+ fondos y bordes)     | `#dc2626`, `#15803d`, `#92400e`            | Errores y alertas                           | Rojo, verde y ámbar de Tailwind                 |

### Tipografía, forma y espaciado

-   Fuente del sistema (`--pz-font`), la misma pila por defecto de Tailwind con la que se ve la aplicación.
-   Título de página 26 px en negrita (`--pz-primary-dark`); eyebrow 12 px en mayúsculas; etiquetas 14 px; campos y botones 16 px.
-   Radios: 12 px en campos y alertas (`--pz-radius-control`, como `rounded-xl` del input) y 8 px en botones (`--pz-radius-button`, como `rounded-lg` del botón).
-   Columna del formulario de 400 px como máximo; 18 px entre campos; campos y botones de 46 px de alto.

### Formularios, botones y mensajes

-   Campos (`.pz-input`) blancos con borde `--pz-border`; con error (`aria-invalid="true"`) el borde pasa a rojo.
-   Error de campo (`.pz-field-error`): texto rojo con ícono, vinculado al campo con `aria-describedby` y anunciado con `aria-live`.
-   Botones: `.pz-button-primary` (acción principal) y `.pz-button-secondary` (cancelar, reiniciar). Enlaces: `.pz-link`.
-   Mensajes de Keycloak con `Alert` (`.pz-alert-success`, `-error`, `-warning`, `-info`); los errores usan `role="alert"` y el resto `role="status"`.
-   Casillas (`.pz-checkbox`) con el azul institucional (`--pz-primary`).

### Responsive

-   Más de 768 px (el `md` del frontend): panel de marca y formulario lado a lado.
-   Hasta 768 px: el panel de marca se reduce a una barra superior y se ocultan el texto de bienvenida y la lista de características.
-   Hasta 480 px: "Volver al inicio" muestra solo el ícono (el texto queda para lectores de pantalla) y se reducen márgenes y título.

### Accesibilidad

-   Todo control tiene etiqueta visible o `aria-label` (el botón de mostrar contraseña cambia entre "Mostrar contraseña" y "Ocultar contraseña").
-   Foco visible en todos los controles: anillo `--pz-focus-ring` en los campos y contorno de 2 px `--pz-accent` en botones, enlaces y casillas. Es más visible que el foco por borde de los inputs de Angular y no debe reducirse para imitarlo.
-   El orden de tabulación sigue el orden del documento (sin `tabIndex` positivos).
-   Las transiciones se desactivan con `prefers-reduced-motion`.

### Coherencia entre páginas

-   Toda página propia usa `AuthLayout`: no repetir el panel de marca, el encabezado ni las alertas.
-   Para campos y mensajes, reutilizar `FormField`, `PasswordInput` y `Alert` antes de crear marcado nuevo.
-   Los cambios visuales se hacen en `auth.css` (variables o clases `pz-*` compartidas), no con estilos en línea ni hojas por página.
-   Tras un cambio visual, revisar en Storybook todas las páginas de la tabla de alcance, en escritorio y en móvil.

## Limitaciones conocidas

-   Como `FRONTEND_PUBLIC_URL` es obligatoria en `infra/compose/keycloak.yml`, cualquier comando de Compose sobre el proyecto (también `ps`, `logs` o `down`) necesita el `.env` que la define.
-   El realm solo habilita `es`; las páginas propias no incluyen selector de idioma.
-   El cliente `piedrazul-frontend` no tiene `baseUrl`, así que Keycloak no entrega "Volver a la aplicación" en `info.ftl` ni `error.ftl`; en su lugar se muestra "Ir a Piedra Azul Salud" con `FRONTEND_PUBLIC_URL`.
