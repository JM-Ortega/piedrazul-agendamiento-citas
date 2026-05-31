import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';

@Injectable({ providedIn: 'root' })
export class PatientAppointmentService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  readonly appointments = signal<AppointmentsPatient[]>([]);

  getAppointmentsByPatient(
    patientId: string,
  ): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idPatient: patientId },
    });
  }

  getMyAppointments(): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(
      `${this.apiUrl}/appointments/me`,
    );
  }

  hasAppointments(patientId: string): Observable<boolean> {
    return this.http.get<boolean>(`${this.apiUrl}/appointments/${patientId}/is-new-patient`);
  }

  cancelAppointment(appointmentId: string): Observable<void> {
    return this.http.put<void>(`${this.apiUrl}/appointments/${appointmentId}/cancel`, {});
  }
}
