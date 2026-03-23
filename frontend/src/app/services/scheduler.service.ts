import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { dtoAppointment } from '../models/DTOs/dtoAppointment.model';
import { dtoDoctor } from '../models/DTOs/dtoDoctor.model';

@Injectable({ providedIn: 'root' })
export class SchedulerService {
  private http = inject(HttpClient);
  private apiUrl = 'https://API';

  getDoctors(): Observable<dtoDoctor[]> {
    return this.http.get<dtoDoctor[]>(`${this.apiUrl}/doctors`);
  }

  getAllAppointments(): Observable<dtoAppointment[]> {
    return this.http.get<dtoAppointment[]>(`${this.apiUrl}/appointments`);
  }

  getAppointmentsByDate(date: string): Observable<dtoAppointment[]> {
    return this.http.get<dtoAppointment[]>(
      `${this.apiUrl}/appointments/date/${date}`,
    );
  }

  getAppointmentsByDoctor(doctorId: string): Observable<dtoAppointment[]> {
    return this.http.get<dtoAppointment[]>(
      `${this.apiUrl}/appointments/doctor/${doctorId}`,
    );
  }

  getAppointmentsByDateAndDoctor(
    date: string,
    doctorId: string,
  ): Observable<dtoAppointment[]> {
    return this.http.get<dtoAppointment[]>(
      `${this.apiUrl}/appointments/date/${date}/doctor/${doctorId}`,
    );
  }
}
