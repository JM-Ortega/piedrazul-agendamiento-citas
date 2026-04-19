import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable, of } from 'rxjs';
import { environment } from '../../environments/environment';
import { AppointmentsPatient } from '../models/dtos/appointments.dto';
import { dtoDoctor } from '../models/dtos/doctor.dto';
import { dtoSchedule } from '../models/dtos/schedule.dto';
import { Doctor } from '../models/interfaces/doctor.model';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  // ── Mock temporal ─────────────────────────────────────────────────────────
  private mockDoctors: (Omit<
    Doctor,
    'daySchedules' | 'startTime' | 'endTime' | 'workdays'
  > & { keycloakId: string })[] = [
    {
      keycloakId: '10c10b07-3d04-40a8-8d6d-286a8316237b', // ← id_user (sub del token)
      id: '7aaedb8c-1802-415b-a85f-cd4ad171dd4b', // ← id_doctor (BD)
      name: 'Clara Inés Córdoba',
      specialty: 'General',
      appointmentInterval: 30,
      laborStart: '2026-01-01',
      laborEnd: '2026-12-31',
      status: true,
      email: undefined,
    },
  ];

  // ── Schedules ─────────────────────────────────────────────────────────────
  getSchedulesByDoctor(doctorId: string): Observable<dtoSchedule[]> {
    return this.http.get<dtoSchedule[]>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
    );
  }

  // ── getMe ─────────────────────────────────────────────────────────────────
  getMe(keycloakId: string): Observable<Doctor | undefined> {
    const found = this.mockDoctors.find((d) => d.keycloakId === keycloakId); // ← fix

    if (!found) return of(undefined);

    return this.getSchedulesByDoctor(found.id).pipe(
      // ← usa id_doctor
      map((schedules) => {
        const workdays: number[] = [];
        const daySchedules: Doctor['daySchedules'] = {};
        let startTime = '08:00';
        let endTime = '17:00';

        schedules.forEach((s) => {
          const day = Number(s.workday);
          workdays.push(day);
          daySchedules![day] = { startTime: s.startTime, endTime: s.endTime };
        });

        if (schedules.length > 0) {
          startTime = schedules[0].startTime;
          endTime = schedules[0].endTime;
        }

        const { keycloakId: _, ...doctorData } = found;
        return {
          ...doctorData,
          workdays,
          daySchedules,
          startTime,
          endTime,
        } as Doctor;
      }),
    );
  }
  // ── Métodos existentes ────────────────────────────────────────────────────
  getDoctorById(doctorId: string): Observable<dtoDoctor> {
    return this.http.get<dtoDoctor>(
      `${this.apiUrl}/doctor/doctors/${doctorId}`,
    );
  }

  getTodayAppointmentsByDoctor(
    doctorId: string,
  ): Observable<AppointmentsPatient[]> {
    const today = new Date().toISOString().split('T')[0];
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId, date: today },
    });
  }
}
