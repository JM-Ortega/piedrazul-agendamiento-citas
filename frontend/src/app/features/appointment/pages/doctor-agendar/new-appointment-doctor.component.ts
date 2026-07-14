import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { AppointmentConfirmedEvent } from '../../models/interfaces/appointmentConfirmedEvent.model';
import { ArrowLeft, LucideAngularModule } from 'lucide-angular';

/**
 * Punto de entrada para el flujo de agendamiento desde el dashboard del médico.
 * Recibe el número de documento del paciente como query param, lo escribe en el estado compartido
 * y muestra el flujo de agendamiento (BookingSpecialtySelector, BookingScheduleSelector, BookingConfirm)
 */
@Component({
  selector: 'app-new-appointment-doctor',
  standalone: true,
  imports: [LucideAngularModule, AppointmentBookingComponent],
  templateUrl: './new-appointment-doctor.component.html',
})
export class NewAppointmentDoctorComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  readonly ArrowLeft = ArrowLeft;

  patientDocument = signal<string>('');

  ngOnInit(): void {
    const docNumber =
      this.route.snapshot.queryParamMap.get('documentNumber') ?? '';
    this.patientDocument.set(docNumber);
  }

  onAppointmentConfirmed(event: AppointmentConfirmedEvent): void {}

  goToDashboard(): void {
    this.router.navigate(['/medico']);
  }
}
