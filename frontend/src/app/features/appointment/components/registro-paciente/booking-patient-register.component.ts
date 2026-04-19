import { Component, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideAngularModule, ArrowLeft } from 'lucide-angular';
import { BookingStateService } from '../../booking-state.service';
import { Patient } from '../../../../models/interfaces/patient.model';

/**
 * Capturar y validar los datos de un paciente que no fue encontrado en el sistema para
 * que pueda ser registrado al confirmar la cita.
 */
@Component({
  selector: 'app-booking-patient-register',
  standalone: true,
  imports: [FormsModule, LucideAngularModule],
  templateUrl: './booking-patient-register.component.html',
})
export class BookingPatientRegisterComponent {
  readonly ArrowLeft = ArrowLeft;
  protected state = inject(BookingStateService);

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
  emailErrorMsg = signal('');

  private readonly NAME_MAX_LENGTH = 30;
  private readonly EMAIL_MAX_LENGTH = 100;
  private readonly VALID_NAME_REGEX = /^[a-zA-ZáéíóúÁÉÍÓÚüÜñÑ\s\-]+$/;
  private readonly VALID_EMAIL_REGEX = /^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$/;

  getPatientField<K extends keyof Omit<Patient, 'id'>>(
    key: K,
  ): Omit<Patient, 'id'>[K] {
    return this.state.patientForm()[key];
  }

  setPatientField<K extends keyof Omit<Patient, 'id'>>(
    key: K,
    value: Omit<Patient, 'id'>[K],
  ): void {
    this.state.patientForm.update((f) => ({ ...f, [key]: value }));
  }

  onContinue(): void {
    if (!this.validateForm()) return;
    this.advance.emit();
  }

  onGoBack(): void {
    this.clearErrors();
    this.goBack.emit();
  }

  private validateForm(): boolean {
    const f = this.state.patientForm();

    this.docTypeError.set(!f.documentType);
    this.genderError.set(!f.gender);
    this.birthDateError.set(!f.birthDate);
    if (!f.firstName?.trim()) {
      this.firstNameError.set(true);
      this.firstNameErrorMsg.set('Este campo es obligatorio');
    }
    if (!f.lastName?.trim()) {
      this.lastNameError.set(true);
      this.lastNameErrorMsg.set('Este campo es obligatorio');
    }
    if (!f.phone?.trim()) {
      this.phoneError.set(true);
    }

    const phoneOk = this.validatePhone();
    const birthDateOk = this.validateBirthDate();
    const firstNameOk = this.validateName('firstName');
    const lastNameOk = this.validateName('lastName');
    const emailOk = this.validateEmail();
    const guardianPhoneOk = this.validateGuardianPhone();

    return (
      !this.docTypeError() &&
      !this.firstNameError() &&
      !this.lastNameError() &&
      !this.phoneError() &&
      !this.genderError() &&
      !this.birthDateError() &&
      phoneOk &&
      birthDateOk &&
      firstNameOk &&
      lastNameOk &&
      emailOk &&
      guardianPhoneOk
    );
  }

  private validateName(field: 'firstName' | 'lastName'): boolean {
    const value = this.state.patientForm()[field]?.trim() ?? '';
    const errorSignal = field === 'firstName' ? this.firstNameError : this.lastNameError;
    const msgSignal = field === 'firstName' ? this.firstNameErrorMsg : this.lastNameErrorMsg;

    if (!value) return false;

    if (value.length > this.NAME_MAX_LENGTH) {
      errorSignal.set(true);
      msgSignal.set(`Se permiten ingresar máximo ${this.NAME_MAX_LENGTH} caracteres`);
      return false;
    }

    if (!this.VALID_NAME_REGEX.test(value)) {
      errorSignal.set(true);
      msgSignal.set('Solo se permiten letras, espacios y guión medio (-)');
      return false;
    }

    errorSignal.set(false);
    msgSignal.set('');
    return true;
  }

  private validatePhone(): boolean {
    const phone = this.state.patientForm().phone;
    if (!phone) return true;
    const valid = /^[0-9]{7,15}$/.test(phone);
    this.phoneError.set(!valid);
    return valid;
  }

  private validateBirthDate(): boolean {
    const birthDate = this.state.patientForm().birthDate;
    if (!birthDate) return true;
    const today = new Date();
    const input = new Date(birthDate);
    today.setHours(0, 0, 0, 0);
    input.setHours(0, 0, 0, 0);
    const valid = input < today;
    this.birthDateError.set(!valid);
    return valid;
  }

  private validateEmail(): boolean {
    const email = this.state.patientForm().email;
    if (!email) return true;

    if (email.length > this.EMAIL_MAX_LENGTH) {
      this.emailError.set(true);
      this.emailErrorMsg.set(`El correo no puede superar los ${this.EMAIL_MAX_LENGTH} caracteres`);
      return false;
    }

    if (!this.VALID_EMAIL_REGEX.test(email)) {
      this.emailError.set(true);
      this.emailErrorMsg.set('Correo electrónico no válido. Evite caracteres especiales como \', ", <, >');
      return false;
    }

    this.emailError.set(false);
    this.emailErrorMsg.set('');
    return true;
  }

  private validateGuardianPhone(): boolean {
    const f = this.state.patientForm();
    if (!f.birthDate) {
      this.guardianPhoneError.set(false);
      return true;
    }

    const birth = new Date(f.birthDate);
    const today = new Date();
    let age = today.getFullYear() - birth.getFullYear();
    const m = today.getMonth() - birth.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birth.getDate())) age--;

    if (age < 18) {
      if (!f.guardianPhone) {
        this.guardianPhoneError.set(true);
        return false;
      }
      const valid = /^[0-9]{7,15}$/.test(f.guardianPhone);
      this.guardianPhoneError.set(!valid);
      return valid;
    }
    return true;
  }

  private clearErrors(): void {
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
    this.emailErrorMsg.set('');
  }
}