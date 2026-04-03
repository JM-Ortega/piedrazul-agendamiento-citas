import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  LucideAngularModule,
  Search,
  CheckCircle,
  ArrowLeft,
  Eye,
  EyeOff,
  UserPlus,
  KeyRound,
} from 'lucide-angular';
import {
  PatientPublicResponse,
  PatientService,
} from '../../services/patient.service';
import Keycloak from 'keycloak-js';

type RegistroStep = 1 | 2 | 3;
type PatientStatus = 'idle' | 'found' | 'already-linked' | 'not-found';

@Component({
  selector: 'app-registro',
  standalone: true,
  imports: [CommonModule, FormsModule, LucideAngularModule],
  templateUrl: './registro.component.html',
  styleUrl: './registro.component.css',
})
export class RegistroComponent {
  private patientService = inject(PatientService);
  private keycloak = inject(Keycloak);
  private router = inject(Router);

  readonly Search = Search;
  readonly CheckCircle = CheckCircle;
  readonly ArrowLeft = ArrowLeft;
  readonly Eye = Eye;
  readonly EyeOff = EyeOff;
  readonly UserPlus = UserPlus;
  readonly KeyRound = KeyRound;

  step = signal<RegistroStep>(1);
  isLoading = signal(false);
  errorMessage = signal('');
  successMessage = signal('');
  showPassword = signal(false);
  showConfirmPassword = signal(false);
  today = signal(new Date().toISOString().split('T')[0]);

  documentNumber = signal('');
  patientStatus = signal<PatientStatus>('idle');
  foundPatient = signal<PatientPublicResponse | null>(null);

  form = signal({
    documentType: '',
    firstName: '',
    lastName: '',
    phone: '',
    gender: '',
    birthDate: '',
    guardianPhone: '',
    email: '',
  });

  password = signal('');
  confirmPassword = signal('');
  verificationCode = signal('');

  errors = signal<Record<string, string>>({});

  readonly isNewPatient = computed(() => this.patientStatus() === 'not-found');
  readonly isExistingPatient = computed(() => this.patientStatus() === 'found');
  readonly isAlreadyLinked = computed(
    () => this.patientStatus() === 'already-linked',
  );

  readonly isMinorPatient = computed(() => {
    if (!this.isNewPatient()) return false;
    return this.isMinorByBirthDate(this.form().birthDate);
  });

  readonly displayName = computed(() => {
    const p = this.foundPatient();
    if (p) return `${p.firstName} ${p.lastName}`;
    const f = this.form();
    if (f.firstName || f.lastName) {
      return `${f.firstName} ${f.lastName}`.trim();
    }
    return '';
  });

  searchPatient(): void {
    this.patientStatus.set('idle');
    this.foundPatient.set(null);
    this.errorMessage.set('');
    this.successMessage.set('');
    this.errors.set({});

    const doc = this.documentNumber().trim();

    if (!/^[0-9]{6,12}$/.test(doc)) {
      this.errors.set({
        documentNumber: 'Ingresa un número de documento válido (6-12 dígitos)',
      });
      return;
    }

    this.isLoading.set(true);

    this.patientService.getPublicByDocument(doc).subscribe({
      next: (patient) => {
        this.isLoading.set(false);
        this.foundPatient.set(patient);

        if (patient.hasUserAccount) {
          this.patientStatus.set('already-linked');
          return;
        }

        this.patientStatus.set('found');
      },
      error: (err) => {
        this.isLoading.set(false);

        if (err.status === 404) {
          this.patientStatus.set('not-found');
        } else if (err.status === 0) {
          this.errorMessage.set(
            'No se pudo conectar con el servidor. Intenta más tarde.',
          );
        } else {
          this.errorMessage.set(
            'Error al buscar el documento. Intenta de nuevo.',
          );
        }
      },
    });
  }

  onDocumentChange(value: string): void {
    this.documentNumber.set(value);
    this.patientStatus.set('idle');
    this.foundPatient.set(null);
    this.errorMessage.set('');
    this.successMessage.set('');
    this.errors.set({});
    this.password.set('');
    this.confirmPassword.set('');
    this.verificationCode.set('');
    this.resetForm();
  }

  goToStep2(): void {
    this.errorMessage.set('');
    this.successMessage.set('');

    if (this.patientStatus() === 'idle') {
      return;
    }

    if (this.patientStatus() === 'already-linked') {
      return;
    }

    if (!this.validateStep1()) {
      return;
    }

    this.step.set(2);
  }

  goBack(): void {
    if (this.step() === 3) {
      this.step.set(2);
      this.errorMessage.set('');
      this.successMessage.set('');
      this.errors.set({});
      return;
    }

    if (this.step() === 2) {
      this.step.set(1);
      this.password.set('');
      this.confirmPassword.set('');
      this.verificationCode.set('');
      this.errors.set({});
      this.errorMessage.set('');
      this.successMessage.set('');
      return;
    }

    this.router.navigate(['/']);
  }

