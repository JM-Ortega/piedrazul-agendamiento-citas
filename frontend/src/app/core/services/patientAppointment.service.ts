import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';

/**
 * Servicio de citas del paciente.
 */
@Injectable({ providedIn: 'root' })
export class PatientAppointmentService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  readonly appointments = signal<AppointmentsPatient[]>([]);

  /**
   * Carga las citas del paciente indicado, opcionalmente filtradas por estado,
   * y actualiza el signal `appointments` con el resultado.
   */
  loadAppointments(params: {
    idPatient: string;
    state?: string;
  }): Observable<AppointmentsPatient[]> {
    return this.getAppointments(params).pipe(
      tap((data) => this.appointments.set(data))
    );
  }

  /**
   * Quita una cita del signal local sin volver a consultar el backend.
   */
  removeAppointment(appointmentId: string): void {
    this.appointments.set(
      this.appointments().filter((a) => a.idAppointment !== appointmentId)
    );
  }

  private getAppointments(params: {
    idPatient: string;
    state?: string;
  }): Observable<AppointmentsPatient[]> {
    let httpParams = new HttpParams().set('idPatient', params.idPatient);
    if (params.state) httpParams = httpParams.set('state', params.state);

    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: httpParams,
    });
  }

  hasAppointments(patientId: string): Observable<boolean> {
    return this.http.get<boolean>(
      `${this.apiUrl}/appointments/${patientId}/is-new-patient`
    );
  }

  cancelAppointment(appointmentId: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/cancel`,
      {}
    );
  }
}
