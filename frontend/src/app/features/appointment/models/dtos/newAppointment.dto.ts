export interface NewAppointment {
  doctorId: string;
  specialty: string;
  date: string;
  startTime: string;
  schedulingOrigin: 'AUTONOMO' | 'MANUAL';
  patientId?: string;
  documentType?: string;
  documentNumber?: string;
  firstName?: string;
  lastName?: string;
  phone?: string;
  gender?: 'MASCULINO' | 'FEMENINO' | 'OTRO' | '';
  birthDate?: string;
  email?: string;
  guardianPhone?: string;
}
