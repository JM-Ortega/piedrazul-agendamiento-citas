import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
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

  /** Se activa a los 30s si Keycloak aún no ha respondido (Ready ni error). */
  private timedOut = signal(false);

  /** True una vez que la app llegó a 'ready' por primera vez. A partir de
   * ahí, eventos de refresh de token (éxito o error puntual) ya no deben
   * tumbar toda la UI — eso lo maneja errorInterceptor en la próxima
   * petición HTTP que falle con 401. */
  private hasBeenReady = signal(false);

  bootstrapState = computed<BootstrapState>(() => {
    const event = this.keycloakEvent();

    if (this.hasBeenReady()) {
      return this.appHealth.backendUnreachable() ? 'backend-error' : 'ready';
    }

    if (
      event.type === KeycloakEventType.AuthError ||
      event.type === KeycloakEventType.AuthRefreshError
    ) {
      return 'keycloak-error';
    }

    if (event.type !== KeycloakEventType.Ready) {
      return this.timedOut() ? 'keycloak-error' : 'loading';
    }

    return this.appHealth.backendUnreachable() ? 'backend-error' : 'ready';
  });

  private readonly KEYCLOAK_TIMEOUT_MS = 30000;

  constructor() {
    effect(() => {
      if (this.keycloakEvent().type === KeycloakEventType.Ready) {
        this.hasBeenReady.set(true);
      }
    });

    setTimeout(() => {
      this.timedOut.set(true);
    }, this.KEYCLOAK_TIMEOUT_MS);
  }

  retry(): void {
    window.location.reload();
  }
}
