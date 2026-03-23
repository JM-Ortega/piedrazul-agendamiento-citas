export interface dtoAppointment {
  id: string;
  date: string;
  time: string;
  status: 'confirmed' | 'pending' | 'cancelled';
  doctorName: string;
  doctorSpecialty: string;
  patientFirstName: string;
  patientLastName: string;
  patientDocument: string;
}
