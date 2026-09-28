import { AuditOutcome } from './audit.dto';

/** Filtros de búsqueda de las pages de auditoría. Un valor vacío significa "sin filtrar por ese campo". */
export interface AuditFilters {
  /** Texto a buscar en el nombre o el documento de quien ejecutó la acción. */
  search: string;
  /** Primer día del rango, `YYYY-MM-DD`. */
  from: string;
  /** Último día del rango, `YYYY-MM-DD`. */
  to: string;
  outcome: AuditOutcome | '';
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  search: '',
  from: '',
  to: '',
  outcome: '',
};

/** Registros por página en las tablas de auditoría. */
export const AUDIT_PAGE_SIZE = 20;

/**
 * Módulo del backend que delimita cada page. El endpoint de auditoría es uno
 * solo; cada page ve la porción de la bitácora que pertenece a su módulo.
 */
export const AUDIT_MODULE = {
  appointments: 'CITAS',
  users: 'USUARIOS',
  medicalCheckup: 'CONTROLES_MEDICOS',
} as const;
