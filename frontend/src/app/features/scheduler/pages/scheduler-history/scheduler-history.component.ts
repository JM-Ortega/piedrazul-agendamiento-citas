import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucideX, LucideDownload, LucideCalendar } from '@lucide/angular';
import { SchedulerService } from '../../../../core/services/scheduler.service';
import { PatientAppointmentService } from '../../../../core/services/patientAppointment.service';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { dtoDoctor } from '../../../../shared/models/dtos/doctor.dto';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { ConfirmModalComponent } from '../../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { ToastComponent } from '../../../../design-system/molecules/toast-message/toast.component';
import { AppointmentTableComponent } from '../../components/table/table.component';
import { SchedulerFiltersComponent } from '../../components/filters/filter.component';
import { SchedulerExportModalComponent } from '../../components/export-modal/export-modal.component';

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
  ],
  templateUrl: './scheduler-history.component.html',
})
export class SchedulerHistoryComponent implements OnInit {
  private schedulerService = inject(SchedulerService);
  private patientAppointmentService = inject(PatientAppointmentService);

  doctors = signal<dtoDoctor[]>([]);
  private appointments = signal<AppointmentsPatient[]>([]);

  filterDoctor = signal('');
  filterDate = signal('');
  filterStatus = signal('');

  showCancelModal = signal(false);
  pendingCancelId = signal<string | null>(null);

  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.name === this.filterDoctor())
  );

  results = computed(() => {
    let filtered = this.appointments();
    if (this.filterDoctor())
      filtered = filtered.filter((a) => a.doctorName === this.filterDoctor());
    if (this.filterDate())
      filtered = filtered.filter((a) => a.date === this.filterDate());
    if (this.filterStatus())
      filtered = filtered.filter(
        (a) => a.appointmentState === this.filterStatus()
      );

    const stateOrder: Record<string, number> = {
      AGENDADA: 1,
      ATENDIDA: 2,
      CANCELADA: 3,
    };
    return [...filtered].sort((a, b) => {
      const stateDiff =
        stateOrder[a.appointmentState] - stateOrder[b.appointmentState];
      if (stateDiff !== 0) return stateDiff;
      return (
        new Date(`${a.date}T${a.startTime}`).getTime() -
        new Date(`${b.date}T${b.startTime}`).getTime()
      );
    });
  });

  activeResults = computed(() =>
    this.results().filter((a) => a.appointmentState !== 'CANCELADA')
  );

  errorMessage = signal('');

  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService.getAllAppointments().subscribe({
      next: (data) => {
        this.appointments.set(data);
      },
      error: () => {
        this.errorMessage.set(
          'No se pudieron cargar las citas. Intente más tarde.'
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
        this.appointments.set(
          this.appointments().map((a) =>
            a.idAppointment === appointmentId
              ? { ...a, appointmentState: 'CANCELADA' }
              : a
          )
        );
      },
      error: () =>
        this.showToast('Ocurrió un error al cancelar la cita', 'error'),
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
