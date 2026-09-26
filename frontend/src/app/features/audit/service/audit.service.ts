import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import {
  AppointmentAuditLog,
  UserManagementAuditLog,
  MedicalRecordAuditLog,
} from '../models/audit.dto';

const MOCK_APPOINTMENT_LOGS: AppointmentAuditLog[] = [
  {
    id: 'apt-001',
    timestamp: '2026-09-18T08:15:00',
    name: 'Camila Restrepo',
    role: 'agendador',
    userId: '1085647123',
    action: 'scheduled',
    status: 'success',
  },
  {
    id: 'apt-002',
    timestamp: '2026-09-18T10:42:00',
    name: 'Camila Restrepo',
    role: 'agendador',
    userId: '1085647123',
    action: 'scheduled',
    status: 'success',
  },
  {
    id: 'apt-003',
    timestamp: '2026-09-19T14:05:00',
    name: 'Jorge Salazar',
    role: 'paciente',
    userId: '1098234567',
    action: 'cancelled',
    status: 'success',
  },
  {
    id: 'apt-004',
    timestamp: '2026-09-20T09:00:00',
    name: 'Laura Gómez',
    role: 'agendador',
    userId: '1085612345',
    action: 'scheduled',
    status: 'failed',
  },
  {
    id: 'apt-005',
    timestamp: '2026-09-21T16:30:00',
    name: 'Andrés Pardo',
    role: 'doctor',
    userId: '1091234789',
    action: 'cancelled',
    status: 'failed',
  },
];

const MOCK_USER_LOGS: UserManagementAuditLog[] = [
  {
    id: 'usr-001',
    timestamp: '2026-09-10T09:00:00',
    name: 'Diana Martínez',
    role: 'administrador',
    userId: '1075632112',
    action: 'created',
    status: 'success',
  },
  {
    id: 'usr-002',
    timestamp: '2026-09-12T11:20:00',
    name: 'Diana Martínez',
    role: 'administrador',
    userId: '1075632112',
    action: 'modified',
    status: 'success',
  },
  {
    id: 'usr-003',
    timestamp: '2026-09-14T15:45:00',
    name: 'Diana Martínez',
    role: 'administrador',
    userId: '1075632112',
    action: 'deactivated',
    status: 'success',
  },
  {
    id: 'usr-004',
    timestamp: '2026-09-17T08:30:00',
    name: 'Sebastián Rojas',
    role: 'administrador',
    userId: '1091223344',
    action: 'activated',
    status: 'failed',
  },
  {
    id: 'usr-005',
    timestamp: '2026-09-21T13:10:00',
    name: 'Diana Martínez',
    role: 'administrador',
    userId: '1075632112',
    action: 'modified',
    status: 'success',
  },
];

const MOCK_RECORD_LOGS: MedicalRecordAuditLog[] = [
  {
    id: 'rec-001',
    timestamp: '2026-09-11T10:00:00',
    name: 'Andrés Pardo',
    role: 'doctor',
    userId: '1091234789',
    action: 'created',
    status: 'success',
  },
  {
    id: 'rec-002',
    timestamp: '2026-09-13T09:15:00',
    name: 'Andrés Pardo',
    role: 'doctor',
    userId: '1091234789',
    action: 'created',
    status: 'success',
  },
  {
    id: 'rec-003',
    timestamp: '2026-09-16T12:40:00',
    name: 'Valentina Torres',
    role: 'doctor',
    userId: '1093456712',
    action: 'created',
    status: 'success',
  },
  {
    id: 'rec-004',
    timestamp: '2026-09-19T17:05:00',
    name: 'Valentina Torres',
    role: 'doctor',
    userId: '1093456712',
    action: 'created',
    status: 'failed',
  },
  {
    id: 'rec-005',
    timestamp: '2026-09-21T08:50:00',
    name: 'Andrés Pardo',
    role: 'doctor',
    userId: '1091234789',
    action: 'created',
    status: 'failed',
  },
];

/**
 * Servicio mock: misma forma que tendrá el servicio real contra el backend
 * (un Observable por cada uno de los 3 logs). Reemplazar los `of(...)` por
 * las llamadas HTTP correspondientes cuando el endpoint exista.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  getAppointmentLogs(): Observable<AppointmentAuditLog[]> {
    return of(MOCK_APPOINTMENT_LOGS).pipe(delay(300));
  }

  getUserManagementLogs(): Observable<UserManagementAuditLog[]> {
    return of(MOCK_USER_LOGS).pipe(delay(300));
  }

  getMedicalRecordLogs(): Observable<MedicalRecordAuditLog[]> {
    return of(MOCK_RECORD_LOGS).pipe(delay(300));
  }
}
