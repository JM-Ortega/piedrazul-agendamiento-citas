export interface MedicalRecord {
  id: string;
  patientId: string;
  doctorId: string;
  date: string;
  time: string;
  status: 'completed' | 'in-progress';
  observations: string;
}