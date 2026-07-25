import {
  Component,
  inject,
  OnInit,
  ChangeDetectionStrategy,
} from '@angular/core';
import { Router } from '@angular/router';
import { ButtonComponent } from '../../atoms/button/button.component';
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
    ButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.component.html',
})
export class HomeComponent implements OnInit {
  private keycloak = inject(Keycloak);
  private router = inject(Router);

  showModal = false;

  ngOnInit(): void {
    if (this.keycloak.authenticated) {
      this.redirectByUserRole();
    }
  }

  agendarCita(): void {
    if (this.keycloak.authenticated) {
      this.router.navigate(['/paciente/agendar']);
    } else {
      this.showModal = true;
    }
  }

  goAcceso(): void {
    if (this.keycloak.authenticated) {
      this.redirectByUserRole();
    } else {
      this.keycloak.login({
        redirectUri: window.location.origin + '/',
      });
    }
  }

  private redirectByUserRole(): void {
    const realmRoles = this.keycloak.realmAccess?.roles || [];

    switch (true) {
      case realmRoles.includes('DOCTOR'):
        this.router.navigate(['/medico'], { replaceUrl: true });
        break;

      case realmRoles.includes('SCHEDULER'):
        this.router.navigate(['/agendador'], { replaceUrl: true });
        break;

      case realmRoles.includes('ADMIN'):
        this.router.navigate(['/admin'], { replaceUrl: true });
        break;

      case realmRoles.includes('PATIENT'):
        this.router.navigate(['/paciente'], { replaceUrl: true });
        break;

      default:
        break;
    }
  }
}
