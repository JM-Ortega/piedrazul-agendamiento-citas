import { HttpErrorResponse } from '@angular/common/http';
/**
 * Traduce un error HTTP a un mensaje legible para el usuario.
 */
export function mapHttpError(err: HttpErrorResponse, fallback: string): string {
  if (err.status === 0) {
    return 'No se pudo conectar con el servidor. Intente más tarde.';
  }
  return err.error?.detail ?? err.error?.message ?? fallback;
}
