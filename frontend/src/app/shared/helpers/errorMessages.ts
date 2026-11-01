/**
 * Mensajes para errores que no traen `detail` del backend porque ocurren
 * antes de llegar a él (sin conexión) o no incluyen cuerpo.
 * Los errores de negocio (ej. `PATIENT_NOT_FOUND`) usan el `detail`
 * que ya redacta el backend y no necesitan entrada aquí.
 */
export const GLOBAL_ERROR_MESSAGES: Record<string, string> = {
  OFFLINE: 'No tienes conexión a internet. Verifica tu red e intenta de nuevo.',
  NETWORK_ERROR: 'No se pudo conectar con el servidor. Intenta más tarde.',
  SERVER_ERROR: 'Ocurrió un error en el servidor. Intenta más tarde.',
  UNAUTHORIZED: 'Tu sesión expiró. Por favor inicia sesión de nuevo.',
  FORBIDDEN: 'No tienes permisos para realizar esta acción.',
  UNKNOWN_ERROR: 'Ocurrió un error inesperado. Intenta de nuevo.',
};
