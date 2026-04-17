/**
 * Datos del paciente que el componente padre (paciente) debe proveer.
 * El agendador los resuelve internamente mediante búsqueda por documento.
 */
export interface PatientSnapshot {
  id?: string;
  firstName?: string;
  lastName?: string;
  documentNumber?: string;
  documentType?: string;
  phone?: string;
  gender?: 'MASCULINO' | 'FEMENINO' | 'OTRO' | '';
  birthDate?: string;
  email?: string;
}