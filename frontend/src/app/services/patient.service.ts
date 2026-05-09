import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Patient } from '../models/interfaces/patient.model';

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

  getMe(): Observable<Patient> {
    return this.http.get<Patient>(`${this.apiUrl}/patients/me`);
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
}
