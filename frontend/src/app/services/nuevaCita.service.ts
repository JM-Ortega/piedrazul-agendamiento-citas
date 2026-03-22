import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of, tap, throwError } from 'rxjs';
import { Patient } from '../models/patient.model';
import { NewAppointment } from '../DTOs/newAppointment';
import { SpecialtyDoctor } from '../DTOs/specialty-doctor';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = 'https://API';

  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(`${this.apiUrl}/Paciente/buscarPorId/${documentId}`);
  }

  getSpecialtiesWithDoctor(): Observable<SpecialtyDoctor[]> {
    return this.http.get<SpecialtyDoctor[]>(`${this.apiUrl}/Doctor/specialties-with-doctor`);
  }

  getSpecialties(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/Doctor/Doctors/specialty`);
  }

  getDoctorsBySpecialty(specialty: string): Observable<SpecialtyDoctor[]> {
    return this.http.get<SpecialtyDoctor[]>(`${this.apiUrl}/Doctor/specialty/${specialty}`);
  }

  getAvailableSlots(doctorId: string, date: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/apointments/available-slots`,{ params: { doctorId, date } });
  }

  addAppointment(data: NewAppointment): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/Appointment`, data);
  }

  addPatient(patient: Omit<Patient, 'id'>): Observable<string> {
    return this.http.post<string>(`${this.apiUrl}/Patient`, patient);
  }
}