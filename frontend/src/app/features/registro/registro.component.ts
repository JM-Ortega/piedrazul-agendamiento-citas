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
type PatientStatus =
  | 'idle'
  | 'found'
  | 'already-linked'
  | 'not-found'
  | 'existing-user';

@Component({
  selector: 'app-registro',
  standalone: true,
  imports: [CommonModule, FormsModule, LucideAngularModule],
  templateUrl: './registro.component.html',
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

  docInputWarning = signal('');
  private docWarnTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly DOC_MAX = 12;
  private readonly INVALID_DOC_CHARS = /[^a-zA-Z0-9]/g;

  handleDocInput(event: Event): void {
    const el    = event.target as HTMLInputElement;
    const raw   = el.value;
    const clean = raw.replace(this.INVALID_DOC_CHARS, '');
    if (clean !== raw) {
      el.value = clean;
      this.flashDocWarning('Solo se permiten letras y números, sin caracteres especiales');
    }
    if (clean.length > this.DOC_MAX) {
      el.value = clean.slice(0, this.DOC_MAX);
      this.flashDocWarning(`Solo se permiten máximo ${this.DOC_MAX} caracteres`);
    }
    this.onDocumentChange(el.value);
  }

  private flashDocWarning(text: string): void {
    this.docInputWarning.set(text);
    if (this.docWarnTimer) clearTimeout(this.docWarnTimer);
    this.docWarnTimer = setTimeout(() => this.docInputWarning.set(''), 3000);
  }

  readonly NAME_MAX = 30;
  readonly EMAIL_MAX = 100;
  readonly PHONE_MAX = 15;
  readonly PHONE_MIN = 7;

  firstNameLimitMsg = signal('');
  lastNameLimitMsg  = signal('');
  phoneLimitMsg     = signal('');
  emailLimitMsg     = signal('');
  guardianPhoneLimitMsg = signal('');

  private readonly VALID_NAME_REGEX  = /^[a-zA-ZáéíóúÁÉÍÓÚüÜñÑ\s\-]+$/;
  private readonly VALID_EMAIL_REGEX = /^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$/;
  private readonly INVALID_EMAIL_CHARS = /['"<>()[\]\\,;:{}|^~`!#$%&*=?/]/;

  private timers: Record<string, ReturnType<typeof setTimeout>> = {};

  readonly isNewPatient = computed(() => this.patientStatus() === 'not-found');
  readonly isExistingPatient = computed(() => this.patientStatus() === 'found');
  readonly isExistingSystemUser = computed(
    () => this.patientStatus() === 'existing-user',
  );
  readonly isAlreadyLinked = computed(
    () => this.patientStatus() === 'already-linked',
  );

  readonly requiresPassword = computed(
    () => this.isNewPatient() || this.isExistingPatient(),
  );

  readonly isMinorPatient = computed(() => {
    if (!this.isNewPatient()) return false;
    return this.isMinorByBirthDate(this.form().birthDate);
  });

  readonly displayName = computed(() => {
    const p = this.foundPatient();

    if (p?.firstName || p?.lastName) {
      return `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
    }

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

    if (!/^[a-zA-Z0-9]{1,12}$/.test(doc)) {
      this.errors.set({
        documentNumber: 'Ingresa un documento válido (máximo 12 caracteres alfanuméricos, sin caracteres especiales)',
      });
      return;
    }

    this.isLoading.set(true);

    this.patientService.getPublicByDocument(doc).subscribe({
      next: (patient) => {
        this.isLoading.set(false);
        this.foundPatient.set(patient);

        // paciente ya vinculado
        if (patient.hasUserAccount) {
          this.patientStatus.set('already-linked');
          return;
        }

        // paciente existente sin cuenta
        if (patient.patientExists) {
          this.patientStatus.set('found');
          return;
        }

        // existe usuario del sistema pero no paciente
        if (patient.hasSystemUser) {
          this.patientStatus.set('existing-user');
          return;
        }

        // fallback defensivo
        this.patientStatus.set('not-found');
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

    if (this.isExistingPatient() || this.isExistingSystemUser()) {
      this.patientService
        .requestLinkUserAccountCode({ documentNumber: this.documentNumber() })
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

    // paciente nuevo real
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
        // solo enviar password si aplica
        password: this.requiresPassword() ? this.password() : undefined,
      })
      .subscribe({
        next: () => this.onSuccess(),
        error: (err) => this.handleError(err),
      });
  }

  handleNameInput(event: Event, field: 'firstName' | 'lastName'): void {
    const el        = event.target as HTMLInputElement;
    const value     = el.value;
    const limitMsg  = field === 'firstName' ? this.firstNameLimitMsg : this.lastNameLimitMsg;

    if (value.length > this.NAME_MAX) {
      el.value = value.slice(0, this.NAME_MAX);
      this.updateFormField(field, el.value);
      this.flash(limitMsg, `Solo se permiten máximo ${this.NAME_MAX} caracteres`, field);
    } else {
      this.updateFormField(field, value);
      limitMsg.set('');
    }
  }

  handlePhoneInput(event: Event): void {
    const el     = event.target as HTMLInputElement;
    const digits = el.value.replace(/\D/g, '');

    if (digits.length > this.PHONE_MAX) {
      el.value = digits.slice(0, this.PHONE_MAX);
      this.updateFormField('phone', el.value);
      this.flash(this.phoneLimitMsg, `Solo se permiten máximo ${this.PHONE_MAX} dígitos`, 'phone');
    } else {
      el.value = digits;
      this.updateFormField('phone', digits);
      this.phoneLimitMsg.set('');
    }
  }

  handleEmailInput(event: Event): void {
    const el    = event.target as HTMLInputElement;
    const value = el.value;

    if (value.length > this.EMAIL_MAX) {
      el.value = value.slice(0, this.EMAIL_MAX);
      this.updateFormField('email', el.value);
      this.flash(this.emailLimitMsg, `Solo se permiten máximo ${this.EMAIL_MAX} caracteres`, 'email');
    } else {
      this.updateFormField('email', value);
      this.emailLimitMsg.set('');
    }
  }

  handleGuardianPhoneInput(event: Event): void {
    const el     = event.target as HTMLInputElement;
    const digits = el.value.replace(/\D/g, '');

    if (digits.length > this.PHONE_MAX) {
      el.value = digits.slice(0, this.PHONE_MAX);
      this.updateFormField('guardianPhone', el.value);
      this.flash(this.guardianPhoneLimitMsg, `Solo se permiten máximo ${this.PHONE_MAX} dígitos`, 'gphone');
    } else {
      el.value = digits;
      this.updateFormField('guardianPhone', digits);
      this.guardianPhoneLimitMsg.set('');
    }
  }

  setFormField(key: string, value: string): void {
    this.form.update((f) => ({ ...f, [key]: value }));
  }

  getFormField(key: string): string {
    return (this.form() as Record<string, string>)[key] ?? '';
  }

  private updateFormField(key: string, value: string): void {
    this.form.update((f) => ({ ...f, [key]: value }));
  }

  private flash(sig: ReturnType<typeof signal<string>>, text: string, key: string): void {
    sig.set(text);
    if (this.timers[key]) clearTimeout(this.timers[key]);
    this.timers[key] = setTimeout(() => sig.set(''), 3000);
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

    if (this.isNewPatient() || this.isExistingSystemUser()) {
      const f = this.form();

      if (!f.documentType) {
        newErrors['documentType'] = 'Selecciona el tipo de documento';
      }

      const firstNameErr = this.validateName(f.firstName, 'Nombres');
      if (firstNameErr) newErrors['firstName'] = firstNameErr;

      const lastNameErr = this.validateName(f.lastName, 'Apellidos');
      if (lastNameErr) newErrors['lastName'] = lastNameErr;

      const phoneErr = this.validatePhone(f.phone, 'Celular');
      if (phoneErr) newErrors['phone'] = phoneErr;

      if (!f.gender) {
        newErrors['gender'] = 'Selecciona el género';
      }

      const birthErr = this.validateBirthDate(f.birthDate, f.documentType);
      if (birthErr) newErrors['birthDate'] = birthErr;

      const emailErr = this.validateEmail(f.email);
      if (emailErr) newErrors['email'] = emailErr;

      const gPhoneErr = this.validateGuardianPhone(f.guardianPhone, f.birthDate);
      if (gPhoneErr) newErrors['guardianPhone'] = gPhoneErr;
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  private validateStep2(): boolean {
    const newErrors: Record<string, string> = {};

    // solo validar contraseña si se va a crear usuario nuevo
    if (this.requiresPassword()) {
      if (this.password().length < 8) {
        newErrors['password'] =
          'La contraseña debe tener al menos 8 caracteres';
      }

      if (this.password() !== this.confirmPassword()) {
        newErrors['confirmPassword'] = 'Las contraseñas no coinciden';
      }
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

  private validateName(value: string | undefined, label: string): string {
    const trimmed = value?.trim() ?? '';

    if (!trimmed) {
      return `Ingresa ${label.toLowerCase()}`;
    }
    if (trimmed.length > this.NAME_MAX) {
      return `Se permiten ingresar máximo ${this.NAME_MAX} caracteres`;
    }
    if (!this.VALID_NAME_REGEX.test(trimmed)) {
      return 'Solo se permiten letras, espacios y guión medio (-)';
    }
    return '';
  }

  private validatePhone(value: string | undefined, label: string, required = true): string {
    const trimmed = value?.trim() ?? '';

    if (!trimmed) {
      return required ? `Ingresa el ${label.toLowerCase()}` : '';
    }
    if (trimmed.length < this.PHONE_MIN) {
      return `El número debe tener al menos ${this.PHONE_MIN} dígitos`;
    }
    if (!/^[0-9]{7,15}$/.test(trimmed)) {
      return `Ingresa un número válido (entre ${this.PHONE_MIN} y ${this.PHONE_MAX} dígitos)`;
    }
    return '';
  }

  private validateBirthDate(value: string | undefined, documentType: string | undefined): string {
    if (!value) {
      return 'Ingresa una fecha de nacimiento válida';
    }

    const today = new Date(); today.setHours(0, 0, 0, 0);
    const input = new Date(value); input.setHours(0, 0, 0, 0);

    if (input >= today) {
      return 'La fecha de nacimiento debe ser anterior a hoy';
    }

    if (documentType === 'CEDULA' || documentType === 'PASAPORTE') {
      const age = this.calcAge(input);
      if (age < 18) {
        return (
          'La fecha ingresada indica que el paciente es menor de edad. ' +
          'Para Cédula o Pasaporte el paciente debe tener 18 años o más.'
        );
      }
    }

    return '';
  }

  private validateEmail(value: string | undefined): string {
    if (!value) return '';

    if (value.length > this.EMAIL_MAX) {
      return `El correo no puede superar los ${this.EMAIL_MAX} caracteres`;
    }
    if (this.INVALID_EMAIL_CHARS.test(value)) {
      return "No se permiten caracteres especiales como ', \", <, >, (, ), [, ], etc.";
    }
    if (!this.VALID_EMAIL_REGEX.test(value)) {
      return 'La estructura del correo no es válida. Ejemplo: nombre@dominio.com';
    }
    return '';
  }

  private validateGuardianPhone(
    guardianPhone: string | undefined,
    birthDate: string | undefined,
  ): string {
    const trimmed = guardianPhone?.trim() ?? '';

    if (trimmed) {
      const formatErr = this.validatePhone(trimmed, 'celular del acudiente', false);
      if (formatErr) return formatErr;
    }

    if (!birthDate) return '';

    const age = this.calcAge(new Date(birthDate));
    if (age < 18 && !trimmed) {
      return 'El celular del acudiente es obligatorio para menores de 18 años';
    }

    return '';
  }

  private calcAge(birthDate: Date): number {
    const today = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const m = today.getMonth() - birthDate.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) age--;
    return age;
  }

  private isMinorByBirthDate(birthDate: string): boolean {
    if (!birthDate) return false;
    return this.calcAge(new Date(birthDate)) < 18;
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
    this.firstNameLimitMsg.set('');
    this.lastNameLimitMsg.set('');
    this.phoneLimitMsg.set('');
    this.emailLimitMsg.set('');
    this.guardianPhoneLimitMsg.set('');
  }
}