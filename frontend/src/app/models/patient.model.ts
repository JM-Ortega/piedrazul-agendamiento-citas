export interface Patient {
  id: string;
  documentId: string;
  firstName: string;
  lastName: string;
  phone: string;
  gender: 'MASCULINO' | 'FEMENINO' | 'OTRO' | '';
  birthDate?: string;
  email?: string;
}