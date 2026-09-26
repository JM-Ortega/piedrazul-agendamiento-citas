/**
 * Contrato único que consumen las 3 pages de auditoría (citas, usuarios, control médico).
 * Cada page mapea su propio log a esta forma antes de pasarlo a `AuditTableComponent`.
 */
export interface AuditRow {
  id: string;
  name: string;
  role: string;
  userId: string;
  timestamp: string;
  action: string;
  status: 'success' | 'failed';
}

export interface AuditFilters {
  search: string;
  status: 'all' | 'success' | 'failed';
  date: string;
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  search: '',
  status: 'all',
  date: '',
};
