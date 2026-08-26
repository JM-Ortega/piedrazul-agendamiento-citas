import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucideX } from '@lucide/angular';
import { SchedulerService } from '../../../../core/services/scheduler.service';
import { PatientAppointmentService } from '../../../../core/services/patientAppointment.service';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { ConfirmModalComponent } from '../../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { ToastComponent } from '../../../../design-system/molecules/toast-message/toast.component';
import { PaginationComponent } from '../../../../design-system/molecules/pagination/pagination.component';
import {
  FiltersComponent,
  FilterValues,
} from '../../../../design-system/organisms/filters/filters.component';
import { FilterFieldConfig } from '../../../../design-system/molecules/filter-field/filterField.model';
import { AppointmentTableComponent } from '../../components/table/table.component';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

const PAGE_SIZE = 5;

@Component({
  selector: 'app-scheduler-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideX,
    ConfirmModalComponent,
    ToastComponent,
    AppointmentTableComponent,
    FiltersComponent,
    PaginationComponent,
  ],
  templateUrl: './scheduler-dashboard.component.html',
})
export class SchedulerDashboardComponent implements OnInit {
  private schedulerService = inject(SchedulerService);
  private patientAppointmentService = inject(PatientAppointmentService);
  private formatoPipe = new FormatoPipe();

  readonly today = (() => {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();

  doctors = signal<dtoDoctor[]>([]);
  states = signal<string[]>([]);

  filterDoctor = signal('');
  filterStatus = signal('');

  appliedFilterValues = computed<FilterValues>(() => ({
    doctor: this.filterDoctor(),
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

  private readonly pageNumber = signal(0);

  showCancelModal = signal(false);
  pendingCancelId = signal<string | null>(null);

  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  errorMessage = signal('');

  /** Metadata de paginación de la última carga, provista por el servicio. */
  readonly pagination = computed(() => this.schedulerService.pagination());
  /** Citas de la página actual. */
  readonly results = computed(() => this.schedulerService.appointments());

  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.id === this.filterDoctor())
  );

  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService
      .getStates()
      .subscribe((data) => this.states.set(data));
    this.loadAppointments('', '', 0);
  }

  /** Se conecta al evento (apply) del componente de filtros. */
  onApplyFilters(filters: FilterValues): void {
    this.filterDoctor.set(filters['doctor'] ?? '');
    this.filterStatus.set(filters['status'] ?? '');
    this.pageNumber.set(0);
    this.loadAppointments(filters['doctor'] ?? '', filters['status'] ?? '', 0);
  }

  /**
   * Navega a la página indicada manteniendo los filtros actuales.
   */
  onPageChange(pageNumber: number): void {
    this.pageNumber.set(pageNumber);
    this.loadAppointments(this.filterDoctor(), this.filterStatus(), pageNumber);
  }

  private loadAppointments(
    doctorId: string,
    status: string,
    pageNumber: number
  ): void {
    this.schedulerService
      .loadAllAppointments({
        date: this.today,
        idDoctor: doctorId || undefined,
        state: status || undefined,
        pageNumber,
        pageSize: PAGE_SIZE,
      })
      .subscribe({
        error: (err: AppError) => {
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
          this.filterDoctor(),
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
