import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentExportRequest } from '../../shared/models/dtos/AppointmentExportRequest.dto';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { dtoDoctor } from '../../shared/models/dtos/doctor.dto';
import { PageResponse } from '../../shared/models/dtos/pageResponse.dto';
import { PaginatedState } from '../../shared/helpers/paginated-state';
import { withPagination } from '../../shared/helpers/http-pagination';

@Injectable({ providedIn: 'root' })
export class SchedulerService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  private readonly DOCTORS_CACHE_TTL_MS = 20 * 60 * 1000; // 20 minutos
  private readonly DOCTORS_CACHE_KEY = 'all';
  private doctorsCache = new Map<string, { data: dtoDoctor[]; ts: number }>();
  private statesCache: string[] | null = null;

  /** Citas del día cargadas de forma paginada (contenido + metadata de paginación). */
  private readonly appointmentsState =
    new PaginatedState<AppointmentsPatient>();
  /** Citas de la página actualmente cargada. Contiene datos de pacientes: sensible. */
  readonly appointments = this.appointmentsState.content;
  /** Metadata de paginación de la última carga. */
  readonly pagination = this.appointmentsState.pagination;

  private isFresh(ts: number): boolean {
    return Date.now() - ts < this.DOCTORS_CACHE_TTL_MS;
  }

  getDoctors(): Observable<dtoDoctor[]> {
    const cached = this.doctorsCache.get(this.DOCTORS_CACHE_KEY);
    if (cached && this.isFresh(cached.ts)) {
      return of(cached.data);
    }
    return this.http.get<dtoDoctor[]>(`${this.apiUrl}/doctor`).pipe(
      tap((data) =>
        this.doctorsCache.set(this.DOCTORS_CACHE_KEY, {
          data,
          ts: Date.now(),
        })
      )
    );
  }

  getStates(): Observable<string[]> {
    if (this.statesCache) {
      return of(this.statesCache);
    }
    return this.http
      .get<string[]>(`${this.apiUrl}/appointments/list-all-states`)
      .pipe(tap((data) => (this.statesCache = data)));
  }

  /**
   * Carga una página de citas según los filtros dados y actualiza los
   * signals `appointments`/`pagination` con el resultado.
   *
   * @param params.idDoctor filtra por doctor (opcional)
   * @param params.date filtra por fecha, formato `YYYY-MM-DD` (opcional)
   * @param params.state filtra por estado de cita (opcional)
   * @param params.pageNumber número de página a solicitar, base 0 (por defecto 0)
   * @param params.pageSize cantidad de citas por página (por defecto 5)
   */
  loadAllAppointments(params?: {
    idDoctor?: string;
    date?: string;
    state?: string;
    pageNumber?: number;
    pageSize?: number;
  }): Observable<PageResponse<AppointmentsPatient>> {
    return this.getAllAppointments(params).pipe(
      tap((page) => this.appointmentsState.set(page))
    );
  }

  /**
   * Realiza la petición HTTP GET paginada de citas al backend, aplicando
   * los filtros opcionales de doctor, fecha y estado.
   */
  private getAllAppointments(params?: {
    idDoctor?: string;
    date?: string;
    state?: string;
    pageNumber?: number;
    pageSize?: number;
  }): Observable<PageResponse<AppointmentsPatient>> {
    let httpParams = new HttpParams();
    if (params?.idDoctor)
      httpParams = httpParams.set('idDoctor', params.idDoctor);
    if (params?.date) httpParams = httpParams.set('date', params.date);
    if (params?.state) httpParams = httpParams.set('state', params.state);
    httpParams = withPagination(
      httpParams,
      params?.pageNumber,
      params?.pageSize
    );

    return this.http.get<PageResponse<AppointmentsPatient>>(
      `${this.apiUrl}/appointments`,
      { params: httpParams }
    );
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
      }
    );
  }

  exportScheduler(payload: AppointmentExportRequest): Observable<Blob> {
    return this.http.post(`${this.apiUrl}/reports/scheduler/export`, payload, {
      responseType: 'blob',
    });
  }

  checkSchedulerAvailability(
    date: string
  ): Observable<{ hasAvailabilitySlots: boolean }> {
    return this.http.get<{ hasAvailabilitySlots: boolean }>(
      `${this.apiUrl}/reports/scheduler/availability?date=${date}`
    );
  }

  /**
   * Borra toda la caché en memoria al cerrar sesión
   */
  clearAllData(): void {
    this.doctorsCache.clear();
    this.appointmentsState.clear();
  }
}
