export interface AppointmentsPatient {
  idAppointment: string;
  date: string;
  startTime: string;
  appointmentState:
    | 'AGENDADA'
    | 'ATENDIDA'
    | 'CANCELADA'
    | 'NO_ASISTIO'
    | 'REPROGRAMADA';
  doctorName: string;
  specialty: string;
  patientFirstName: string;
  patientLastName: string;
  documentNumber: string;
}
