import { Injectable, NgZone, effect, inject, signal } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AppService } from './app.service';
import Keycloak from 'keycloak-js';

const ACTIVITY_EVENTS = [
  'mousemove',
  'mousedown',
  'keydown',
  'wheel',
  'scroll',
  'touchstart',
] as const;
const CHECK_INTERVAL_MS = 10_000;
const SHARED_ACTIVITY_KEY = 'piedrazul.lastActivity';

/**
 * Detecta la inactividad del usuario autenticado y marca la sesión como
 * expirada cuando supera el límite de su rol activo
 * (`environment.session.inactivityMinutes`).
 *
 * La última actividad se comparte entre pestañas vía `localStorage`, para que
 * una pestaña olvidada no expire la sesión (compartida en Keycloak) mientras
 * el usuario trabaja en otra. También sobrevive a las recargas de página: al
 * iniciar se retoma la última actividad guardada en lugar de reiniciar el
 * contador.
 *
 * Este servicio no cierra la sesión: solo expone `expired`. La UI debe mostrar
 * el aviso y llamar a `AppService.logout()`.
 */
@Injectable({ providedIn: 'root' })
export class SessionInactivityService {
  private readonly appService = inject(AppService);
  private readonly ngZone = inject(NgZone);
  private readonly keycloak = inject(Keycloak);

  private readonly _expired = signal(false);
  /** True cuando la sesión venció por inactividad */
  readonly expired = this._expired.asReadonly();

  private watching = false;
  private lastActivity = Date.now();
  private lastPersisted = 0;
  private intervalId?: number;

  /** Inicia o detiene el monitoreo según el usuario esté autenticado o no. */
  constructor() {
    effect(() => {
      if (this.appService.isAuthenticated()) this.start();
      else this.stop();
    });
  }

  /**
   * Registra actividad del usuario. Se ignora si la sesión ya expiró, para que
   * mover el mouse no la reviva. Persiste la marca con throttle para no
   * escribir en `localStorage` en cada evento.
   */
  private readonly onActivity = (): void => {
    if (this._expired()) return;
    const now = Date.now();
    this.lastActivity = now;
    if (now - this.lastPersisted > CHECK_INTERVAL_MS) this.persist(now);
  };

  /**
   * Reevalúa al volver a la pestaña, ya que el navegador ralentiza los
   * temporizadores mientras está en segundo plano.
   */
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') this.evaluate();
  };

  /**
   * Arranca el monitoreo: fija el punto de partida, registra los listeners de
   * actividad fuera de la zona de Angular y evalúa de inmediato, para que una
   * sesión ya vencida se detecte al cargar sin esperar al primer intervalo.
   */
  private start(): void {
    if (this.watching) return;
    this.watching = true;
    this.lastActivity = this.initialActivity();
    this.persist(this.lastActivity);

    this.ngZone.runOutsideAngular(() => {
      ACTIVITY_EVENTS.forEach((e) =>
        window.addEventListener(e, this.onActivity, {
          passive: true,
          capture: true,
        })
      );
      document.addEventListener('visibilitychange', this.onVisibilityChange);
      this.intervalId = window.setInterval(
        () => this.evaluate(),
        CHECK_INTERVAL_MS
      );
    });
    this.evaluate();
  }

  /**
   * Punto de partida al iniciar (carga o recarga). Retoma la última actividad
   * guardada para que recargar no reinicie el contador; si el usuario se
   * autenticó después de esa actividad, el login cuenta como actividad.
   */
  private initialActivity(): number {
    const stored = this.readShared();
    if (!stored) return Date.now();
    const authTime =
      Number(this.keycloak.tokenParsed?.['auth_time'] ?? 0) * 1000;
    return Math.max(stored, authTime);
  }

  /**
   * Detiene el monitoreo (listeners e intervalo) y limpia el estado de
   * expiración. Se ejecuta cuando el usuario deja de estar autenticado.
   */
  private stop(): void {
    if (!this.watching) return;
    this.watching = false;
    ACTIVITY_EVENTS.forEach((e) =>
      window.removeEventListener(e, this.onActivity, { capture: true })
    );
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
    window.clearInterval(this.intervalId);
    this._expired.set(false);
  }

  /**
   * Marca la sesión como expirada si el tiempo sin actividad alcanzó el límite
   * del rol. Toma la actividad más reciente entre esta pestaña y las demás
   * (`localStorage`).
   */
  private evaluate(): void {
    if (this._expired()) return;
    const last = Math.max(this.lastActivity, this.readShared());
    if (Date.now() - last >= this.timeoutMs()) {
      this.ngZone.run(() => this._expired.set(true));
    }
  }

  /**
   * Límite de inactividad en milisegundos según el rol activo; usa `default`
   * si el rol no está configurado.
   */
  private timeoutMs(): number {
    const limits = environment.session.inactivityMinutes;
    const role = this.appService.currentRole();
    return (limits[role ?? ''] ?? limits['default']) * 60_000;
  }

  /** Guarda la última actividad en `localStorage` para compartirla entre pestañas. */
  private persist(timestamp: number): void {
    this.lastPersisted = timestamp;
    try {
      localStorage.setItem(SHARED_ACTIVITY_KEY, String(timestamp));
    } catch {
      // storage no disponible: se usa solo la actividad de esta pestaña
    }
  }

  /** Lee la última actividad compartida; devuelve 0 si no existe o no se puede leer. */
  private readShared(): number {
    try {
      return Number(localStorage.getItem(SHARED_ACTIVITY_KEY)) || 0;
    } catch {
      return 0;
    }
  }
}
