/** Resultado de una acción auditada. */
export type AuditOutcome = 'EXITOSO' | 'FALLIDO' | 'DENEGADO';

/**
 * Registro de auditoría devuelto por `GET /api/audit`.
 */
export interface AuditEventResponse {
  /** Identificador del registro de auditoría. */
  id: string;
  /** Instante en que ocurrió la acción, ISO-8601 en UTC. */
  timestamp: string;
  /** Nombre completo vigente de quien ejecutó la acción. `null` si la cuenta no tiene persona asociada. */
  actorName: string | null;
  /** Usuario de Keycloak (número de documento). `null` si la cuenta no tiene persona asociada. */
  actorUsername: string | null;
  /** Id de la cuenta de Keycloak. Es `system` cuando la acción la hace el sistema. */
  actorId: string;
  /** Roles que tenía quien ejecutó la acción en ese momento. */
  actorRoles: string[];
  /** Código de la acción (ej. `PACIENTE_MODIFICADO`). Su nombre legible está en el catálogo. */
  action: string;
  moduleCode: string;
  moduleName: string;
  outcome: AuditOutcome;
  /** Tipo del objeto afectado. */
  targetEntityType: string | null;
  /** Id del objeto afectado. `N/A` cuando la acción no tiene un objeto identificable. */
  targetEntityId: string | null;
}

export interface AuditCatalogOption {
  code: string;
  name: string;
}

/** Acción del catálogo, con el módulo al que pertenece. */
export interface AuditActionOption extends AuditCatalogOption {
  moduleCode: string;
  moduleName: string;
}

export interface AuditFilterCatalog {
  modules: AuditCatalogOption[];
  actions: AuditActionOption[];
  outcomes: AuditCatalogOption[];
  targetEntityTypes: AuditCatalogOption[];
  /** Máximo de días que puede abarcar el rango `from`–`to`. */
  maxRangeDays: number;
  /** Máximo de caracteres del texto de búsqueda. */
  maxSearchLength: number;
}

export interface AuditQueryParams {
  /** Primer día del rango, `YYYY-MM-DD`. */
  from?: string;
  /** Último día del rango, `YYYY-MM-DD`. */
  to?: string;
  moduleCode?: string;
  outcome?: AuditOutcome | '';
  /** Texto a buscar en el nombre o el documento de quien ejecutó la acción. */
  search?: string;
  pageNumber?: number;
  pageSize?: number;
}
