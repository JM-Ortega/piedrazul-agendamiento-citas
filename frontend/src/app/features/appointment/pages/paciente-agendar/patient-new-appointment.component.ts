import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { AppService } from '../../../../services/app.service';
import { PatientAppointmentService } from '../../../../services/PatientApointment.service';
import { PatientService } from '../../../../services/patient.service';
import { Patient } from '../../../../models/interfaces/patient.model';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { AppointmentConfirmedEvent } from '../../../../models/interfaces/appointmentConfirmedEvent.model';

@Component({
  selector: 'app-patient-new-appointment',
  standalone: true,
  imports: [
    CommonModule,
    AppointmentBookingComponent,
  ],
  templateUrl: './patient-new-appointment.component.html',
})
export class PatientNewAppointmentComponent implements OnInit {

  protected appService        = inject(AppService);
  private patientService      = inject(PatientService);
  private appointmentService  = inject(PatientAppointmentService);
  private router              = inject(Router);
  private currentPatient = signal<Patient | null>(null);

  /**
   * Snapshot que se pasa al componente atómico.
   * Combina los datos del backend con los datos mínimos del token en caso de que el paciente aún no exista en la base de datos.
   */
  readonly patientSnapshot = computed<Partial<Patient>>(() => { 
    const patient = this.currentPatient();
    return {
      id: patient?.id ?? this.appService.keycloakId() ?? undefined,
      firstName:      patient?.firstName      ?? this.appService.firstName(),
      lastName:       patient?.lastName       ?? this.appService.lastName(),
      documentNumber: patient?.documentNumber,
      documentType:   patient?.documentType,
      phone:          patient?.phone,
      gender:         patient?.gender,
      birthDate:      patient?.birthDate,
      email:          patient?.email,
    };
   });

  ngOnInit(): void {
    // Carga datos del paciente desde el backend usando el keycloakId del token
    this.patientService.getMe().subscribe({
      next: (patient) => this.currentPatient.set(patient),
      error: () => {
        // Si no existe el paciente en la DB aún, no bloqueamos el flujo
        // El backend lo creará con los datos mínimos del token
      },
    });
  }

  onAppointmentConfirmed(event: AppointmentConfirmedEvent): void {
    this.appointmentService.getAppointmentsByPatient(event.patientId).subscribe(appts => this.appointmentService.appointments.set(appts));
  }

  goBack(): void {
    this.router.navigate(['/paciente']);
  }
}