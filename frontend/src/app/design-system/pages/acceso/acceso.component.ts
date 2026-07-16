import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import {
  LucideCalendar,
  LucideClipboardCheck,
  LucideSettings,
  LucideStethoscope,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';

@Component({
  selector: 'app-acceso',
  standalone: true,
  imports: [
    LucideCalendar,
    LucideClipboardCheck,
    LucideSettings,
    LucideStethoscope,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './acceso.component.html',
})
export class AccesoComponent {
  private keycloak = inject(Keycloak);

  login(redirectPath: string): void {
    this.keycloak.login({
      redirectUri: window.location.origin + redirectPath,
    });
  }
}
