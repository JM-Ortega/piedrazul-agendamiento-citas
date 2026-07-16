import {
  Component,
  EventEmitter,
  Output,
  inject,
  ChangeDetectionStrategy,
} from '@angular/core';

import { Router } from '@angular/router';
import {
  LucideX,
  LucideLogIn,
  LucideUserPlus,
  LucideMessageCircle,
  LucidePhone,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-appointment-modal',
  standalone: true,
  imports: [
    LucideX,
    LucideLogIn,
    LucideUserPlus,
    LucideMessageCircle,
    LucidePhone,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './appointment-modal.component.html',
})
export class AppointmentModalComponent {
  @Output() finish = new EventEmitter<void>();

  private keycloak = inject(Keycloak);
  private router = inject(Router);

  readonly whatsappUrl = `https://wa.me/${environment.contact.whatsapp}`;
  readonly whatsappDisplay = environment.contact.whatsappDisplay;

  readonly phoneUrl = `tel:${environment.contact.phone}`;
  readonly phoneDisplay = environment.contact.phoneDisplay;

  login(): void {
    this.keycloak.login({
      redirectUri: window.location.origin + '/paciente/agendar',
    });
  }

  register(): void {
    this.router.navigate(['/registro']);
    this.finish.emit();
  }

  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.finish.emit();
    }
  }
}
