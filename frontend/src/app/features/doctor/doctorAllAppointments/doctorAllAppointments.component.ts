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
  LucideChevronDown,
  LucideClock,
  LucideCreditCard,
  LucideDownload,
  LucideFileSpreadsheet,
  LucideFilter,
} from '@lucide/angular';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { FilterFieldConfig } from '../../../design-system/molecules/filter-field/filterField.model';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';
import {
  FiltersComponent,
  FilterValues,
} from '../../../design-system/organisms/filters/filters.component';
import {
  APPOINTMENT_STATUS_CLASSES,
  APPOINTMENT_STATUS_LABELS,
} from '../../../shared/helpers/appointment-status';
import {
  formatLongDateEs,
  getMonthShort,
} from '../../../shared/helpers/date-format';
import { PaginatedState } from '../../../shared/helpers/paginated-state';
import { scrollToElementById } from '../../../shared/helpers/scroll-to-element';
import { toIsoDateString } from '../../../shared/helpers/transform-date-local';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { AppError } from '../../../shared/models/interfaces/api-error.model';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { ExportModalComponent } from '../components/exportModal/exportModal.component';

type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status'
  | 'specialty'
  | 'doctorName';

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
}

@Component({
  selector: 'app-doctor-all-appointments',
  templateUrl: './doctorAllAppointments.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideChevronDown,
    LucideClock,
    LucideCreditCard,
    LucideDownload,
    LucideFilter,
    LucideFileSpreadsheet,
    ExportModalComponent,
    PaginationComponent,
    FiltersComponent,
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
  readonly PAGE_SIZE = 4;
  private loaded = signal(false);

  filterDate = signal('');
  filterStatus = signal('');
  errorCarga = signal('');
  showExportModal = signal(false);
  /** Controla si el panel de filtros está desplegado. */
  filtersOpen = signal(false);
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

  readonly filterDateConfig: FilterFieldConfig = {
    id: 'filterDate',
    type: 'select',
    label: 'Por Fecha',
    options: [
      { value: 'all', label: 'Todas las fechas' },
      { value: 'specific', label: 'Fecha específica' },
      { value: 'upcoming', label: 'Próximas' },
      { value: 'past', label: 'Pasadas' },
    ],
  };

  readonly filterSpecificDateConfig: FilterFieldConfig = {
    id: 'filterSpecificDate',
    type: 'date',
    label: 'Fecha específica',
  };

  readonly filterStatusConfig: FilterFieldConfig = {
    id: 'filterStatus',
    type: 'select',
    label: 'Por Estado',
    options: [
      { value: 'all', label: 'Todos los estados' },
      { value: 'AGENDADA', label: 'Agendadas' },
      { value: 'REPROGRAMADA', label: 'Reprogramadas' },
      { value: 'CANCELADA', label: 'Canceladas' },
      { value: 'NO_ASISTIO', label: 'No asistió' },
      { value: 'ATENDIDA', label: 'Atendidas' },
    ],
  };
  // ── Computed ──────────────────────────────────────────────────────────────
  hasTodayAppointments = computed(() =>
    this.appointmentsState
      .content()
      .some((a) => a.date === this.today && a.appointmentState !== 'CANCELADA')
  );
  hasAnyAppointments = computed(
    () => this.appointmentsState.content().length > 0
  );

  todayAppointmentsList = computed(() =>
    this.appointmentsState.content().filter((a) => a.date === this.today)
  );
  filteredAppointments = computed(() => {
    let result = this.appointmentsState.content();

    if (this.filterStatus())
      result = result.filter((a) => a.appointmentState === this.filterStatus());

    if (this.filterDate())
      result = result.filter((a) => a.date === this.filterDate());

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
  appliedFilterValues = computed<FilterValues>(() => ({
    date: this.filterDate(),
    status: this.filterStatus(),
  }));

  /** Cantidad de filtros con un valor asignado actualmente. */
  activeFilterCount = computed(
    () => [this.filterDate(), this.filterStatus()].filter(Boolean).length
  );
  hasActiveFilters = computed(() => this.activeFilterCount() > 0);

  filterFields = computed<FilterFieldConfig[]>(() => {
    const statusOptions = [
      { value: 'AGENDADA', label: 'Agendadas' },
      { value: 'REPROGRAMADA', label: 'Reprogramadas' },
      { value: 'CANCELADA', label: 'Canceladas' },
      { value: 'NO_ASISTIO', label: 'No asistió' },
      { value: 'ATENDIDA', label: 'Atendidas' },
    ];

    return [
      {
        id: 'date',
        type: 'date',
        label: 'Fecha Específica',
        formatValue: (d) => formatLongDateEs(d),
      },
      {
        id: 'status',
        type: 'select',
        label: 'Por Estado',
        placeholder: 'Todos los estados',
        options: statusOptions,
        formatValue: (v) =>
          statusOptions.find((o) => o.value === v)?.label ?? v,
      },
    ];
  });
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
    this.errorCarga.set('');
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        if (!doctor) {
          this.router.navigate(['/']);
          return;
        }
        this.currentDoctor.set(doctor);
        this.doctorService
          .getAppointmentsByDoctor(doctor.id, pageNumber, this.PAGE_SIZE)
          .subscribe({
            next: (response) => this.appointmentsState.set(response),
            error: (err: AppError) => this.errorCarga.set(err.message),
          });
      },
      error: (err: AppError) => {
        this.errorCarga.set(err.message);
        this.router.navigate(['/']);
      },
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
  onApplyFilters(filters: FilterValues): void {
    this.filterDate.set(filters['date'] ?? '');
    this.filterStatus.set(filters['status'] ?? '');
  }

  /** Alterna la visibilidad del panel de filtros. */
  toggleFilters(): void {
    const willOpen = !this.filtersOpen();
    this.filtersOpen.set(willOpen);
    if (willOpen) {
      scrollToElementById('filters-panel', {
        offset: 12,
      });
    }
  }
}
