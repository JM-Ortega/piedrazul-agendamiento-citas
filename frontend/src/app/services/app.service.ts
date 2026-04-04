import { Injectable, inject, computed } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import Keycloak from 'keycloak-js';
import {
  KEYCLOAK_EVENT_SIGNAL,
  KeycloakEventType,
  ReadyArgs,
} from 'keycloak-angular';

@Injectable({ providedIn: 'root' })
export class AppService {
  private keycloak = inject(Keycloak);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);
  private router = inject(Router);

  private currentUrl = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map((e) => (e as NavigationEnd).urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  readonly isAuthenticated = computed(() => {
    const event = this.keycloakEvent();

    return event.type === KeycloakEventType.Ready
      ? ((event.args as ReadyArgs) ?? false)
      : (this.keycloak.authenticated ?? false);
  });

  readonly currentRole = computed<string | null>(() => {
    this.keycloakEvent();
    if (!this.keycloak.authenticated) return null;
    const roles = this.keycloak.realmAccess?.roles ?? [];
    if (roles.includes('ADMIN')) return 'ADMIN';
    if (roles.includes('SCHEDULER')) return 'SCHEDULER';
    if (roles.includes('DOCTOR')) return 'DOCTOR';
    if (roles.includes('PATIENT')) return 'PATIENT';
    return null;
  });

  readonly firstName = computed<string>(() => {
    this.keycloakEvent();
    return this.keycloak.tokenParsed?.['given_name'] ?? '';
  });

  readonly lastName = computed<string>(() => {
    this.keycloakEvent();
    return this.keycloak.tokenParsed?.['family_name'] ?? '';
  });

  readonly fullName = computed<string>(() => {
    this.keycloakEvent();
    return this.keycloak.tokenParsed?.['name'] ?? '';
  });

  readonly keycloakId = computed<string | null>(() => {
    this.keycloakEvent();
    return this.keycloak.tokenParsed?.['sub'] ?? null;
  });

  hasRole(role: string): boolean {
    this.keycloakEvent();
    return this.keycloak.realmAccess?.roles?.includes(role) ?? false;
  }

  readonly activeRoleLabel = computed<string>(() => {
    this.keycloakEvent();
    const url = this.currentUrl();
    if (url.startsWith('/admin')) return 'Administrador';
    if (url.startsWith('/agendador')) return 'Agendador';
    if (url.startsWith('/paciente')) return 'Paciente';
    if (url.startsWith('/medico')) return 'Médico';
    return this.roleLabel();
  });

  private roleLabel(): string {
    const roles = this.keycloak.realmAccess?.roles ?? [];
    if (roles.includes('ADMIN')) return 'Administrador';
    if (roles.includes('SCHEDULER')) return 'Agendador';
    if (roles.includes('DOCTOR')) return 'Médico';
    if (roles.includes('PATIENT')) return 'Paciente';
    return '';
  }

  logout(): void {
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
