import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  LucideCalendarDays,
  LucideChevronRight,
  LucideClock,
  LucidePlusCircle,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { AppService } from '../../../core/services/app.service';
import { PatientAppointmentService } from '../../../core/services/patientAppointment.service';
import { PatientService } from '../../../core/services/patient.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';
import { ConfirmModalComponent } from '../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { ToastComponent } from '../../../design-system/molecules/toast-message/toast.component';
import { getMonthShort } from '../../../shared/helpers/date-format';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';

const PAGE_SIZE = 5;

/**
 * Dashboard principal del paciente autenticado.
 *
 * Muestra un resumen de sus próximas citas (estado AGENDADA),
 * con acceso rápido para agendar una nueva cita y cancelar las existentes.
 */
@Component({
  selector: 'app-patient-dashboard',
  templateUrl: './patient-dashboard.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendarDays,
    LucideChevronRight,
    LucideClock,
    LucidePlusCircle,
    LucideUser,
    LucideX,
    RouterLink,
    FormatoPipe,
    ButtonComponent,
    ConfirmModalComponent,
    ToastComponent,
    PaginationComponent,
  ],
})
export class PatientDashboardComponent implements OnInit {
  protected appService = inject(AppService);
  private patientService = inject(PatientService);
  private appointmentService = inject(PatientAppointmentService);

  isLoading = signal(false);
  errorMessage = signal('');
  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  readonly showCancelModal = signal(false);
  readonly pendingCancelId = signal<string | null>(null);
  private readonly patientId = signal<string | null>(null);

  /** Citas de la página actualmente cargada (solo estado AGENDADA). */
  readonly upcomingAppointments = computed<AppointmentsPatient[]>(() =>
    this.appointmentService.appointments()
  );

  /** Metadata de paginación de la última carga. */
  readonly pagination = computed(() => this.appointmentService.pagination());

  /**
   * Obtiene el paciente autenticado y carga la primera página de sus
   * próximas citas.
   */
  ngOnInit(): void {
    this.isLoading.set(true);

    this.patientService.getMe().subscribe({
      next: (patient) => {
        this.patientId.set(patient.id);
        this.loadPage(0);
      },
      error: () => {
        this.errorMessage.set(
          'No se pudo obtener la información del paciente.'
        );
        this.isLoading.set(false);
      },
    });
  }

  /**
   * Carga una página específica de próximas citas (AGENDADA) del paciente.
   * Se invoca al inicializar el componente, al navegar de página desde
   * `app-pagination`, y tras cancelar una cita para refrescar el listado.
   *
   * @param pageNumber número de página a solicitar, base 0
   */
  loadPage(pageNumber: number): void {
    const idPatient = this.patientId();
    if (!idPatient) return;

    this.isLoading.set(true);

    this.appointmentService
      .loadAppointments({
        idPatient,
        state: 'AGENDADA',
        pageNumber,
        pageSize: PAGE_SIZE,
      })
      .subscribe({
        next: () => this.isLoading.set(false),
        error: () => {
          this.errorMessage.set(
            'No se pudieron cargar las citas. Intente más tarde.'
          );
          this.isLoading.set(false);
        },
      });
  }

  /**
   * Obtiene el nombre corto del mes (ej. "ENE") a partir de una fecha en
   * formato `YYYY-MM-DD`, para mostrarlo en la tarjeta de cada cita.
   *
   * @param dateStr fecha de la cita en formato `YYYY-MM-DD`
   */
  getMonthShort(dateStr: string): string {
    return getMonthShort(dateStr);
  }

  /**
   * Abre el modal de confirmación para cancelar la cita indicada.
   *
   * @param appointmentId id de la cita a cancelar
   */
  requestCancelAppointment(appointmentId: string): void {
    this.pendingCancelId.set(appointmentId);
    this.showCancelModal.set(true);
  }

  /**
   * Confirma la cancelación de la cita pendiente: cierra el modal, llama
   * al backend, y recarga la página actual para mantener la metadata de
   * paginación sincronizada con el servidor.
   */
  confirmCancelAppointment(): void {
    const appointmentId = this.pendingCancelId();
    if (!appointmentId) return;

    this.showCancelModal.set(false);
    this.pendingCancelId.set(null);

    this.appointmentService.cancelAppointment(appointmentId).subscribe({
      next: () => {
        this.showToast('La cita fue cancelada exitosamente', 'success');
        const p = this.pagination();
        this.loadPage(p?.pageNumber ?? 0);
      },
      error: () => {
        this.showToast('Ocurrió un error al cancelar la cita', 'error');
      },
    });
  }

  /**
   * Cierra el modal de confirmación sin cancelar la cita.
   */
  dismissCancelModal(): void {
    this.showCancelModal.set(false);
    this.pendingCancelId.set(null);
  }

  /**
   * Muestra un toast temporal con el resultado de una acción,
   * ocultándolo automáticamente después de 3 segundos.
   *
   * @param message texto a mostrar en el toast
   * @param type tipo de toast (`success` o `error`), determina su estilo
   */
  private showToast(message: string, type: 'success' | 'error'): void {
    this.toastMessage.set(message);
    this.toastType.set(type);
    setTimeout(() => {
      this.toastMessage.set('');
      this.toastType.set(null);
    }, 3000);
  }
}
