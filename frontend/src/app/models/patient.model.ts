export interface Patient {
  id: string;
  documentId: string;
  firstName: string;
  lastName: string;
  phone: string;
  gender: 'Hombre' | 'Mujer' | 'Otro' | '';
  birthDate?: string;
  email?: string;
}