import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Appointment } from '../models/appointment.model';
import { Doctor } from '../models/doctor.model';
import { Patient } from '../models/patient.model';

@Injectable({ providedIn: 'root' })
export class SchedulerService {
  private http = inject(HttpClient);
  private apiUrl = 'https://tu-api.com';

  getDoctors(): Observable<Doctor[]> {
    return this.http.get<Doctor[]>(`${this.apiUrl}/doctors`);
  }

  getAppointments(doctorId?: string, date?: string): Observable<Appointment[]> {
    const params: Record<string, string> = {};
    if (doctorId) params['doctorId'] = doctorId;
    if (date) params['date'] = date;
    return this.http.get<Appointment[]>(`${this.apiUrl}/appointments`, {
      params,
    });
  }

  getPatientById(id: string): Observable<Patient> {
    return this.http.get<Patient>(`${this.apiUrl}/patients/${id}`);
  }

  getDoctorById(id: string): Observable<Doctor> {
    return this.http.get<Doctor>(`${this.apiUrl}/doctors/${id}`);
  }
}
