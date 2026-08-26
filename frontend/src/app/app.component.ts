import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { AppHealthService } from './core/services/app-health.service';
import { NavbarComponent } from './shared/components/navbar/navbar.component';

type BootstrapState = 'loading' | 'ready' | 'keycloak-error' | 'backend-error';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavbarComponent],
  templateUrl: './app.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.component.css',
})
export class AppComponent {
  title = 'GestionDeCitas';

  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);
  private appHealth = inject(AppHealthService);

  /** Se activa a los 10s si Keycloak aún no ha respondido (Ready ni error). */
  private timedOut = signal(false);

  bootstrapState = computed<BootstrapState>(() => {
    const event = this.keycloakEvent();

    if (
      event.type === KeycloakEventType.AuthError ||
      event.type === KeycloakEventType.AuthRefreshError
    ) {
      return 'keycloak-error';
    }

    if (event.type !== KeycloakEventType.Ready) {
      return this.timedOut() ? 'keycloak-error' : 'loading';
    }

    // Keycloak ya respondió: ahora el backend manda el estado.
    if (this.appHealth.backendUnreachable()) {
      return 'backend-error';
    }

    return 'ready';
  });
  private readonly KEYCLOAK_TIMEOUT_MS = 30000;

  constructor() {
    setTimeout(() => {
      // Se ejecuta UNA sola vez. Si a los 30s seguimos sin Ready/AuthError,
      // mostramos el error — no hay reintentos automáticos después de esto,
      // solo el botón "Reintentar" (que recarga la página por completo).
      this.timedOut.set(true);
    }, this.KEYCLOAK_TIMEOUT_MS);
  }
  retry(): void {
    window.location.reload();
  }
}
