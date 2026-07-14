import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { Router } from '@angular/router';
import { AppService } from '../../../../core/services/app.service';
import { PatientService } from '../../../../core/services/patient.service';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { AppointmentConfirmedEvent } from '../../models/interfaces/appointmentConfirmedEvent.model';
import { PatientAppointmentService } from '../../services/PatientApointment.service';
import { ArrowLeft, LucideAngularModule } from 'lucide-angular';

@Component({
  selector: 'app-patient-new-appointment',
  standalone: true,
  imports: [LucideAngularModule, AppointmentBookingComponent],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './patient-new-appointment.component.html',
})
export class PatientNewAppointmentComponent implements OnInit {
  protected appService = inject(AppService);
  private patientService = inject(PatientService);
  private appointmentService = inject(PatientAppointmentService);
  private router = inject(Router);
  private currentPatient = signal<Patient | null>(null);
  protected isNewPatient = signal<boolean>(false);

  readonly ArrowLeft = ArrowLeft;

  /**
   * Snapshot que se pasa al componente atómico.
   * Combina los datos del backend con los datos mínimos del token en caso de que el paciente aún no exista en la base de datos.
   */
  readonly patientSnapshot = computed<Partial<Patient>>(() => {
    const patient = this.currentPatient();
    return {
      id: patient?.id ?? this.appService.keycloakId() ?? undefined,
      firstName: patient?.firstName ?? this.appService.firstName(),
      lastName: patient?.lastName ?? this.appService.lastName(),
      documentNumber: patient?.documentNumber,
      documentType: patient?.documentType,
      phone: patient?.phone,
      gender: patient?.gender,
      birthDate: patient?.birthDate,
      email: patient?.email,
    };
  });

  ngOnInit(): void {
    this.patientService.getMe().subscribe({
      next: (patient) => {
        this.currentPatient.set(patient);

        if (patient?.id) {
          this.appointmentService.hasAppointments(patient.id).subscribe({
            next: (isNew) => this.isNewPatient.set(isNew),
            error: () => this.isNewPatient.set(false),
          });
        }
      },
      error: () => {
        this.isNewPatient.set(false);
      },
    });
  }

  onAppointmentConfirmed(event: AppointmentConfirmedEvent): void {
    this.appointmentService
      .getAppointmentsByPatient(event.patientId)
      .subscribe((appts) => this.appointmentService.appointments.set(appts));
  }

  goBack(): void {
    this.router.navigate(['/paciente']);
  }
}
