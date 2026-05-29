import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { dtoSchedule } from '../../../shared/models/dtos/schedule.dto';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { CreateSchedulerRequest } from '../models/dtos/create-scheduler-request.dto';
import { CreateDoctorRequestDto } from '../models/dtos/CreateDoctorRequest.dto';
import { SystemUser } from '../models/interfaces/system-user.model';

// ─────────────────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class AdminService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  // ── Doctors ──────────────────────────────────────────────────────────────

  getDoctors(): Observable<Doctor[]> {
    return this.http.get<Doctor[]>(`${this.apiUrl}/doctor/doctors/detailed`);
  }

  /**
   * Crea un nuevo doctor en el backend.
   * El servidor responde 204 No Content en caso de éxito.
   * @param payload Datos del doctor según CreateDoctorRequest del backend
   */
  createDoctor(payload: CreateDoctorRequestDto): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/doctor/doctors`, payload);
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

  deleteSchedule(doctorId: string, workday: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`,
    );
  }

  // ── System Users ──────────────────────────────────────────────────────────

  getSystemUsers(): Observable<SystemUser[]> {
    return this.http.get<SystemUser[]>(`${this.apiUrl}/user/system-users`);
  }

  createScheduler(request: CreateSchedulerRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/user/schedulers`, request);
  }
  // ── Specialties ───────────────────────────────────────────────────────────

  getAllSpecialties(): Observable<string[]> {
    return this.http.get<string[]>(
      `${this.apiUrl}/doctor/doctors/all-specialties`,
    );
  }

  // ── Document Types ────────────────────────────────────────────────────────

  getAllDocumentTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/document-types`);
  }
}
