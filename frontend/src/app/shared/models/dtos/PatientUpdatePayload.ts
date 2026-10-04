import { Patient } from '../interfaces/patient.model';

export interface PatientUpdatePayload {
  identificationType: Patient['identificationType'];
  identification: string;
  firstName: string;
  lastName: string;
  phone: string;
  sex: Patient['sex'];
  birthDate: string;
  email: string | null;
  guardianPhone: string | null;
}
