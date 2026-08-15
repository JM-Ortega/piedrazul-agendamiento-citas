import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentExportRequest } from '../../shared/models/dtos/AppointmentExportRequest.dto';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { dtoDoctor } from '../../shared/models/dtos/doctor.dto';

@Injectable({ providedIn: 'root' })
export class SchedulerService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  private readonly DOCTORS_CACHE_TTL_MS = 20 * 60 * 1000; // 20 minutos
  private readonly DOCTORS_CACHE_KEY = 'all';
  private doctorsCache = new Map<string, { data: dtoDoctor[]; ts: number }>();
  private statesCache: string[] | null = null;

  private isFresh(ts: number): boolean {
    return Date.now() - ts < this.DOCTORS_CACHE_TTL_MS;
  }

  getDoctors(): Observable<dtoDoctor[]> {
    const cached = this.doctorsCache.get(this.DOCTORS_CACHE_KEY);
    if (cached && this.isFresh(cached.ts)) {
      return of(cached.data);
    }
    return this.http.get<dtoDoctor[]>(`${this.apiUrl}/doctor`).pipe(
      tap((data) =>
        this.doctorsCache.set(this.DOCTORS_CACHE_KEY, {
          data,
          ts: Date.now(),
        })
      )
    );
  }

  getStates(): Observable<string[]> {
    if (this.statesCache) {
      return of(this.statesCache);
    }
    return this.http
      .get<string[]>(`${this.apiUrl}/appointments/list-all-states`)
      .pipe(tap((data) => (this.statesCache = data)));
  }

  getAllAppointments(params?: {
    idDoctor?: string;
    date?: string;
    state?: string;
  }): Observable<AppointmentsPatient[]> {
    let httpParams = new HttpParams();
    if (params?.idDoctor)
      httpParams = httpParams.set('idDoctor', params.idDoctor);
    if (params?.date) httpParams = httpParams.set('date', params.date);
    if (params?.state) httpParams = httpParams.set('state', params.state);

    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: httpParams,
    });
  }

  getAppointmentsByDoctor(doctorId: string): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId },
    });
  }

  exportAppointments(payload: AppointmentExportRequest): Observable<Blob> {
    return this.http.post(
      `${this.apiUrl}/reports/appointments/export`,
      payload,
      {
        responseType: 'blob',
      }
    );
  }
  exportScheduler(payload: AppointmentExportRequest): Observable<Blob> {
    return this.http.post(`${this.apiUrl}/reports/scheduler/export`, payload, {
      responseType: 'blob',
    });
  }
  checkSchedulerAvailability(
    date: string
  ): Observable<{ hasAvailabilitySlots: boolean }> {
    return this.http.get<{ hasAvailabilitySlots: boolean }>(
      `${this.apiUrl}/reports/scheduler/availability?date=${date}`
    );
  }

  /**
   * Borra toda la caché en memoria al cerrar sesión
   */
  clearAllData(): void {
    this.doctorsCache.clear();
  }
}
