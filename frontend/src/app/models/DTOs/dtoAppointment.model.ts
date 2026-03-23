export interface dtoAppointment {
  id: string;
  date: string;
  time: string;
  appointmentState:
    | 'AGENDADA'
    | 'ATENDIDA'
    | 'CANCELADA'
    | 'NO_ASISTIO'
    | 'REPROGRAMADA';
  doctorName: string;
  doctorSpecialty: string;
  patientFirstName: string;
  patientLastName: string;
  patientDocument: string;
}
