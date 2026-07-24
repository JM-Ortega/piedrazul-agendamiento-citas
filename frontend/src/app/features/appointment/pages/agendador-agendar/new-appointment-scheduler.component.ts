import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';
import { LucideArrowLeft } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button.component';

@Component({
  selector: 'app-new-appointment-scheduler',
  standalone: true,
  imports: [LucideArrowLeft, AppointmentBookingComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-appointment-scheduler.component.html',
})
export class NewAppointmentSchedulerComponent {
  private router = inject(Router);

  goToScheduler(): void {
    this.router.navigate(['/agendador']);
  }
}
