/** Paciente tal como lo devuelve `GET /user/patients` (datos básicos + estado de la cuenta). */
export interface SystemPatient {
  id: string;
  firstName: string;
  lastName: string;
  documentId: string;
  accountEnabled: boolean;
}