  submit(): void {
    if (!this.validateStep2()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    if (this.isExistingPatient()) {
      this.patientService
        .requestLinkUserAccountCode({
          documentNumber: this.documentNumber(),
        })
        .subscribe({
          next: () => {
            this.isLoading.set(false);
            this.successMessage.set(
              'Se generó un código de verificación. Por ahora revísalo en la consola del backend.',
            );
            this.step.set(3);
          },
          error: (err) => this.handleError(err),
        });
      return;
    }

    const f = this.form();

    this.patientService
      .createWithUser({
        username: this.documentNumber(),
        password: this.password(),
        documentType: f.documentType,
        documentNumber: this.documentNumber(),
        firstName: f.firstName,
        lastName: f.lastName,
        phone: f.phone,
        email: f.email.trim() || undefined,
        gender: f.gender,
        birthDate: f.birthDate,
        guardianPhone: f.guardianPhone.trim() || undefined,
      })
      .subscribe({
        next: () => this.onSuccess(),
        error: (err) => this.handleError(err),
      });
  }

  confirmCode(): void {
    if (!this.validateStep3()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    this.patientService
      .confirmLinkUserAccount({
        documentNumber: this.documentNumber(),
        code: this.verificationCode(),
        password: this.password(),
      })
      .subscribe({
        next: () => this.onSuccess(),
        error: (err) => this.handleError(err),
      });
  }

  setFormField(key: string, value: string): void {
    const numericFields = ['phone', 'guardianPhone'];
    const normalizedValue = numericFields.includes(key)
      ? value.replace(/[^\d]/g, '')
      : value;

    this.form.update((f) => ({ ...f, [key]: normalizedValue }));

    if (this.step() === 1 && this.isNewPatient()) {
      this.validateStep1();
    }
  }

  getFormField(key: string): string {
    return (this.form() as any)[key] ?? '';
  }

  private onSuccess(): void {
    this.isLoading.set(false);
    this.keycloak.login({
      loginHint: this.documentNumber(),
      redirectUri: window.location.origin + '/paciente/agendar',
    });
  }

  private handleError(err: any): void {
    this.isLoading.set(false);

    const detail = err.error?.detail;
    const errorCode = err.error?.errorCode;

    if (err.status === 0) {
      this.errorMessage.set(
        'No se pudo conectar con el servidor. Intenta más tarde.',
      );
      return;
    }

    switch (errorCode) {
      case 'PATIENT_ALREADY_LINKED':
        this.errorMessage.set('Este paciente ya tiene una cuenta asociada.');
        break;
      case 'USERNAME_TAKEN':
        this.errorMessage.set(
          'Ya existe una cuenta asociada a este documento.',
        );
        break;
      case 'INVALID_VERIFICATION_CODE':
        this.errorMessage.set('El código de verificación es inválido.');
        break;
      case 'VERIFICATION_CODE_EXPIRED':
        this.errorMessage.set('El código de verificación expiró.');
        break;
      case 'VERIFICATION_CODE_BLOCKED':
        this.errorMessage.set(
          'El código fue bloqueado por exceso de intentos.',
        );
        break;
      default:
        this.errorMessage.set(
          detail || 'Ocurrió un error al crear la cuenta. Intenta de nuevo.',
        );
        break;
    }
  }

  private validateStep1(): boolean {
    const newErrors: Record<string, string> = {};

    if (this.isNewPatient()) {
      const f = this.form();

      if (!f.documentType) {
        newErrors['documentType'] = 'Selecciona el tipo de documento';
      }

      if (!f.firstName.trim()) {
        newErrors['firstName'] = 'Ingresa los nombres';
      }

      if (!f.lastName.trim()) {
        newErrors['lastName'] = 'Ingresa los apellidos';
      }

      if (!f.phone.trim()) {
        newErrors['phone'] = 'Ingresa el celular';
      } else if (!this.isValidPhone(f.phone)) {
        newErrors['phone'] = 'Ingresa un número de celular válido';
      }

      if (!f.gender) {
        newErrors['gender'] = 'Selecciona el género';
      }

      if (!f.birthDate) {
        newErrors['birthDate'] = 'Selecciona la fecha de nacimiento';
      } else if (f.birthDate > this.today()) {
        newErrors['birthDate'] = 'La fecha de nacimiento no puede ser futura';
      }

      if (
        f.email.trim() &&
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim())
      ) {
        newErrors['email'] = 'Ingresa un correo electrónico válido';
      }

      if (this.isMinorByBirthDate(f.birthDate)) {
        if (!f.guardianPhone.trim()) {
          newErrors['guardianPhone'] =
            'Para pacientes menores de edad debes ingresar el celular del acudiente';
        } else if (!this.isValidPhone(f.guardianPhone)) {
          newErrors['guardianPhone'] =
            'Ingresa un celular válido del acudiente';
        }
      }
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  private validateStep2(): boolean {
    const newErrors: Record<string, string> = {};

    if (this.password().length < 8) {
      newErrors['password'] = 'La contraseña debe tener al menos 8 caracteres';
    }

    if (this.password() !== this.confirmPassword()) {
      newErrors['confirmPassword'] = 'Las contraseñas no coinciden';
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  private validateStep3(): boolean {
    const newErrors: Record<string, string> = {};

    if (!/^\d{6}$/.test(this.verificationCode().trim())) {
      newErrors['verificationCode'] =
        'Ingresa el código de verificación de 6 dígitos';
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  private isValidPhone(phone: string): boolean {
    if (!phone) return false;

    const cleaned = phone.replace(/\s+/g, '');

    if (!/^\d+$/.test(cleaned)) return false;
    if (cleaned.length < 10 || cleaned.length > 13) return false;

    return true;
  }

  private isMinorByBirthDate(birthDate: string): boolean {
    if (!birthDate) return false;

    const today = new Date();
    const birth = new Date(birthDate);

    let age = today.getFullYear() - birth.getFullYear();
    const monthDiff = today.getMonth() - birth.getMonth();

    if (
      monthDiff < 0 ||
      (monthDiff === 0 && today.getDate() < birth.getDate())
    ) {
      age--;
    }

    return age < 18;
  }

  private resetForm(): void {
    this.form.set({
      documentType: '',
      firstName: '',
      lastName: '',
      phone: '',
      gender: '',
      birthDate: '',
      guardianPhone: '',
      email: '',
    });
  }
}
