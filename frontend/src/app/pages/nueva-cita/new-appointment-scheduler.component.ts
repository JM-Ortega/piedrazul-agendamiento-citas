import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatNativeDateModule } from '@angular/material/core';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { CalendarService } from '../../services/calendar.service';
import { Patient } from '../../models/patient.model';
import { NewAppointment } from '../../models/DTOs/newAppointment';
import { SpecialtyDoctor } from '../../models/DTOs/specialty-doctor';
import { LucideAngularModule, CheckCircle, User, Stethoscope, UserSearch } from 'lucide-angular';

type BookingMode = 'specialty' | 'specialty-doctor' | null;

@Component({
  selector: 'app-new-appointment-scheduler',
  imports: [
    CommonModule,
    FormsModule,
    LucideAngularModule,
    MatDatepickerModule,
    MatInputModule,
    MatFormFieldModule,
    MatNativeDateModule,
  ],
  templateUrl: './new-appointment-scheduler.component.html'
})
export class NewAppointmentSchedulerComponent {
  private service = inject(NuevaCitaService);
  private router = inject(Router);
  private calendarService = inject(CalendarService);

  // Modo de agendamiento 
  bookingMode = signal<BookingMode>(null);
  
  // Estado de UI 
  step = signal(1);
  isLoading = signal(false);
  errorMessage = signal('');
  errorMessageSpecialty = signal('');
  errorMessageDoctors = signal('');
  errorMessageSlots = signal('');
  showSuccessModal = signal(false);

  // Errores de validación
  docTypeError = signal(false);
  documentError = signal(false);
  firstNameError = signal(false);
  lastNameError = signal(false);
  phoneError = signal(false);
  genderError = signal(false);
  birthDateError = signal(false);
  emailError = signal(false);
  guardianPhoneError = signal(false);
  noSlotsAvailable = signal(false);
  specialtyError = signal(false);

  // Paciente
  documentId = signal('');
  foundPatient = signal<Patient | null>(null);
  notFound = signal(false);
  patientId = signal<string | null>(null);

  patientForm = signal<Omit<Patient, 'id'>>({
    documentType: '',
    documentNumber: '',
    firstName: '',
    lastName: '',
    phone: '',
    gender: '',
    birthDate: '',
    email: '',
    guardianPhone: ''
  });

  // Especialidad / médico
  specialtiesWithDoctor = signal<SpecialtyDoctor[]>([]);
  doctorsBySpecialty = signal<SpecialtyDoctor[]>([]);
  noDoctorsFound        = signal(false);
  noSpecialtyAvailable = signal(false);
  selectedSpecialty = signal('');
  assignedDoctor = signal<SpecialtyDoctor | null>(null);
  selectedDoctorId = signal('');
  selectedDoctorName = signal('');

  readonly uniqueSpecialties = computed(() =>
    [...new Set(this.specialtiesWithDoctor().map(s => s.specialty))]
  );

  readonly effectiveDoctor = computed<SpecialtyDoctor | null>(() =>
    this.bookingMode() === 'specialty'
      ? this.assignedDoctor()
      : (this.doctorsBySpecialty().find(d => d.id === this.selectedDoctorId()) ?? null)
  );

  readonly effectiveDoctorId = computed(() => this.effectiveDoctor()?.id ?? '');

  // Calendario
  selectedDate   = signal<Date | null>(null);
  selectedTime   = signal('');
  availableSlots = signal<string[]>([]);

  // Guards de navegación
  readonly canGoToStep4 = computed(() =>
    !!this.selectedDate() && !!this.selectedTime()
  );
 
  readonly canGoToStep3 = computed(() => {
    if (this.bookingMode() === 'specialty') {
      return !!this.selectedSpecialty();
    }
    return !!this.selectedSpecialty() && !!this.selectedDoctorId();
  });

  readonly dateFilter = computed(() => {
    const doctor = this.effectiveDoctor();
    if (!doctor) return () => false;
    return this.calendarService.buildDateFilter(doctor, true);
  });

  readonly minDate = computed(() => this.calendarService.getMinDate(true));

  readonly maxDate = computed(() => {
    const doctor = this.effectiveDoctor();
    if (!doctor) return this.calendarService.getMinDate();
    return this.calendarService.getMaxDate(doctor);
  });

