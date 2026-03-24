import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Calendar,
  ClipboardCheck,
  LucideAngularModule,
  Settings,
  Stethoscope,
} from 'lucide-angular';
import { AppService } from '../../services/app.service';

@Component({
  selector: 'app-acceso',
  standalone: true,
  imports: [CommonModule, LucideAngularModule],
  templateUrl: './acceso.component.html',
})
export class AccesoComponent {
  private appService = inject(AppService);
  private router = inject(Router);

  readonly Calendar = Calendar;
  readonly ClipboardCheck = ClipboardCheck;
  readonly Stethoscope = Stethoscope;
  readonly Settings = Settings;

  showModal = signal<'patient' | 'scheduler' | 'admin' | 'doctor' | null>(null);
  email = signal('');
  password = signal('');
  schedulerPass = signal('');
  adminPass = signal('');
  doctorEmail = signal('');
  doctorPass = signal('');
  error = signal('');

  openModal(type: 'patient' | 'scheduler' | 'admin' | 'doctor'): void {
    this.closeAll();
    this.showModal.set(type);
  }

  closeAll(): void {
    this.showModal.set(null);
    this.error.set('');
    this.email.set('');
    this.password.set('');
    this.schedulerPass.set('');
    this.adminPass.set('');
    this.doctorEmail.set('');
    this.doctorPass.set('');
  }

  loginPatient(): void {
    if (this.appService.login(this.email(), this.password())) {
      this.router.navigate(['/paciente']);
    } else {
      this.error.set('Correo o contraseña incorrectos');
    }
  }

  loginScheduler(): void {
    if (this.appService.loginAsScheduler(this.schedulerPass())) {
      this.router.navigate(['/agendador']);
    } else {
      this.error.set('Contraseña incorrecta');
    }
  }

  loginAdmin(): void {
    if (this.appService.loginAsAdmin(this.adminPass())) {
      this.router.navigate(['/admin']);
    } else {
      this.error.set('Contraseña incorrecta');
    }
  }

  loginDoctor(): void {
    if (this.appService.loginAsDoctor(this.doctorEmail(), this.doctorPass())) {
      this.router.navigate(['/medico']);
    } else {
      this.error.set('Correo o contraseña incorrectos');
    }
  }

  goRegister(): void {
    this.closeAll();
    this.router.navigate(['/paciente/registro']);
  }
}
