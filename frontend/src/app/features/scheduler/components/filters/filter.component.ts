import { Component, Input, model } from '@angular/core';
import { LucideSearch } from '@lucide/angular';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { APPOINTMENT_STATUS_LABELS } from '../../../../shared/helpers/appointment-status';
import { ButtonComponent } from '../../../../design-system/atoms/button.component';

export type SchedulerFilterField = 'doctor' | 'date' | 'status';

@Component({
  selector: 'app-filter',
  standalone: true,
  imports: [LucideSearch, FormatoPipe, ButtonComponent],
  templateUrl: './filter.component.html',
})
export class SchedulerFiltersComponent {
  @Input() fields: SchedulerFilterField[] = ['doctor', 'status'];
  @Input() doctors: dtoDoctor[] = [];
  @Input() title = 'Filtros';
  @Input() description = '';

  filterDoctor = model('');
  filterDate = model('');
  filterStatus = model('');

  hasField(field: SchedulerFilterField): boolean {
    return this.fields.includes(field);
  }

  get gridColsClass(): string {
    return this.fields.length === 3 ? 'md:grid-cols-3' : 'md:grid-cols-2';
  }

  get selectedDoctor(): dtoDoctor | undefined {
    return this.doctors.find((d) => d.name === this.filterDoctor());
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
