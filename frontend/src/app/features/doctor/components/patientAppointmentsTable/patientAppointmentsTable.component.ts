import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCalendarDays,
  LucideCalendarX,
  LucideClock,
} from '@lucide/angular';
import { PaginationComponent } from '../../../../designSystem/molecules/pagination/pagination.component';
import {
  APPOINTMENT_STATUS_CLASSES,
  APPOINTMENT_STATUS_LABELS,
} from '../../../../shared/helpers/appointmentStatus';
import { formatShortDateEs } from '../../../../shared/helpers/dateFormat';
import { PaginationMeta } from '../../../../shared/helpers/paginatedState';
import { parseLocalDateString } from '../../../../shared/helpers/transformDateLocal';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

/**
 * Tabla paginada con el histórico de citas de un paciente con el médico
 * autenticado. Es presentacional: recibe las citas de la página actual, la
 * metadata de paginación y el estado de carga, y emite `pageChange`.
 */
@Component({
  selector: 'app-patient-appointments-table',
  templateUrl: './patientAppointmentsTable.component.html',
  styleUrl: './patientAppointmentsTable.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    LucideCalendar,
    LucideCalendarDays,
    LucideCalendarX,
    LucideClock,
    PaginationComponent,
    FormatoPipe,
  ],
})
export class PatientAppointmentsTableComponent {
  // ── Inputs / Outputs ──────────────────────────────────────────────────────
  appointments = input.required<AppointmentsPatient[]>();
  pagination = input<PaginationMeta | null>(null);
  loading = input(false);
  /** Nueva página solicitada (base 0). */
  pageChange = output<number>();

  /** Filas del esqueleto de carga (mismo alto aproximado que una página). */
  readonly skeletonRows = [1, 2, 3, 4, 5];

  // ── Helpers de presentación ──────────────────────────────────────────────
  /** Fecha `yyyy-MM-dd` en formato corto: "02 nov 2026". */
  formatDate(iso: string): string {
    return formatShortDateEs(parseLocalDateString(iso));
  }

  statusLabel(state: AppointmentsPatient['appointmentState']): string {
    return APPOINTMENT_STATUS_LABELS[state] ?? state;
  }

  statusClass(state: AppointmentsPatient['appointmentState']): string {
    return APPOINTMENT_STATUS_CLASSES[state] ?? 'bg-gray-100 text-gray-700';
  }
}
