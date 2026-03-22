export interface Appointment {
  id: string;
  patientId: string;
  doctorId: string;
  date: string;
  time: string;
  status: 'confirmed' | 'pending' | 'cancelled';
}
