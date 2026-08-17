import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideCalendar,
  LucideClock,
  LucideCreditCard,
  LucideDownload,
  LucideFileSpreadsheet,
  LucideFilter,
} from '@lucide/angular';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';
import { ExportModalComponent } from '../../../design-system/organisms/export-modal/export-modal.component';
import {
  APPOINTMENT_STATUS_CLASSES,
  APPOINTMENT_STATUS_LABELS,
} from '../../../shared/helpers/appointment-status';
import {
  formatLongDateEs,
  getMonthShort,
} from '../../../shared/helpers/date-format';
import { PaginatedState } from '../../../shared/helpers/paginated-state';
import { toIsoDateString } from '../../../shared/helpers/transform-date-local';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';

type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status'
  | 'specialty'
  | 'doctorName';
type FilterDate = 'all' | 'specific' | 'upcoming' | 'past';
type FilterStatus =
  'all' | 'AGENDADA' | 'REPROGRAMADA' | 'CANCELADA' | 'NO_ASISTIO' | 'ATENDIDA';

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
}

@Component({
  selector: 'app-doctor-all-appointments',
  templateUrl: './doctor-all-appointments.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideClock,
    LucideCreditCard,
    LucideDownload,
    LucideFileSpreadsheet,
    LucideFilter,
    ExportModalComponent,
    PaginationComponent,
  ],
})
export class DoctorAllAppointmentsComponent {
  private router = inject(Router);
  private doctorService = inject(DoctorService);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  // ── State ─────────────────────────────────────────────────────────────────
  today = toIsoDateString(new Date());
  currentDoctor = signal<Doctor | null>(null);
  private appointmentsState = new PaginatedState<AppointmentsPatient>();
  pagination = this.appointmentsState.pagination;
  readonly PAGE_SIZE = 10;
  private loaded = signal(false);

  filterStatus = signal<FilterStatus>('all');
  filterDate = signal<FilterDate>('all');
  filterSpecificDate = signal<string>('');
  showExportModal = signal(false);
  getMonthShort = getMonthShort;
  formatDate = formatLongDateEs;

  readonly columnDefs: ColumnDef[] = [
    { key: 'date', label: 'Fecha de la Cita' },
    { key: 'time', label: 'Hora de la Cita' },
    { key: 'patient', label: 'Nombre del Paciente' },
    { key: 'documentId', label: 'Documento de Identidad' },
    { key: 'phone', label: 'Teléfono del Paciente' },
    { key: 'status', label: 'Estado de la Cita' },
    { key: 'specialty', label: 'Especialidad' },
    { key: 'doctorName', label: 'Nombre del Médico' },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  hasTodayAppointments = computed(() =>
    this.appointmentsState
      .content()
      .some((a) => a.date === this.today && a.appointmentState !== 'CANCELADA')
  );
  hasAnyAppointments = computed(
    () => this.appointmentsState.content().length > 0
  );
  filteredAppointments = computed(() => {
    let result = this.appointmentsState.content();

    if (this.filterStatus() !== 'all')
      result = result.filter((a) => a.appointmentState === this.filterStatus());

    if (this.filterDate() === 'specific' && this.filterSpecificDate())
      result = result.filter((a) => a.date === this.filterSpecificDate());
    else if (this.filterDate() === 'upcoming')
      result = result.filter(
        (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA'
      );
    else if (this.filterDate() === 'past')
      result = result.filter((a) => a.date < this.today);

    return [...result].sort((a, b) => {
      const d = b.date.localeCompare(a.date);
      return d !== 0 ? d : b.startTime.localeCompare(a.startTime);
    });
  });

  stats = computed(() => ({
    total: this.appointmentsState.content().length,
    upcoming: this.appointmentsState
      .content()
      .filter((a) => a.date >= this.today && a.appointmentState !== 'CANCELADA')
      .length,
    pending: this.appointmentsState
      .content()
      .filter((a) => a.appointmentState === 'ATENDIDA').length,
  }));

  // ── Constructor ───────────────────────────────────────────────────────────
  constructor() {
    effect(() => {
      this.keycloakEvent();
      if (this.loaded()) return;
      this.loaded.set(true);
      this.loadData();
    });
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  private loadData(pageNumber = 0): void {
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        if (!doctor) {
          this.router.navigate(['/']);
          return;
        }
        this.currentDoctor.set(doctor);
        this.doctorService
          .getAppointmentsByDoctor(doctor.id, pageNumber, this.PAGE_SIZE)
          .subscribe((response) => this.appointmentsState.set(response));
      },
      error: () => this.router.navigate(['/']),
    });
  }

  onPageChange(pageNumber: number): void {
    this.loadData(pageNumber);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  isPast(dateStr: string): boolean {
    return dateStr < this.today;
  }

  statusLabel(s: AppointmentsPatient['appointmentState']): string {
    return APPOINTMENT_STATUS_LABELS[s] ?? s;
  }

  statusColor(s: AppointmentsPatient['appointmentState']): string {
    return (
      (APPOINTMENT_STATUS_CLASSES[s] ?? 'bg-gray-100 text-gray-700') +
      ' border-current/20'
    );
  }
}
