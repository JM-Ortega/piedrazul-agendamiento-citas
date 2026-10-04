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
} from '@lucide/angular';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { FilterFieldConfig } from '../../../designSystem/molecules/filterField/filterField.model';
import { PaginationComponent } from '../../../designSystem/molecules/pagination/pagination.component';
import { FilterValues } from '../../../designSystem/organisms/filters/filters.component';
import { PatientQuickSearchComponent } from '../../../designSystem/organisms/patientQuickSearch/patientQuickSearch.component';
import {
  APPOINTMENT_STATUS_CLASSES,
  APPOINTMENT_STATUS_LABELS,
} from '../../../shared/helpers/appointmentStatus';
import {
  formatLongDateEs,
  getMonthShort,
} from '../../../shared/helpers/dateFormat';
import { PaginatedState } from '../../../shared/helpers/paginatedState';
import { toIsoDateString } from '../../../shared/helpers/transformDateLocal';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { PatientQuickResult } from '../../../shared/models/dtos/patientQuickResult.dto';
import { AppError } from '../../../shared/models/interfaces/apiError.model';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { ExportModalComponent } from '../components/exportModal/exportModal.component';
import { FiltersPanelComponent } from '../components/filtersPanel/filtersPanel.component';

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

/**
 * Página de historial de citas del médico. Carga las citas paginadas del
 * doctor autenticado, permite filtrarlas por fecha/estado y por paciente
 * (vía app-filters-panel + app-patient-quick-search, ambos filtros
 * combinables en la misma consulta al backend), y exportar un reporte de
 * las citas de hoy.
 */
@Component({
  selector: 'app-doctor-all-appointments',
  templateUrl: './doctorAllAppointments.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideClock,
    LucideCreditCard,
    LucideDownload,
    LucideFileSpreadsheet,
    ExportModalComponent,
    PaginationComponent,
    FiltersPanelComponent,
    PatientQuickSearchComponent,
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
  private todayAppointmentsState = signal<AppointmentsPatient[]>([]);

  /** Página actualmente solicitada (base 0). Se resetea a 0 al cambiar filtros o paciente. */
  private readonly pageNumber = signal(0);

  filterDate = signal('');
  filterStatus = signal('');
  selectedPatient = signal<PatientQuickResult | null>(null);

  errorCarga = signal('');
  showExportModal = signal(false);
  getMonthShort = getMonthShort;
  formatDate = formatLongDateEs;

  /** Columnas disponibles para el reporte exportable de citas de hoy. */
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
  /** True si hay al menos una cita de hoy que no esté cancelada (independiente de la página mostrada). */
  hasTodayAppointments = computed(() =>
    this.todayAppointmentsState().some(
      (a) => a.appointmentState !== 'CANCELADA'
    )
  );
  /** True si el doctor tiene al menos una cita en total, sin importar la página mostrada. */
  hasAnyAppointments = computed(
    () =>
      (this.pagination()?.totalElements ??
        this.appointmentsState.content().length) > 0
  );

  /** Citas del día de hoy, para el modal de exportación (independiente de la página mostrada). */
  todayAppointmentsList = computed(() => this.todayAppointmentsState());

  /** Citas de la página actual, ya filtradas por el backend. */
  filteredAppointments = computed(() => this.appointmentsState.content());

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

  /** Valores actuales de filtro, en el shape que espera app-filters-panel. */
  appliedFilterValues = computed<FilterValues>(() => ({
    date: this.filterDate(),
    status: this.filterStatus(),
  }));

  /** Configuración de campos del panel de filtros (fecha específica + estado). */
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
  /** Carga los datos una sola vez, tras la primera señal de Keycloak (usuario autenticado). */
  constructor() {
    effect(() => {
      this.keycloakEvent();
      if (this.loaded()) return;
      this.loaded.set(true);
      this.loadInitialData();
    });
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  /** Obtiene el doctor autenticado y luego sus citas paginadas + las de hoy. */
  private loadInitialData(): void {
    this.errorCarga.set('');
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        if (!doctor) {
          this.router.navigate(['/']);
          return;
        }
        this.currentDoctor.set(doctor);
        this.loadAppointments(null, '', '', 0);
        this.loadTodayAppointments(doctor.id);
      },
      error: (err: AppError) => {
        this.errorCarga.set(err.message);
        this.router.navigate(['/']);
      },
    });
  }

  /**
   * Carga las citas del doctor autenticado, combinando (si aplica) el
   * paciente seleccionado con los filtros de fecha/estado vigentes.
   * Es el único punto de entrada a `getAppointmentsByDoctor` para que
   * paciente y filtros nunca se pisen entre sí — mismo patrón que el
   * agendador.
   */
  private loadAppointments(
    patientId: string | null,
    date: string,
    status: string,
    pageNumber: number
  ): void {
    const doctor = this.currentDoctor();
    if (!doctor) return;

    this.doctorService
      .getAppointmentsByDoctor(doctor.id, pageNumber, this.PAGE_SIZE, {
        patientId: patientId || undefined,
        date: date || undefined,
        state: status || undefined,
      })
      .subscribe({
        next: (response) => this.appointmentsState.set(response),
        error: (err: AppError) => this.errorCarga.set(err.message),
      });
  }

  /**
   * Carga las citas de hoy del doctor en una petición aparte, no ligada a
   * la paginación de la tabla ni a los filtros vigentes (siempre son
   * TODAS las citas de hoy, para el reporte de exportación).
   */
  private loadTodayAppointments(doctorId: string): void {
    const TODAY_FETCH_SIZE = 200;
    this.doctorService
      .getAppointmentsByDoctor(doctorId, 0, TODAY_FETCH_SIZE)
      .subscribe({
        next: (response) => {
          this.todayAppointmentsState.set(
            response.content.filter((a) => a.date === this.today)
          );
        },
        error: () => this.todayAppointmentsState.set([]),
      });
  }

  // ── Filtros y paciente ────────────────────────────────────────────────────
  /** Recibe los valores confirmados desde app-filters-panel y recarga, conservando el paciente si hay uno seleccionado. */
  onApplyFilters(filters: FilterValues): void {
    this.filterDate.set(filters['date'] ?? '');
    this.filterStatus.set(filters['status'] ?? '');
    this.pageNumber.set(0);
    this.loadAppointments(
      this.selectedPatient()?.id ?? null,
      filters['date'] ?? '',
      filters['status'] ?? '',
      0
    );
  }

  onPatientSelected(patient: PatientQuickResult): void {
    this.selectedPatient.set(patient);
    this.pageNumber.set(0);
    this.loadAppointments(
      patient.id,
      this.filterDate(),
      this.filterStatus(),
      0
    );
  }

  onClearPatientFilter(): void {
    this.selectedPatient.set(null);
    this.pageNumber.set(0);
    this.loadAppointments(null, this.filterDate(), this.filterStatus(), 0);
  }

  onPageChange(pageNumber: number): void {
    this.pageNumber.set(pageNumber);
    this.loadAppointments(
      this.selectedPatient()?.id ?? null,
      this.filterDate(),
      this.filterStatus(),
      pageNumber
    );
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
