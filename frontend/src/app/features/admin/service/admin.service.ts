import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { CreateUserRequestDto } from '../models/dtos/CreateUserRequestDto';
import { dtoSchedule } from '../models/dtos/schedule.dto';

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
  getDoctors(): Observable<Doctor[]> {
    return this.http.get<Doctor[]>(`${this.apiUrl}/doctor/doctors/detailed`);
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
      `${this.apiUrl}/doctor/doctors/${doctorId}/appointment-interval`,
      null,
      { params: { appointmentInterval } }
    );
  }

  updateLaborStart(doctorId: string, laborStart: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/labor-start`,
      null,
      { params: { laborStart } }
    );
  }

  updateLaborEnd(doctorId: string, laborEnd: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/labor-end`,
      null,
      { params: { laborEnd } }
    );
  }
  /**
updateLaborHours(
  doctorId: string,
  laborStart: string,
  laborEnd: string
): Observable<void> {
  return this.http.put<void>(
    `${this.apiUrl}/doctor/doctors/${doctorId}/labor-hours`,
    null,
    { params: { laborStart, laborEnd } }
  );
}
   */

  enableDoctor(
    doctorId: string,
    laborStart: string,
    laborEnd: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/enable`,
      null,
      { params: { laborStart, laborEnd } }
    );
  }

  disableDoctor(doctorId: string, force = false): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/disable`,
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
  createSchedule(
    doctorId: string,
    workday: string,
    startTime: string,
    endTime: string
  ): Observable<dtoSchedule> {
    return this.http.post<dtoSchedule>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
      { startTime, endTime, workday }
    );
  }

  updateSchedule(
    doctorId: string,
    workday: string,
    startTime: string,
    endTime: string
  ): Observable<dtoSchedule> {
    return this.http.put<dtoSchedule>(
      `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`,
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
    return this.http.get<string[]>(
      `${this.apiUrl}/doctor/doctors/all-specialties`
    );
  }
  addSpecialties(doctorId: string, specialties: string[]): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/specialties`,
      specialties
    );
  }

  removeSpecialties(doctorId: string, specialties: string[]): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/doctor/doctors/${doctorId}/specialties`,
      { body: specialties }
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
