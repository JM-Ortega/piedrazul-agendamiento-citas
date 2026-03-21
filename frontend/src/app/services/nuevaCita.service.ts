import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of, tap, throwError } from 'rxjs';
import { Patient } from '../models/patient.model';
import { NewAppointment } from '../pages/nueva-cita/DTO/newAppointment';
import { SpecialtyDoctor } from '../pages/nueva-cita/DTO/specialty-doctor';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = 'https://API';

  readonly doctors = signal<SpecialtyDoctor[]>([]);

  getDoctors(): Observable<SpecialtyDoctor[]> {
    if (this.doctors().length > 0) {
      return of(this.doctors());
    }

    //return this.http.get<Doctor[]>(`${this.apiUrl}/Doctor/doctors`).pipe(
    return of<SpecialtyDoctor[]>([
      {
        specialty: 'terapista',
        doctorId: '2',
        doctorName: 'Ibis Gonzales'
      },
      {
        specialty: 'neurocirujano',
        doctorId: '3',
        doctorName: 'Clara Gomez'
      }
    ]).pipe(
      tap(data => this.doctors.set(data))
    );
  }

  //return this.http.get<Patient>(`${this.apiUrl}/Paciente/buscarPorId/${documentId}`);
  getPatientByDocument(documentId: string): Observable<Patient | null> {
    const paciente: Patient = {
      id: '2',
      documentId: '12345678',
      firstName: 'Maryuri',
      lastName: 'Fernandez Salazar',
      phone: '3172356973',
      gender: 'Mujer'
    };
    return of(paciente);
  
   //return throwError(() => ({ status: 404 }));
  }

  getSpecialtiesWithDoctor(): Observable<SpecialtyDoctor[]> {
    // return this.http.get<SpecialtyDoctor[]>(`${this.apiUrl}/Doctor/specialties-with-doctor`);
    return of<SpecialtyDoctor[]>([
      { specialty: 'Cardiología',     doctorId: '2', doctorName: 'Ibis Gonzales' },
      { specialty: 'Neurología',      doctorId: '3', doctorName: 'Clara Gomez'   },
      { specialty: 'Pediatría',       doctorId: '2', doctorName: 'Ibis Gonzales' },
      { specialty: 'Traumatología',   doctorId: '3', doctorName: 'Clara Gomez'   },
    ]);
  }

  getSpecialties(): Observable<string[]> {
    // return this.http.get<string[]>(`${this.apiUrl}/Doctor/specialties`);
    return of(['Cardiología', 'Neurología', 'Pediatría', 'Traumatología']);
  }

  getDoctorsBySpecialty(specialty: string): Observable<{ doctorId: string; doctorName: string }[]> {
    // return this.http.get<...>(`${this.apiUrl}/Doctor/by-specialty/${specialty}`);
    const map: Record<string, { doctorId: string; doctorName: string }[]> = {
      'Cardiología':   [{ doctorId: '2', doctorName: 'Ibis Gonzales' }],
      'Neurología':    [{ doctorId: '3', doctorName: 'Clara Gomez'   }],
      'Pediatría':     [{ doctorId: '2', doctorName: 'Ibis Gonzales' }],
      'Traumatología': [{ doctorId: '3', doctorName: 'Clara Gomez'   }],
    };
    return of(map[specialty] ?? []);
  }

  //return this.http.get<string[]>(`${this.apiUrl}/Doctor/${doctorId}/available-dates`);
  getAvailableDates(doctorId: string): Observable<string[]> {
    const dates: string[] = ['2025-07-14', '2025-07-15', '2025-07-16'];
    return of(dates);
  }

  //return this.http.get<string[]>(`${this.apiUrl}/Doctor/${doctorId}/available-slots`,{ params: { date } });
  getAvailableSlots(doctorId: string, date: string): Observable<string[]> {
    const slots: string[] = ['07:00', '07:20', '07:40', '08:00'];
    return of(slots);
  }

  addAppointment(data: NewAppointment): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/Appointment`, data);
  }

  //return this.http.post<string>(`${this.apiUrl}/Patient`, patient);
  addPatient(patient: Omit<Patient, 'id'>): Observable<string> {
    return of('1');
  }
}