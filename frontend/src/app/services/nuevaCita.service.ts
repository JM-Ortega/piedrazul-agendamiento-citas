import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, of, tap, throwError } from 'rxjs';
import { DoctorSelected } from '../pages/nueva-cita/DTO/doctorSelected.model';
import { Patient } from '../models/patient.model';
import { NewAppointment } from '../pages/nueva-cita/DTO/newAppointment.model';

@Injectable({ providedIn: 'root' })
export class NuevaCitaService {
  private http = inject(HttpClient);
  private apiUrl = 'https://API';

  readonly doctors = signal<DoctorSelected[]>([]);

  getDoctors(): Observable<DoctorSelected[]> {
    if (this.doctors().length > 0) {
      return of(this.doctors());
    }

    //return this.http.get<Doctor[]>(`${this.apiUrl}/Doctor/doctors`).pipe(
    return of<DoctorSelected[]>([
      {
        id: '2',
        name: 'Ibis Gonzales',
        specialty: 'terapista'
      },
      {
        id: '3',
        name: 'Clara Gomez',
        specialty: 'neurocirujano'
      }
    ]).pipe(
      // Sincroniza el signal interno cada vez que llegan datos frescos
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