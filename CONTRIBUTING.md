# Guía de Contribución — Piedra Azul

Este documento define cómo trabajamos como equipo en este repositorio: estrategia de ramas y convención de commits. Su objetivo es mantener un historial legible, facilitar el trabajo en paralelo entre backend y frontend, y hacer que cualquier persona del equipo pueda entender qué cambió y por qué con solo mirar el log de Git.

## Estrategia de ramas

`main` es la rama principal y refleja lo que está en producción. `develop` es la rama de integración: todas las ramas de trabajo se crean a partir de ella y ahí se hace merge de los cambios. Cuando se va a desplegar, se sube `develop` a `main`. Nadie trabaja directamente sobre `main` ni sobre `develop`.

### Estructura del nombre

``` text
<área>/<tipo>/<descripción>
```

- **Área**: dónde se hace el cambio — `backend`, `frontend` o `infra`.
- **Tipo**: `feature` (nueva funcionalidad), `refactor` (refactorización), `fix` (arreglo de bugs), `docs` (documentación), `migration` (migraciones).
- **Descripción**: módulo, componente o cambio específico. Puede tener subniveles adicionales separados por `/`.

### Convención de formato

Cuando la descripción tiene varias palabras, se separan con guion medio (`kebab-case`).

### Reglas

- Todo en minúsculas.
- Separar segmentos únicamente con `/`.
- Sin espacios.
- Sin caracteres inválidos en Windows: `\ : * ? " < > |`
- Sin tildes ni caracteres especiales (ñ, á, é, etc.).
- Sin punto final ni doble punto (`..`) en ningún segmento.

---

## Convención de commits

### Estructura

``` text
<emoji> <tipo> <(módulo)>: <descripción>

[cuerpo opcional]
```

### Tipos

| Emoji | Tipo | Uso |
| --- | --- | --- |
| 🎉 | `tada` | Comenzar un proyecto |
| 🚀 | `release` | Deploy de cosas |
| 🔖 | `new version` | Etiqueta / versión de lanzamiento |
| ✨ | `feat` | Nueva característica |
| ♻️ | `refactor` | Refactorizar código |
| 🐛 | `fix` | Arreglo de un bug |
| 🩹 | `patch` | Solución simple para un problema no crítico |
| ⚡️ | `perf` | Mejorar el rendimiento |
| 🎨 | `style` | Mejorar estructura/formato del código |
| 💄 | `ui` | Agregar o actualizar interfaz de usuario y estilos |
| 🚸 | `ux` | Mejorar experiencia de usuario / usabilidad |
| 🔥 | `remove` | Eliminar código o archivos |
| 🚧 | `construction` | Trabajo en progreso |
| ➕ | `add` | Agregar una dependencia |
| ⏫ | `upgrade` | Actualizar dependencia |
| ⏬ | `downgrade` | Degradar dependencia |
| ⏪ | `revert` | Revertir cambios |
| ♿️ | `accessibility` | Mejorar la accesibilidad |
| 🔀 | `merge` | Fusionar ramas |
| ✅ | `test` | Agregar o actualizar pruebas |
| 🔧 | `config` | Agregar o actualizar archivos de configuración |
| 🔨 | `script` | Agregar o actualizar scripts de desarrollo |
| ✏️ | `typos` | Corregir errores tipográficos |
| 💬 | `test` | Agregar o actualizar texto y literales |
| 🚚 | `resources` | Mover o renombrar recursos (archivos) |
| 🍱 | `assets` | Agregar o actualizar assets (imágenes) |
| 🙈 | `gitignore` | Agregar o actualizar `.gitignore` |
| 📱 | `responsive` | Trabajo en diseño responsive |
| ⚗️ | `experiment` | Realizar experimentos |
| 📝 | `docs` | Agregar o actualizar documentación |
| 💡 | `comments` | Agregar o actualizar comentarios en el código |
| 🔍️ | `seo` | Mejorar SEO |
| 🌱 | `seeds` | Agregar o actualizar archivos semilla |
| 🤖 | `chore` | Otros cambios que no modifican `src` ni archivos de prueba |
| 👷 | `ci` | Cambios en archivos/scripts de configuración de CI |
| 🔒️ | `security` | Solucionar problemas de seguridad |
| 🔐 | `secrets` | Agregar o actualizar secretos (variables de entorno) |
| 🚨 | `linter` | Arreglar advertencias del compilador/linter |
| 🌐 | `internationalization` | Internacionalización y localización |
| 👽️ | `alien` | Actualizar código por cambios en una API externa |
| 🗃️ | `db` | Cambios relacionados con la base de datos |
| 🛡️ | `types` | Agregar o actualizar tipos |
| 💫 | `animations` | Agregar o actualizar animaciones y transiciones |
| 🛂 | `auth` | Código relacionado con autorización, roles y permisos |
| ⚰️ | `dead` | Eliminar código muerto |
| 👔 | `business` | Agregar o actualizar lógica de negocio |
| 🦺 | `validation` | Agregar o actualizar código de validación |
| 💥 | `BREAKING CHANGE` | Cambio grande que rompe la API. Se indica con un footer `BREAKING CHANGE:` o un `!` después del tipo/scope. Puede combinarse con cualquier otro tipo. |

### Descripción

- Entre 50 y 60 caracteres.
- Presente imperativo: "agregar", no "agregado" ni "agregando".
- No inicia con mayúscula.
- Sin punto (`.`) al final.

### Módulo

Módulo o componente afectado por el cambio. Puede omitirse si no aplica.

### Cuerpo (opcional)

Se añade cuando el cambio necesita más contexto del que cabe en la descripción corta. Debe explicar el porqué del cambio, no repetir lo que ya se ve en el diff.

---

## Referencias

- Convención de commits: basada en la [guía de commits en git](https://platzi.com/blog/guia-de-commits-en-git/)
