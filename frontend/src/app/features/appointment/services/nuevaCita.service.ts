import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { NewAppointment } from '../models/dtos/newAppointment.dto';
import { PatientSuggestion } from '../models/dtos/patientSuggestion.dto';
import { RescheduleAppointment } from '../models/dtos/rescheduleAppointment.dto';
import { SpecialtyDoctor } from '../models/dtos/specialtyDoctor.dto';
import { SchedulingOrigin } from '../models/types/schedulingOrigin.type';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  /**
   * Busca un paciente por su número de documento exacto.
   * @param documentId Número de documento del paciente.
   * @returns El paciente encontrado; si no existe, el backend responde con error.
   */
  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentId}`
    );
  }

  /**
   * Obtiene sugerencias de pacientes cuyo documento empieza por el prefijo dado.
   * @param documentPrefix Primeros dígitos del número de documento.
   * @returns Lista de pacientes sugeridos para el autocompletado.
   */
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

  /** Horarios disponibles de un médico para una fecha puntual. */
  getAvailableSlots(doctorId: string, date: string): Observable<string[]> {
    return this.http.get<string[]>(
      `${this.apiUrl}/doctor/${doctorId}/available-slots`,
      { params: { date } }
    );
  }

  /**
   * Registra una cita nueva.
   * @param data Datos de la cita y del paciente.
   */
  addAppointment(data: NewAppointment): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/appointments`, data);
  }

  /**
   * Reagenda una cita existente.
   * @param appointmentId Id de la cita a reagendar.
   * @param data Datos de la nueva cita (doctor, especialidad, fecha y hora).
   */
  rescheduleAppointment(
    appointmentId: string,
    data: RescheduleAppointment
  ): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/appointments/${appointmentId}/reschedule`,
      data
    );
  }

  /**
   * Registra un paciente nuevo.
   * @param patient Datos del paciente, sin id.
   * @returns El id que el backend asignó al paciente creado.
   */
  addPatient(patient: Omit<Patient, 'id'>): Observable<string> {
    return this.http.post<string>(`${this.apiUrl}/Patient`, patient);
  }
}
