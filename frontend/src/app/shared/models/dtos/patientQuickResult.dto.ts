/**
 * Resultado de búsqueda rápida de pacientes.
 * TODO(backend): alinear con el DTO real cuando exista el endpoint
 * `GET /patients/search?query=...`.
 */
export interface PatientQuickResult {
  id: string;
  firstName: string;
  lastName: string;
  documentNumber: string;
  phone: string;
  sex: string;
  appointmentsCount: number;
}
