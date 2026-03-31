import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of, tap, throwError } from 'rxjs';
import { Patient } from '../models/patient.model';
import { NewAppointment } from '../models/DTOs/newAppointment';
import { SpecialtyDoctor } from '../models/DTOs/specialty-doctor';
import { map } from 'rxjs/operators';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentId}`,
    );
  }

  getSpecialtiesWithDoctor(): Observable<SpecialtyDoctor[]> {
    return this.http.get<SpecialtyDoctor[]>(
      `${this.apiUrl}/appointments/specialties-with-doctor`,
    );
  }

  getSpecialties(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/doctor/doctors/specialty`);
  }

  getDoctorsBySpecialty(specialty: string): Observable<SpecialtyDoctor[]> {
    return this.http.get<SpecialtyDoctor[]>(
      `${this.apiUrl}/doctor/doctors/specialty/${specialty}`,
    );
  }

  getAvailableSlots(doctorId: string, date: string): Observable<string[]> {
    return this.http
      .get<
        { time: string }[]
      >(`${this.apiUrl}/appointments/available-slots`, { params: { doctorId, date } })
      .pipe(map((slots) => slots.map((s) => s.time)));
  }

  addAppointment(data: NewAppointment): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/appointments`, data);
  }

  addPatient(patient: Omit<Patient, 'id'>): Observable<string> {
    return this.http.post<string>(`${this.apiUrl}/Patient`, patient);
  }
}
