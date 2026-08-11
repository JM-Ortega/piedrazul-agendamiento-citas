import { AppError } from '../models/interfaces/api-error.model';

/**
 * Devuelve el mensaje a mostrar al usuario a partir de un error ya
 * normalizado por `errorInterceptor`.
 *
 * @param error - Error normalizado recibido en el callback `error` de un subscribe().
 * @param fallback - Mensaje alternativo, solo se usa si `error.message` viene vacío.
 * @returns El mensaje listo para mostrar en pantalla.
 */
export function mapHttpError(error: AppError, fallback?: string): string {
  return error.message || fallback || 'Ocurrió un error inesperado.';
}
