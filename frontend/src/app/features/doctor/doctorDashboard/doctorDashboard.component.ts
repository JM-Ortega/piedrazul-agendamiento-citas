import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideCalendar,
  LucideCheck,
  LucideChevronDown,
  LucideClipboardList,
  LucideClock,
  LucideFileText,
  LucideUser,
  LucideUserPlus,
  LucideUserX,
} from '@lucide/angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { ButtonComponent } from '../../../designSystem/atoms/button/button.component';
import { PaginationComponent } from '../../../designSystem/molecules/pagination/pagination.component';
import { ConfirmModalComponent } from '../../../designSystem/organisms/confirmModal/confirmModal.component';
import { PaginatedState } from '../../../shared/helpers/paginatedState';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../shared/helpers/transformDateLocal';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { AppError } from '../../../shared/models/interfaces/apiError.model';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

/**
 * Panel principal del médico autenticado.
 *
 * Muestra los datos del doctor logueado y su agenda del día actual
 *   Permite marcar una cita como atendida
 * (redirige a control médico) o como no asistida (actualiza el estado
 * in-place), y da acceso directo a la atención de pacientes sin cita previa.
 */
@Component({
  selector: 'app-doctor-dashboard',
  templateUrl: './doctorDashboard.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideCheck,
    LucideChevronDown,
    LucideClipboardList,
    LucideClock,
    LucideFileText,
    LucideUser,
    LucideUserPlus,
    LucideUserX,
    ButtonComponent,
    ConfirmModalComponent,
    PaginationComponent,
  ],
})
export class DoctorDashboardComponent implements OnInit {
  private formatoPipe = new FormatoPipe();
  private doctorService = inject(DoctorService);
  private router = inject(Router);

  /** Fecha de hoy en formato ISO (`yyyy-MM-dd`) */
  today = toIsoDateString(new Date());

  // ── Signals de datos ─────────────────────────────────────────────

  /** Doctor autenticado actualmente (null mientras carga o si falla `getMe()`). */
  currentDoctor = signal<Doctor | null>(null);

  /** Mensaje de error a mostrar en el banner*/
  errorCarga = signal('');

  // ── Signals de paginación ────────────────────────────────────────

  private appointmentsState = new PaginatedState<AppointmentsPatient>();
  pagination = this.appointmentsState.pagination;
  readonly PAGE_SIZE = 3;

  // ── Signals de UI: modal de confirmación ────────────────────────

  /** Controla la visibilidad del modal de confirmación de estado. */
  showConfirmModal = signal(false);

  /** ID de la cita seleccionada para actualizar (null si no hay ninguna). */
  selectedAppointmentId = signal<string | null>(null);

  /** Resultado elegido en el dropdown de la tarjeta ('ATENDIDA' o 'NO_ASISTIO'). */
  selectedOutcome = signal<'ATENDIDA' | 'NO_ASISTIO' | null>(null);

  /** Indica si la actualización de estado está en curso (deshabilita acciones). */
  isMarkingAttended = signal(false);

  // ── Signals de UI: dropdowns ─────────────────────────────────────

  /** ID de la tarjeta cuyo dropdown "Estado" está abierto (null = todos cerrados). */
  openCardDropdownId = signal<string | null>(null);

  /** Visibilidad del dropdown de resultado dentro del modal (uso auxiliar). */
  showOutcomeDropdown = signal(false);

  // ── Computed / filtros derivados ─────────────────────────────────

  /**
   * Citas de hoy listas para renderizar: excluye canceladas y ordena
   * primero por prioridad de estado (Agendada > Atendida) y luego por
   * hora de inicio. El backend ya filtra por doctor y fecha; este filtro
   * es una salvaguarda adicional en el cliente.
   */
  todaysAppointments = computed(() =>
    [...this.appointmentsState.content()]
      .filter(
        (a) => a.date === this.today && a.appointmentState !== 'CANCELADA'
      )
      .sort((a, b) => {
        const stateOrder: Record<string, number> = {
          AGENDADA: 1,
          ATENDIDA: 2,
        };
        const stateDiff =
          (stateOrder[a.appointmentState] ?? 99) -
          (stateOrder[b.appointmentState] ?? 99);
        if (stateDiff !== 0) return stateDiff;
        return a.startTime.localeCompare(b.startTime);
      })
  );

  // ── Ciclo de vida ─────────────────────────────────────────────────

