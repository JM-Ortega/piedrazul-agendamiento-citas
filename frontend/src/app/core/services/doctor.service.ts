import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { map, Observable, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PatientSuggestion } from '../../features/appointment/models/dtos/patient-suggestion.dto';
import { withPagination } from '../../shared/helpers/http-pagination';
import { PaginatedState } from '../../shared/helpers/paginated-state';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { MedicalRecord } from '../../shared/models/dtos/medicalRecord.dto';
import { PageResponse } from '../../shared/models/dtos/pageResponse.dto';
import { UnscheduledAttention } from '../../shared/models/dtos/unscheduledAttention.dto';
import { Doctor } from '../../shared/models/interfaces/doctor.model';
import { Patient } from '../../shared/models/interfaces/patient.model';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  /** Tamaño de página por defecto para el historial clínico (3 registros a la vez). */
  private readonly MEDICAL_RECORDS_PAGE_SIZE = 3;

  /** Historial clínico paginado del paciente actualmente visualizado. */
  readonly medicalRecordsState = new PaginatedState<MedicalRecord>();
  readonly isLoadingRecords = signal(false);
  private meCache: Doctor | null = null;
  private meCacheTimestamp = 0;
  private readonly ME_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos

  /**
   * Obtiene los datos del doctor autenticado actualmente (según el token de sesión).
   * Usa un caché en memoria de 5 minutos para evitar llamadas repetidas al backend
   * cuando el componente se vuelve a montar en poco tiempo.
   *
   * @param forceRefresh - Si es true, ignora el caché y fuerza una nueva petición
   * (útil después de editar el perfil del doctor).
   * @returns Observable con los datos del doctor logueado.
   */
  getMe(forceRefresh = false): Observable<Doctor> {
    const isCacheValid =
      this.meCache && Date.now() - this.meCacheTimestamp < this.ME_CACHE_TTL_MS;

    if (isCacheValid && !forceRefresh) {
      return of(this.meCache as Doctor);
    }

    interface DoctorMeResponse {
      id: string;
      name: string;
      specialty: string[];
      appointmentInterval: number;
      bookingWindowWeeks: number;
      laborStart: string;
      laborEnd: string;
      status: boolean;
    }

    return this.http.get<DoctorMeResponse>(`${this.apiUrl}/doctor/me`).pipe(
      map(
        (res) =>
          ({
            id: res.id,
            name: res.name,
            specialty: res.specialty,
            appointmentInterval: res.appointmentInterval,
            bookingWindowWeeks: res.bookingWindowWeeks,
            laborStart: res.laborStart,
            laborEnd: res.laborEnd,
            status: res.status,
            workdays: [],
            startTime: '',
            endTime: '',
            daySchedules: {},
          }) as Doctor
      ),
      tap((doctor) => {
        this.meCache = doctor;
        this.meCacheTimestamp = Date.now();
      })
    );
  }

  /**
   * Obtiene la agenda diaria del doctor autenticado (resuelto vía JWT en backend),
   * organizada por prioridad de estado. Reemplaza al antiguo endpoint de citas
   * filtradas manualmente por `idDoctor` + `date`.
   *
   * @param date - Fecha (ISO `yyyy-MM-dd`) de la agenda a consultar.
   * @param pageNumber - Índice de página (base 0). Por defecto 0.
   * @param pageSize - Cantidad de citas por página. Por defecto 10.
   * @param state - (Opcional) Filtrar por un estado de cita específico.
   * @returns Observable con la respuesta paginada completa (content + metadata).
   */
  getDoctorDailyAgenda(
    date: string,
    pageNumber = 0,
    pageSize = 10,
    state?: string
  ): Observable<PageResponse<AppointmentsPatient>> {
    let params = withPagination(
      new HttpParams().set('date', date),
      pageNumber,
      pageSize
    );
    if (state) {
      params = params.set('state', state);
    }
    return this.http.get<PageResponse<AppointmentsPatient>>(
      `${this.apiUrl}/appointments/doctor-daily-agenda`,
      { params }
    );
  }

  /**
   * Obtiene una página de citas de un médico específico.
   *
   * @param doctorId - ID del médico.
   * @param pageNumber - Índice de página (base 0). Por defecto 0.
   * @param pageSize - Cantidad de citas por página. Por defecto 10.
   * @returns Observable con la respuesta paginada completa (content + metadata).
   */
  getAppointmentsByDoctor(
    doctorId: string,
    pageNumber = 0,
    pageSize = 4
  ): Observable<PageResponse<AppointmentsPatient>> {
    const params = withPagination(
      new HttpParams().set('idDoctor', doctorId),
      pageNumber,
      pageSize
    );
    return this.http.get<PageResponse<AppointmentsPatient>>(
      `${this.apiUrl}/appointments`,
      { params }
    );
  }
  /**
   * Marca una cita como atendida.
   *
   * @param appointmentId - ID de la cita a actualizar.
   * @param state - Nuevo estado a enviar en el body (ej. `'ATENDIDA'`).
   * @returns Observable vacío que se completa al confirmar la actualización.
   */
  updateAppointmentAsAttended(
    appointmentId: string,
    description: string | null
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/mark-as-attended`,
      { description }
    );
  }

  /**
   * Marca una cita como atendida, opcionalmente con una observación clínica.
   *
   * @param appointmentId - ID de la cita a actualizar.
   * @param description - Observación clínica a registrar (o `null` si no aplica).
   * @returns Observable vacío que se completa al confirmar la actualización.
   */
  updateAppointmentAsUnassisted(
    appointmentId: string,
    state: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/mark-as-unassisted`,
      { appointmentState: state }
    );
  }

  /**
   * Carga una página del historial clínico del paciente.
   *
   * @param patientId - ID del paciente cuyo historial se desea cargar.
   * @param pageNumber - Índice de página (base 0). Por defecto 0.
   */
  loadMedicalRecordsByPatient(patientId: string, pageNumber = 0): void {
    if (this.isLoadingRecords()) return;

    this.isLoadingRecords.set(true);
    const params = withPagination(
      new HttpParams(),
      pageNumber,
      this.MEDICAL_RECORDS_PAGE_SIZE
    );

    this.http
      .get<PageResponse<MedicalRecord>>(
        `${this.apiUrl}/clinical-history/patient/${patientId}`,
        { params }
      )
      .subscribe({
        next: (res) => {
          this.medicalRecordsState.set(res);
          this.isLoadingRecords.set(false);
        },
        error: () => this.isLoadingRecords.set(false),
      });
  }

  /**
   * Obtiene los datos del paciente asociado a una cita específica.
   *
   * @param appointmentId - ID de la cita de la que se quiere obtener el paciente.
   * @returns Observable con los datos del paciente.
   */
  getPatientByAppointment(appointmentId: string): Observable<Patient> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/${appointmentId}/patient-attended`
    );
  }

  /**
   * Obtiene los datos completos de un paciente mediante su número de documento.
   *
   * @param {string} documentId - El número de documento del paciente.
   * @returns {Observable<Patient | null>} Un Observable que emite el objeto del paciente si existe, o null si no se encuentra.
   */
  getPatientByDocument(documentId: string): Observable<Patient | null> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentId}`
    );
  }

  /**
   * Obtiene una lista de sugerencias de pacientes cuyo número de documento coincida con el prefijo ingresado.
   *
   * @param {string} documentPrefix - Prefijo o primeros dígitos del documento para realizar la búsqueda predictiva.
   * @returns {Observable<PatientSuggestion[]>} Un Observable que emite un listado de sugerencias de pacientes coincidentes.
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
   * Registra la atención de un paciente sin cita previa.
   * Incluye sus datos completos (para el caso en que también deba registrarse como paciente nuevo),
   * la especialidad y la observación clínica opcional.
   *
   * @param {UnscheduledAttention} request - Objeto con la información requerida para la atención no programada y datos del paciente.
   * @returns {Observable<void>} Un Observable que se completa cuando la solicitud de registro es exitosa.
   */
  registerUnscheduledAttention(
    request: UnscheduledAttention
  ): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/appointments/unscheduled`,
      request
    );
  }

  /**
   * Limpia el caché en memoria de `getMe()`, forzando que la próxima
   * llamada consulte al backend en lugar de devolver el dato cacheado.
   * Útil tras editar el perfil del doctor o al cerrar sesión.
   */
  clearMeCache(): void {
    this.meCache = null;
    this.meCacheTimestamp = 0;
  }

  /**
   * Reinicia el historial clínico paginado. Debe llamarse siempre que se
   * cambie de paciente, antes de cargar la página 0.
   */
  resetMedicalRecords(): void {
    this.medicalRecordsState.clear();
  }

  /**
   * Limpia todo el estado en memoria del doctor
   * (perfil, historial clínico, paginación).
   */
  clearAllData(): void {
    this.clearMeCache();
    this.resetMedicalRecords();
  }
}