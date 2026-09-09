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

  //Id del contenedor, usado para el scroll al abrir.
  panelId = input('filters-panel');
  closedHint = input('Toca aquí para aplicar filtros');

  filtersOpen = signal(false);

  activeFilterCount = computed(
    () => Object.values(this.appliedValues()).filter(Boolean).length
  );
  hasActiveFilters = computed(() => this.activeFilterCount() > 0);

  toggleFilters(): void {
    const willOpen = !this.filtersOpen();
    this.filtersOpen.set(willOpen);
    if (willOpen) {
      scrollToElementById(this.panelId(), { offset: 12 });
    }
  }

  onFiltersApply(values: FilterValues): void {
    this.apply.emit(values);
    this.filtersOpen.set(false);
  }
}
