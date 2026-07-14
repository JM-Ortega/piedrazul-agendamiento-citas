import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { AppointmentConfirmedEvent } from '../../models/interfaces/appointmentConfirmedEvent.model';
import { ArrowLeft, LucideAngularModule } from 'lucide-angular';

@Component({
  selector: 'app-new-appointment-scheduler',
  standalone: true,
  imports: [LucideAngularModule, AppointmentBookingComponent],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './new-appointment-scheduler.component.html',
})
export class NewAppointmentSchedulerComponent {
  private router = inject(Router);
  readonly ArrowLeft = ArrowLeft;

  onAppointmentConfirmed(_event: AppointmentConfirmedEvent): void {}

  goToScheduler(): void {
    this.router.navigate(['/agendador']);
  }
}
