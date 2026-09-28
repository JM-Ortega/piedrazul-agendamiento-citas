import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucideCalendar, LucideDownload, LucideX } from '@lucide/angular';
import { PatientAppointmentService } from '../../../../core/services/patient.service';
import { SchedulerService } from '../../../../core/services/scheduler.service';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import { FilterFieldConfig } from '../../../../designSystem/molecules/filterField/filterField.model';
import { PaginationComponent } from '../../../../designSystem/molecules/pagination/pagination.component';
import { ToastComponent } from '../../../../designSystem/molecules/toastMessage/toast.component';
import { ConfirmModalComponent } from '../../../../designSystem/organisms/confirmModal/confirmModal.component';
import { FilterValues } from '../../../../designSystem/organisms/filters/filters.component';
import { PatientQuickSearchComponent } from '../../../../designSystem/organisms/patientQuickSearch/patientQuickSearch.component';
import { formatLongDateEs } from '../../../../shared/helpers/dateFormat';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { PatientQuickResult } from '../../../../shared/models/dtos/patientQuickResult.dto';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { SchedulerExportModalComponent } from '../../components/exportModal/exportModal.component';
import { FiltersPanelComponent } from '../../components/filtersPanel/filtersPanel.component';
import { AppointmentTableComponent } from '../../components/table/table.component';
const PAGE_SIZE = 5;

@Component({
  selector: 'app-scheduler-history',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideX,
    LucideDownload,
    LucideCalendar,
    ConfirmModalComponent,
    ToastComponent,
    AppointmentTableComponent,
    FiltersPanelComponent,
    SchedulerExportModalComponent,
    ButtonComponent,
    PaginationComponent,
    PatientQuickSearchComponent,
  ],
  templateUrl: './schedulerHistory.component.html',
})
export class SchedulerHistoryComponent implements OnInit {
  private schedulerService = inject(SchedulerService);
  private patientAppointmentService = inject(PatientAppointmentService);

  doctors = signal<dtoDoctor[]>([]);
  states = signal<string[]>([]);

  // Filtros aplicados (los que están reflejados en la consulta actual).
  filterDoctor = signal('');
  filterDate = signal('');
  filterStatus = signal('');

  private formatoPipe = new FormatoPipe();

  appliedFilterValues = computed<FilterValues>(() => ({
    doctor: this.filterDoctor(),
    date: this.filterDate(),
    status: this.filterStatus(),
  }));

  filterFields = computed<FilterFieldConfig[]>(() => [
    {
      id: 'doctor',
      type: 'select',
      label: 'Médico / Terapista',
      placeholder: 'Todos los médicos',
      options: this.doctors().map((d) => ({
        value: d.id,
        label: `${d.name} — ${d.specialties.map((s) => this.formatoPipe.transform(s)).join(', ')}`,
      })),
      formatValue: (id) => this.doctors().find((d) => d.id === id)?.name ?? id,
    },
    {
      id: 'date',
      type: 'date',
      label: 'Fecha Específica',
      formatValue: (d) => formatLongDateEs(d),
    },
    {
      id: 'status',
      type: 'select',
      label: 'Estado de la Cita',
      placeholder: 'Todos los estados',
      options: this.states().map((s) => ({
        value: s,
        label: this.formatoPipe.transform(s),
      })),
      formatValue: (s) => this.formatoPipe.transform(s),
    },
  ]);

  /** Página actualmente solicitada (base 0). Se resetea a 0 al cambiar los filtros. */
  private readonly pageNumber = signal(0);

  showCancelModal = signal(false);
  pendingCancelId = signal<string | null>(null);

  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  errorMessage = signal('');
  loadingPatientAppointments = signal(false);

  /** Metadata de paginación de la última carga, provista por el servicio. */
  readonly pagination = computed(() => this.schedulerService.pagination());
  /**
   * Citas de la página actual. Es la misma fuente sin importar si se está
   * mostrando el listado filtrado por médico/fecha/estado, o las citas de
   * un paciente específico — la diferencia está solo en qué parámetros se
   * mandaron en la última llamada a `loadAllAppointments`.
   */
  readonly results = computed(() => this.schedulerService.appointments());

  selectedPatient = signal<PatientQuickResult | null>(null);

  onPatientSelected(patient: PatientQuickResult): void {
    this.selectedPatient.set(patient);
    this.pageNumber.set(0);
    this.loadAppointments(
      patient.id,
      this.filterDoctor(),
      this.filterDate(),
      this.filterStatus(),
      0
    );
  }

