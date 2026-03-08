export interface Patient {
  id: string;
  documentId: string;
  firstName: string;
  lastName: string;
  phone: string;
  gender: 'Hombre' | 'Mujer' | 'Otro';
  birthDate?: string;
  email?: string;
  //❌ No se debe sacar el password del back para autenticar, es inseguro
  password?: string;
}