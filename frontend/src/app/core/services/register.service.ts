import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of } from 'rxjs';
import { shareReplay, tap } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { MedicalRecord } from '../../shared/models/dtos/medicalRecord.dto';
import { Patient } from '../../shared/models/interfaces/patient.model';

/**
 * Estado público de un documento: permite saber si el paciente existe, si ya
 * tiene cuenta y de qué rol/usuario dispone, sin exponer el documento completo.
 */
export interface PatientPublicResponse {
  identificationType: string | null;
  maskedDocument: string;
  firstName: string | null;
  lastName: string | null;
  patientExists: boolean;
  hasUserAccount: boolean;
  hasSystemUser: boolean;
  hasPatientRole: boolean;
}

/** Campos que el paciente puede modificar de su propio perfil. */
export type UpdatePatientRequest = Pick<
  Patient,
  | 'firstName'
  | 'lastName'
  | 'phone'
  | 'sex'
  | 'birthDate'
  | 'email'
  | 'guardianPhone'
>;

/**
 * Servicio de pacientes: registro, vinculación de cuenta, catálogos de
 * formulario y datos del paciente autenticado (`me`).
 */
@Injectable({ providedIn: 'root' })
export class PatientService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  medicalRecords = signal<MedicalRecord[]>([]);
  error = signal<string | null>(null);
  /** Tipos de documento disponibles, cargados con {@link loadDocumentTypes}. */
  readonly documentTypes = signal<string[]>([]);
  /** Paciente autenticado en memoria; `null` hasta que {@link getMe} lo cargue. */
  readonly me = signal<Patient | null>(null);
  private me$: Observable<Patient> | null = null;
  /** Opciones de sexo disponibles, cargadas con {@link loadSexOptions}. */
  readonly sexOptions = signal<string[]>([]);

  /** Carga las opciones de sexo en {@link sexOptions}; no repite la carga si ya existen. */
  loadSexOptions(): void {
    if (this.sexOptions().length > 0) return;
    this.getAllSexOptions().subscribe({
      next: (options) => this.sexOptions.set(options),
      error: () => {
        console.error('Error al cargar las opciones de sexo');
      },
    });
  }

  /**
   * Obtiene las opciones de género.
   */
  getAllSexOptions(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/gender-types`);
  }

  /** Carga los tipos de documento en {@link documentTypes}; no repite la carga si ya existen. */
  loadDocumentTypes(): void {
    if (this.documentTypes().length > 0) return;
    this.getAllDocumentTypes().subscribe({
      next: (types) => this.documentTypes.set(types),
      error: () => {
        console.error('Error al cargar los tipos de documento');
      },
    });
  }

  /**
   * Devuelve los datos del paciente autenticado. La primera llamada cachea
   * el resultado en memoria para el resto de la sesión; las siguientes
   * reutilizan esa caché sin repetir la petición.
   */
  getMe(): Observable<Patient> {
    const cached = this.me();
    if (cached) return of(cached);

    if (!this.me$) {
      this.me$ = this.http.get<Patient>(`${this.apiUrl}/patients/me`).pipe(
        tap((patient) => this.me.set(patient)),
        shareReplay(1)
      );
    }
    return this.me$;
  }

  /**
   * Reemplaza los datos del paciente autenticado y refresca la caché `me`
   * con la respuesta del backend.
   *
   * @param data campos editables del perfil (el documento no se puede modificar)
   */
  updateMe(data: UpdatePatientRequest): Observable<Patient> {
    return this.http
      .put<Patient>(`${this.apiUrl}/patients/me`, data)
      .pipe(tap((patient) => this.me.set(patient)));
  }

  /** Borra la caché del paciente autenticado; la próxima llamada a {@link getMe} consulta de nuevo al backend. */
  invalidateMeCache(): void {
    this.me.set(null);
    this.me$ = null;
  }

  /**
   * Consulta el estado público de un documento (si el paciente existe y si ya
   * tiene cuenta), usado para decidir el flujo de registro.
   *
   * @param documentNumber número de documento a consultar
   */
  getPublicByDocument(
    documentNumber: string
  ): Observable<PatientPublicResponse> {
    return this.http.get<PatientPublicResponse>(
      `${this.apiUrl}/patients/document/${documentNumber}/public`
    );
  }

  /**
   * Crea un paciente nuevo junto con su cuenta de usuario.
   *
   * @param data datos del paciente y credenciales de la cuenta
   */
  createWithUser(data: {
    username: string;
    password: string;
    identificationType: string;
    identification: string;
    firstName: string;
    lastName: string;
    phone: string;
    email?: string;
    sex: string;
    birthDate: string;
    guardianPhone?: string;
  }): Observable<Patient> {
    return this.http.post<Patient>(`${this.apiUrl}/patients/with-user`, data);
  }

  /**
   * Solicita un código OTP para vincular una cuenta o completar el registro
   * de un paciente existente.
   *
   * @param data.identification documento del paciente
   */
  requestLinkUserAccountCode(data: {
    identification: string;
  }): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/patients/link-user-account/request-code`,
      data
    );
  }

  /**
   * Confirma el código OTP y, según el estado del paciente, crea o vincula su
   * cuenta de usuario. Los campos opcionales completan los datos que faltaban
   * del paciente.
   *
   * @param data.identification documento del paciente
   * @param data.code código OTP recibido
   */
  confirmLinkUserAccount(data: {
    identification: string;
    code: string;
    password?: string;
    sex?: string;
    birthDate?: string;
    guardianPhone?: string;
  }): Observable<Patient> {
    return this.http.post<Patient>(
      `${this.apiUrl}/patients/link-user-account/confirm`,
      data
    );
  }

  /** Obtiene del backend los tipos de documento válidos. */
  getAllDocumentTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/document-types`);
  }

  /**
   * Resetea todo el estado en memoria y la caché del servicio antes de cerrar sesión.
   */
  clearAllData(): void {
    this.me.set(null);
    this.me$ = null;
    this.medicalRecords.set([]);
    this.error.set(null);
  }
}
