import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../../../environments/environment';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { NewAppointment } from '../models/dtos/newAppointment.dto';
import { PatientSuggestion } from '../models/dtos/patient-suggestion.dto';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentId}`,
    );
  }

  getPatientSuggestionsByDocument(
    documentPrefix: string,
  ): Observable<PatientSuggestion[]> {
    return this.http.get<PatientSuggestion[]>(
      `${this.apiUrl}/patients/search/by-document-prefix`,
      { params: { documentPrefix } },
    );
  }

  getSpecialtiesWithDoctor(patientId: string | null): Observable<SpecialtyDoctor[]> {
    const url = patientId
      ? `${this.apiUrl}/appointments/specialties-with-doctor/${patientId}`
      : `${this.apiUrl}/appointments/specialties-with-doctor`;
    return this.http.get<SpecialtyDoctor[]>(url);
  }

  getSpecialties(patientId: string | null): Observable<string[]> {
    const url = patientId
    ? `${this.apiUrl}/doctor/doctors/patients/specialities/${patientId}`
    : `${this.apiUrl}/doctor/doctors/specialities`;
    return this.http.get<string[]>(url);
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