  onClearPatientFilter(): void {
    this.selectedPatient.set(null);
    this.pageNumber.set(0);
    this.loadAppointments(
      null,
      this.filterDoctor(),
      this.filterDate(),
      this.filterStatus(),
      0
    );
  }

  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.id === this.filterDoctor())
  );

  historyDescription = computed(() => {
    const parts: string[] = [];
    if (this.filterDoctor()) parts.push(this.selectedDoctor()?.name ?? '');
    if (this.filterDate()) parts.push(this.formatDate(this.filterDate()));
    if (this.filterStatus())
      parts.push(this.formatoPipe.transform(this.filterStatus()));

    if (!parts.length) {
      return 'Mostrando todas las citas de todos los médicos';
    }
    return parts.join(' — ');
  });

  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService
      .getStates()
      .subscribe((data) => this.states.set(data));
    this.loadAppointments(null, '', '', '', 0);
  }

  /**
   * Se conecta al evento (apply) del componente de filtros. Si hay un
   * paciente seleccionado, los filtros se aplican dentro de sus citas
   * (no reemplazan la búsqueda por paciente); si no, filtran el listado
   * general.
   */
  onApplyFilters(filters: FilterValues): void {
    this.filterDoctor.set(filters['doctor'] ?? '');
    this.filterDate.set(filters['date'] ?? '');
    this.filterStatus.set(filters['status'] ?? '');
    this.pageNumber.set(0);
    this.loadAppointments(
      this.selectedPatient()?.id ?? null,
      filters['doctor'] ?? '',
      filters['date'] ?? '',
      filters['status'] ?? '',
      0
    );
  }

  /**
   * Navega a la página indicada, respetando el modo actual: si hay un
   * paciente filtrado, pagina sus citas (con los filtros vigentes
   * aplicados también); si no, pagina el listado general.
   * Conectado al evento `pageChange` de `app-pagination`.
   */
  onPageChange(pageNumber: number): void {
    this.pageNumber.set(pageNumber);
    this.loadAppointments(
      this.selectedPatient()?.id ?? null,
      this.filterDoctor(),
      this.filterDate(),
      this.filterStatus(),
      pageNumber
    );
  }

  /**
   * Carga las citas combinando, si aplica, el paciente seleccionado con
   * los filtros de médico/fecha/estado vigentes. Es el único punto de
   * entrada a `loadAllAppointments`, para que ambos criterios (paciente
   * y filtros) nunca se pisen entre sí.
   */
  private loadAppointments(
    patientId: string | null,
    doctorId: string,
    date: string,
    status: string,
    pageNumber: number
  ): void {
    if (patientId) this.loadingPatientAppointments.set(true);
    this.schedulerService
      .loadAllAppointments({
        idPatient: patientId || undefined,
        idDoctor: doctorId || undefined,
        date: date || undefined,
        state: status || undefined,
        pageNumber,
        pageSize: PAGE_SIZE,
      })
      .subscribe({
        next: () => {
          if (patientId) this.loadingPatientAppointments.set(false);
        },
        error: (err: AppError) => {
          if (patientId) this.loadingPatientAppointments.set(false);
          this.errorMessage.set(
            'No se pudieron cargar las citas: ' + err.message
          );
        },
      });
  }

  requestCancelAppointment(appointmentId: string): void {
    this.pendingCancelId.set(appointmentId);
    this.showCancelModal.set(true);
  }

  confirmCancelAppointment(): void {
    const appointmentId = this.pendingCancelId();
    if (!appointmentId) return;
    this.showCancelModal.set(false);
    this.pendingCancelId.set(null);
    this.patientAppointmentService.cancelAppointment(appointmentId).subscribe({
      next: () => {
        this.showToast('La cita fue cancelada exitosamente', 'success');
        this.loadAppointments(
          this.selectedPatient()?.id ?? null,
          this.filterDoctor(),
          this.filterDate(),
          this.filterStatus(),
          this.pageNumber()
        );
      },
      error: (err: AppError) =>
        this.showToast(
          'Ocurrió un error al cancelar la cita: ' + err.message,
          'error'
        ),
    });
  }

  dismissCancelModal(): void {
    this.showCancelModal.set(false);
    this.pendingCancelId.set(null);
  }

  formatDate(dateStr: string): string {
    return formatLongDateEs(dateStr);
  }

  private showToast(message: string, type: 'success' | 'error'): void {
    this.toastMessage.set(message);
    this.toastType.set(type);
    setTimeout(() => {
      this.toastMessage.set('');
      this.toastType.set(null);
    }, 3000);
  }
}
