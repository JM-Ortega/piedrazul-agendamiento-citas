import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { map, Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { MedicalRecord } from '../../shared/models/dtos/medicalRecord.dto';
import { Doctor } from '../../shared/models/interfaces/doctor.model';
import { Patient } from '../../shared/models/interfaces/patient.model';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  medicalRecords = signal<MedicalRecord[]>([]);

  /**
   * Obtiene los datos del doctor autenticado actualmente (según el token de sesión).
   * Mapea la respuesta cruda del backend (`DoctorMeResponse`) al modelo `Doctor`
   * @returns Observable con los datos del doctor logueado.
   */
  getMe(): Observable<Doctor> {
    interface DoctorMeResponse {
      id: string;
      name: string;
      specialty: string;
      appointmentInterval: number;
      laborStart: string;
      laborEnd: string;
      status: boolean;
    }

    return this.http
      .get<DoctorMeResponse>(`${this.apiUrl}/doctor/doctors/me`)
      .pipe(
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
        )
      );
  }
  /** 
  getDoctorById(doctorId: string): Observable<dtoDoctor> {
    return this.http.get<dtoDoctor>(
      `${this.apiUrl}/doctor/doctors/${doctorId}`
    );
  }

  getSchedulesByDoctor(doctorId: string): Observable<dtoSchedule[]> {
    return this.http.get<dtoSchedule[]>(
      `${this.apiUrl}/doctor/schedules/${doctorId}`
    );
  }
*/

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
    state: string
  ): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/appointments/${appointmentId}/mark-as-attended`,
      { appointmentState: state }
    );
  }

  /**
   * Marca una cita como no asistida (el paciente no se presentó).
   *
   * @param appointmentId - ID de la cita a actualizar.
   * @param state - Nuevo estado a enviar en el body (ej. `'NO_ASISTIO'`).
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
   * Carga el historial clínico de un paciente y lo guarda en el signal `medicalRecords`.
   * @param patientId - ID del paciente cuyo historial se desea cargar.
   */
  loadMedicalRecordsByPatient(patientId: string): void {
    this.http
      .get<MedicalRecord[]>(
        `${this.apiUrl}/clinical-history/patient/${patientId}`
      )
      .subscribe((records) => this.medicalRecords.set(records));
  }

  /**
   * Agrega un nuevo registro (observación) al historial clínico asociado a una cita.
   *
   * @param idAppointment - ID de la cita sobre la que se registra la observación.
   * @param description - Texto de la observación clínica.
   * @returns Observable con el registro clínico recién creado.
   */
  addMedicalRecord(
    idAppointment: string,
    description: string
  ): Observable<MedicalRecord> {
    return this.http.post<MedicalRecord>(`${this.apiUrl}/clinical-history`, {
      idAppointment,
      description,
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
}
