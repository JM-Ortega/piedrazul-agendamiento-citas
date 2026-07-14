import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import {
  Calendar,
  ClipboardCheck,
  LucideAngularModule,
  Settings,
  Stethoscope,
} from 'lucide-angular';
import Keycloak from 'keycloak-js';

@Component({
  selector: 'app-acceso',
  standalone: true,
  imports: [LucideAngularModule],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './acceso.component.html',
})
export class AccesoComponent {
  private keycloak = inject(Keycloak);

  readonly Calendar = Calendar;
  readonly ClipboardCheck = ClipboardCheck;
  readonly Stethoscope = Stethoscope;
  readonly Settings = Settings;

  login(redirectPath: string): void {
    this.keycloak.login({
      redirectUri: window.location.origin + redirectPath,
    });
  }
}
