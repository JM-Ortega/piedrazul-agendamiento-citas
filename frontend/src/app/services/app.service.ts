import { computed, Injectable, signal } from '@angular/core';
import { Patient } from '../models/patient.model';
import { UserRole } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class AppService {
  private _currentPatient = signal<Patient | null>({
    id: '6d7d17f0-6a3e-43d2-83da-e57849a79266',
    documentType: 'CEDULA',
    documentNumber: '12345678',
    firstName: 'Maria',
    lastName: 'Lopez',
    phone: '3001234567',
    gender: 'FEMENINO',
    birthDate: '1990-05-15',
    email: 'maria.lopez@email.com'
  });
  readonly currentPatient = computed(() => this._currentPatient());
  private _currentUser = signal<Patient | null>(null);
  private _currentRole = signal<UserRole>(null);

  readonly currentUser = computed(() => this._currentUser());
  readonly currentRole = computed(() => this._currentRole());

  login(email: string, password: string): boolean {
    //funcionalidad de login de paciente, conectar con back
    if (password === 'patient123' && email === 'maria.lopez@email.com') {
      this._currentRole.set('patient');
      return true;
    }
    return false;
  }

  loginAsScheduler(password: string): boolean {
    //se deja así para probar por ahora en front
    if (password === 'scheduler123') {
      this._currentRole.set('scheduler');
      return true;
    }
    return false;
  }

  loginAsAdmin(password: string): boolean {
    if (password === 'admin123') {
      this._currentRole.set('admin');
      return true;
    }
    return false;
  }

  loginAsDoctor(email: string, password: string): boolean {
    //funcionalidad de login, conectar con back
    return false;
  }

  logout(): void {
    this._currentUser.set(null);
    this._currentRole.set(null);
  }
}
