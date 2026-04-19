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
    this.firstNameError.set(!f.firstName?.trim());
    this.lastNameError.set(!f.lastName?.trim());
    this.phoneError.set(!f.phone?.trim());
    this.genderError.set(!f.gender);
    this.birthDateError.set(!f.birthDate);

    const phoneOk = this.validatePhone();
    const birthDateOk = this.validateBirthDate();
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
      emailOk &&
      guardianPhoneOk
    );
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
    const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
    this.emailError.set(!valid);
    return valid;
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
  }
}