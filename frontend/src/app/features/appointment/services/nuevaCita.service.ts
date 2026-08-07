import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { map, tap } from 'rxjs/operators';
import { environment } from '../../../../environments/environment';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { NewAppointment } from '../models/dtos/newAppointment.dto';
import { PatientSuggestion } from '../models/dtos/patient-suggestion.dto';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  // Caché en memoria para especialidades y doctores por especialidad
  private readonly CACHE_TTL_MS = 20 * 60 * 1000; // 20 minutos
  private specialtiesWithDoctorCache = new Map<
    string,
    { data: SpecialtyDoctor[]; ts: number }
  >();
  private specialtiesCache = new Map<string, { data: string[]; ts: number }>();
  private doctorsBySpecialtyCache = new Map<
    string,
    { data: SpecialtyDoctor[]; ts: number }
  >();

  private isFresh(ts: number): boolean {
    return Date.now() - ts < this.CACHE_TTL_MS;
  }

  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentId}`
    );
  }

  getPatientSuggestionsByDocument(
    documentPrefix: string
  ): Observable<PatientSuggestion[]> {
    return this.http.get<PatientSuggestion[]>(
      `${this.apiUrl}/patients/search/by-document-prefix`,
      { params: { documentPrefix } }
    );
  }

  getSpecialtiesWithDoctor(
    patientId: string | null
  ): Observable<SpecialtyDoctor[]> {
    const key = patientId ?? 'none';
    const cached = this.specialtiesWithDoctorCache.get(key);
    if (cached && this.isFresh(cached.ts)) return of(cached.data);

    const url = patientId
      ? `${this.apiUrl}/appointments/specialties-with-doctor?patientId=${patientId}`
      : `${this.apiUrl}/appointments/specialties-with-doctor`;
    return this.http
      .get<SpecialtyDoctor[]>(url)
      .pipe(
        tap((data) =>
          this.specialtiesWithDoctorCache.set(key, { data, ts: Date.now() })
        )
      );
  }

  getSpecialties(patientId: string | null): Observable<string[]> {
    const key = patientId ?? 'none';
    const cached = this.specialtiesCache.get(key);
    if (cached && this.isFresh(cached.ts)) return of(cached.data);

    const url = patientId
      ? `${this.apiUrl}/doctor/patients/specialties?patientId=${patientId}`
      : `${this.apiUrl}/doctor/patients/specialties`;
    return this.http
      .get<string[]>(url)
      .pipe(
        tap((data) => this.specialtiesCache.set(key, { data, ts: Date.now() }))
      );
  }

  getDoctorsBySpecialty(specialty: string): Observable<SpecialtyDoctor[]> {
    const cached = this.doctorsBySpecialtyCache.get(specialty);
    if (cached && this.isFresh(cached.ts)) return of(cached.data);

    return this.http
      .get<SpecialtyDoctor[]>(`${this.apiUrl}/doctor/specialty/${specialty}`)
      .pipe(
        tap((data) =>
          this.doctorsBySpecialtyCache.set(specialty, { data, ts: Date.now() })
        )
      );
  }

  getAvailableSlots(doctorId: string, date: string): Observable<string[]> {
    return this.http
      .get<{ time: string }[]>(`${this.apiUrl}/appointments/available-slots`, {
        params: { doctorId, date },
      })
      .pipe(map((slots) => slots.map((s) => s.time)));
  }

  addAppointment(data: NewAppointment): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/appointments`, data);
  }

  addPatient(patient: Omit<Patient, 'id'>): Observable<string> {
    return this.http.post<string>(`${this.apiUrl}/Patient`, patient);
  }
}
