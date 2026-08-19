import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { normalizeHttpError } from './normalize-http-error';

/**
 * Interceptor HTTP global de manejo de errores.
 *
 * Captura cualquier error de respuesta, lo normaliza a `AppError` vía
 * `normalizeHttpError`, y lo relanza para que cada componente decida cómo
 * reaccionar (mostrar mensaje, navegar, limpiar campos, etc.).
 *
 * El único caso resuelto de forma global, sin intervención de componentes,
 * es la sesión expirada (`UNAUTHORIZED`): siempre redirige a login, ya que
 * no existe una decisión de negocio distinta posible para ese caso.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const keycloak = inject(Keycloak);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const appError = normalizeHttpError(error);

      if (appError.errorCode === 'UNAUTHORIZED') {
        keycloak.login({ redirectUri: window.location.href });
      }

      return throwError(() => appError);
    })
  );
};
