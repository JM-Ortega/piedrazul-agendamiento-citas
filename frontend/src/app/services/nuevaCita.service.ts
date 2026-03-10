import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Doctor } from '../models/doctor.model';
import { Patient } from '../models/patient.model';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = 'https://API';

  getDoctors(): Observable<Doctor[]> {
    return this.http.get<Doctor[]>(`${this.apiUrl}/Doctor/doctors`);
  }

  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(
        `${this.apiUrl}/Paciente/buscarPorId/${documentId}`
    );
  }

  getAvailableDates(doctorId: string): Observable<string[]> {
    return this.http.get<string[]>(
        `${this.apiUrl}/Doctor/${doctorId}/available-dates`
    );
  }

  getAvailableSlots(doctorId: string, date: string): Observable<string[]> {
    return this.http.get<string[]>(
        `${this.apiUrl}/Doctor/${doctorId}/available-slots`,
        {params: { date }}
    );
  }

  addAppointment(data: {
    patientId: string;
    doctorId: string;
    date: string;
    time: string;
  }): Observable<any> {
    return this.http.post(`${this.apiUrl}/Appointment`,data);
  }
}
