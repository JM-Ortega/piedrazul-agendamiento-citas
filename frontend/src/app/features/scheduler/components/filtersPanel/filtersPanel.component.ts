import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { LucideChevronDown, LucideFilter } from '@lucide/angular';
import { FilterFieldConfig } from '../../../../design-system/molecules/filter-field/filterField.model';
import {
  FiltersComponent,
  FilterValues,
} from '../../../../design-system/organisms/filters/filters.component';
import { scrollToElementById } from '../../../../shared/helpers/scroll-to-element';

/**
 * Panel colapsable de filtros para el historial de citas del agendador.
 */
@Component({
  selector: 'app-filters-panel',
  templateUrl: './filtersPanel.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideFilter, LucideChevronDown, FiltersComponent],
})
export class FiltersPanelComponent {
  fields = input.required<FilterFieldConfig[]>();
  appliedValues = input.required<FilterValues>();
  apply = output<FilterValues>();

  /** Controla si el panel de filtros está desplegado. */
  filtersOpen = signal(false);

  /** Cantidad de filtros con un valor asignado, sin importar qué campos sean. */
  activeFilterCount = computed(
    () => Object.values(this.appliedValues()).filter(Boolean).length
  );
  hasActiveFilters = computed(() => this.activeFilterCount() > 0);

  /** Alterna la visibilidad del panel y, al abrir, hace scroll hasta él. */
  toggleFilters(): void {
    const willOpen = !this.filtersOpen();
    this.filtersOpen.set(willOpen);
    if (willOpen) {
      scrollToElementById('filters-panel', { offset: 12 });
    }
  }

  /** Reenvía lo aplicado desde `app-filters` y cierra el panel. */
  onFiltersApply(values: FilterValues): void {
    this.apply.emit(values);
    this.filtersOpen.set(false);
  }
}
