export interface Patient {
  id: string;
  documentType:
    'REGISTRO_NACIMIENTO' | 'TARJETA_IDENTIDAD' | 'CEDULA' | 'PASAPORTE' | '';
  documentNumber: string;
  firstName: string;
  lastName: string;
  phone: string;
  gender: 'MASCULINO' | 'FEMENINO' | 'OTRO' | '';
  birthDate: string;
  email?: string;
  guardianPhone?: string;
}
