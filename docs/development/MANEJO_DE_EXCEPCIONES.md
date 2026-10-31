# Manejo de Excepciones — Convención Backend / Frontend

## 1. Propósito

Es necesario llegar a un acuerdo sobre el formato de los errores que se envían al frontend, para que este pueda **recuperar con claridad los errores que vienen del backend** sin recibir un JSON de error diferente por cada tipo de excepción.

Sin un estándar, el frontend puede terminar recibiendo respuestas como esta, generadas automáticamente por Spring cuando falla una validación:

```json
{
  "timestamp": "2026-07-23T15:03:37.238Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for object='createSystemUserPayload'. Error count: 1",
  "errors": [
    {
      "objectName": "createSystemUserPayload",
      "field": "user.identification",
      "rejectedValue": "10552",
      "codes": [
        "ValidDocument.createSystemUserPayload.user.identification",
        "ValidDocument.user.identification",
        "ValidDocument.identification",
        "ValidDocument.java.lang.String",
        "ValidDocument"
      ],
      "arguments": [
        {
          "arguments": null,
          "code": "user.identification",
          "codes": [
            "createSystemUserPayload.user.identification",
            "user.identification"
          ],
          "defaultMessage": "user.identification"
        }
      ],
      "defaultMessage": "user.identification"
    }
  ]
}
```

Esto no es una excepción de dominio, es una **excepción de validación** (`@NotBlank`, `@Pattern`, `@ValidDocument`, etc.). Normalizando los errores logramos que al frontend siempre le llegue **un mismo formato estándar**, sin importar qué módulo o qué tipo de excepción lo originó, y así estandarizamos la forma de mostrarle los errores al usuario.

---

## 2. Reglas para las validaciones (`@NotBlank`, `@Pattern`, `@NotNull`, etc.)

Todas las anotaciones de validación deben tener un **mensaje explícito**, para que el error sea diciente y no uno genérico.

❌ Incorrecto (mensaje genérico):

```java
public record CreateSystemUserPayload(
        @Valid
        @NotNull
        CreateSystemUserRequest user,
        ...
) {}
```

✅ Correcto (mensaje descriptivo):

```java
public record CreateSystemUserPayload(
        @Valid
        @NotNull(message = "La informacion para crear el usuario debe ser proporcionada")
        CreateSystemUserRequest user,
        ...
) {}
```

Esto hace que el `defaultMessage` que llega en la respuesta sea claro:

```json
"defaultMessage": "La informacion para crear el usuario debe ser proporcionada"
```

### `@Validated` en los endpoints

Todo endpoint que reciba un objeto anotado con validaciones (`@NotBlank`, `@Pattern`, etc.) debe marcar el parámetro con `@Validated` para que las validaciones se ejecuten al momento de recibir la petición:

```java
@PostMapping("/{doctorId}")
@PreAuthorize("hasAnyRole('ADMIN', 'SCHEDULER', 'PATIENT', 'DOCTOR')")
public ResponseEntity<?> createSchedule(
        @PathVariable UUID doctorId,
        @RequestBody @Validated CreateScheduleRequest request
) {
    ...
}
```

---

## 3. Estandarización del manejo de errores (Backend)

### 3.1 Estándar de referencia: RFC 9457

La estructura de respuesta se basa en el **RFC 9457 (Problem Details for HTTP APIs)**, que es la especificación que estandariza el formato de los mensajes de error dentro del cuerpo de las respuestas de una API. El uso del `HttpStatus` debe respetar el listado que provee Spring.

### 3.2 `BaseExceptionHandler`

En la carpeta `shared` existe un `BaseExceptionHandler` que construye la respuesta estándar (`ProblemDetail`):

```java
// ProblemDetail, estándar definido en RFC 9457
public abstract class BaseExceptionHandler {

    protected ProblemDetail buildProblem(
            HttpStatus status,
            String title,
            String detail,
            String module,
            String errorCode,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        String typeUri = String.format(
                "https://piedrazul/errors/%s/%s",
                module,
                errorCode.toLowerCase().replace("_", "-")
        );
        problem.setType(URI.create(typeUri));

        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("module", module);
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }
}
```

