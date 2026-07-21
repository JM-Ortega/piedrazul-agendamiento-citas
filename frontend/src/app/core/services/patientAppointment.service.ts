import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';

/**
 * Servicio de citas del paciente.
 */
@Injectable({ providedIn: 'root' })
export class PatientAppointmentService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  readonly appointments = signal<AppointmentsPatient[]>([]);

  private readonly CACHE_TTL_MS = 3 * 60 * 1000; // 3 minutos
  private readonly CACHE_KEY = 'me';
  private appointmentsCache = new Map<
    string,
    { data: AppointmentsPatient[]; ts: number }
  >();

  private isFresh(ts: number): boolean {
    return Date.now() - ts < this.CACHE_TTL_MS;
  }

  /**
   * Carga las citas del paciente autenticado. Si ya se pidieron hace menos
   * de 3 minutos, devuelve el dato cacheado sin hacer la petición http
   */
  loadMyAppointments(): Observable<AppointmentsPatient[]> {
    const cached = this.appointmentsCache.get(this.CACHE_KEY);
    if (cached && this.isFresh(cached.ts)) {
      return of(cached.data);
    }
    return this.getMyAppointments().pipe(
      tap((data) => {
        this.appointments.set(data);
        this.appointmentsCache.set(this.CACHE_KEY, { data, ts: Date.now() });
      })
    );
  }

  /**
   * Invalida la caché para forzar una consulta fresca
   */
  invalidateCache(): void {
    this.appointmentsCache.delete(this.CACHE_KEY);
  }

  /**
   * Actualiza el estado de una cita en el signal y en la entrada
   * cacheada, para que ambos queden sincronizados
   */
  patchAppointmentStatus(
    appointmentId: string,
    newState: AppointmentsPatient['appointmentState']
  ): void {
    const updated = this.appointments().map((a) =>
      a.idAppointment === appointmentId
        ? { ...a, appointmentState: newState }
        : a
    );
    this.appointments.set(updated);

    const cached = this.appointmentsCache.get(this.CACHE_KEY);
    if (cached) {
      this.appointmentsCache.set(this.CACHE_KEY, {
        data: updated,
        ts: cached.ts,
      });
    }
  }

  private getMyAppointments(): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(
      `${this.apiUrl}/appointments/me`
    );
  }

  hasAppointments(patientId: string): Observable<boolean> {
    return this.http.get<boolean>(
      `${this.apiUrl}/appointments/${patientId}/is-new-patient`
    );
  }

  cancelAppointment(appointmentId: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/cancel`,
      {}
    );
  }
}
