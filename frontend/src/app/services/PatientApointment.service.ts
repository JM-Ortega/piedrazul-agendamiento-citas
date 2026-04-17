import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of } from 'rxjs';
import { AppointmentsPatient } from '../models/dtos/appointments.dto';
import { environment } from '../../environments/environment';

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
}
