    import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Calendar,
  Clock,
  LucideAngularModule,
  Phone,
  Shield,
} from 'lucide-angular';
import { AppService } from '../../services/app.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, LucideAngularModule],
  templateUrl: './home.component.html',
})
export class HomeComponent {
  private appService = inject(AppService);
  private router = inject(Router);

  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly Shield = Shield;
  readonly Phone = Phone;

  showModal = signal(false);
  email = signal('');
  password = signal('');
  error = signal('');

  openModal(): void {
    this.showModal.set(true);
    this.error.set('');
  }

  closeModal(): void {
    this.showModal.set(false);
    this.error.set('');
    this.email.set('');
    this.password.set('');
  }

  loginPatient(): void {
    if (this.appService.login(this.email(), this.password())) {
      this.router.navigate(['/paciente']);
    } else {
      this.error.set('Correo o contraseña incorrectos');
    }
  }

  goRegister(): void {
    this.closeModal();
    this.router.navigate(['/paciente/registro']);
  }

  goAcceso(): void {
    this.router.navigate(['/acceso']);
  }
}