  readonly confirmFirstName = computed(
    () => this.foundPatient()?.firstName ?? this.patientForm().firstName
  );
  readonly confirmLastName = computed(
    () => this.foundPatient()?.lastName ?? this.patientForm().lastName
  );
  readonly confirmDocumentType = computed(
    () => this.foundPatient()?.documentType ?? this.patientForm().documentType
  );
  readonly confirmDocument = computed(
    () => this.foundPatient()?.documentNumber ?? this.documentId()
  );
  readonly confirmPhone = computed(
    () => this.foundPatient()?.phone ?? this.patientForm().phone
  );
  readonly confirmGender = computed(
    () => this.foundPatient()?.gender ?? this.patientForm().gender
  );
  readonly confirmBirthDate = computed(
    () => this.foundPatient()?.birthDate ?? this.patientForm().birthDate
  );

  readonly confirmDoctorName = computed(() =>
    this.bookingMode() === 'specialty'
      ? (this.assignedDoctor()?.name ?? '')
      : this.selectedDoctorName()
  );

  readonly confirmDate = computed(() => {
    const d = this.selectedDate();
    if (!d) return '';
    const days   = ['Domingo','Lunes','Martes','Miércoles','Jueves','Viernes','Sábado'];
    const months = ['Enero','Febrero','Marzo','Abril','Mayo','Junio','Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'];
    return `${days[d.getDay()]} ${d.getDate()} de ${months[d.getMonth()]} de ${d.getFullYear()}`;
  });

  // Selección de modo inicial
  selectMode(mode: BookingMode): void {
    this.bookingMode.set(mode);
  }

  // Búsqueda de paciente 
  searchPatient(): void {
    this.foundPatient.set(null);
    this.documentError.set(false);
    this.errorMessage.set('');

    if (!/^[0-9]{6,12}$/.test(this.documentId())) {
      this.errorMessage.set('El número de documento solo debe contener entre 6 y 12 números');
      return;
    }

    this.service.getPatientByDocument(this.documentId()).subscribe({
      next: (patient) => {
        this.foundPatient.set(patient ?? null);
        this.notFound.set(!patient);
        if (patient) {
          this.resetPatientForm();
        }else{
          this.patientForm.update(f => ({ ...f, documentId: this.documentId() }));
        }
      },
      error: (err) => {
        switch(err.status){
          case 404:
            this.foundPatient.set(null);
            this.notFound.set(true);
            break;
          case 0:
            this.errorMessage.set('No se pudo conectar con el servidor. Intente mas tarde');
            break;
          default:
            this.errorMessage.set('Error al buscar paciente');
          break;
        }
      }
    });
  }

  goToSpecialtyStep(): void {
    // Caso 1: paciente encontrado en el backend
    if (this.foundPatient()) {
      this.patientId.set(this.foundPatient()!.id);
      this._enterStep2(); 
      return;
    }
 
    //caso 2: no se encontró, validar formulario del nuevo paciente
    if (!this._validatePatientForm()) return;
    this._enterStep2(); 
  }

  private _enterStep2(): void {
    this.step.set(2);
    if (this.bookingMode() === 'specialty-doctor') {
        this.loadSpecialties();
      }else{
        this.loadSpecialtysWhitDoctor();
      }
    }

    loadSpecialties(): void {
      this.service.getSpecialties().subscribe({
      next: (specs) => {
        if (!specs || specs.length === 0) {
          this.noSpecialtyAvailable.set(true);
          this.errorMessageSpecialty.set('⚠️ No hay especialidades disponibles.');
          return;
        }

        this.specialtiesWithDoctor.set(
          specs.map(s => ({
            specialty: s,
            id: '',
            name: '',
            laborEnd: null,
            workdays: []
          }))
        );
      },
      error: (err) => {
        this.noSpecialtyAvailable.set(true);

        switch(err.status){
          case 404:
            this.errorMessageSpecialty.set('⚠️ No hay médicos disponibles para ninguna especialidad. Intente más tarde.');
            break;
          case 0:
            this.errorMessageSpecialty.set('No se pudo conectar con el servidor. Intente mas tarde');
            break;
          default:
            this.errorMessageSpecialty.set('Error al obtener especialidades');
        }
      }
    });
  }

  loadSpecialtysWhitDoctor():void{
    if (this.bookingMode() === 'specialty') {
      this.service.getSpecialtiesWithDoctor().subscribe({
        next: data => this.specialtiesWithDoctor.set(data),
        error: (err) => { 
          this.noSpecialtyAvailable.set(true);
          switch(err.status){
            case 404:
              this.errorMessageSpecialty.set('⚠️ No hay médicos disponibles para ninguna especialidad. Intente más tarde.');
              break;
            case 0:
              this.errorMessageSpecialty.set('No se pudo conectar con el servidor. Intente mas tarde');
              break;
            default:
              this.errorMessageSpecialty.set('Error al obtener las especialidades');
            break;
          }
        } 
      });
    }
  }

