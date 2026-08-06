import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { LucideArrowLeft } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';

/**
 * Punto de entrada para el flujo de agendamiento desde el dashboard del médico.
 * Recibe el número de documento del paciente como query param, lo escribe en el estado compartido
 * y muestra el flujo de agendamiento (BookingSpecialtySelector, BookingScheduleSelector, BookingConfirm)
 */
@Component({
  selector: 'app-new-appointment-doctor',
  standalone: true,
  imports: [LucideArrowLeft, AppointmentBookingComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-appointment-doctor.component.html',
})
export class NewAppointmentDoctorComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  patientDocument = signal<string>('');

  ngOnInit(): void {
    const docNumber =
      this.route.snapshot.queryParamMap.get('documentNumber') ?? '';
    this.patientDocument.set(docNumber);
  }

  goToDashboard(): void {
    this.router.navigate(['/medico']);
  }
}
