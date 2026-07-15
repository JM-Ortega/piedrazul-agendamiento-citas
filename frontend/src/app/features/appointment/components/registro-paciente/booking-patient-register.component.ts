import {
  Component,
  inject,
  OnInit,
  output,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { LucideArrowLeft, LucideCalendar } from '@lucide/angular';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { PatientService } from '../../../../core/services/patient.service';
import { BookingStateService } from '../../services/booking-state.service';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

/**
 * Capturar y validar los datos de un paciente que no fue encontrado en el sistema para
 * que pueda ser registrado al confirmar la cita.
 */
@Component({
  selector: 'app-booking-patient-register',
  standalone: true,
  imports: [
    FormsModule,
    LucideArrowLeft,
    LucideCalendar,
    MatDatepickerModule,
    FormatoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './booking-patient-register.component.html',
})
export class BookingPatientRegisterComponent implements OnInit {
  protected state = inject(BookingStateService);
  protected patientService = inject(PatientService);

  ngOnInit(): void {
    this.patientService.loadDocumentTypes();
  }

  advance = output<void>();
  goBack = output<void>();

  docTypeError = signal(false);
  firstNameError = signal(false);
  lastNameError = signal(false);
  phoneError = signal(false);
  genderError = signal(false);
  birthDateError = signal(false);
  emailError = signal(false);
  guardianPhoneError = signal(false);
  firstNameErrorMsg = signal('');
  lastNameErrorMsg = signal('');
  phoneErrorMsg = signal('');
  emailErrorMsg = signal('');
  guardianPhoneErrorMsg = signal('');
  birthDateErrorMsg = signal('Ingrese una fecha de nacimiento válida');
  firstNameLimitMsg = signal('');
  lastNameLimitMsg = signal('');
  phoneLimitMsg = signal('');
  emailLimitMsg = signal('');
  guardianPhoneLimitMsg = signal('');

  readonly maxBirthDate = new Date();
  readonly NAME_MAX = 30;
  readonly EMAIL_MAX = 100;
  readonly PHONE_MAX = 10;

  private readonly VALID_NAME_REGEX = /^[a-zA-ZáéíóúÁÉÍÓÚüÜñÑ\s\-]+$/;
  private readonly VALID_EMAIL_REGEX =
    /^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$/;
  private readonly INVALID_EMAIL_CHARS = /['"<>()[\]\\,;:{}|^~`!#$%&*=?/]/;

  private timers: Record<string, ReturnType<typeof setTimeout>> = {};

  getPatientField<K extends keyof Omit<Patient, 'id'>>(
    key: K
  ): Omit<Patient, 'id'>[K] {
    return this.state.patientForm()[key];
  }

  setPatientField<K extends keyof Omit<Patient, 'id'>>(
    key: K,
    value: Omit<Patient, 'id'>[K]
  ): void {
    this.state.patientForm.update((f) => ({ ...f, [key]: value }));
  }

  handleNameInput(event: Event, field: 'firstName' | 'lastName'): void {
    const el = event.target as HTMLInputElement;
    const limitMsg =
      field === 'firstName' ? this.firstNameLimitMsg : this.lastNameLimitMsg;
    let value = el.value.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    value = value.replace(/[^a-zA-ZñÑ\s]/g, '');
    value = value.replace(/^\s+/, '');
    value = value.replace(/\s{2,}/g, ' ');
    value = value.replace(
      /(\S+)/g,
      (word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()
    );
    if (value.length > this.NAME_MAX) {
      value = value.slice(0, this.NAME_MAX);
      this.flash(
        limitMsg,
        `Solo se permiten máximo ${this.NAME_MAX} caracteres`,
        field
      );
    } else {
      limitMsg.set('');
    }

    el.value = value;
    this.setPatientField(field, value as any);
  }

  handlePhoneInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const digits = el.value.replace(/\D/g, '');

    if (digits.length > this.PHONE_MAX) {
      el.value = digits.slice(0, this.PHONE_MAX);
      this.setPatientField('phone', el.value as any);
      this.flash(
        this.phoneLimitMsg,
        `Solo se permiten máximo ${this.PHONE_MAX} dígitos`,
        'phone'
      );
    } else {
      el.value = digits;
      this.setPatientField('phone', digits as any);
      this.phoneLimitMsg.set('');
    }
  }

  handleEmailInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const value = el.value;

    if (value.length > this.EMAIL_MAX) {
      el.value = value.slice(0, this.EMAIL_MAX);
      this.setPatientField('email', el.value as any);
      this.flash(
        this.emailLimitMsg,
        `Solo se permiten máximo ${this.EMAIL_MAX} caracteres`,
        'email'
      );
    } else {
      this.setPatientField('email', value as any);
      this.emailLimitMsg.set('');
    }
  }

  handleGuardianPhoneInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const digits = el.value.replace(/\D/g, '');

    if (digits.length > this.PHONE_MAX) {
      el.value = digits.slice(0, this.PHONE_MAX);
      this.setPatientField('guardianPhone', el.value as any);
      this.flash(
        this.guardianPhoneLimitMsg,
        `Solo se permiten máximo ${this.PHONE_MAX} dígitos`,
        'gphone'
      );
    } else {
      el.value = digits;
      this.setPatientField('guardianPhone', digits as any);
      this.guardianPhoneLimitMsg.set('');
    }
  }

  private flash(
    sig: ReturnType<typeof signal<string>>,
    text: string,
    key: string
  ): void {
    sig.set(text);
    if (this.timers[key]) clearTimeout(this.timers[key]);
    this.timers[key] = setTimeout(() => sig.set(''), 3000);
  }

  onContinue(): void {
    if (!this.validateForm()) return;
    this.advance.emit();
  }

  onGoBack(): void {
    this.clearAllErrors();
    this.goBack.emit();
  }

  private validateForm(): boolean {
    const f = this.state.patientForm();

    const docTypeOk = this.validateDocType(f.documentType);
    const genderOk = this.validateGender(f.gender);
    const fnOk = this.validateNameField('firstName', f.firstName);
    const lnOk = this.validateNameField('lastName', f.lastName);
    const phoneOk = this.validatePhoneField(f.phone);
    const birthOk = this.validateBirthDate(f.birthDate, f.documentType);
    const emailOk = this.validateEmailField(f.email);
    const gPhoneOk = this.validateGuardianPhone(f);

    return (
      docTypeOk &&
      genderOk &&
      fnOk &&
      lnOk &&
      phoneOk &&
      birthOk &&
      emailOk &&
      gPhoneOk
    );
  }

  private validateDocType(value: string | undefined): boolean {
    const ok = !!value;
    this.docTypeError.set(!ok);
    return ok;
  }

  private validateGender(value: string | undefined): boolean {
    const ok = !!value;
    this.genderError.set(!ok);
    return ok;
  }

  private validateNameField(
    field: 'firstName' | 'lastName',
    value: string | undefined
  ): boolean {
    const errorSig =
      field === 'firstName' ? this.firstNameError : this.lastNameError;
    const msgSig =
      field === 'firstName' ? this.firstNameErrorMsg : this.lastNameErrorMsg;
    const trimmed = value?.trim() ?? '';

    if (!trimmed) {
      errorSig.set(true);
      msgSig.set('Este campo es obligatorio');
      return false;
    }
    if (trimmed.length > this.NAME_MAX) {
      errorSig.set(true);
      msgSig.set(`Se permiten ingresar máximo ${this.NAME_MAX} caracteres`);
      return false;
    }
    if (!this.VALID_NAME_REGEX.test(trimmed)) {
      errorSig.set(true);
      msgSig.set('Solo se permiten letras, espacios y guión medio (-)');
      return false;
    }
    errorSig.set(false);
    msgSig.set('');
    return true;
  }

  private validatePhoneField(value: string | undefined): boolean {
    const trimmed = value?.trim() ?? '';

    if (!trimmed) {
      this.phoneError.set(true);
      this.phoneErrorMsg.set('Este campo es obligatorio');
      return false;
    }
    if (!/^[0-9]{10}$/.test(trimmed)) {
      this.phoneError.set(true);
      this.phoneErrorMsg.set(
        'Ingrese un número válido de exactamente 10 dígitos'
      );
      return false;
    }
    this.phoneError.set(false);
    this.phoneErrorMsg.set('');
    return true;
  }

  private validateBirthDate(
    value: string | undefined,
    documentType: string | undefined
  ): boolean {
    if (!value) {
      this.birthDateError.set(true);
      this.birthDateErrorMsg.set('Ingrese una fecha de nacimiento válida');
      return false;
    }

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const input = new Date(value);
    input.setHours(0, 0, 0, 0);

    if (input >= today) {
      this.birthDateError.set(true);
      this.birthDateErrorMsg.set(
        'La fecha de nacimiento debe ser anterior a hoy'
      );
      return false;
    }

    if (documentType === 'CEDULA') {
      const age = this.calcAge(input);
      if (age < 18) {
        this.birthDateError.set(true);
        this.birthDateErrorMsg.set(
          'La fecha ingresada indica que el paciente es menor de edad. ' +
            'Para Cédula el paciente debe tener 18 años o más.'
        );
        return false;
      }
    }

    this.birthDateError.set(false);
    this.birthDateErrorMsg.set('');
    return true;
  }

  private calcAge(birthDate: Date): number {
    const today = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const m = today.getMonth() - birthDate.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) age--;
    return age;
  }

  private validateEmailField(value: string | undefined): boolean {
    if (!value) {
      this.emailError.set(false);
      this.emailErrorMsg.set('');
      return true;
    }

    if (value.length > this.EMAIL_MAX) {
      this.emailError.set(true);
      this.emailErrorMsg.set(
        `El correo no puede superar los ${this.EMAIL_MAX} caracteres`
      );
      return false;
    }

    if (this.INVALID_EMAIL_CHARS.test(value)) {
      this.emailError.set(true);
      this.emailErrorMsg.set(
        'No se permiten caracteres especiales como \', ", <, >, (, ), [, ], etc.'
      );
      return false;
    }

    if (!this.VALID_EMAIL_REGEX.test(value)) {
      this.emailError.set(true);
      this.emailErrorMsg.set(
        'La estructura del correo no es válida. Ejemplo: nombre@dominio.com'
      );
      return false;
    }

    this.emailError.set(false);
    this.emailErrorMsg.set('');
    return true;
  }

  private validateGuardianPhone(f: Omit<Patient, 'id'>): boolean {
    if (f.guardianPhone) {
      if (!/^[0-9]{10}$/.test(f.guardianPhone)) {
        this.guardianPhoneError.set(true);
        this.guardianPhoneErrorMsg.set(
          `Ingrese un número válido de ${this.PHONE_MAX} dígitos`
        );
        return false;
      }
      this.guardianPhoneError.set(false);
      this.guardianPhoneErrorMsg.set('');
    }

    if (!f.birthDate) return true;
    const age = this.calcAge(new Date(f.birthDate));

    if (age < 18 && !f.guardianPhone) {
      this.guardianPhoneError.set(true);
      this.guardianPhoneErrorMsg.set(
        'El celular del acudiente es obligatorio para menores de 18 años'
      );
      return false;
    }

    if (age >= 18) {
      this.guardianPhoneError.set(false);
      this.guardianPhoneErrorMsg.set('');
    }
    return true;
  }

  private clearAllErrors(): void {
    this.docTypeError.set(false);
    this.firstNameError.set(false);
    this.lastNameError.set(false);
    this.phoneError.set(false);
    this.genderError.set(false);
    this.birthDateError.set(false);
    this.emailError.set(false);
    this.guardianPhoneError.set(false);
    this.firstNameErrorMsg.set('');
    this.lastNameErrorMsg.set('');
    this.phoneErrorMsg.set('');
    this.emailErrorMsg.set('');
    this.guardianPhoneErrorMsg.set('');
    this.birthDateErrorMsg.set('Ingrese una fecha de nacimiento válida');
    this.firstNameLimitMsg.set('');
    this.lastNameLimitMsg.set('');
    this.phoneLimitMsg.set('');
    this.emailLimitMsg.set('');
    this.guardianPhoneLimitMsg.set('');
  }

  onBirthDateChange(value: Date | string): void {
    const formatted =
      value instanceof Date ? this.state.formatLocalDate(value) : value;
    this.setPatientField('birthDate', formatted as any);
  }
}
