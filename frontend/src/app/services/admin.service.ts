import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { environment } from '../../environments/environment';
import { dtoSchedule } from '../models/dtos/schedule.dto';
import { Doctor } from '../models/interfaces/doctor.model';
import { SystemUser } from '../models/interfaces/system-user.model';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  // ── Mock in-memory store (temporal hasta que exista el backend) ───────────
  private mockUsers: SystemUser[] = [];

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

  deleteSchedule(doctorId: string, workday: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`,
    );
  }

  // ── System Users (mock hasta disponibilidad del backend) ─────────────────

  /**
   * Persiste el nuevo usuario en el store en memoria.
   * TODO: Reemplazar por llamada HTTP cuando el endpoint esté disponible:
   *   return this.http.post<SystemUser>(`${this.apiUrl}/admin/users`, user);
   */
  createSystemUserMock(user: SystemUser): void {
    this.mockUsers.push(user);
    console.log('[AdminService] Usuario añadido al store mock:', user);
  }

  getSchedulers(): Observable<SystemUser[]> {
    return of([
      {
        id: 'sch-1',
        firstName: 'Laura',
        lastName: 'Pérez',
        documentId: '1023456780',
        roles: ['scheduler'],
      },
      {
        id: 'sch-2',
        firstName: 'Carlos',
        lastName: 'Rodríguez',
        documentId: '2034567891',
        roles: ['scheduler'],
      },
      {
        id: 'sch-3',
        firstName: 'Valeria',
        lastName: 'Torres',
        documentId: '3045678902',
        roles: ['scheduler'],
      },
      ...this.mockUsers.filter(
        (u) => u.roles.includes('scheduler') && !u.roles.includes('doctor'),
      ),
    ]);
  }

  getBothRoleUsers(): Observable<SystemUser[]> {
    return of([
      {
        id: 'both-1',
        firstName: 'María',
        lastName: 'González',
        documentId: '4056789013',
        roles: ['doctor', 'scheduler'],
        doctorData: {
          specialty: 'Fisioterapia',
          startTime: '07:00',
          endTime: '12:00',
          interval: 30,
        },
      },
      {
        id: 'both-2',
        firstName: 'Andrés',
        lastName: 'Muñoz',
        documentId: '5067890124',
        roles: ['doctor', 'scheduler'],
        doctorData: {
          specialty: 'Medicina General',
          startTime: '08:00',
          endTime: '12:00',
          interval: 20,
        },
      },
      ...this.mockUsers.filter(
        (u) => u.roles.includes('doctor') && u.roles.includes('scheduler'),
      ),
    ]);
  }
}
