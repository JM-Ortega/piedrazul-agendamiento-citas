import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { LucideChevronDown, LucideFilter } from '@lucide/angular';
import { FilterFieldConfig } from '../../../../designSystem/molecules/filterField/filterField.model';
import {
  FiltersComponent,
  FilterValues,
} from '../../../../designSystem/organisms/filters/filters.component';
import { scrollToElementById } from '../../../../shared/helpers/scrollToElement';

/**
 * Panel colapsable de filtros. Encapsula su propio estado de apertura,
 * el scroll hacia el panel al abrirlo, y el conteo de filtros activos
 * (derivado genéricamente de appliedValues, sin importar qué campos traiga).
 * El padre solo provee los campos disponibles y los valores aplicados, y
 * escucha `apply` cuando el usuario confirma cambios.
 */
@Component({
  selector: 'app-filters-panel',
  templateUrl: './filtersPanel.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideFilter, LucideChevronDown, FiltersComponent],
})
export class FiltersPanelComponent {
  // ── Inputs / Outputs ──────────────────────────────────────────────────────
  fields = input.required<FilterFieldConfig[]>();
  appliedValues = input.required<FilterValues>();
  apply = output<FilterValues>();

  // ── Estado ────────────────────────────────────────────────────────────────
  /** Controla si el panel de filtros está desplegado. */
  filtersOpen = signal(false);

  /** Cantidad de filtros con un valor asignado, sin importar qué campos sean. */
  activeFilterCount = computed(
    () => Object.values(this.appliedValues()).filter(Boolean).length
  );
  hasActiveFilters = computed(() => this.activeFilterCount() > 0);

  // ── Toggle ────────────────────────────────────────────────────────────────
  /** Alterna la visibilidad del panel y, al abrir, hace scroll hasta él. */
  toggleFilters(): void {
    const willOpen = !this.filtersOpen();
    this.filtersOpen.set(willOpen);
    if (willOpen) {
      scrollToElementById('filters-panel', { offset: 12 });
    }
  }
}
