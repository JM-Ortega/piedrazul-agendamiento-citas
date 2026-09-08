import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { AvailableDateSlots } from '../models/dtos/availableDateSlots.dto';
import { NewAppointment } from '../models/dtos/newAppointment.dto';
import { PatientSuggestion } from '../models/dtos/patient-suggestion.dto';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';
import { SchedulingOrigin } from '../models/types/schedulingOrigin.type';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

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

  /**
   * Médicos con agendamiento disponible y sus especialidades.
   */
  getSpecialtiesWithActiveDoctors(
    patientId: string | null,
    schedulingOrigin: SchedulingOrigin
  ): Observable<SpecialtyDoctor[]> {
    let params = new HttpParams().set('schedulingOrigin', schedulingOrigin);
    if (patientId) params = params.set('patientId', patientId);
    return this.http.get<SpecialtyDoctor[]>(
      `${this.apiUrl}/doctor/specialties-with-active-doctors`,
      { params }
    );
  }

  /** Todas las fechas disponibles de un médico junto con sus horarios. */
  getAvailableDateSlots(doctorId: string): Observable<AvailableDateSlots[]> {
    return this.http.get<AvailableDateSlots[]>(
      `${this.apiUrl}/appointments/slots`,
      { params: { doctorId } }
    );
  }

  addAppointment(data: NewAppointment): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/appointments`, data);
  }

  addPatient(patient: Omit<Patient, 'id'>): Observable<string> {
    return this.http.post<string>(`${this.apiUrl}/Patient`, patient);
  }
}
