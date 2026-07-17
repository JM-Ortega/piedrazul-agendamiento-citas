import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { LucideArrowLeft } from '@lucide/angular';

@Component({
  selector: 'app-new-appointment-scheduler',
  standalone: true,
  imports: [LucideArrowLeft, AppointmentBookingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-appointment-scheduler.component.html',
})
export class NewAppointmentSchedulerComponent {
  private router = inject(Router);

  goToScheduler(): void {
    this.router.navigate(['/agendador']);
  }
}
