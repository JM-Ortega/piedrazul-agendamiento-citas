import { signal } from '@angular/core';
import { PageResponse } from '../models/dtos/pageResponse.dto';

/**
 * Metadata de paginación, desacoplada del contenido.
 * Se deriva de cualquier `PageResponse<T>` que devuelva el backend.
 */
export interface PaginationMeta {
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

/**
 * Encapsula el patrón repetido de "signal de contenido + signal de metadata
 * de paginación" que se necesita en cualquier servicio que consuma un
 * endpoint paginado (`PageResponse<T>`).
 */
export class PaginatedState<T> {
  private readonly _content = signal<T[]>([]);
  private readonly _pagination = signal<PaginationMeta | null>(null);

  /** Contenido de la página actualmente cargada. */
  readonly content = this._content.asReadonly();

  /** Metadata de paginación de la última carga. */
  readonly pagination = this._pagination.asReadonly();

  /** Actualiza `content` y `pagination` a partir de una respuesta paginada del backend. */
  set(page: PageResponse<T>): void {
    this._content.set(page.content);
    this._pagination.set({
      pageNumber: page.pageNumber,
      pageSize: page.pageSize,
      totalElements: page.totalElements,
      totalPages: page.totalPages,
      first: page.first,
      last: page.last,
    });
  }

  /** Limpia contenido y metadata*/
  clear(): void {
    this._content.set([]);
    this._pagination.set(null);
  }
}
