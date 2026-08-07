export interface Patient {
  id: string;
  identificationType:
    'REGISTRO_NACIMIENTO' | 'TARJETA_IDENTIDAD' | 'CEDULA' | 'PASAPORTE' | '';
  identification: string;
  firstName: string;
  lastName: string;
  phone: string;
  sex: 'MASCULINO' | 'FEMENINO' | '';
  birthDate: string;
  email?: string;
  guardianPhone?: string;
}
