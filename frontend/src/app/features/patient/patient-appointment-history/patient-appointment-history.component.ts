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
import { AppService } from '../../../core/services/app.service';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { PatientAppointmentService } from '../../../core/services/patientAppointment.service';
import { Appointment } from '../models/interfaces/appointment.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-patient-appointment-history',
  templateUrl: './patient-appointment-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideCalendarDays, LucideClock, CommonModule, FormatoPipe],
})
export class PatientAppointmentHistoryComponent implements OnInit {
  protected appService = inject(AppService);
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

  readonly statusLabels: Record<Appointment['status'], string> = {
    AGENDADA: 'Agendada',
    ATENDIDA: 'Atendida',
    CANCELADA: 'Cancelada',
    NO_ASISTIO: 'No asistió',
    REPROGRAMADA: 'Reprogramada',
  };

  readonly statusClasses: Record<
    AppointmentsPatient['appointmentState'],
    string
  > = {
    AGENDADA: 'bg-green-100 text-green-700',
    ATENDIDA: 'bg-blue-100 text-blue-700',
    CANCELADA: 'bg-red-100 text-red-700',
    NO_ASISTIO: 'bg-orange-100 text-orange-700',
    REPROGRAMADA: 'bg-yellow-100 text-yellow-700',
  };

  readonly upcomingAppointments = computed<AppointmentsPatient[]>(() => {
    return this.appointmentService
      .appointments()
      .filter((a) => a.appointmentState !== 'AGENDADA')
      .sort((a, b) => (a.date + a.startTime > b.date + b.startTime ? 1 : -1));
  });

  ngOnInit(): void {
    this.isLoading.set(true);

    this.appointmentService.getMyAppointments().subscribe({
      next: (data) => {
        this.appointmentService.appointments.set(data);
        this.isLoading.set(false);
      },
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
