import { AuditRow } from './audit.model';

// TODO Estos 3 tipos deben reemplazarse por los DTOs reales que exponga el backend para cada log de auditoría.

export interface AppointmentAuditLog {
  id: string; //este es el id del registro
  timestamp: string;
  name: string;
  role: string;
  //authenticationId: string;
  userId: string;
  action: 'scheduled' | 'rescheduled' | 'cancelled'; //crear un enum con las 9 accciones que tiene lau en el backend y colocar el tipo aquí
  status: 'success' | 'failed';
  //targetEntityId: string; este es el id del objeto afectado
}

export interface UserManagementAuditLog {
  id: string;
  timestamp: string;
  name: string;
  role: string;
  userId: string;
  action: 'created' | 'modified' | 'deactivated' | 'activated';
  status: 'success' | 'failed';
}

export interface MedicalRecordAuditLog {
  id: string;
  timestamp: string;
  name: string;
  role: string;
  userId: string;
  action: 'created';
  status: 'success' | 'failed';
}

/** Normaliza un log de auditoría de citas a la fila común de la tabla. */
export function toAppointmentRow(log: AppointmentAuditLog): AuditRow {
  return log;
}

/** Normaliza un log de auditoría de usuarios a la fila común de la tabla. */
export function toUserRow(log: UserManagementAuditLog): AuditRow {
  return log;
}

/** Normaliza un log de auditoría de historias clínicas a la fila común de la tabla. */
export function toRecordRow(log: MedicalRecordAuditLog): AuditRow {
  return log;
}
