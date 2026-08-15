import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { LucideChevronLeft, LucideChevronRight } from '@lucide/angular';
import { PaginationMeta } from '../../../shared/helpers/paginated-state';

@Component({
  selector: 'app-pagination',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideChevronLeft, LucideChevronRight],
  templateUrl: './pagination.component.html',
})
export class PaginationComponent {
  /** Metadata de paginación actual. Si es `null` o solo hay 1 página, no se renderiza nada. */
  pagination = input<PaginationMeta | null>(null);

  /** Emite el número de página (base 0) al que el usuario quiere navegar. */
  pageChange = output<number>();

  /**
   * Señal computada (*computed signal*) que calcula los números de página
   * y los elipsis (`'ellipsis'`) a mostrar en el control de paginación.
   *
   * @remarks
   * - Retorna una lista vacía si no hay información de paginación o si solo existe 1 página.
   * - Mantiene fijas la primera (0) y la última página (`totalPages - 1`).
   * - Inserta la cadena `'ellipsis'` cuando existe un salto de más de una página entre los bloques visibles.
   *
   * @returns Array con los índices de página navegables y cadenas `'ellipsis'` para los saltos.
   */
  readonly pageNumbers = computed<(number | 'ellipsis')[]>(() => {
    const p = this.pagination();
    if (!p || p.totalPages <= 1) return [];

    const total = p.totalPages;
    const current = p.pageNumber;
    const pages: (number | 'ellipsis')[] = [];

    pages.push(0);
    if (current > 2) pages.push('ellipsis');

    const start = Math.max(1, current - 1);
    const end = Math.min(total - 2, current + 1);
    for (let i = start; i <= end; i++) pages.push(i);

    if (current < total - 3) pages.push('ellipsis');
    if (total > 1) pages.push(total - 1);

    return pages;
  });

  /**
   * Navega hacia una página específica seleccionada por el usuario.
   *
   * @param page - Índice base 0 de la página destino o la marca `'ellipsis'`.
   *               Si el valor es `'ellipsis'` o la página solicitada es la actual, la acción se ignora.
   */
  goToPage(page: number | 'ellipsis'): void {
    if (page === 'ellipsis') return;
    const p = this.pagination();
    if (!p || page === p.pageNumber) return;
    this.pageChange.emit(page);
  }

  /**
   * Navega a la página anterior dentro de la lista.
   * No emite ningún cambio si el usuario se encuentra en la primera página.
   */
  goToPrevious(): void {
    const p = this.pagination();
    if (!p || p.first) return;
    this.pageChange.emit(p.pageNumber - 1);
  }

  /**
   * Navega a la página siguiente dentro de la lista.
   * No emite ningún cambio si el usuario se encuentra en la última página.
   */
  goToNext(): void {
    const p = this.pagination();
    if (!p || p.last) return;
    this.pageChange.emit(p.pageNumber + 1);
  }
}
