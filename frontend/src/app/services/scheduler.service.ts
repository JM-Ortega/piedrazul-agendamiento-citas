import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AppointmentsPatient } from '../models/dtos/appointments.dto';
import { dtoDoctor } from '../models/dtos/doctor.dto';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class SchedulerService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  getDoctors(): Observable<dtoDoctor[]> {
    return this.http.get<dtoDoctor[]>(`${this.apiUrl}/doctor/doctors`);
  }

  getAllAppointments(): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`);
  }

  getAppointmentsByDate(date: string): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { date: date },
    });
  }
  getAppointmentsByDoctor(doctorId: string): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId },
    });
  }

  getAppointmentsByDateAndDoctor(
    date: string,
    doctorId: string,
  ): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId, date: date },
    });
  }
}