  private _validatePatientForm(): boolean {
    const f = this.patientForm();
    this.firstNameError.set(!f.firstName?.trim());
    this.lastNameError.set(!f.lastName?.trim());
    this.phoneError.set(!f.phone?.trim());
    this.genderError.set(!f.gender);
    this.birthDateError.set(!f.birthDate);
    this.docTypeError.set(!f.documentType);
    const phoneOk     = this.validatePhone();
    const birthDateOk = this.validateBirthDate();
    const emailOk     = this.validateEmail();
    const guardianPhoneOk = this.validateGuardianPhoneMenor();
    return !this.docTypeError() && !this.firstNameError() && !this.lastNameError() &&
           !this.phoneError()     && !this.genderError() && !this.birthDateError()  &&
           phoneOk && birthDateOk && emailOk && guardianPhoneOk;
  }

  // Especialidad (y médico si aplica)
  onSpecialtyChange(specialty: string): void {
    this.selectedSpecialty.set(specialty);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
    this.assignedDoctor.set(null);

    if (this.bookingMode() === 'specialty') {
      this.noSpecialtyAvailable.set(false);
      if (!specialty) return;
      const match = this.specialtiesWithDoctor().find(s => s.specialty === specialty);
      this.assignedDoctor.set(match ?? null);
      if (!match && specialty) this.noSpecialtyAvailable.set(true);
    } else {
      this.doctorsBySpecialty.set([]);
      this.noDoctorsFound.set(false);

      if (!specialty) return;

      this.service.getDoctorsBySpecialty(specialty).subscribe({
        next: (docs) => {
          this.doctorsBySpecialty.set(docs);
          this.noDoctorsFound.set(docs.length === 0);
        },
        error: (err) => {
          this.noDoctorsFound.set(true)
          switch (err.status) {
            case 404:
              this.errorMessageDoctors.set('⚠️ No hay médicos disponibles para esta especialidad.');
              break;
            case 0:
              this.errorMessageDoctors.set('No se pudo conectar con el servidor. Intente más tarde.');
              break;
            default:
              this.errorMessageDoctors.set('Error al obtener los médicos.');
              break;
          }
        }
      });
    }
  }

  onDoctorChange(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    const doc = this.doctorsBySpecialty().find(d => d.id === doctorId);
    this.selectedDoctorName.set(doc?.name ?? '');
  }

  onDocumentChange(value: string): void {
    this.documentId.set(value);

    this.foundPatient.set(null);
    this.notFound.set(false);
    this.patientId.set(null);

    this.documentError.set(false);
    this.errorMessage.set('');

    this.resetPatientForm();
  }

  goToScheduleStep(): void {
    this.selectedDate.set(null);
    this.selectedTime.set('');
    this.availableSlots.set([]);
    this.step.set(3);
  }

  resetStep2(): void {
    this.selectedSpecialty.set('');
    this.assignedDoctor.set(null);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
    this.doctorsBySpecialty.set([]);
    this.noDoctorsFound.set(false);
    this.noSpecialtyAvailable.set(false);
  }

  // Horario con Datepicker
  onDateSelected(date: Date | null): void {
    this.selectedDate.set(date);
    this.selectedTime.set('');
    this.availableSlots.set([]);
    this.errorMessageSlots.set('');
    this.noSlotsAvailable.set(false);

    if (!date) return;

    const dateStr = date.toISOString().slice(0, 10);
    this.service.getAvailableSlots(this.effectiveDoctorId(), dateStr)
      .subscribe({
        next: (slots) => {
          const today = new Date().toISOString().slice(0, 10);
          if (dateStr === today) {
            const horaMx = new Date(Date.now() + 10 * 60 * 1000);
            const horaMxStr = `${String(horaMx.getHours()).padStart(2, '0')}:${String(horaMx.getMinutes()).padStart(2, '0')}`;
            slots = slots.filter(s => s >= horaMxStr);
          }
          this.availableSlots.set(slots);

          if (slots.length === 0) {
            this.noSlotsAvailable.set(true);
            this.errorMessageSlots.set('⚠️ No hay horarios disponibles para esta fecha.');
          }
        },
        error: (err) =>{
          this.availableSlots.set([]);
          this.noSlotsAvailable.set(true);

          switch (err.status) {
            case 404:
              this.errorMessageSlots.set('⚠️ No hay horarios disponibles para esta fecha.');
              break;
            case 0:
              this.errorMessageSlots.set('No se pudo conectar con el servidor. Intente más tarde.');
              break;
            default:
              this.errorMessageSlots.set('Error al obtener los horarios.');
              break;
          }
        }
      });
  }

