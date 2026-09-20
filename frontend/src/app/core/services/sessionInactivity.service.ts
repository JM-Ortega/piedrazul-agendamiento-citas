import { Injectable, NgZone, effect, inject, signal } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AppService } from './app.service';

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
 * expirada cuando supera el límite de su rol activo.
 *
 * La última actividad se comparte entre pestañas vía `localStorage`, para que
 * una pestaña olvidada no expire la sesión (compartida en Keycloak) mientras
 * el usuario trabaja en otra.
 */
@Injectable({ providedIn: 'root' })
export class SessionInactivityService {
  private readonly appService = inject(AppService);
  private readonly ngZone = inject(NgZone);

  private readonly _expired = signal(false);
  /** True cuando la sesión venció por inactividad */
  readonly expired = this._expired.asReadonly();

  private watching = false;
  private lastActivity = Date.now();
  private lastPersisted = 0;
  private intervalId?: number;

  constructor() {
    effect(() => {
      if (this.appService.isAuthenticated()) this.start();
      else this.stop();
    });
  }

  private readonly onActivity = (): void => {
    if (this._expired()) return;
    const now = Date.now();
    this.lastActivity = now;
    if (now - this.lastPersisted > CHECK_INTERVAL_MS) this.persist(now);
  };

  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') this.evaluate();
  };

  private start(): void {
    if (this.watching) return;
    this.watching = true;
    const now = Date.now();
    this.lastActivity = now;
    this.persist(now);

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
  }

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

  private evaluate(): void {
    if (this._expired()) return;
    const last = Math.max(this.lastActivity, this.readShared());
    if (Date.now() - last >= this.timeoutMs()) {
      this.ngZone.run(() => this._expired.set(true));
    }
  }

  private timeoutMs(): number {
    const limits = environment.session.inactivityMinutes;
    const role = this.appService.currentRole();
    return (limits[role ?? ''] ?? limits['default']) * 60_000;
  }

  private persist(timestamp: number): void {
    this.lastPersisted = timestamp;
    try {
      localStorage.setItem(SHARED_ACTIVITY_KEY, String(timestamp));
    } catch {
      // storage no disponible: se usa solo la actividad de esta pestaña
    }
  }

  private readShared(): number {
    try {
      return Number(localStorage.getItem(SHARED_ACTIVITY_KEY)) || 0;
    } catch {
      return 0;
    }
  }
}
