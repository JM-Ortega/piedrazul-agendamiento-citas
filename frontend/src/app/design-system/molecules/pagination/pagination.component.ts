import {
  ChangeDetectionStrategy,
  Component,
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
