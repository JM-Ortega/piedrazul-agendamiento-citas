import { Component, computed, input, output, signal } from '@angular/core';
import { LucideSearch } from '@lucide/angular';
import { ButtonComponent } from '../../atoms/button/button.component';
import { FilterFieldComponent } from '../../molecules/filter-field/filterField.component';
import { FilterFieldConfig } from '../../molecules/filter-field/filterField.model';

export type FilterValues = Record<string, string>;

/**
 * Organismo de filtros genérico. Recibe una lista de {@link FilterFieldConfig} y
 * renderiza un campo por cada una, maneja un borrador local independiente de lo
 * ya aplicado, y solo propaga los cambios al padre cuando se presiona
 * "Aplicar Filtros".
 */
@Component({
  selector: 'app-filters',
  standalone: true,
  imports: [LucideSearch, ButtonComponent, FilterFieldComponent],
  templateUrl: './filters.component.html',
})
export class FiltersComponent {
  /** Definición de los campos a renderizar, en el orden en que se muestran. */
  fields = input<FilterFieldConfig[]>([]);
  title = input('Filtros');
  description = input('');

  /** Valores actualmente aplicados por el padre (última consulta ejecutada). */
  appliedValues = input<FilterValues>({});

  /** Se emite únicamente al hacer clic en "Aplicar Filtros", con el borrador vigente. */
  apply = output<FilterValues>();

  /**
   * Borrador local: lo que el usuario está seleccionando en pantalla,
   * inicializado una sola vez desde `appliedValues()`.
   */
  draftValues = signal<FilterValues>(this.appliedValues());

  /** True si el borrador difiere de lo último aplicado en algún campo. */
  isDirty = computed(() => {
    const draft = this.draftValues();
    const applied = this.appliedValues();
    return this.fields().some(
      (f) => (draft[f.id] ?? '') !== (applied[f.id] ?? '')
    );
  });

  /** Campos del borrador que tienen un valor no vacío, para renderizar los chips. */
  activeFilters = computed(() =>
    this.fields()
      .map((f) => ({ field: f, value: this.draftValues()[f.id] ?? '' }))
      .filter((entry) => entry.value)
  );

  get gridColsClass(): string {
    return this.fields().length >= 3 ? 'md:grid-cols-3' : 'md:grid-cols-2';
  }

  getValue(fieldId: string): string {
    return this.draftValues()[fieldId] ?? '';
  }

  setValue(fieldId: string, value: string): void {
    this.draftValues.update((current) => ({ ...current, [fieldId]: value }));
  }

  clearValue(fieldId: string): void {
    this.setValue(fieldId, '');
  }

  /** Formatea el valor crudo de un campo para mostrarlo en su chip, usando `field.formatValue` si existe. */
  displayValue(field: FilterFieldConfig, value: string): string {
    return field.formatValue ? field.formatValue(value) : value;
  }

  /** Emite `apply` con el borrador vigente, solo si hay cambios pendientes. */
  onApply(): void {
    if (!this.isDirty()) return;
    this.apply.emit(this.draftValues());
  }
}
