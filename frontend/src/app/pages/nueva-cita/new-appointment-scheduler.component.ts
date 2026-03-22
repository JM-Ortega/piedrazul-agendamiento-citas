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
import { NewAppointment } from './DTO/newAppointment';
import { SpecialtyDoctor } from './DTO/specialty-doctor';
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

  // Errores de validación
  documentError = signal(false);
  firstNameError = signal(false);
  lastNameError = signal(false);
  phoneError = signal(false);
  genderError = signal(false);
  birthDateError = signal(false);
  emailError = signal(false);

  // Paciente
  documentId = signal('');
  foundPatient = signal<Patient | null>(null);
  notFound = signal(false);
  patientId = signal<string | null>(null);

  patientForm = signal<Omit<Patient, 'id'>>({
    documentId: '',
    firstName: '',
    lastName: '',
    phone: '',
    gender: '',
    birthDate: '',
    email: ''
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
      : (this.doctorsBySpecialty().find(d => d.doctorId === this.selectedDoctorId()) ?? null)
  );

  readonly effectiveDoctorId = computed(() => this.effectiveDoctor()?.doctorId ?? '');

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
    return this.calendarService.buildDateFilter(doctor);
  });

  readonly minDate = computed(() => this.calendarService.getMinDate());

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
  readonly confirmDocument = computed(
    () => this.foundPatient()?.documentId ?? this.documentId()
  );
  readonly confirmPhone = computed(
    () => this.foundPatient()?.phone ?? this.patientForm().phone
  );
  readonly confirmDoctorName = computed(() =>
    this.bookingMode() === 'specialty'
      ? (this.assignedDoctor()?.doctorName ?? '')
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
    if (mode === 'specialty') {
      this.service.getSpecialtiesWithDoctor().subscribe({
        next: data => this.specialtiesWithDoctor.set(data),
        error: () => this.noSpecialtyAvailable.set(true)
      });
    }
  }

  // Búsqueda de paciente 
  searchPatient(): void {
    this.documentError.set(false);

    if (!/^[0-9]+$/.test(this.documentId())) {
      this.documentError.set(true);
      return;
    }

    this.service.getPatientByDocument(this.documentId()).subscribe({
      next: (patient) => {
        this.foundPatient.set(patient ?? null);
        this.notFound.set(!patient);
        if (!patient) {
          this.patientForm.update(f => ({ ...f, documentId: this.documentId() }));
        }
      },
      error: (err) => {
        if (err.status === 404) {
          this.foundPatient.set(null);
          this.notFound.set(true);
        } else {
          this.errorMessage.set('Error al buscar paciente');
        }
      }
    });
  }

  goToSpecialtyStep(): void {
    // Caso 1: ya tenemos patientId (paciente previamente registrado)
    if (this.patientId()) { this._enterStep2(); return; }

    // Caso 2: paciente encontrado en el backend
    if (this.foundPatient()) {
      this.patientId.set(this.foundPatient()!.id);
      this._enterStep2(); return;
    }
 
    // Validar formulario nuevo paciente
    if (!this._validatePatientForm()) return;
    const f = this.patientForm();
    this.isLoading.set(true);
    this.service.addPatient({
      ...f,
      documentId: this.documentId(),
      birthDate: f.birthDate || undefined,
      email:     f.email     || undefined
    }).subscribe({
      next: (id) => { this.isLoading.set(false); this.patientId.set(id); this._enterStep2(); },
      error: ()  => { this.isLoading.set(false); this.errorMessage.set('No se pudo registrar el paciente.'); }
    });
  }

  private _enterStep2(): void {
    if (this.bookingMode() === 'specialty-doctor') {
      this.service.getSpecialties().subscribe(specs =>
        this.specialtiesWithDoctor.set(
          specs.map(s => ({ specialty: s, doctorId: '', doctorName: '', fechaFinalTrabajo: null, workDays: [] }))
        )
      );
    }
    this.step.set(2);
  }

  private _validatePatientForm(): boolean {
    const f = this.patientForm();
    this.firstNameError.set(!f.firstName);
    this.lastNameError.set(!f.lastName);
    this.phoneError.set(!f.phone);
    this.genderError.set(!f.gender);
    const phoneOk     = this.validatePhone();
    const birthDateOk = this.validateBirthDate();
    const emailOk     = this.validateEmail();
    return !this.firstNameError() && !this.lastNameError() &&
           !this.phoneError()     && !this.genderError()   &&
           phoneOk && birthDateOk && emailOk;
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
        error: () => this.noDoctorsFound.set(true)
      });
    }
  }

  onDoctorChange(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    const doc = this.doctorsBySpecialty().find(d => d.doctorId === doctorId);
    this.selectedDoctorName.set(doc?.doctorName ?? '');
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

    if (!date) return;

    const dateStr = date.toISOString().slice(0, 10);
    this.service.getAvailableSlots(this.effectiveDoctorId(), dateStr)
      .subscribe(slots => this.availableSlots.set(slots));
  }

  // Confirmación
  confirm(): void {
    const date = this.selectedDate();
    if (!date || !this.selectedTime() || !this.patientId() || !this.effectiveDoctorId()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');
 
    const data: NewAppointment = {
      patientId: this.patientId()!,
      doctorId:  this.effectiveDoctorId(),
      date:      date.toISOString().slice(0, 10),
      time:      this.selectedTime()
    };
 
    this.service.addAppointment(data).subscribe({
      next: () => {
        this.isLoading.set(false);
        setTimeout(() => this.router.navigate(['/agendador']), 1500);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(
          err.status === 409
            ? 'El horario ya fue tomado por otro usuario.'
            : 'Ocurrió un error al registrar la cita.'
        );
      }
    });
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