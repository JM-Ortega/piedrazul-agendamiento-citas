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
  LucideUserX,
} from '@lucide/angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';
import { ConfirmModalComponent } from '../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { PaginatedState } from '../../../shared/helpers/paginated-state';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../shared/helpers/transform-date-local';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { AppError } from '../../../shared/models/interfaces/api-error.model';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-doctor-dashboard',
  templateUrl: './doctor-dashboard.component.html',
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

  today = toIsoDateString(new Date());

  currentDoctor = signal<Doctor | null>(null);
  private appointmentsState = new PaginatedState<AppointmentsPatient>();
  pagination = this.appointmentsState.pagination;
  readonly PAGE_SIZE = 3;
  errorCarga = signal('');

  showConfirmModal = signal(false);
  selectedAppointmentId = signal<string | null>(null);
  selectedOutcome = signal<'ATENDIDA' | 'NO_ASISTIO' | null>(null);
  isMarkingAttended = signal(false);

  openCardDropdownId = signal<string | null>(null);
  showOutcomeDropdown = signal(false);

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

  ngOnInit(): void {
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        this.currentDoctor.set(doctor);
        this.loadAppointments(doctor.id);
      },
      error: (err: AppError) => {
        this.errorCarga.set(err.message);
        this.router.navigate(['/']);
      },
    });
  }

  private loadAppointments(doctorId: string, pageNumber = 0): void {
    this.errorCarga.set('');
    this.doctorService
      .getTodayAppointmentsByDoctor(doctorId, pageNumber, this.PAGE_SIZE)
      .subscribe({
        next: (response) => this.appointmentsState.set(response),
        error: (err: AppError) => {
          this.errorCarga.set(err.message);
          this.appointmentsState.clear();
        },
      });
  }

  onPageChange(pageNumber: number): void {
    const doctorId = this.currentDoctor()?.id;
    if (doctorId) this.loadAppointments(doctorId, pageNumber);
  }

  toggleCardDropdown(appointmentId: string): void {
    this.openCardDropdownId.update((current) =>
      current === appointmentId ? null : appointmentId
    );
  }

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

  toggleOutcomeDropdown(): void {
    this.showOutcomeDropdown.update((v) => !v);
  }

  selectOutcome(value: 'ATENDIDA' | 'NO_ASISTIO'): void {
    this.selectedOutcome.set(value);
    this.showOutcomeDropdown.set(false);
  }

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
        const doctorId = this.currentDoctor()?.id;
        if (doctorId) this.loadAppointments(doctorId);
      },
      error: (err: AppError) => {
        this.isMarkingAttended.set(false);
        alert(err.message);
      },
    });
  }

  closeModal(): void {
    this.showConfirmModal.set(false);
    this.selectedAppointmentId.set(null);
    this.selectedOutcome.set(null);
    this.showOutcomeDropdown.set(false);
    this.openCardDropdownId.set(null);
    this.isMarkingAttended.set(false);
  }

  scheduleNewAppointment(
    documentNumber: string,
    specialty: string,
    idDoctor: string | undefined
  ): void {
    this.router.navigate(['/medico/nueva-cita'], {
      state: { documentNumber, specialty, idDoctor },
    });
  }

  formatDate(dateStr: string): string {
    const date = parseLocalDateString(dateStr);
    return new Intl.DateTimeFormat('es-CO', {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    }).format(date);
  }
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
  formattedSpecialties(specialties: string[] | undefined): string {
    return (specialties ?? [])
      .map((s) => this.formatoPipe.transform(s))
      .join(', ');
  }
}
