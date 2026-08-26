import { Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SelectComponent } from '../../atoms/select/select.component';
import { DatepickerComponent } from '../datepicker/datepicker.component';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../shared/helpers/transform-date-local';
import { FilterFieldConfig } from './filterField.model';

/**
 * Molécula que renderiza un único campo de filtro (select o fecha) a partir
 * de una {@link FilterFieldConfig}.
 */
@Component({
  selector: 'app-filter-field',
  standalone: true,
  imports: [FormsModule, SelectComponent, DatepickerComponent],
  templateUrl: './filterField.component.html',
})
export class FilterFieldComponent {
  /** Definición del campo a renderizar (tipo, opciones, etiqueta, etc.). */
  config = input.required<FilterFieldConfig>();
  /** Valor actual del campo, como string. Vacío significa "sin selección". */
  value = input('');
  /** Se emite cada vez que el usuario cambia el valor del campo. */
  valueChange = output<string>();

  /**
   * Convierte `value()` a `Date` para el datepicker.
   */
  dateValue = computed<Date | null>(() => {
    const raw = this.value();
    return raw ? parseLocalDateString(raw) : null;
  });

  /** Traduce la selección del datepicker de vuelta a string ISO. */
  onDateChange(date: Date | null): void {
    this.valueChange.emit(date ? toIsoDateString(date) : '');
  }
}
