import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Output,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideLogIn,
  LucideMessageCircle,
  LucideUserPlus,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';
import { environment } from '../../../../environments/environment';
import { ButtonComponent } from '../../atoms/button/button.component';

@Component({
  selector: 'app-appointment-modal',
  standalone: true,
  imports: [LucideLogIn, LucideUserPlus, LucideMessageCircle, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './appointmentModal.component.html',
})
export class AppointmentModalComponent {
  @Output() finish = new EventEmitter<void>();

  private keycloak = inject(Keycloak);
  private router = inject(Router);

  // wa.me exige el número en formato internacional (57 = Colombia).
  readonly whatsappUrl = `https://wa.me/57${environment.contact.whatsapp}`;
  readonly phoneWhatsappUrl = `https://wa.me/57${environment.contact.phone}`;
  readonly whatsappDisplay = environment.contact.whatsappDisplay;

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
