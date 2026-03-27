import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Doctor } from '../models/doctor.model';
import { dtoSchedule } from '../models/DTOs/dtoSchedule.model';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private http = inject(HttpClient);
  private apiUrl = 'http://localhost:8080/api';

  // ── Doctors ──────────────────────────────────────────────────────────────

  getDoctors(): Observable<Doctor[]> {
    return this.http.get<Doctor[]>(`${this.apiUrl}/doctor/doctors/detailed`);
  }

  updateAppointmentInterval(
    doctorId: string,
    appointmentInterval: number,
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/appointment-interval`,
      null,
      { params: { appointmentInterval } },
    );
  }

  updateLaborStart(doctorId: string, laborStart: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/labor-start`,
      null,
      { params: { laborStart } },
    );
  }

  updateLaborEnd(doctorId: string, laborEnd: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/labor-end`,
      null,
      { params: { laborEnd } },
    );
  }

  enableDoctor(
    doctorId: string,
    laborStart: string,
    laborEnd: string,
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/enable`,
      null,
      { params: { laborStart, laborEnd } },
    );
  }

  disableDoctor(doctorId: string, force = false): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/disable`,
      null,
      { params: { force } },
    );
  }

  // ── Schedules ─────────────────────────────────────────────────────────────

  getSchedulesByDoctor(doctorId: string): Observable<dtoSchedule[]> {
    return this.http.get<dtoSchedule[]>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
    );
  }

  createSchedule(
    doctorId: string,
    workday: string,
    startTime: string,
    endTime: string,
  ): Observable<dtoSchedule> {
    return this.http.post<dtoSchedule>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
      { startTime, endTime, workday },
    );
  }

  updateSchedule(
    doctorId: string,
    workday: string,
    startTime: string,
    endTime: string,
  ): Observable<dtoSchedule> {
    return this.http.put<dtoSchedule>(
      `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`,
      { startTime, endTime, workday },
    );
  }
}
