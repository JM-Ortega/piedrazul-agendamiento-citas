import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { MedicalRecord } from '../../shared/models/dtos/medicalRecord.dto';
import { Patient } from '../../shared/models/interfaces/patient.model';

export interface PatientPublicResponse {
  identificationType: string | null;
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
      error: () => {
        console.error('Error al cargar los tipos de documento');
      },
    });
  }

  getMe(): Observable<Patient> {
    return this.http.get<Patient>(`${this.apiUrl}/patients/me`);
  }

  // consulta el estado público del documento
  getPublicByDocument(
    documentNumber: string
  ): Observable<PatientPublicResponse> {
    return this.http.get<PatientPublicResponse>(
      `${this.apiUrl}/patients/document/${documentNumber}/public`
    );
  }

  // crea paciente nuevo con cuenta nueva
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

  // solicita OTP para vincular o completar registro
  requestLinkUserAccountCode(data: {
    identification: string;
  }): Observable<void> {
    return this.http.post<void>(
      `${this.apiUrl}/patients/link-user-account/request-code`,
      data
    );
  }

  // confirma OTP y crea o vincula la cuenta según el caso
  confirmLinkUserAccount(data: {
    identification: string;
    code: string;
    password?: string;
  }): Observable<Patient> {
    return this.http.post<Patient>(
      `${this.apiUrl}/patients/link-user-account/confirm`,
      data
    );
  }

  getAllDocumentTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/patients/document-types`);
  }
}
