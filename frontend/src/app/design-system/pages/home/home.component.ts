import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';

import {
  LucideCalendar,
  LucideClock,
  LucidePhone,
  LucideShield,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';
import { AppointmentModalComponent } from '../../organisms/appointment-modal/appointment-modal.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [
    LucideCalendar,
    LucideClock,
    LucidePhone,
    LucideShield,
    AppointmentModalComponent,
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './home.component.html',
})
export class HomeComponent {
  private keycloak = inject(Keycloak);
  private router = inject(Router);

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
