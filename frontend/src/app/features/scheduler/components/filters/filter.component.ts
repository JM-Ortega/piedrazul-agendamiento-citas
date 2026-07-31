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
      label: `${d.name} — ${this.formatoPipe.transform(d.specialty)}`,
    }))
  );

  /** Convierte el string 'yyyy-mm-dd' del filtro a Date para el datepicker. */
  filterDateValue = computed<Date | null>(() => {
    const raw = this.filterDate();
    return raw ? this.parseLocalDateString(raw) : null;
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
    this.filterDate.set(date ? this.toIsoString(date) : '');
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

  private parseLocalDateString(value: string): Date {
    const m = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    return new Date(value);
  }

  private toIsoString(date: Date): string {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }
}
