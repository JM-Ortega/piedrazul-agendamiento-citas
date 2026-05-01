import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { AppointmentsPatient } from '../models/dtos/appointments.dto';
import { dtoDoctor } from '../models/dtos/doctor.dto';
import { Patient } from '../models/interfaces/patient.model';
export type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status';

export type ExportFormatBackend = 'EXCEL' | 'PDF' | 'CSV';
export type ExportColumnBackend =
  | 'FECHA_CITA'
  | 'HORA_CITA'
  | 'NOMBRE_PACIENTE'
  | 'DOCUMENTO_IDENTIDAD'
  | 'TELEFONO_PACIENTE'
  | 'NOMBRE_MEDICO'
  | 'ESPECIALIDAD'
  | 'ESTADO_CITA';

export interface AppointmentExportRequest {
  idDoctor?: string | null;
  format: ExportFormatBackend;
  columns: ExportColumnBackend[];
  state?: string | null;
}
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
      },
    );
  }
  getAppointmentsByDateAndDoctor(
    date: string,
    doctorId: string,
  ): Observable<AppointmentsPatient[]> {
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId, date: date },
    });
  }
  getByDocument(documentNumber: string): Observable<Patient> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentNumber}`,
    );
  }
}
