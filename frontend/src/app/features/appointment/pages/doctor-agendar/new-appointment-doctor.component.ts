import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { LucideArrowLeft } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { AppointmentBookingComponent } from '../../components/orquestador-agendamiento/appointment-booking.component';

interface NewAppointmentDoctorNavState {
  documentNumber?: string;
  specialty?: string;
  idDoctor?: string;
}

/**
 * Punto de entrada para el flujo de agendamiento desde el dashboard del
 * médico. Recibe documento del paciente, especialidad e id del médico
 * (para reagendar con el mismo médico y especialidad de una cita previa)
 * a través del estado de navegación (`router.navigate(..., { state })`),
 * no como query params, para no exponer estos datos en la URL.
 *
 * El nombre del médico no se recibe: se obtiene de la lista real de
 * médicos de la especialidad ya cargada en el flujo, haciendo match por
 * `idDoctor` — más confiable que confiar en un string que viajó aparte.
 */
@Component({
  selector: 'app-new-appointment-doctor',
  standalone: true,
  imports: [LucideArrowLeft, AppointmentBookingComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-appointment-doctor.component.html',
})
export class NewAppointmentDoctorComponent implements OnInit {
  private router = inject(Router);

  patientDocument = signal('');
  prefillSpecialty = signal('');
  prefillDoctorId = signal('');

  private readonly STORAGE_KEY = 'new_appointment_doctor_state';

  constructor() {
    const navState = this.router.getCurrentNavigation()?.extras.state as
      NewAppointmentDoctorNavState | undefined;

    if (navState) {
      this.applyState(navState);
      // Guardar la copia de respaldo en sessionStorage
      sessionStorage.setItem(this.STORAGE_KEY, JSON.stringify(navState));
    }
  }

  ngOnInit(): void {
    // Si no hay datos (F5/recarga), recuperar de sessionStorage
    if (!this.patientDocument()) {
      const savedState = sessionStorage.getItem(this.STORAGE_KEY);
      if (savedState) {
        try {
          const parsedState = JSON.parse(
            savedState
          ) as NewAppointmentDoctorNavState;
          this.applyState(parsedState);
        } catch {
          this.handleMissingData();
        }
      } else {
        this.handleMissingData();
      }
    }
  }

  goToDashboard(): void {
    // Limpiar el storage
    sessionStorage.removeItem(this.STORAGE_KEY);
    this.router.navigate(['/medico']);
  }

  private handleMissingData(): void {
    // Si no hay información mínima obligatoria, redirige al dashboard
    this.goToDashboard();
  }

  private applyState(state: NewAppointmentDoctorNavState): void {
    this.patientDocument.set(state.documentNumber ?? '');
    this.prefillSpecialty.set(state.specialty ?? '');
    this.prefillDoctorId.set(state.idDoctor ?? '');
  }
}
