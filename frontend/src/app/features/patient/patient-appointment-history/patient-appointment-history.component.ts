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
import {
  APPOINTMENT_STATUS_LABELS,
  APPOINTMENT_STATUS_CLASSES,
} from '../../../shared/helpers/appointment-status';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-patient-appointment-history',
  templateUrl: './patient-appointment-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideCalendarDays, LucideClock, CommonModule, FormatoPipe],
})
export class PatientAppointmentHistoryComponent implements OnInit {
  private appointmentService = inject(PatientAppointmentService);

  isLoading = signal(false);
  errorMessage = signal('');

  readonly monthNames = [
    'enero',
    'febrero',
    'marzo',
    'abril',
    'mayo',
    'junio',
    'julio',
    'agosto',
    'septiembre',
    'octubre',
    'noviembre',
    'diciembre',
  ];

  readonly statusLabels = APPOINTMENT_STATUS_LABELS;
  readonly statusClasses = APPOINTMENT_STATUS_CLASSES;

  readonly pastAppointments = computed<AppointmentsPatient[]>(() => {
    return this.appointmentService
      .appointments()
      .filter((a) => a.appointmentState !== 'AGENDADA')
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
    const month = parseInt(dateStr.split('-')[1]) - 1;
    return this.monthNames[month].slice(0, 3);
  }
}
