import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentExportRequest } from '../../shared/models/dtos/AppointmentExportRequest.dto';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { dtoDoctor } from '../../shared/models/dtos/doctor.dto';

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

  exportAppointments(payload: AppointmentExportRequest): Observable<Blob> {
    return this.http.post(
      `${this.apiUrl}/reports/appointments/export`,
      payload,
      {
        responseType: 'blob',
      }
    );
  }
  exportScheduler(payload: AppointmentExportRequest): Observable<Blob> {
    return this.http.post(`${this.apiUrl}/reports/scheduler/export`, payload, {
      responseType: 'blob',
    });
  }
  checkSchedulerAvailability(
    date: string
  ): Observable<{ hasAvailabilitySlots: boolean }> {
    return this.http.get<{ hasAvailabilitySlots: boolean }>(
      `${this.apiUrl}/reports/scheduler/availability?date=${date}`
    );
  }
  getAppointmentsByDateAndDoctor(
    date: string,
    doctorId: string
  ): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId, date: date },
    });
  }
}
