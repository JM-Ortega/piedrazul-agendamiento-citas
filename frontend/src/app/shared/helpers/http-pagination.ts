import { HttpParams } from '@angular/common/http';

const DEFAULT_PAGE_SIZE = 5;

/** Agrega los parámetros `pageNumber`/`pageSize` a un `HttpParams` existente. */
export function withPagination(
  params: HttpParams,
  pageNumber?: number,
  pageSize?: number
): HttpParams {
  return params
    .set('pageNumber', (pageNumber ?? 0).toString())
    .set('pageSize', (pageSize ?? DEFAULT_PAGE_SIZE).toString());
}
