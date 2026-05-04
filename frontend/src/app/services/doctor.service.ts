import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { AppointmentsPatient } from '../models/dtos/appointments.dto';
import { dtoDoctor } from '../models/dtos/doctor.dto';
import { dtoSchedule } from '../models/dtos/schedule.dto';
import { Doctor } from '../models/interfaces/doctor.model';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  getMe(): Observable<Doctor> {
    return this.http.get<any>(`${this.apiUrl}/doctor/doctors/me`).pipe(
      map(
        (res) =>
          ({
            id: res.id,
            name: res.name,
            specialty: res.specialty,
            appointmentInterval: res.appointmentInterval,
            laborStart: res.laborStart,
            laborEnd: res.laborEnd,
            status: res.status,
            workdays: [],
            startTime: '',
            endTime: '',
            daySchedules: {},
          }) as Doctor,
      ),
    );
  }

  getDoctorById(doctorId: string): Observable<dtoDoctor> {
    return this.http.get<dtoDoctor>(
      `${this.apiUrl}/doctor/doctors/${doctorId}`,
    );
  }

  getSchedulesByDoctor(doctorId: string): Observable<dtoSchedule[]> {
    return this.http.get<dtoSchedule[]>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
    );
  }

  getTodayAppointmentsByDoctor(
    doctorId: string,
  ): Observable<AppointmentsPatient[]> {
    const d = new Date();
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId, date: today },
    });
  }

  updateAppointmentState(appointmentId: string, state: string,): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/mark-as-attended`,
      { appointmentState: state },
    );
  }
}