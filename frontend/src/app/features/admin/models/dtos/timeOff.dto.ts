/** Estado de un descanso según la fecha actual. */
export type TimeOffStatus = 'PROGRAMADO' | 'EN_CURSO' | 'FINALIZADO';

/** Descanso de un doctor tal como lo devuelve el backend. */
export interface TimeOffDto {
  id: string;
  doctorId: string;
  startDate: string; // yyyy-MM-dd (inclusiva)
  endDate: string; // yyyy-MM-dd (inclusiva)
  reason: string;
  status: TimeOffStatus;
}

/** Cuerpo de `POST /doctor/time-off`. */
export interface CreateTimeOffRequest {
  doctorId: string;
  startDate: string; // yyyy-MM-dd
  endDate: string; // yyyy-MM-dd
  reason: string;
}
