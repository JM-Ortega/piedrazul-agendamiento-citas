/**
 * Forma exacta del cuerpo de error que envía el backend
 * siguiendo el estándar RFC 7807.
 */
export interface ApiProblemDetails {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errorCode?: string;
  module?: string;
  timestamp?: string;
}

/**
 * Forma normalizada de un error HTTP.
 * Es siempre la misma estructura, sin importar de donde vino el error.
 */
export interface AppError {
  status: number;
  errorCode: string;
  message: string;
  module?: string;
}
