import { Injectable, signal } from '@angular/core';

/**
 * Estado global de conectividad con el backend, alimentado por
 * `errorInterceptor`. Distinto del estado de autenticación de Keycloak:
 * este servicio solo refleja si el backend responde o no.
 */
@Injectable({ providedIn: 'root' })
export class AppHealthService {
  /** True cuando la última petición falló por falta total de conexión (NETWORK_ERROR/OFFLINE). */
  readonly backendUnreachable = signal(false);

  reportUnreachable(): void {
    this.backendUnreachable.set(true);
  }

  /** Se llama cuando una petición sí obtiene respuesta, para recuperar el estado automáticamente. */
  reportReachable(): void {
    if (this.backendUnreachable()) {
      this.backendUnreachable.set(false);
    }
  }
}
