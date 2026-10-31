# Convenciones del Frontend

## 1. Propósito

El frontend de Piedra Azul creció de forma orgánica junto con los módulos `admin`, `appointment`, `doctor`, `patient` y `scheduler`. En el camino se adoptaron de manera implícita varios patrones (atomic design, separación de modelos, paginación server-side, manejo centralizado de errores, linting), pero sin un documento formal que los explique.

Sin esta convención, un desarrollador nuevo puede terminar reimplementando lógica ya resuelta (un botón propio, un nuevo interceptor de errores, paginación en el cliente) o mezclando el contrato de red con el modelo de dominio. Este documento estandariza esas decisiones para que todo el equipo las aplique de la misma forma hacia adelante.

---

## 2. Atomic Design en el `design-system/`

### 2.1 Niveles

El `design-system/` concentra los componentes puramente visuales, en tres niveles:

| Nivel         | Ejemplos                                             | Resuelve                                            |
| ------------- | ----------------------------------------------------- | ---------------------------------------------------- |
| **Atoms**     | `button`, `input`, `select`                          | La unidad mínima de UI; un solo lugar para estilos y estados (foco, error, hover). |
| **Molecules** | `datepicker`, `pagination`, `toast-message`          | Un patrón de UI reutilizable, con su propia lógica de interacción. |
| **Organisms** | `appointment-modal`, `confirm-modal` | Bloques funcionales completos que combinan moléculas y átomos. |

### 2.2 Consumo desde las features

Las features **no reimplementan** estos componentes: los consumen directamente desde el `design-system/`. Esto da:

- **Consistencia visual**: toda la app comparte la misma base de UI.
- **Separación de responsabilidades**: el `design-system/` solo presenta; la lógica de negocio vive en la feature.

---

## 3. Organización de carpetas

```
src/
├── core/            # servicios, guards e interceptors compartidos por varias features
├── design-system/   # atoms / molecules / organisms
├── features/
|   ├── admin/
│   ├── appointment/
│   │   ├── components/   # usados por las pages de esta misma feature
│   │   ├── models/
│   │   ├── pages/
│   │   └── services/      # propios de este dominio, no compartidos
│   ├── doctor/
│   ├── patient/
|   ├── registration/
│   └── scheduler/
└── shared/          # reutilizable entre features, no puramente visual (navbar, patient-form, etc.)
```

- Un servicio usado por **más de una feature** vive en `core/`; si es propio de un solo dominio, vive dentro de esa feature.
- Cada feature es autocontenida: sus `components/` solo son usados por sus propias `pages/`.
- Si un servicio de una feature termina siendo necesario en otra, se **promueve** a `core/` o `shared/` en lugar de duplicarlo.

Esta organización ubica el código por **dominio de negocio primero**, no por tipo técnico, lo que facilita el trabajo en paralelo entre features y hace explícito el alcance de cada pieza según dónde vive.

---

## 4. Manejo de errores HTTP

### 4.1 Interceptor global

Todo error HTTP pasa por un interceptor en `core/interceptors/`, basado en **RFC 7807 (Problem Details)**. Garantiza que ningún error quede sin manejar ni se muestre un error técnico crudo al usuario.

### 4.2 Catálogo `GLOBAL_ERROR_MESSAGES`

Mapea tipos/códigos de error de negocio a mensajes de UI. Es la única fuente de verdad: evita que el mismo error muestre mensajes distintos según quién lo implementó.

### 4.3 `mapHttpError()`

Cuando un flujo puntual necesita un mensaje distinto al genérico (ej. conflicto de horarios al agendar una cita), se usa este utilitario en ese punto, en lugar de escribir un nuevo `catchError` propio.

---

## 5. ESLint y Prettier

- Las violaciones de ESLint se **corrigen en el código**, no se suprimen. `eslint-disable` solo se justifica en casos excepcionales y documentados.
- Prettier define el formato automáticamente (indentación, comillas, longitud de línea), para que el code review no discuta estilo.
- Esta convención surgió de una limpieza general del proyecto, donde se corrigió código muerto (ej. un output `appointmentConfirmed` sin uso) y llamadas HTTP redundantes en vez de silenciarlas.

---

## 6. Separación de modelos

| Carpeta        | Contenido                                                   |
| -------------- | ------------------------------------------------------------ |
| `dtos/`        | Contratos que viajan por la red (request/response de la API). |
| `interfaces/`  | Modelos de dominio usados en el frontend.                    |
| `types/`       | Tipos auxiliares (uniones, alias, enums de UI).              |

Aplica tanto en `shared/models` como dentro de cada feature que lo requiera.

---

## 7. Paginación server-side

Todo listado paginado usa el mismo patrón:

- `PaginatedState<T>` — estado del listado (página, tamaño, total, datos).
- `withPagination()` — conecta ese estado con la llamada HTTP.
- `PageResponse<T>` — forma estándar en que el backend devuelve una página.

`patient`, `scheduler`, `doctor` y `admin` lo consumen de la misma forma, delegando filtrado, ordenamiento y paginado al backend. No se implementa paginación client-side ni scroll infinito para listados nuevos.
