import { HttpClient } from '@angular/common/http';
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

  private isFresh(ts: number): boolean {
    return Date.now() - ts < this.DOCTORS_CACHE_TTL_MS;
  }

  getDoctors(): Observable<dtoDoctor[]> {
    const cached = this.doctorsCache.get(this.DOCTORS_CACHE_KEY);
    if (cached && this.isFresh(cached.ts)) {
      return of(cached.data);
    }
    return this.http.get<dtoDoctor[]>(`${this.apiUrl}/doctor/doctors`).pipe(
      tap((data) =>
        this.doctorsCache.set(this.DOCTORS_CACHE_KEY, {
          data,
          ts: Date.now(),
        })
      )
    );
  }

  getAllAppointments(): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`);
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
}
