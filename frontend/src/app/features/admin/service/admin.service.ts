import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../../../environments/environment';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { CreateUserRequestDto } from '../models/dtos/CreateUserRequestDto';
import { dtoSchedule } from '../models/dtos/schedule.dto';
import { PagedResponse } from '../models/interfaces/PagedResponse';

import { DoctorAdminDto } from '../models/dtos/DoctorAdminDto';
import { SystemUser } from '../models/interfaces/system-user.model';
// ─────────────────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class AdminService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  // ── Doctors ──────────────────────────────────────────────────────────────
  createUser(payload: CreateUserRequestDto): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/user/users`, payload);
  }
  // ⚠️ PARCHE TEMPORAL: el backend ahora pagina este endpoint (default size=5).
  // Pedimos un tamaño grande fijo para traer "todo" mientras no se implementa
  // paginación real (botones anterior/siguiente) en el panel de admin.
  // TODO: reemplazar por paginación real cuando la cantidad de doctores crezca.
  getDoctors(): Observable<Doctor[]> {
    return this.http
      .get<PagedResponse<Doctor>>(`${this.apiUrl}/doctor/detailed`, {
        params: { size: 100 },
      })
      .pipe(map((response) => response.content));
  }

  getDoctorsAdmin(): Observable<DoctorAdminDto[]> {
    return this.http.get<DoctorAdminDto[]>(
      `${this.apiUrl}/user/system-doctors`
    );
  }
  updateAppointmentInterval(
    doctorId: string,
    appointmentInterval: number
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/appointment-interval`,
      null,
      { params: { appointmentInterval } }
    );
  }

  updateLaborDate(
    doctorId: string,
    laborStart: string,
    laborEnd: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/labor-date`,
      null,
      { params: { laborStart, laborEnd } }
    );
  }

  enableDoctor(
    doctorId: string,
    laborStart: string,
    laborEnd: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/enable`,
      null,
      { params: { laborStart, laborEnd } }
    );
  }

  disableDoctor(doctorId: string, force = false): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/disable`,
      null,
      { params: { force } }
    );
  }

  // ── Schedules ─────────────────────────────────────────────────────────────

  getSchedulesByDoctor(doctorId: string): Observable<dtoSchedule[]> {
    return this.http.get<dtoSchedule[]>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`
    );
  }

  /**
  private buildSchedulePayload(workday: string, startTime: string, endTime: string) {
  return { startTime, endTime, workday };
}

createSchedule(doctorId: string, workday: string, startTime: string, endTime: string): Observable<dtoSchedule> {
  return this.http.post<dtoSchedule>(
    `${this.apiUrl}/doctor/schedules/${doctorId}`,
    this.buildSchedulePayload(workday, startTime, endTime)
  );
}

updateSchedule(doctorId: string, workday: string, startTime: string, endTime: string): Observable<dtoSchedule> {
  return this.http.put<dtoSchedule>(
    `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`,
    this.buildSchedulePayload(workday, startTime, endTime)
  );
}

   */

  updateSchedule(
    doctorId: string,
    workday: string,
    startTime: string,
    endTime: string
  ): Observable<dtoSchedule> {
    return this.http.put<dtoSchedule>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
      { startTime, endTime, workday }
    );
  }

  deleteSchedule(doctorId: string, workday: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`
    );
  }

  // ── System Users ──────────────────────────────────────────────────────────

  getSystemUsers(): Observable<SystemUser[]> {
    return this.http.get<SystemUser[]>(`${this.apiUrl}/user/system-users`);
  }

  // ── Specialties ───────────────────────────────────────────────────────────

  getAllSpecialties(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/doctor/all-specialties`);
  }

  changeSpecialties(doctorId: string, specialties: string[]): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/specialties`,
      specialties
    );
  }
  // ── Document Types ────────────────────────────────────────────────────────

  getAllDocumentTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/document-types`);
  }
  giveDoctorSchedulerRole(username: string): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/user/${username}/give-doctor-scheduler`,
      null
    );
  }

  revokeDoctorSchedulerRole(username: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/user/${username}/revoke-doctor-scheduler`
    );
  }
}
