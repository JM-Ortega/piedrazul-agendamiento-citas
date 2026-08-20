import { Injectable, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import {
  KEYCLOAK_EVENT_SIGNAL,
  KeycloakEventType,
  ReadyArgs,
} from 'keycloak-angular';
import Keycloak from 'keycloak-js';
import { filter, map } from 'rxjs';
import { DoctorService } from './doctor.service';
import { PatientService } from './patient.service';
import { PatientAppointmentService } from './patientAppointment.service';
import { SchedulerService } from './scheduler.service';

@Injectable({ providedIn: 'root' })
export class AppService {
  private keycloak = inject(Keycloak);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);
  private router = inject(Router);
  private doctorService = inject(DoctorService);
  private patientService = inject(PatientService);
  private patientAppointmentService = inject(PatientAppointmentService);
  private schedulerService = inject(SchedulerService);

  private currentUrl = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map((e) => (e as NavigationEnd).urlAfterRedirects)
    ),
    { initialValue: this.router.url }
  );

  // ── Rol activo seleccionado manualmente ───────────────────────────────────
  private _activeRole = signal<string | null>(null);

  readonly isAuthenticated = computed(() => {
    const event = this.keycloakEvent();
    return event.type === KeycloakEventType.Ready
      ? ((event.args as ReadyArgs) ?? false)
      : (this.keycloak.authenticated ?? false);
  });

  // Todos los roles del usuario (solo los del negocio)
  readonly allRoles = computed<string[]>(() => {
    this.keycloakEvent();
    if (!this.keycloak.authenticated) return [];
    const roles = this.keycloak.realmAccess?.roles ?? [];
    return roles.filter((r) =>
      ['ADMIN', 'SCHEDULER', 'DOCTOR', 'PATIENT'].includes(r)
    );
  });

  readonly hasMultipleRoles = computed(() => this.allRoles().length > 1);

  // Rol activo: el seleccionado manualmente o el primero disponible
  readonly currentRole = computed<string | null>(() => {
    this.keycloakEvent();
    if (!this.keycloak.authenticated) return null;
    const roles = this.allRoles();
    if (roles.length === 0) return null;
    const active = this._activeRole();
    if (active && roles.includes(active)) return active;
    // Si no hay selección manual, inferir por URL
    const url = this.currentUrl();
    if (url.startsWith('/admin') && roles.includes('ADMIN')) return 'ADMIN';
    if (url.startsWith('/agendador') && roles.includes('SCHEDULER'))
      return 'SCHEDULER';
    if (url.startsWith('/medico') && roles.includes('DOCTOR')) return 'DOCTOR';
    if (url.startsWith('/paciente') && roles.includes('PATIENT'))
      return 'PATIENT';
    return roles[0];
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
    return this.currentRole() === role;
  }

  // Cambiar rol activo y navegar a su ruta principal
  switchRole(role: string): void {
    if (!this.allRoles().includes(role)) return;
    this._activeRole.set(role);
    const routeMap: Record<string, string> = {
      ADMIN: '/admin',
      SCHEDULER: '/agendador',
      DOCTOR: '/medico',
      PATIENT: '/paciente',
    };
    this.router.navigate([routeMap[role] ?? '/']);
  }

  readonly activeRoleLabel = computed<string>(() => {
    this.keycloakEvent();
    const url = this.currentUrl();
    if (url.startsWith('/admin')) return 'Administrador';
    if (url.startsWith('/agendador')) return 'Agendador';
    if (url.startsWith('/paciente')) return 'Paciente';
    if (url.startsWith('/medico')) return 'Médico';
    return this.roleLabelFor(this.currentRole() ?? '');
  });

  roleLabelFor(role: string): string {
    const map: Record<string, string> = {
      ADMIN: 'Administrador',
      SCHEDULER: 'Agendador',
      DOCTOR: 'Médico',
      PATIENT: 'Paciente',
    };
    return map[role] ?? '';
  }

  /**
   * Realiza el cierre de sesión seguro del usuario.
   *
   * Limpia el estado en memoria de los servicios Singleton (Signals y cachés),
   * elimina cualquier dato persistido en el almacenamiento local del navegador
   * y redirige al flujo de cierre de sesión unificado con Keycloak.
   */
  logout(): void {
    // 1. Purga de datos sensibles y estado en memoria de los servicios Singleton
    this.doctorService.clearAllData();
    this.patientService.clearAllData();
    this.patientAppointmentService.clearAllData();
    this.schedulerService.clearAllData();
    // 2. Limpieza de storages del navegador (tokens locales, flags de sesión, etc.)
    localStorage.clear();
    sessionStorage.clear();
    // 3. Redirección y destrucción de la sesión en el servidor de identidad (Keycloak)
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
  async refreshRoles(): Promise<void> {
    try {
      await this.keycloak.updateToken(-1);
    } catch {
      // silencioso
    }
  }
}
