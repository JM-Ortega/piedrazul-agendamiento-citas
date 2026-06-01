import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Patient } from '../../shared/models/interfaces/patient.model';
import { MedicalRecord } from '../../shared/models/dtos/medicalRecord.dto';

export interface PatientPublicResponse {
  documentType: string | null;
  maskedDocument: string;
  firstName: string | null;
  lastName: string | null;
  patientExists: boolean;
  hasUserAccount: boolean;
  hasSystemUser: boolean;
}

@Injectable({ providedIn: 'root' })
export class PatientService {
  private http = inject(HttpClient);
  private apiUrl = environment.apiUrl;

  medicalRecords = signal<MedicalRecord[]>([]);
  error = signal<string | null>(null);
  readonly documentTypes = signal<string[]>([]);

  loadDocumentTypes(): void {
    if (this.documentTypes().length > 0) return;
    this.getAllDocumentTypes().subscribe({
      next: (types) => this.documentTypes.set(types),
      error: () => {},
    });
  }

  getMe(): Observable<Patient> {
    return this.http.get<Patient>(`${this.apiUrl}/patients/me`);
  }

  loadMyMedicalRecords(patientId: string | null): void {
    this.http.get<MedicalRecord[]>(`${this.apiUrl}/clinical-history/patient/${patientId}`)
      .subscribe({
        next: (records) => this.medicalRecords.set(records),
        error: (err) => {
          if (err.status === 0) {
            this.error.set('No se pudo conectar con el servidor.');
          } else {
            this.error.set(err.error?.message ?? 'Error al cargar el historial.');
          }
        }
      });
  }

  getByDocument(documentNumber: string): Observable<Patient> {
    return this.http.get<Patient>(
      `${this.apiUrl}/patients/document/${documentNumber}`,
    );
  }

  // consulta el estado público del documento
  getPublicByDocument(
    documentNumber: string,
  ): Observable<PatientPublicResponse> {
    return this.http.get<PatientPublicResponse>(
      `${this.apiUrl}/patients/document/${documentNumber}/public`,
    );
  }

  // crea paciente nuevo con cuenta nueva
  createWithUser(data: {
    username: string;
    password: string;
    documentType: string;
    documentNumber: string;
    firstName: string;
    lastName: string;
    phone: string;
    email?: string;
    gender: string;
    birthDate: string;
    guardianPhone?: string;
  }): Observable<Patient> {
    return this.http.post<Patient>(`${this.apiUrl}/patients/with-user`, data);
  }

  // solicita OTP para vincular o completar registro
  requestLinkUserAccountCode(data: {
    documentNumber: string;
  }): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/patients/link-user-account/request-code`,
      data,
    );
  }

  // confirma OTP y crea o vincula la cuenta según el caso
  confirmLinkUserAccount(data: {
    documentNumber: string;
    code: string;
    password?: string;
  }): Observable<Patient> {
    return this.http.post<Patient>(
      `${this.apiUrl}/patients/link-user-account/confirm`,
      data,
    );
  }

  getAllDocumentTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/document-types`);
  }
}