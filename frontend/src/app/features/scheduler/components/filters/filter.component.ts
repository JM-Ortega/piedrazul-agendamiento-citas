import { Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideSearch } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../../design-system/molecules/datepicker/datepicker.component';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../../shared/helpers/transform-date-local';

export type SchedulerFilterField = 'doctor' | 'date' | 'status';

export interface SchedulerFilterValues {
  doctor: string;
  date: string;
  status: string;
}

@Component({
  selector: 'app-filter',
  standalone: true,
  imports: [
    FormsModule,
    LucideSearch,
    ButtonComponent,
    SelectComponent,
    DatepickerComponent,
  ],
  templateUrl: './filter.component.html',
})
export class SchedulerFiltersComponent {
  fields = input<SchedulerFilterField[]>(['doctor', 'status']);
  doctors = input<dtoDoctor[]>([]);
  states = input<string[]>([]);
  title = input('Filtros');
  description = input('');

  /**
   * Valores actualmente aplicados. Se usan para inicializar el
   * borrador y para saber si hay cambios pendientes.
   */
  appliedDoctor = input('');
  appliedDate = input('');
  appliedStatus = input('');

  /** Se emite únicamente cuando el usuario hace clic en "Aplicar Filtros". */
  apply = output<SchedulerFilterValues>();

  private formatoPipe = new FormatoPipe();

  /**
   * Borrador local: lo que el usuario está seleccionando en pantalla,
   * inicializado desde los valores aplicados. No se propaga al padre
   * hasta que se presiona "Aplicar".
   */
  filterDoctor = signal(this.appliedDoctor());
  filterDate = signal(this.appliedDate());
  filterStatus = signal(this.appliedStatus());

  /** True si el borrador difiere de lo último aplicado. */
  isDirty = computed(
    () =>
      this.filterDoctor() !== this.appliedDoctor() ||
      this.filterDate() !== this.appliedDate() ||
      this.filterStatus() !== this.appliedStatus()
  );

  doctorOptions = computed<SelectOption[]>(() =>
    this.doctors().map((d) => ({
      value: d.id,
      label: `${d.name} — ${d.specialties.map((s) => this.formatoPipe.transform(s)).join(', ')}`,
    }))
  );

  /** Convierte el string 'yyyy-mm-dd' del filtro a Date para el datepicker. */
  statusOptions = computed<SelectOption[]>(() =>
    this.states().map((s) => ({
      value: s,
      label: this.formatoPipe.transform(s),
    }))
  );

  filterDateValue = computed<Date | null>(() => {
    const raw = this.filterDate();
    return raw ? parseLocalDateString(raw) : null;
  });

  hasField(field: SchedulerFilterField): boolean {
    return this.fields().includes(field);
  }

  get gridColsClass(): string {
    return this.fields().length === 3 ? 'md:grid-cols-3' : 'md:grid-cols-2';
  }

  get selectedDoctor(): dtoDoctor | undefined {
    return this.doctors().find((d) => d.id === this.filterDoctor());
  }

  formatDate(dateStr: string): string {
    return formatLongDateEs(dateStr);
  }

  statusLabel(s: string): string {
    return this.formatoPipe.transform(s);
  }

  onDateChange(date: Date | null): void {
    this.filterDate.set(date ? toIsoDateString(date) : '');
  }

  clearDoctor(): void {
    this.filterDoctor.set('');
  }

  clearDate(): void {
    this.filterDate.set('');
  }

  clearStatus(): void {
    this.filterStatus.set('');
  }

  onApply(): void {
    if (!this.isDirty()) return;
    this.apply.emit({
      doctor: this.filterDoctor(),
      date: this.filterDate(),
      status: this.filterStatus(),
    });
  }
}
