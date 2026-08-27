export interface Appointment {
  id: string;
  patientId: string;
  doctorId: string;
  doctorName: string;
  specialty: string;
  date: string;
  time: string;
  status: 'AGENDADA' | 'ATENDIDA' | 'CANCELADA' | 'NO_ASISTIO' | 'REPROGRAMADA';
}
