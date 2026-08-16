import { CommonModule } from '@angular/common';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { LucideCalendarDays, LucideClock } from '@lucide/angular';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { PatientAppointmentService } from '../../../core/services/patientAppointment.service';
import { PatientService } from '../../../core/services/patient.service';
import { getMonthShort } from '../../../shared/helpers/date-format';
import {
  APPOINTMENT_STATUS_LABELS,
  APPOINTMENT_STATUS_CLASSES,
} from '../../../shared/helpers/appointment-status';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';

const PAGE_SIZE = 5;

/**
 * Muestra el historial completo de citas del paciente autenticado.
 */
@Component({
  selector: 'app-patient-appointment-history',
  templateUrl: './patient-appointment-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendarDays,
    LucideClock,
    CommonModule,
    FormatoPipe,
    PaginationComponent,
  ],
})
export class PatientAppointmentHistoryComponent implements OnInit {
  private patientService = inject(PatientService);
  private appointmentService = inject(PatientAppointmentService);

  isLoading = signal(false);
  errorMessage = signal('');

  readonly statusLabels = APPOINTMENT_STATUS_LABELS;
  readonly statusClasses = APPOINTMENT_STATUS_CLASSES;
  private readonly patientId = signal<string | null>(null);

  /** Citas de la página actualmente cargada (todos los estados, sin filtrar). */
  readonly appointments = computed<AppointmentsPatient[]>(() =>
    this.appointmentService.appointments()
  );

  /** Metadata de paginación de la última carga. */
  readonly pagination = computed(() => this.appointmentService.pagination());

  /**
   * Obtiene el paciente autenticado y carga la primera página de su historial de citas.
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
   * Carga una página específica del historial de citas del paciente.
   * Se invoca al inicializar el componente y cada vez que el usuario
   * navega de página desde `app-pagination` (evento `pageChange`).
   *
   * @param pageNumber número de página a solicitar, base 0
   */
  loadPage(pageNumber: number): void {
    const idPatient = this.patientId();
    if (!idPatient) return;

    this.isLoading.set(true);

    this.appointmentService
      .loadAppointments({ idPatient, pageNumber, pageSize: PAGE_SIZE })
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
}
