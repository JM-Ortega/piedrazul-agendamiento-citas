/**
 * Resultado de búsqueda de pacientes.
 * Coincide con `PatientSummaryResponse` del backend
 * (GET /api/patients?search=...).
 */
export interface PatientQuickResult {
  id: string;
  identification: string;
  firstName: string;
  lastName: string;
}
