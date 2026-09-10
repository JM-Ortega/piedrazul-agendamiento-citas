import { AppointmentsPatient } from '../models/dtos/appointments.dto';

type AppointmentState = AppointmentsPatient['appointmentState'];

export const APPOINTMENT_STATUS_LABELS: Record<AppointmentState, string> = {
  AGENDADA: 'Agendada',
  ATENDIDA: 'Atendida',
  CANCELADA: 'Cancelada',
  NO_ASISTIO: 'No asistió',
  REPROGRAMADA: 'Reprogramada',
};

export const APPOINTMENT_STATUS_CLASSES: Record<AppointmentState, string> = {
  AGENDADA: 'bg-green-100 text-green-700',
  ATENDIDA: 'bg-blue-200 text-blue-700',
  CANCELADA: 'bg-red-100 text-red-700',
  NO_ASISTIO: 'bg-orange-100 text-orange-700',
  REPROGRAMADA: 'bg-yellow-100 text-yellow-700',
};
