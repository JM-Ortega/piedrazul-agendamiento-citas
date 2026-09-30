import { AuditOutcome } from './audit.dto';

/**
 * Filtros de búsqueda de la page de auditoría. Un valor vacío significa "sin
 * filtrar por ese campo". `search` y `targetEntityId` son mutuamente
 * independientes: `search` hace match por prefijo en nombre o documento;
 * `targetEntityId` requiere el id exacto.
 */
export interface AuditFilters {
  /** Texto a buscar en el nombre o el documento de quien ejecutó la acción (prefijo). */
  search: string;
  /** Id exacto del objeto afectado. */
  targetEntityId: string;
  /** Primer día del rango, `YYYY-MM-DD`. */
  from: string;
  /** Último día del rango, `YYYY-MM-DD`. */
  to: string;
  outcome: AuditOutcome | '';
  /** Código de acción (ej. `PACIENTE_MODIFICADO`), del catálogo. */
  action: string;
  /** Código de módulo (ej. `CITAS`), del catálogo. */
  moduleCode: string;
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  search: '',
  targetEntityId: '',
  from: '',
  to: '',
  outcome: '',
  action: '',
  moduleCode: '',
};

/** Registros por página en la tabla de auditoría. */
export const AUDIT_PAGE_SIZE = 20;
