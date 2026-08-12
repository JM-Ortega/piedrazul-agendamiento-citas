import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';

import { map, Observable, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { MedicalRecord } from '../../shared/models/dtos/medicalRecord.dto';
import { Doctor } from '../../shared/models/interfaces/doctor.model';
import { Patient } from '../../shared/models/interfaces/patient.model';
import { PagedResponse } from '../../shared/models/dtos/paged-response.dto';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  medicalRecords = signal<MedicalRecord[]>([]);
  private currentPage = signal(0);
  private totalPages = signal(0);
  readonly isLoadingRecords = signal(false);
  private meCache: Doctor | null = null;
  private meCacheTimestamp = 0;
  private readonly ME_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos

  get hasMoreRecords(): boolean {
    return this.currentPage() < this.totalPages() - 1;
  }

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
   * Obtiene las citas del día actual (fecha local del cliente) para un doctor específico.
   * La fecha se calcula en formato `YYYY-MM-DD` y se envía como query param junto al `idDoctor`.
   *
   * @param doctorId - ID del doctor cuyas citas de hoy se quieren consultar.
   * @returns Observable con la lista de citas del día para ese doctor.
   */
  getTodayAppointmentsByDoctor(
    doctorId: string
  ): Observable<AppointmentsPatient[]> {
    const d = new Date();
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return this.http.get<AppointmentsPatient[]>(`${this.apiUrl}/appointments`, {
      params: { idDoctor: doctorId, date: today },
    });
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
   * Carga la siguiente página del historial clínico del paciente
   * y la acumula sobre las ya cargadas.
   * @param patientId - ID del paciente cuyo historial se desea cargar.
   */
  loadMedicalRecordsByPatient(patientId: string): void {
    if (this.isLoadingRecords() || !this.hasMoreRecordsFor()) return;

    this.isLoadingRecords.set(true);
    const nextPage =
      this.currentPage() === 0 && this.medicalRecords().length === 0
        ? 0
        : this.currentPage() + 1;

    this.http
      .get<PagedResponse<MedicalRecord>>(
        `${this.apiUrl}/clinical-history/patient/${patientId}`,
        { params: { page: nextPage } }
      )
      .subscribe({
        next: (res) => {
          this.medicalRecords.update((prev) => [...prev, ...res.content]);
          this.currentPage.set(res.page);
          this.totalPages.set(res.totalPages);
          this.isLoadingRecords.set(false);
        },
        error: () => this.isLoadingRecords.set(false),
      });
  }

  private hasMoreRecordsFor(): boolean {
    return this.medicalRecords().length === 0 || this.hasMoreRecords;
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
   * Limpia el caché en memoria de `getMe()`, forzando que la próxima
   * llamada consulte al backend en lugar de devolver el dato cacheado.
   * Útil tras editar el perfil del doctor o al cerrar sesión.
   */
  clearMeCache(): void {
    this.meCache = null;
    this.meCacheTimestamp = 0;
  }

  /**
   * Reinicia el estado de paginación e historial. Debe llamarse
   * siempre que se cambie de paciente, antes de cargar la página 0.
   */
  resetMedicalRecords(): void {
    this.medicalRecords.set([]);
    this.currentPage.set(0);
    this.totalPages.set(0);
  }

  /**
   * Limpia todo el estado en memoria del doctor
   * (perfil, historial clínico, paginación).
   */
  clearAllDoctorData(): void {
    this.clearMeCache();
    this.resetMedicalRecords();
  }
}
