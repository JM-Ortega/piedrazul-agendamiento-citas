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
  LucideAlertCircle,
  LucideCalendarDays,
  LucideCheck,
  LucideChevronRight,
  LucideClock,
  LucidePlusCircle,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { AppService } from '../../../core/services/app.service';
import { PatientAppointmentService } from '../../../core/services/patientAppointment.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';
import { getMonthShort } from '../../../shared/helpers/date-format';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

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
    LucideAlertCircle,
    LucideCheck,
    RouterLink,
    FormatoPipe,
    ButtonComponent,
  ],
})
export class PatientDashboardComponent implements OnInit {
  protected appService = inject(AppService);
  private appointmentService = inject(PatientAppointmentService);

  isLoading = signal(false);
  errorMessage = signal('');
  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  readonly showCancelModal = signal(false);
  readonly pendingCancelId = signal<string | null>(null);

  readonly upcomingAppointments = computed<AppointmentsPatient[]>(() => {
    return this.appointmentService
      .appointments()
      .filter((a) => a.appointmentState === 'AGENDADA')
      .sort((a, b) => (a.date + a.startTime > b.date + b.startTime ? 1 : -1));
  });

  ngOnInit(): void {
    this.isLoading.set(true);

    this.appointmentService.loadMyAppointments().subscribe({
      next: () => this.isLoading.set(false),
      error: () => {
        this.errorMessage.set(
          'No se pudieron cargar las citas. Intente más tarde.'
        );
        this.isLoading.set(false);
      },
    });
  }

  getMonthShort(dateStr: string): string {
    return getMonthShort(dateStr);
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

    this.appointmentService.cancelAppointment(appointmentId).subscribe({
      next: () => {
        this.showToast('La cita fue cancelada exitosamente', 'success');
        this.appointmentService.patchAppointmentStatus(
          appointmentId,
          'CANCELADA'
        );
      },
      error: () => {
        this.showToast('Ocurrió un error al cancelar la cita', 'error');
      },
    });
  }

  dismissCancelModal(event?: MouseEvent): void {
    if (event && event.target !== event.currentTarget) return;
    this.showCancelModal.set(false);
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
