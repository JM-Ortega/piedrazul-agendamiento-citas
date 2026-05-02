import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { AppointmentConfirmedEvent } from '../../../../models/interfaces/appointmentConfirmedEvent.model';

@Component({
  selector: 'app-new-appointment-scheduler',
  standalone: true,
  imports: [ CommonModule, AppointmentBookingComponent],
  templateUrl: './new-appointment-scheduler.component.html',
})
export class NewAppointmentSchedulerComponent {

  private router = inject(Router);

  onAppointmentConfirmed(_event: AppointmentConfirmedEvent): void {}

  goToScheduler(): void {
    this.router.navigate(['/agendador']);
  }
}