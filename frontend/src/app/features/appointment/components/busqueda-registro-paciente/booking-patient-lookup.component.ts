import { Component, inject, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideAngularModule, CheckCircle } from 'lucide-angular';
import { BookingStateService } from '../../booking-state.service';
import { NuevaCitaService } from '../../../../services/nuevaCita.service';
import { Patient } from '../../../../models/interfaces/patient.model';

/**
 * Búsqueda de un paciente por número de documento
 * y, si no existe, captura de los datos para registrarlo.
 */
@Component({
  selector: 'app-booking-patient-lookup',
  standalone: true,
  imports: [CommonModule, FormsModule, LucideAngularModule],
  templateUrl: './booking-patient-lookup.component.html',
})
export class BookingPatientLookupComponent {

  readonly CheckCircle = CheckCircle;

  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  documentId    = signal('');
  documentError = signal(false);
  errorMessage  = signal('');

  docTypeError       = signal(false);
  firstNameError     = signal(false);
  lastNameError      = signal(false);
  phoneError         = signal(false);
  genderError        = signal(false);
  birthDateError     = signal(false);
  emailError         = signal(false);
  guardianPhoneError = signal(false);

  advance = output<void>();
  changeMode = output<void>();

  onDocumentChange(value: string): void {
    this.documentId.set(value);
    this.state.foundPatient.set(null);
    this.state.notFound.set(false);
    this.state.patientId.set(null);
    this.documentError.set(false);
    this.errorMessage.set('');
    this.state.resetPatientForm();
  }

  searchPatient(): void {
    this.state.foundPatient.set(null);
    this.documentError.set(false);
    this.errorMessage.set('');

    if (!/^[0-9]{6,12}$/.test(this.documentId())) {
      this.documentError.set(true);
      this.errorMessage.set('El número de documento solo debe contener entre 6 y 12 números.');
      return;
    }

    this.citaService.getPatientByDocument(this.documentId()).subscribe({
      next: (patient: Patient | null) => {
        this.state.foundPatient.set(patient ?? null);
        this.state.notFound.set(!patient);
        if (patient) {
          this.state.patientId.set(patient.id);
          this.state.resetPatientForm();
        } else {
          this.state.patientForm.update(f => ({ ...f, documentNumber: this.documentId() }));
        }
      },
      error: (err) => {
        switch (err.status) {
          case 404:
            this.state.foundPatient.set(null);
            this.state.notFound.set(true);
            this.state.patientForm.update(f => ({ ...f, documentNumber: this.documentId() }));
            break;
          case 0:
            this.errorMessage.set('No se pudo conectar con el servidor. Intente más tarde.');
            break;
          default:
            this.errorMessage.set('Error al buscar el paciente.');
        }
      },
    });
  }

  getPatientField<K extends keyof Omit<Patient, 'id'>>(key: K): Omit<Patient, 'id'>[K] {
    return this.state.patientForm()[key];
  }

  setPatientField<K extends keyof Omit<Patient, 'id'>>(key: K, value: Omit<Patient, 'id'>[K]): void {
    this.state.patientForm.update(f => ({ ...f, [key]: value }));
  }

  goToSpecialtyStep(): void {
    if (this.state.foundPatient()) {
      this.advance.emit();
      return;
    }
    if (!this._validatePatientForm()) return;
    this.advance.emit();
  }

  onChangeMode(): void {
    this.state.bookingMode.set(null);
    this.state.foundPatient.set(null);
    this.state.notFound.set(false);
    this.state.patientId.set(null);
    this.documentId.set('');
    this.errorMessage.set('');
    this.state.resetPatientForm();
    this.changeMode.emit();
  }

  private _validatePatientForm(): boolean {
    const f = this.state.patientForm();
    this.firstNameError.set(!f.firstName?.trim());
    this.lastNameError.set(!f.lastName?.trim());
    this.phoneError.set(!f.phone?.trim());
    this.genderError.set(!f.gender);
    this.birthDateError.set(!f.birthDate);
    this.docTypeError.set(!f.documentType);

    const phoneOk         = this._validatePhone();
    const birthDateOk     = this._validateBirthDate();
    const emailOk         = this._validateEmail();
    const guardianPhoneOk = this._validateGuardianPhone();

    return !this.docTypeError()  && !this.firstNameError() &&
           !this.lastNameError() && !this.phoneError()     &&
           !this.genderError()   && !this.birthDateError() &&
           phoneOk && birthDateOk && emailOk && guardianPhoneOk;
  }

  private _validatePhone(): boolean {
    const phone = this.state.patientForm().phone;
    if (!phone) return true;
    const valid = /^[0-9]{7,15}$/.test(phone);
    this.phoneError.set(!valid);
    return valid;
  }

  private _validateBirthDate(): boolean {
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

  private _validateEmail(): boolean {
    const email = this.state.patientForm().email;
    if (!email) return true;
    const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
    this.emailError.set(!valid);
    return valid;
  }

  private _validateGuardianPhone(): boolean {
    const f = this.state.patientForm();
    if (!f.birthDate) { this.guardianPhoneError.set(false); return true; }

    const birthDate = new Date(f.birthDate);
    const today     = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const monthDiff = today.getMonth() - birthDate.getMonth();
    if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birthDate.getDate())) age--;

    if (age < 18) {
      if (!f.guardianPhone) { this.guardianPhoneError.set(true); return false; }
      const valid = /^[0-9]{7,15}$/.test(f.guardianPhone);
      this.guardianPhoneError.set(!valid);
      return valid;
    }
    return true;
  }
}