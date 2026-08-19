import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucideX, LucideDownload, LucideCalendar } from '@lucide/angular';
import { SchedulerService } from '../../../../core/services/scheduler.service';
import { PatientAppointmentService } from '../../../../core/services/patientAppointment.service';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { ConfirmModalComponent } from '../../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { ToastComponent } from '../../../../design-system/molecules/toast-message/toast.component';
import { PaginationComponent } from '../../../../design-system/molecules/pagination/pagination.component';
import { AppointmentTableComponent } from '../../components/table/table.component';
import { SchedulerFiltersComponent } from '../../components/filters/filter.component';
import { SchedulerExportModalComponent } from '../../components/export-modal/export-modal.component';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

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
    SchedulerFiltersComponent,
    SchedulerExportModalComponent,
    ButtonComponent,
    PaginationComponent,
  ],
  templateUrl: './scheduler-history.component.html',
})
export class SchedulerHistoryComponent implements OnInit {
  private schedulerService = inject(SchedulerService);
  private patientAppointmentService = inject(PatientAppointmentService);

  doctors = signal<dtoDoctor[]>([]);
  states = signal<string[]>([]);

  filterDoctor = signal('');
  filterDate = signal('');
  filterStatus = signal('');

  /** Página actualmente solicitada (base 0). Se resetea a 0 al cambiar los filtros. */
  private readonly pageNumber = signal(0);

  showCancelModal = signal(false);
  pendingCancelId = signal<string | null>(null);

  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  errorMessage = signal('');

  /** Metadata de paginación de la última carga, provista por el servicio. */
  readonly pagination = computed(() => this.schedulerService.pagination());
  /** Citas de la página actual.*/
  readonly results = computed(() => this.schedulerService.appointments());

  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.id === this.filterDoctor())
  );

  constructor() {
    effect(() => {
      const doctorId = this.filterDoctor();
      const date = this.filterDate();
      const status = this.filterStatus();
      this.pageNumber.set(0);
      this.loadAppointments(doctorId, date, status, 0);
    });
  }

  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService
      .getStates()
      .subscribe((data) => this.states.set(data));
  }

  /**
   * Navega a la página indicada manteniendo los filtros actuales.
   * Conectado al evento `pageChange` de `app-pagination`.
   */
  onPageChange(pageNumber: number): void {
    this.pageNumber.set(pageNumber);
    this.loadAppointments(
      this.filterDoctor(),
      this.filterDate(),
      this.filterStatus(),
      pageNumber
    );
  }

  private loadAppointments(
    doctorId: string,
    date: string,
    status: string,
    pageNumber: number
  ): void {
    this.schedulerService
      .loadAllAppointments({
        idDoctor: doctorId || undefined,
        date: date || undefined,
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
