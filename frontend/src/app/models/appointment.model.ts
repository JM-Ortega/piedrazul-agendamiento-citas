export interface Appointment {
  id: string;
  patientId: string;
  doctorId: string;
  date: string;
  time: string;
  status: 'AGENDADA' | 'ATENDIDA' | 'CANCELADA' | 'NO_ASISTIO' | 'REPROGRAMADA';
}
