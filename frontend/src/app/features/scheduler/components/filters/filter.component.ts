import { Component, computed, input, model } from '@angular/core';
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

  private formatoPipe = new FormatoPipe();

  filterDoctor = model('');
  filterDate = model('');
  filterStatus = model('');

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
}