Notas sobre los campos:

- `HttpServletRequest request` permite obtener la ruta que falló (ej. `/api/user/users`), usada en `instance`.
- `type` se construye a partir del `module` y el `errorCode`, dando una URI identificable del error dentro del proyecto.

### 3.3 Un `@RestControllerAdvice` por módulo

Debe existir un `@RestControllerAdvice` **por módulo**, es decir, un manejador global de excepciones para cada módulo. Esto permite:

- Mantener las reglas de negocio aisladas por módulo.
- Evitar que un fallo en un módulo exponga los detalles de otro.

Ejemplo de respuesta esperada:

```json
{
  "type": "https://example.com",
  "title": "Tu cuenta no tiene fondos suficientes.",
  "status": 403,
  "detail": "El cobro de $50 USD falló porque tu saldo actual es de...",
  "instance": "/compras/transaccion-88492"
}
```

Para lograr el aislamiento entre módulos, cada handler debe declarar `basePackageClasses`, indicando explícitamente qué controladores atiende:

```java
@RestControllerAdvice(basePackageClasses = {DoctorController.class, SheduleController.class})
@Slf4j
public class DoctorExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ...
}
```

> El handler de `Doctor` es la **plantilla** para todos los demás handlers del proyecto: deben tener exactamente los mismos métodos. Lo único que cambia entre módulos es el nombre del módulo y el código de error.

Ejemplo de método de manejo de una excepción de dominio:

```java
@ExceptionHandler(EntityNotFoundException.class)
public ProblemDetail handleNotFound(
        EntityNotFoundException ex,
        HttpServletRequest request
) {
    log.warn("Recurso no encontrado en doctors: {}", ex.getMessage());

    return buildProblem(
            HttpStatus.NOT_FOUND,
            "Not found",
            ex.getMessage(),
            "doctors",
            "DOCTOR_NOT_FOUND",
            request
    );
}
```

### 3.4 Todas las excepciones deben heredar de `BusinessException`

Es obligatorio que **todas** las excepciones de dominio hereden de `BusinessException` (a través de una clase base por módulo), definiendo en su constructor el `errorCode` y el `HttpStatus`:

```java
public abstract class DoctorBusinessException extends RuntimeException implements BusinessException {
    private final String errorCode;
    private final HttpStatus status;
    ...
}
```

```java
public class DateConflictException extends DoctorBusinessException {
    public DateConflictException(String message) {
        super(message, "DATE_CONFLICT", HttpStatus.CONFLICT);
    }
}
```

Al lanzar la excepción, se define el **mensaje detallado y conciso** que se mostrará en el frontend:

```java
if (doctor.getLaborEnd() != null && newLaborStart.isAfter(doctor.getLaborEnd())) {
    throw new DateConflictException("La fecha de inicio no puede ser posterior a la fecha de finalización");
}
```

Resumen del reparto de responsabilidades:

| Elemento                      | Dónde se define                           |
| ----------------------------- | ----------------------------------------- |
| Código de error (`errorCode`) | En la clase de la excepción               |
| `HttpStatus`                  | En la clase de la excepción               |
| Mensaje detallado (`detail`)  | En el `throw new ...Exception("mensaje")` |

### 3.5 Formato final de respuesta

Con esta convención, el frontend deja de recibir el JSON gigante inicial y siempre recibe los **mismos campos**:

```json
{
  "detail": "El horario debe tener un dia asignado",
  "instance": "/api/doctor/schedules/5207e023-3570-4bd8-ae01-c0596e60e576",
  "status": 400,
  "title": "Validation error",
  "type": "https://piedrazul/errors/doctors/validation-error",
  "errorCode": "VALIDATION_ERROR",
  "module": "doctors",
  "timestamp": "2026-07-24T15:00:53.481378624Z"
}
```

---

## 4. Qué debe hacer el Frontend (Angular)

### 4.1 Modelo único de error

Definir un único modelo de error, que representa todos los errores que devuelve el backend, independientemente del módulo (users, doctors, appointments, etc.):

