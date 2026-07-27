import { Component, EventEmitter, Input, Output } from '@angular/core';
import {
  LucideCalendar,
  LucideClock,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import {
  APPOINTMENT_STATUS_CLASSES,
  APPOINTMENT_STATUS_LABELS,
} from '../../../../shared/helpers/appointment-status';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

export type AppointmentTableView = 'today' | 'all';

@Component({
  selector: 'app-appointment-table',
  standalone: true,
  imports: [
    LucideCalendar,
    LucideClock,
    LucideUser,
    LucideX,
    FormatoPipe,
    ButtonComponent,
  ],
  templateUrl: './table.component.html',
})
export class AppointmentTableComponent {
  @Input() view: AppointmentTableView = 'all';
  @Input() results: AppointmentsPatient[] = [];

  @Output() cancelRequested = new EventEmitter<string>();

  formatDate(dateStr: string): string {
    return formatLongDateEs(dateStr);
  }

  statusLabel(s: string): string {
    return (
      APPOINTMENT_STATUS_LABELS[s as AppointmentsPatient['appointmentState']] ??
      s
    );
  }

  statusColor(s: string): string {
    return (
      APPOINTMENT_STATUS_CLASSES[
        s as AppointmentsPatient['appointmentState']
      ] ?? ''
    );
  }

  onCancel(id: string): void {
    this.cancelRequested.emit(id);
  }
}
