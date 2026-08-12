/**
 * Interfaz genérica para manejar respuestas paginadas provenientes del backend.
 * @template T - Tipo de dato de los elementos contenidos en la página.
 * Puede ser un tipo primitivo o definir una interfaz personalizada
 * (ej. `<PagedResponse<MedicalRecord>>`).
 */
export interface PagedResponse<T> {
  content: T[];
  page: number;
  totalPages: number;
  totalElements: number;
}