  /**
   * Carga el doctor autenticado y, si tiene éxito, dispara la carga
   * inicial de su agenda diaria. Si falla, redirige a la raíz.
   */
  ngOnInit(): void {
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        this.currentDoctor.set(doctor);
        this.loadAppointments();
      },
      error: (err: AppError) => {
        this.errorCarga.set(err.message);
        this.router.navigate(['/']);
      },
    });
  }

  // ── Carga de datos / paginación ──────────────────────────────────

  /**
   * Carga una página de la agenda diaria del doctor autenticado.
   *
   * @param pageNumber - Índice de página (base 0). Por defecto 0.
   */
  private loadAppointments(pageNumber = 0): void {
    this.errorCarga.set('');
    this.doctorService
      .getDoctorDailyAgenda(this.today, pageNumber, this.PAGE_SIZE)
      .subscribe({
        next: (response) => this.appointmentsState.set(response),
        error: (err: AppError) => {
          this.errorCarga.set(err.message);
          this.appointmentsState.clear();
        },
      });
  }

  /**
   * Handler del evento de cambio de página emitido por `app-pagination`.
   *
   * @param pageNumber - Nueva página seleccionada (base 0).
   */
  onPageChange(pageNumber: number): void {
    this.loadAppointments(pageNumber);
  }

  // ── Acciones sobre una cita ──────────────────────────────────────

  /**
   * Abre/cierra el dropdown "Estado" de una tarjeta puntual, cerrando
   * cualquier otro dropdown abierto.
   *
   * @param appointmentId - ID de la cita cuya tarjeta se alterna.
   */
  toggleCardDropdown(appointmentId: string): void {
    this.openCardDropdownId.update((current) =>
      current === appointmentId ? null : appointmentId
    );
  }

  /**
   * Selecciona el resultado elegido desde el dropdown de una tarjeta
   * y abre el modal de confirmación correspondiente.
   *
   * @param appointmentId - ID de la cita afectada.
   * @param outcome - Resultado elegido ('ATENDIDA' o 'NO_ASISTIO').
   */
  selectCardOutcome(
    appointmentId: string,
    outcome: 'ATENDIDA' | 'NO_ASISTIO'
  ): void {
    this.openCardDropdownId.set(null);
    this.selectedOutcome.set(outcome);
    this.selectedAppointmentId.set(appointmentId);
    this.showOutcomeDropdown.set(false);
    this.showConfirmModal.set(true);
  }

  /** Alterna la visibilidad del dropdown de resultado dentro del modal. */
  toggleOutcomeDropdown(): void {
    this.showOutcomeDropdown.update((v) => !v);
  }

  /**
   * Fija el resultado elegido dentro del modal y cierra su dropdown.
   *
   * @param value - Resultado elegido ('ATENDIDA' o 'NO_ASISTIO').
   */
  selectOutcome(value: 'ATENDIDA' | 'NO_ASISTIO'): void {
    this.selectedOutcome.set(value);
    this.showOutcomeDropdown.set(false);
  }

  /**
   * Confirma la acción elegida en el modal:
   * - 'ATENDIDA': cierra el modal y redirige a control médico.
   * - 'NO_ASISTIO': actualiza el estado de la cita en backend y recarga la agenda.
   */
  confirmMarkAsAttended(): void {
    const id = this.selectedAppointmentId();
    const outcome = this.selectedOutcome();
    if (!id || !outcome) return;
    if (outcome === 'ATENDIDA') {
      this.closeModal();
      this.router.navigate(['medico/control-medico/', id]);
      return;
    }

    this.isMarkingAttended.set(true);

    this.doctorService.updateAppointmentAsUnassisted(id, outcome).subscribe({
      next: () => {
        this.closeModal();
        this.loadAppointments();
      },
      error: (err: AppError) => {
        this.isMarkingAttended.set(false);
        alert(err.message);
      },
    });
  }

  /** Cierra el modal de confirmación y resetea todo su estado asociado. */
  closeModal(): void {
    this.showConfirmModal.set(false);
    this.selectedAppointmentId.set(null);
    this.selectedOutcome.set(null);
    this.showOutcomeDropdown.set(false);
    this.openCardDropdownId.set(null);
    this.isMarkingAttended.set(false);
  }

  // ── Navegación ────────────────────────────────────────────────────

  /**
   * Navega al formulario de nueva cita, precargando documento,
   * especialidad y doctor mediante el estado de navegación de Angular.
   *
   * @param documentNumber - Documento del paciente a preseleccionar.
   * @param specialty - Especialidad a preseleccionar.
   * @param idDoctor - ID del doctor actual (puede ser undefined si aún no cargó).
   */
  scheduleNewAppointment(
    documentNumber: string,
    specialty: string,
    idDoctor: string | undefined
  ): void {
    this.router.navigate(['/medico/nueva-cita'], {
      state: { documentNumber, specialty, idDoctor },
    });
  }

  /** Navega al flujo de atención de un paciente sin cita previa. */
  goToUnscheduledAttention(): void {
    this.router.navigate(['/medico/atencion-sin-cita']);
  }

  // ── Formateo / helpers de presentación ───────────────────────────

  /**
   * Formatea una fecha ISO a texto legible en español ("jueves, 10 de...").
   *
   * @param dateStr - Fecha en formato ISO (`yyyy-MM-dd`).
   * @returns Fecha formateada en `es-CO`.
   */
  formatDate(dateStr: string): string {
    const date = parseLocalDateString(dateStr);
    return new Intl.DateTimeFormat('es-CO', {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    }).format(date);
  }

  /**
   * Clases de color (fondo/texto/borde) asociadas a un estado de cita.
   *
   * @param state - Estado de la cita.
   * @returns Clases Tailwind correspondientes, o gris por defecto si no coincide.
   */
  statusColor(state: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'bg-green-100 text-green-800 border-green-300',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-800 border-yellow-300',
      CANCELADA: 'bg-gray-100 text-gray-800 border-gray-300',
      NO_ASISTIO: 'bg-orange-100 text-orange-800 border-orange-300',
      ATENDIDA: 'bg-blue-100 text-blue-800 border-blue-300',
    };
    return map[state] ?? 'bg-gray-100 text-gray-800 border-gray-300';
  }

  /**
   * Etiqueta legible en español para un estado de cita.
   *
   * @param state - Estado de la cita.
   * @returns Etiqueta correspondiente, o el estado crudo si no coincide.
   */
  statusLabel(state: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'Agendada',
      ATENDIDA: 'Atendida',
      CANCELADA: 'Cancelada',
      NO_ASISTIO: 'No asistió',
      REPROGRAMADA: 'Pendiente',
    };
    return map[state] ?? state;
  }

  /**
   * Formatea la lista de especialidades del doctor para mostrarla en el header.
   *
   * @param specialties - Lista cruda de especialidades (puede ser undefined).
   * @returns Especialidades formateadas y unidas por coma.
   */
  formattedSpecialties(specialties: string[] | undefined): string {
    return (specialties ?? [])
      .map((s) => this.formatoPipe.transform(s))
      .join(', ');
  }
}
