import { Component, EventEmitter, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import {
  LucideAngularModule,
  X,
  LogIn,
  UserPlus,
  MessageCircle,
  Phone,
} from 'lucide-angular';
import Keycloak from 'keycloak-js';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-appointment-modal',
  standalone: true,
  imports: [CommonModule, LucideAngularModule],
  templateUrl: './appointment-modal.component.html',
  styleUrl: './appointment-modal.component.css',
})
export class AppointmentModalComponent {
  @Output() close = new EventEmitter<void>();

  private keycloak = inject(Keycloak);
  private router = inject(Router);

  readonly X = X;
  readonly LogIn = LogIn;
  readonly UserPlus = UserPlus;
  readonly MessageCircle = MessageCircle;
  readonly Phone = Phone;

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
    this.close.emit();
  }

  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.close.emit();
    }
  }
}
