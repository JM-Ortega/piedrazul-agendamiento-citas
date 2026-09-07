/** Payload para registrar la atención de un paciente sin cita previa */
export interface UnscheduledAttention {
  documentType: string;
  documentNumber: string;
  firstName: string;
  lastName: string;
  phone: string;
  gender: string;
  birthDate: string;
  email: string | undefined;
  guardianPhone: string | undefined;
  specialty: string;
  medicalCheckup: string | null;
}
