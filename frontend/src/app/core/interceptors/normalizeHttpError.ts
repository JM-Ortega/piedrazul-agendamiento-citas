import { HttpErrorResponse } from '@angular/common/http';
import {
  AppError,
  ApiProblemDetails,
} from '../../shared/models/interfaces/apiError.model';
import { GLOBAL_ERROR_MESSAGES } from '../../shared/helpers/errorMessages';

/**
 * Con `responseType: 'blob'` Angular entrega el cuerpo de un error HTTP como
 * `Blob`, por lo que el Problem Details del backend no se puede leer de forma
 * directa. Lo decodifica como JSON y devuelve un `HttpErrorResponse` con el
 * cuerpo ya parseado; si no es un `Blob` o no es JSON válido, devuelve el
 * error original sin cambios.
 */
export async function parseBlobErrorBody(
  error: HttpErrorResponse
): Promise<HttpErrorResponse> {
  if (!(error.error instanceof Blob)) return error;

  try {
    const body: unknown = JSON.parse(await error.error.text());
    return new HttpErrorResponse({
      error: body,
      headers: error.headers,
      status: error.status,
      statusText: error.statusText,
      url: error.url ?? undefined,
    });
  } catch {
    return error;
  }
}

/**
 * Traduce cualquier `HttpErrorResponse` a un `AppError` consistente,
 * sin importar su origen: cuerpo Problem Details del backend, caída total
 * de red, ausencia de conexión a internet, o error HTTP genérico sin cuerpo.
 *
 * Prioridad de resolución del mensaje:
 * 1. `detail` del backend, si el cuerpo trae `errorCode`.
 * 2. Catálogo `GLOBAL_ERROR_MESSAGES`, para el resto de los casos.
 *
 * @param error - Error HTTP crudo capturado por `errorInterceptor`.
 * @returns El error normalizado, listo para relanzar a los componentes.
 */
export function normalizeHttpError(error: HttpErrorResponse): AppError {
  // Caso 1: no hubo respuesta del servidor en absoluto.
  if (error.status === 0) {
    const offline = typeof navigator !== 'undefined' && !navigator.onLine;
    const errorCode = offline ? 'OFFLINE' : 'NETWORK_ERROR';
    return {
      status: 0,
      errorCode,
      message: GLOBAL_ERROR_MESSAGES[errorCode],
    };
  }

  // Caso 2: el backend respondió con el formato Problem Details.
  const body = error.error as ApiProblemDetails | null;
  if (body?.errorCode) {
    return {
      status: body.status ?? error.status,
      errorCode: body.errorCode,
      message: body.detail || GLOBAL_ERROR_MESSAGES['UNKNOWN_ERROR'],
      module: body.module,
    };
  }

  // Caso 3: hubo respuesta HTTP, pero sin cuerpo Problem Details.
  const errorCode =
    error.status === 401
      ? 'UNAUTHORIZED'
      : error.status === 403
        ? 'FORBIDDEN'
        : error.status >= 500
          ? 'SERVER_ERROR'
          : 'UNKNOWN_ERROR';

  return {
    status: error.status,
    errorCode,
    message: GLOBAL_ERROR_MESSAGES[errorCode],
  };
}
