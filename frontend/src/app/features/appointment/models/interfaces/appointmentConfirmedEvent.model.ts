/**
 * Evento emitido al confirmar la cita exitosamente.
 * El padre decide qué hacer después (navegar, refrescar lista, etc.).
 */
export interface AppointmentConfirmedEvent {
  patientId: string;
}