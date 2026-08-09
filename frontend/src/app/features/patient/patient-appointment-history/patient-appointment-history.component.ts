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

@Component({
  selector: 'app-patient-appointment-history',
  templateUrl: './patient-appointment-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideCalendarDays, LucideClock, CommonModule, FormatoPipe],
})
export class PatientAppointmentHistoryComponent implements OnInit {
  private patientService = inject(PatientService);
  private appointmentService = inject(PatientAppointmentService);

  isLoading = signal(false);
  errorMessage = signal('');

  readonly statusLabels = APPOINTMENT_STATUS_LABELS;
  readonly statusClasses = APPOINTMENT_STATUS_CLASSES;

  readonly pastAppointments = computed<AppointmentsPatient[]>(() =>
    this.appointmentService
      .appointments()
      .filter((a) => a.appointmentState !== 'AGENDADA')
  );

  ngOnInit(): void {
    this.isLoading.set(true);

    this.patientService.getMe().subscribe({
      next: (patient) => {
        this.appointmentService
          .loadAppointments({ idPatient: patient.id })
          .subscribe({
            next: () => this.isLoading.set(false),
            error: () => {
              this.errorMessage.set(
                'No se pudieron cargar las citas. Intente más tarde.'
              );
              this.isLoading.set(false);
            },
          });
      },
      error: () => {
        this.errorMessage.set(
          'No se pudo obtener la información del paciente.'
        );
        this.isLoading.set(false);
      },
    });
  }

  getMonthShort(dateStr: string): string {
    return getMonthShort(dateStr);
  }
}