```ts
// app/shared/models/interfaces/ApiProblem.ts
export interface ApiProblem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  errorCode: string;
  module: string;
  timestamp: string;
}
```

### 4.2 `HttpInterceptor` para errores globales

Se implementa un `HttpInterceptor` para manejar los errores **verdaderamente globales** (401, 403, 500, problemas de red), dejando que cada componente decida qué hacer con los errores de negocio o de validación.

Ejemplo: el componente de "Crear médico" puede mostrar el `detail` en una alerta, mientras que el de "Agendar cita" puede mostrar un diálogo o volver al paso correspondiente si el error es `APPOINTMENT_OVERLAP`.

```ts
@Injectable()
export class ApiErrorInterceptor implements HttpInterceptor {
  intercept(
    req: HttpRequest<unknown>,
    next: HttpHandler,
  ): Observable<HttpEvent<unknown>> {
    return next.handle(req).pipe(
      catchError((response: HttpErrorResponse) => {
        const problem = response.error as ApiProblem;

        switch (problem?.errorCode) {
          case "UNAUTHORIZED":
            // redirigir al login
            break;

          case "FORBIDDEN":
            // mostrar diálogo
            break;

          case "INTERNAL_ERROR":
            // log
            break;
        }

        return throwError(() => problem);
      }),
    );
  }
}
```

### 4.3 Ejemplos de acciones por `errorCode`

| `errorCode`        | Acción esperada              |
| ------------------ | ---------------------------- |
| `VALIDATION_ERROR` | Mostrar un modal             |
| `UNAUTHORIZED`     | Redirigir al login           |
| `SESSION_EXPIRED`  | Cerrar la sesión             |
| `DOCTOR_NOT_FOUND` | Volver a la lista de médicos |

En general, se puede mostrar el mensaje traído directamente del backend (`detail`), por lo que **no es necesario que el frontend conozca internamente** qué pasó para que se diera el error (por ejemplo, un cruce de horarios o que una hora de cita ya no esté disponible). Solo se muestra el error por defecto, y únicamente si es necesario ejecutar una acción diferente se usa el `switch` sobre `errorCode`, por ejemplo, si el error es de validación, devolver al usuario a la vista donde llena los datos.

Ejemplo de uso en un componente:

```ts
forkJoin(calls).subscribe({
  next: () => {
    this.reloadDoctor(form.id, form);
    this.savedId.set(form.id);
    this.editingId.set(null);
    setTimeout(() => this.savedId.set(null), 3000);
  },
  error: (problem: ApiProblem) => {
    switch (problem.errorCode) {
      case "DOCTOR_ALREADY_ACTIVE":
        this.errorGuardado.set(
          "El médico ya está trabajando activamente. Debe deshabilitarlo primero para poder cambiar su período laboral.",
        );
        break;

      default:
        this.errorGuardado.set(problem.detail);
    }

    this.showErrorModal.set(true);
  },
});
```

---

## 5. Checklist para el equipo

- [ ] Todas las anotaciones de validación (`@NotBlank`, `@Pattern`, `@NotNull`, etc.) tienen `message` explícito y descriptivo.
- [ ] Todos los endpoints que reciben objetos validables usan `@Validated`.
- [ ] Cada módulo tiene su propio `@RestControllerAdvice` extendiendo `BaseExceptionHandler`, con `basePackageClasses` apuntando solo a sus controladores.
- [ ] Todas las excepciones de dominio heredan de `BusinessException` (vía una clase base de módulo), definiendo `errorCode` y `HttpStatus` en el constructor.
- [ ] El mensaje detallado (`detail`) se define en el `throw new ...Exception("mensaje conciso")`.
- [ ] El frontend usa el modelo único `ApiProblem` para tipar todos los errores del backend.
- [ ] El `HttpInterceptor` centraliza el manejo de errores globales (401, 403, 500, red).
- [ ] Cada componente maneja sus propios `errorCode` de negocio/validación cuando se requiere una acción distinta al mensaje por defecto.

---

## 6. Referencias

- [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [Hypertext Transfer Protocol (HTTP) Status Code Registry](https://www.iana.org/assignments/http-status-codes)
