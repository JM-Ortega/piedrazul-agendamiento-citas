import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import {
  Calendar,
  Clock,
  LucideAngularModule,
  Phone,
  Shield,
} from 'lucide-angular';
import Keycloak from 'keycloak-js';
import { AppointmentModalComponent } from '../../components/appointment-modal/appointment-modal.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [LucideAngularModule, CommonModule, AppointmentModalComponent],
  templateUrl: './home.component.html',
})
export class HomeComponent {
  private keycloak = inject(Keycloak);
  private router = inject(Router);

  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly Shield = Shield;
  readonly Phone = Phone;

  showModal = false;

  agendarCita(): void {
    if (this.keycloak.authenticated) {
      this.router.navigate(['/paciente/agendar']);
    } else {
      this.showModal = true;
    }
  }

  goAcceso(): void {
    if (this.keycloak.authenticated) {
      this.router.navigate(['/acceso']);
    } else {
      this.keycloak.login({
        redirectUri: window.location.origin + '/acceso',
      });
    }
  }
}
