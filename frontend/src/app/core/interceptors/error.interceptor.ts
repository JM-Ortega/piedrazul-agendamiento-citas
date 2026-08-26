import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import Keycloak from 'keycloak-js';
import { catchError, tap, throwError } from 'rxjs';
import { AppHealthService } from '../services/app-health.service';
import { normalizeHttpError } from './normalize-http-error';

/**
 * Interceptor HTTP global de manejo de errores.
 *
 * Captura cualquier error de respuesta, lo normaliza a `AppError` vía
 * `normalizeHttpError`, y lo relanza para que cada componente decida cómo
 * reaccionar (mostrar mensaje, navegar, limpiar campos, etc.).
 *
 * Dos casos se resuelven de forma global, sin intervención de componentes:
 * - Sesión expirada (`UNAUTHORIZED`): siempre redirige a login.
 * - Backend inalcanzable (`NETWORK_ERROR`/`OFFLINE`): activa un estado
 *   global (`AppHealthService`) que bloquea la UI con una pantalla de error,
 *   ya que en ese caso ningún componente puede operar con datos reales.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const keycloak = inject(Keycloak);
  const appHealth = inject(AppHealthService);

  return next(req).pipe(
    tap(() => appHealth.reportReachable()),
    catchError((error: HttpErrorResponse) => {
      const appError = normalizeHttpError(error);

      if (appError.errorCode === 'UNAUTHORIZED') {
        keycloak.login({ redirectUri: window.location.href });
      }

      if (
        appError.errorCode === 'NETWORK_ERROR' ||
        appError.errorCode === 'OFFLINE'
      ) {
        appHealth.reportUnreachable();
      }

      return throwError(() => appError);
    })
  );
};
