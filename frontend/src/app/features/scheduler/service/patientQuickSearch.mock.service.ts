import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { PatientQuickResult } from '../../../shared/models/dtos/patientQuickResult.dto';

/**
 * ⚠️ SERVICIO TEMPORAL (MOCK) ⚠️
 *
 * Sustituye a los endpoints de búsqueda de pacientes y filtrado de citas
 * por paciente, que aún no existen en el backend.
 *
 * TODO(backend): reemplazar `searchPatients` por
 *   GET /api/patients/search?query=...
 * TODO(backend): reemplazar `getAppointmentsByPatient` por el filtro
 *   real de citas (el backend ya soporta `idPatient` en
 *   ListAppointmentFiltersRequest — solo falta exponerlo en
 *   SchedulerService.loadAllAppointments).
 *
 * Ambos métodos devuelven el mismo tipo `Observable<T>` que tendrá la
 * versión real, para que el reemplazo sea solo un cambio de
 * implementación interna, sin tocar el componente que los consume.
 */
@Injectable({ providedIn: 'root' })
export class PatientQuickSearchMockService {
  private readonly MOCK_PATIENTS: PatientQuickResult[] = [
    {
      id: 'p1',
      firstName: 'Alberto',
      lastName: 'Martínez',
      documentNumber: '45678912',
      phone: '3201237890',
      sex: 'MASCULINO',
      appointmentsCount: 2,
    },
    {
      id: 'p2',
      firstName: 'Pedro',
      lastName: 'Morales',
      documentNumber: '75395128',
      phone: '3197531596',
      sex: 'MASCULINO',
      appointmentsCount: 1,
    },
    {
      id: 'p3',
      firstName: 'Alejandra',
      lastName: 'Ríos',
      documentNumber: '33221144',
      phone: '3159876543',
      sex: 'FEMENINO',
      appointmentsCount: 3,
    },
    {
      id: 'p4',
      firstName: 'María',
      lastName: 'López',
      documentNumber: '10203040',
      phone: '3001112233',
      sex: 'FEMENINO',
      appointmentsCount: 5,
    },
  ];

  private readonly MOCK_APPOINTMENTS: Record<string, AppointmentsPatient[]> = {
    p1: [
      {
        idAppointment: 'a1',
        date: '2026-10-06',
        startTime: '09:00:00',
        patientFirstName: 'Alberto',
        patientLastName: 'Martínez',
        documentNumber: '45678912',
        doctorName: 'María López',
        specialty: 'CARDIOLOGIA',
        appointmentState: 'AGENDADA',
      } as AppointmentsPatient,
      {
        idAppointment: 'a2',
        date: '2026-11-11',
        startTime: '10:00:00',
        patientFirstName: 'Alberto',
        patientLastName: 'Martínez',
        documentNumber: '45678912',
        doctorName: 'Carlos Ramírez',
        specialty: 'MEDICINA_GENERAL',
        appointmentState: 'AGENDADA',
      } as AppointmentsPatient,
    ],
    p2: [
      {
        idAppointment: 'a3',
        date: '2026-09-02',
        startTime: '08:30:00',
        patientFirstName: 'Pedro',
        patientLastName: 'Morales',
        documentNumber: '75395128',
        doctorName: 'Luis Herrera',
        specialty: 'FISIOTERAPIA',
        appointmentState: 'ATENDIDA',
      } as AppointmentsPatient,
    ],
  };

  searchPatients(query: string): Observable<PatientQuickResult[]> {
    const q = query.trim().toLowerCase();
    if (!q) return of([]);
    const results = this.MOCK_PATIENTS.filter(
      (p) =>
        `${p.firstName} ${p.lastName}`.toLowerCase().includes(q) ||
        p.documentNumber.includes(q)
    );
    return of(results).pipe(delay(250));
  }

  getAppointmentsByPatient(
    patientId: string
  ): Observable<AppointmentsPatient[]> {
    return of(this.MOCK_APPOINTMENTS[patientId] ?? []).pipe(delay(250));
  }
}
