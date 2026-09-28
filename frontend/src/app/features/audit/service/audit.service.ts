import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { PaginatedState } from '../../../shared/helpers/paginatedState';
import { PageResponse } from '../../../shared/models/dtos/pageResponse.dto';
import { withPagination } from '../../../shared/helpers/httpPagination';
import {
  AuditEventResponse,
  AuditFilterCatalog,
  AuditQueryParams,
} from '../models/audit.dto';

@Injectable({ providedIn: 'root' })
export class AuditService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  /** Registros de auditoría cargados de forma paginada (contenido + metadata). */
  private readonly eventsState = new PaginatedState<AuditEventResponse>();
  /** Registros de la página actualmente cargada.*/
  readonly events = this.eventsState.content;
  /** Metadata de paginación de la última carga. */
  readonly pagination = this.eventsState.pagination;

  private readonly _catalog = signal<AuditFilterCatalog | null>(null);
  /** Catálogo de filtros del backend. */
  readonly catalog = this._catalog.asReadonly();
  /** Nombre legible de cada acción, indexado por su código. */
  readonly actionNames = computed<Record<string, string>>(() =>
    Object.fromEntries(
      (this._catalog()?.actions ?? []).map((a) => [a.code, a.name])
    )
  );

  /**
   * Carga el catálogo de filtros y lo deja en el signal `catalog`.
   */
  loadCatalog(): Observable<AuditFilterCatalog> {
    const cached = this._catalog();
    if (cached) {
      return of(cached);
    }
    return this.http
      .get<AuditFilterCatalog>(`${this.apiUrl}/audit/catalog/filters`)
      .pipe(tap((catalog) => this._catalog.set(catalog)));
  }

  /**
   * Carga una página de registros de auditoría según los filtros dados y
   * actualiza los signals `events`/`pagination` con el resultado.
   *
   * @param params.from primer día del rango, `YYYY-MM-DD` (opcional)
   * @param params.to último día del rango, `YYYY-MM-DD` (opcional)
   * @param params.moduleCode módulo al que pertenecen las acciones (opcional)
   * @param params.outcome resultado de la acción (opcional)
   * @param params.search texto a buscar en nombre o documento del actor (opcional)
   * @param params.pageNumber número de página a solicitar, base 0
   * @param params.pageSize cantidad de registros por página
   */
  loadEvents(
    params?: AuditQueryParams
  ): Observable<PageResponse<AuditEventResponse>> {
    return this.getEvents(params).pipe(
      tap((page) => this.eventsState.set(page))
    );
  }

  /**
   * Realiza la petición HTTP GET paginada de registros de auditoría,
   * enviando solo los filtros que tienen valor.
   */
  private getEvents(
    params?: AuditQueryParams
  ): Observable<PageResponse<AuditEventResponse>> {
    let httpParams = new HttpParams();
    if (params?.from) httpParams = httpParams.set('from', params.from);
    if (params?.to) httpParams = httpParams.set('to', params.to);
    if (params?.moduleCode)
      httpParams = httpParams.set('moduleCode', params.moduleCode);
    if (params?.outcome) httpParams = httpParams.set('outcome', params.outcome);
    if (params?.search?.trim())
      httpParams = httpParams.set('search', params.search.trim());
    httpParams = withPagination(
      httpParams,
      params?.pageNumber,
      params?.pageSize
    );

    return this.http.get<PageResponse<AuditEventResponse>>(
      `${this.apiUrl}/audit`,
      { params: httpParams }
    );
  }

  /**
   * Vacía los registros cargados. Se usa al entrar a una page de auditoría
   * para no mostrar por un instante los registros de otra.
   */
  clearEvents(): void {
    this.eventsState.clear();
  }

  /**
   * Borra toda la caché en memoria al cerrar sesión
   */
  clearAllData(): void {
    this.eventsState.clear();
    this._catalog.set(null);
  }
}
