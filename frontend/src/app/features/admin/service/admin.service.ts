import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../shared/models/dtos/pageResponse.dto';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';
import { CreateUserRequestDto } from '../models/dtos/CreateUserRequestDto';
import { dtoSchedule } from '../models/dtos/schedule.dto';

import { DoctorAdminDto } from '../models/dtos/DoctorAdminDto';
import { SystemUser } from '../models/interfaces/system-user.model';
// ─────────────────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class AdminService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  // ── Doctors ──────────────────────────────────────────────────────────────

  /**
   * Crea un nuevo usuario en el sistema, opcionalmente con rol de médico
   * y/o agendador según el payload recibido.
   *
   * @param payload - Datos del usuario, y opcionalmente del médico, a crear.
   * @returns Observable que completa sin contenido si la creación fue exitosa.
   */
  createUser(payload: CreateUserRequestDto): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/user/users`, payload);
  }

  /**
   * Obtiene una página de médicos con información detallada, ordenados
   * por nombre ascendente.
   *
   * @param page - Índice de página (base 0).
   * @param size - Cantidad de médicos por página.
   * @returns Observable con la respuesta paginada completa (content + metadata).
   */
  getDoctors(page = 0, size = 4): Observable<PageResponse<Doctor>> {
    return this.http.get<PageResponse<Doctor>>(
      `${this.apiUrl}/doctor/detailed`,
      { params: { page, size, sort: 'name,asc' } }
    );
  }

  /**
   * Obtiene una página de médicos con información detallada para el panel
   * de administración (roles y especialidades), ordenados por nombre ascendente.
   *
   * @param page - Índice de página (base 0).
   * @param size - Cantidad de médicos por página.
   * @returns Observable con la respuesta paginada completa (content + metadata).
   */
  getDoctorsAdmin(
    page = 0,
    size = 4
  ): Observable<PageResponse<DoctorAdminDto>> {
    return this.http.get<PageResponse<DoctorAdminDto>>(
      `${this.apiUrl}/user/system-doctors`,
      { params: { page, size } }
    );
  }

  /**
   * Actualiza el intervalo entre citas (en minutos) de un médico.
   *
   * @param doctorId - Identificador del médico.
   * @param appointmentInterval - Nuevo intervalo entre citas, en minutos.
   * @returns Observable que completa sin contenido si la actualización fue exitosa.
   */
  updateAppointmentInterval(
    doctorId: string,
    appointmentInterval: number
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/appointment-interval`,
      null,
      { params: { appointmentInterval } }
    );
  }

  /**
   * Actualiza el rango de fechas del período laboral (fecha de inicio y
   * fin) durante el cual un médico está activo en el sistema.
   *
   * @param doctorId - Identificador del médico.
   * @param laborStart - Nueva fecha de inicio laboral (formato ISO, ej. 'YYYY-MM-DD').
   * @param laborEnd - Nueva fecha de fin laboral (formato ISO, ej. 'YYYY-MM-DD').
   * @returns Observable que completa sin contenido si la actualización fue exitosa.
   */
  updateLaborDate(
    doctorId: string,
    laborStart: string,
    laborEnd: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/labor-date`,
      null,
      { params: { laborStart, laborEnd } }
    );
  }

  /**
   * Habilita a un médico en el sistema, asignándole un nuevo período laboral.
   *
   * @param doctorId - Identificador del médico.
   * @param laborStart - Fecha de inicio laboral (formato ISO, ej. 'YYYY-MM-DD').
   * @param laborEnd - Fecha de fin laboral (formato ISO, ej. 'YYYY-MM-DD').
   * @returns Observable que completa sin contenido si la habilitación fue exitosa.
   */
  enableDoctor(
    doctorId: string,
    laborStart: string,
    laborEnd: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/enable`,
      null,
      { params: { laborStart, laborEnd } }
    );
  }

  /**
   * Deshabilita a un médico en el sistema.
   *
   * @param doctorId - Identificador del médico.
   * @param force - Si es `true`, fuerza la deshabilitación aunque el médico
   * tenga citas u horarios pendientes asociados. Por defecto `false`.
   * @returns Observable que completa sin contenido si la deshabilitación fue exitosa.
   */
  disableDoctor(doctorId: string, force = false): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/disable`,
      null,
      { params: { force } }
    );
  }

  // ── Schedules ─────────────────────────────────────────────────────────────

  /**
   * Obtiene los horarios (días y franjas de atención) configurados para un médico.
   *
   * @param doctorId - Identificador del médico.
   * @returns Observable con el arreglo de horarios del médico.
   */
  getSchedulesByDoctor(doctorId: string): Observable<dtoSchedule[]> {
    return this.http.get<dtoSchedule[]>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`
    );
  }

  /**
   * Crea o actualiza el horario de un médico para un día de la semana
   * específico.
   *
   * @param doctorId - Identificador del médico.
   * @param workday - Día de la semana a actualizar (ej. 'LUNES', 'MARTES').
   * @param startTime - Hora de inicio de atención (formato 'HH:mm:ss').
   * @param endTime - Hora de fin de atención (formato 'HH:mm:ss').
   * @returns Observable con el horario actualizado.
   */
  updateSchedule(
    doctorId: string,
    workday: string,
    startTime: string,
    endTime: string
  ): Observable<dtoSchedule> {
    return this.http.put<dtoSchedule>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`,
      { startTime, endTime, workday }
    );
  }

  /**
   * Elimina el horario de un médico para un día de la semana específico.
   *
   * @param doctorId - Identificador del médico.
   * @param workday - Día de la semana a eliminar (ej. 'LUNES', 'MARTES').
   * @returns Observable que completa sin contenido si la eliminación fue exitosa.
   */
  deleteSchedule(doctorId: string, workday: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/doctor/schedules/${doctorId}/${workday}`
    );
  }

  // ── System Users ──────────────────────────────────────────────────────────

  /**
   * Obtiene una página de usuarios del sistema (médicos y/o agendadores).
   *
   * @param page - Índice de página (base 0).
   * @param size - Cantidad de usuarios por página.
   * @returns Observable con la respuesta paginada completa (content + metadata).
   */
  getSystemUsers(page = 0, size = 9): Observable<PageResponse<SystemUser>> {
    return this.http.get<PageResponse<SystemUser>>(
      `${this.apiUrl}/user/system-users`,
      { params: { page, size } }
    );
  }

  // ── Specialties ───────────────────────────────────────────────────────────
  /**
   * Obtiene el listado de todas las especialidades médicas disponibles
   * en el sistema.
   *
   * @returns Observable con el arreglo de nombres de especialidades.
   */
  getAllSpecialties(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/doctor/all-specialties`);
  }

  /**
   * Actualiza las especialidades asociadas a un médico.
   *
   * @param doctorId - Identificador del médico.
   * @param specialties - Arreglo con los nombres de las especialidades a asignar.
   * @returns Observable que completa sin contenido si la actualización fue exitosa.
   */
  changeSpecialties(doctorId: string, specialties: string[]): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/doctor/${doctorId}/specialties`,
      specialties
    );
  }
  // ── Document Types ────────────────────────────────────────────────────────
  /**
   * Obtiene el listado de tipos de documento de identidad soportados
   * por el sistema (ej. 'CEDULA', 'PASAPORTE').
   *
   * @returns Observable con el arreglo de tipos de documento.
   */
  getAllDocumentTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/document-types`);
  }

  /**
   * Otorga el rol de agendador a un usuario que ya tiene rol de médico.
   *
   * @param username - Nombre de usuario (username) del médico.
   * @returns Observable que completa sin contenido si la operación fue exitosa.
   */
  giveDoctorSchedulerRole(username: string): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/user/${username}/give-doctor-scheduler`,
      null
    );
  }
  /**
   * Revoca el rol de agendador de un usuario que tiene rol de médico,
   * dejándolo únicamente con el rol de médico.
   *
   * @param username - Nombre de usuario (username) del médico.
   * @returns Observable que completa sin contenido si la operación fue exitosa.
   */
  revokeDoctorSchedulerRole(username: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/user/${username}/revoke-doctor-scheduler`
    );
  }
}
