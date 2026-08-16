import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { PageResponse } from '../../shared/models/dtos/pageResponse.dto';
import { PaginatedState } from '../../shared/helpers/paginated-state';
import { withPagination } from '../../shared/helpers/http-pagination';

/**
 * Servicio de citas del paciente.
 */
@Injectable({ providedIn: 'root' })
export class PatientAppointmentService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  private readonly appointmentsState =
    new PaginatedState<AppointmentsPatient>();

  readonly appointments = this.appointmentsState.content;
  readonly pagination = this.appointmentsState.pagination;

  /**
   * Limpia las citas en memoria y datos de paginación al cerrar sesión
   */
  clearAllData(): void {
    this.appointmentsState.clear();
  }

  /**
   * Carga una página de citas del paciente indicado
   * opcionalmente filtradas por estado.
   *
   * @param params.idPatient id del paciente a consultar
   * @param params.state estado de la cita a filtrar (ej. 'AGENDADA'); si se omite, trae todos los estados
   * @param params.pageNumber número de página a solicitar, base 0 (por defecto 0)
   * @param params.pageSize cantidad de citas por página (por defecto 5)
   * @returns Observable con la respuesta paginada completa del backend
   */
  loadAppointments(params: {
    idPatient: string;
    state?: string;
    pageNumber?: number;
    pageSize?: number;
  }): Observable<PageResponse<AppointmentsPatient>> {
    return this.getAppointments(params).pipe(
      tap((page) => this.appointmentsState.set(page))
    );
  }

  /**
   * Realiza la petición HTTP GET paginada de citas al backend.
   *
   * @param params filtros y parámetros de paginación
   * @returns Observable con la respuesta cruda del backend (`PageResponse<AppointmentsPatient>`)
   */
  private getAppointments(params: {
    idPatient: string;
    state?: string;
    pageNumber?: number;
    pageSize?: number;
  }): Observable<PageResponse<AppointmentsPatient>> {
    let httpParams = new HttpParams().set('idPatient', params.idPatient);
    if (params.state) httpParams = httpParams.set('state', params.state);
    httpParams = withPagination(httpParams, params.pageNumber, params.pageSize);

    return this.http.get<PageResponse<AppointmentsPatient>>(
      `${this.apiUrl}/appointments`,
      { params: httpParams }
    );
  }

  /**
   * Verifica si el paciente indicado no tiene citas registradas (paciente nuevo).
   *
   * @param patientId id del paciente
   */
  hasAppointments(patientId: string): Observable<boolean> {
    return this.http.get<boolean>(
      `${this.apiUrl}/appointments/${patientId}/is-new-patient`
    );
  }

  /**
   * Cancela una cita por su id.
   *
   * @param appointmentId id de la cita a cancelar
   */
  cancelAppointment(appointmentId: string): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/cancel`,
      {}
    );
  }
}
