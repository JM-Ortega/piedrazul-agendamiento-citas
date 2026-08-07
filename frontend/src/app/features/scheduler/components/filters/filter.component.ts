import { Component, computed, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideSearch } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../../design-system/molecules/datepicker/datepicker.component';
import { APPOINTMENT_STATUS_LABELS } from '../../../../shared/helpers/appointment-status';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../../shared/helpers/transform-date-local';

export type SchedulerFilterField = 'doctor' | 'date' | 'status';

const STATUS_OPTIONS: SelectOption[] = [
  { value: 'AGENDADA', label: 'Agendada' },
  { value: 'ATENDIDA', label: 'Atendida' },
  { value: 'CANCELADA', label: 'Cancelada' },
  { value: 'NO_ASISTIO', label: 'No asistió' },
  { value: 'REPROGRAMADA', label: 'Reprogramada' },
];

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
  title = input('Filtros');
  description = input('');

  private formatoPipe = new FormatoPipe();

  filterDoctor = model('');
  filterDate = model('');
  filterStatus = model('');

  readonly statusOptions = STATUS_OPTIONS;

  doctorOptions = computed<SelectOption[]>(() =>
    this.doctors().map((d) => ({
      value: d.id,
      label: `${d.name} — ${d.specialties.map((s) => this.formatoPipe.transform(s)).join(', ')}`,
    }))
  );

  /** Convierte el string 'yyyy-mm-dd' del filtro a Date para el datepicker. */
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
    return (
      APPOINTMENT_STATUS_LABELS[s as AppointmentsPatient['appointmentState']] ??
      s
    );
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
