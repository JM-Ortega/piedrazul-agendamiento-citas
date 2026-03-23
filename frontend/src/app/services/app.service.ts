import { computed, Injectable, signal } from '@angular/core';
import { Patient } from '../models/patient.model';
import { UserRole } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class AppService {
  private _currentUser = signal<Patient | null>(null);
  private _currentRole = signal<UserRole>(null);

  readonly currentUser = computed(() => this._currentUser());
  readonly currentRole = computed(() => this._currentRole());

  login(email: string, password: string): boolean {
    //funcionalidad de login de paciente, conectar con back
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