  // Confirmación
  confirm(): void {
    const date = this.selectedDate();
    
    if (!date || !this.selectedTime() || !this.effectiveDoctorId()) {
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');
 
    const f = this.patientForm();

    const baseData = {
      doctorId: this.effectiveDoctorId(),
      specialty: this.selectedSpecialty(),
      documentType: this.confirmDocumentType(),
      documentNumber: this.confirmDocument(),
      firstName: this.confirmFirstName(),
      lastName: this.confirmLastName(),
      phone: this.confirmPhone(),
      date: date.toISOString().slice(0, 10),
      startTime: this.selectedTime(),
      schedulingOrigin: 'MANUAL' as const,
      gender: this.confirmGender(),
      birthDate: this.confirmBirthDate()

    };

    let data: NewAppointment;

    if (this.patientId()) {
      data = {
        ...baseData,
        patientId: this.patientId() || undefined
      };
    } else {
      data = {
        ...baseData,
        email: f.email || undefined,
        guardianPhone: f.guardianPhone || undefined
      };
    }
 
    this.service.addAppointment(data).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.showSuccessModal.set(true);
        setTimeout(() => this.router.navigate(['/agendador']), 3000);
      },
      error: (err) => {
        this.isLoading.set(false);
        switch (err.status) {
          case 0:
            this.errorMessage.set('No se pudo conectar con el servidor. Intente más tarde.');
            break;
          case 409:
            this.errorMessage.set('⚠️ El horario ya fue tomado por otro usuario.');
            break;
          default:
            this.errorMessage.set('Ocurrió un error al registrar la cita.');
            break;
        }
      }
    });
  }

  goToScheduler() {
    this.showSuccessModal.set(false);
    this.router.navigate(['/agendador']);
  }

  resetPatientForm(): void {
    this.patientForm.set({
      documentNumber: '',
      documentType: '',
      firstName: '',
      lastName: '',
      phone: '',
      gender: '',
      birthDate: '',
      email: '',
      guardianPhone: ''
    });

    this.firstNameError.set(false);
    this.lastNameError.set(false);
    this.phoneError.set(false);
    this.genderError.set(false);
    this.birthDateError.set(false);
    this.emailError.set(false);
    this.guardianPhoneError.set(false);
    this.docTypeError.set(false);
  }

  getPatientField<K extends keyof Omit<Patient, 'id'>>(key: K): Omit<Patient, 'id'>[K] {
    return this.patientForm()[key];
  }
 
  setPatientField<K extends keyof Omit<Patient, 'id'>>(key: K, value: Omit<Patient, 'id'>[K]): void {
    this.patientForm.update(f => ({ ...f, [key]: value }));
  }

  // Validaciones
  validatePhone(): boolean {
    const phone = this.patientForm().phone;
    if (!phone) return true;

    const valid = /^[0-9]{7,15}$/.test(phone);
    this.phoneError.set(!valid);
    return valid;
  }

  validateGuardianPhoneMenor(): boolean {
    const f = this.patientForm();
    if (!f.birthDate){
      this.guardianPhoneError.set(false);
      return true;
    }
    const birthDate = new Date(f.birthDate);
    const today = new Date();

    let age = today.getFullYear() - birthDate.getFullYear();
    const monthDiff = today.getMonth() - birthDate.getMonth();

    if (monthDiff < 0 ||
      (monthDiff === 0 && today.getDate() < birthDate.getDate())
    ) {age--;}

    if(age < 18){
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

  validateBirthDate(): boolean {
    const birthDate = this.patientForm().birthDate;
    if (!birthDate) return true;

    const today = new Date();
    const inputDate = new Date(birthDate);
    today.setHours(0, 0, 0, 0);
    inputDate.setHours(0, 0, 0, 0);

    const valid = inputDate < today;
    this.birthDateError.set(!valid);
    return valid;
  }

  validateEmail(): boolean {
    const email = this.patientForm().email;
    if (!email) return true;

    const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
    this.emailError.set(!valid);
    return valid;
  }
}