import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { LucideArrowLeft } from '@lucide/angular';
import { AppService } from '../../../../core/services/app.service';
import { PatientService } from '../../../../core/services/patient.service';
import { PatientAppointmentService } from '../../../../core/services/patientAppointment.service';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';

@Component({
  selector: 'app-patient-new-appointment',
  standalone: true,
  imports: [LucideArrowLeft, AppointmentBookingComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './patient-new-appointment.component.html',
})
export class PatientNewAppointmentComponent implements OnInit {
  protected appService = inject(AppService);
  private patientService = inject(PatientService);
  private appointmentService = inject(PatientAppointmentService);
  private router = inject(Router);
  private currentPatient = signal<Patient | null>(null);
  protected isNewPatient = signal<boolean>(false);

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
      identification: patient?.identification,
      identificationType: patient?.identificationType,
      phone: patient?.phone,
      sex: patient?.sex,
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

  goBack(): void {
    this.router.navigate(['/paciente']);
  }
}
