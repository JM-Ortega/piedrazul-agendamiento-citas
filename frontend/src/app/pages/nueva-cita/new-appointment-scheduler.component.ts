import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { Patient } from '../../models/patient.model';
import { NewAppointment } from './DTO/newAppointment';
import { SpecialtyDoctor } from './DTO/specialty-doctor';
import { LucideAngularModule, Search, CheckCircle, User, Stethoscope, UserSearch } from 'lucide-angular';

type BookingMode = 'specialty' | 'specialty-doctor' | null;

@Component({
  selector: 'app-new-appointment-scheduler',
  imports: [CommonModule, LucideAngularModule, FormsModule],
  templateUrl: './new-appointment-scheduler.component.html'
})
export class NewAppointmentSchedulerComponent implements OnInit {
  private service = inject(NuevaCitaService);
  private router = inject(Router);

  readonly doctors = this.service.doctors;

  // Modo de agendamiento 
  bookingMode = signal<BookingMode>(null);
  
  // Estado de UI 
  step = signal(1);
  isLoading = signal(false);
  success = signal(false);
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

  // Especialidad 
  // Flujo "Por Especialidad"
  specialtiesWithDoctor = signal<SpecialtyDoctor[]>([]);
  readonly uniqueSpecialties = computed(() =>
    [...new Set(this.specialtiesWithDoctor().map(s => s.specialty))]
  );
 
  selectedSpecialty = signal('');
  assignedDoctor = signal<{ doctorId: string; doctorName: string } | null>(null);

  // Flujo "Por Especialidad y Médico"
  doctorsBySpecialty = signal<{ doctorId: string; doctorName: string }[]>([]);
  selectedDoctorId   = signal('');
  selectedDoctorName = signal('');

  // Horario
  selectedDate = signal('');
  selectedTime = signal('');
  availableDates = signal<string[]>([]);
  availableSlots = signal<string[]>([]);

  readonly canGoToStep4 = computed(() =>
    !!this.selectedDate() && !!this.selectedTime()
  );
 
  readonly canGoToStep3 = computed(() => {
    if (this.bookingMode() === 'specialty') {
      return !!this.selectedSpecialty();
    }
    return !!this.selectedSpecialty() && !!this.selectedDoctorId();
  });

  readonly effectiveDoctorId = computed(() =>
    this.bookingMode() === 'specialty'
      ? (this.assignedDoctor()?.doctorId ?? '')
      : this.selectedDoctorId()
  );

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

  readonly dayNames = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  readonly monthNames = [
    'Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
    'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'
  ];

  ngOnInit(): void {
    this.service.getDoctors().subscribe();
  }

  // Selección de modo inicial
  selectMode(mode: BookingMode): void {
    this.bookingMode.set(mode);
    if (mode === 'specialty') {
      this.service.getSpecialtiesWithDoctor().subscribe(
        data => this.specialtiesWithDoctor.set(data)
      );
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
    if (this.patientId()) {
      this.step.set(2);
      this._loadStep2Data();
      return;
    }
    if (this.foundPatient()) {
      this.patientId.set(this.foundPatient()!.id);
      this.step.set(2);
      this._loadStep2Data();
      return;
    }
 
    // Validar formulario nuevo paciente
    const f = this.patientForm();
    this.firstNameError.set(!f.firstName);
    this.lastNameError.set(!f.lastName);
    this.phoneError.set(!f.phone);
    this.genderError.set(!f.gender);
 
    const phoneValid     = this.validatePhone();
    const birthDateValid = this.validateBirthDate();
    const emailValid     = this.validateEmail();
 
    if (
      this.firstNameError() || this.lastNameError() ||
      this.phoneError()     || this.genderError()   ||
      !phoneValid || !birthDateValid || !emailValid
    ) { return; }
 
    const newPatient: Omit<Patient, 'id'> = {
      ...f,
      documentId: this.documentId(),
      birthDate:  f.birthDate || undefined,
      email:      f.email     || undefined
    };
 
    this.isLoading.set(true);
    this.service.addPatient(newPatient).subscribe({
      next: (id) => {
        this.isLoading.set(false);
        this.patientId.set(id);
        this.step.set(2);
        this._loadStep2Data();
      },
      error: () => {
        this.isLoading.set(false);
        this.errorMessage.set('No se pudo registrar el paciente.');
      }
    });
  }

  private _loadStep2Data(): void {
    if (this.bookingMode() === 'specialty-doctor') {
      this.service.getSpecialties().subscribe(
        specialties => {
          this.specialtiesWithDoctor.set(
            specialties.map(s => ({ specialty: s, doctorId: '', doctorName: '' }))
          );
        }
      );
    }
  }

  onSpecialtyChange(specialty: string): void {
    this.selectedSpecialty.set(specialty);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
 
    if (this.bookingMode() === 'specialty') {
      const match = this.specialtiesWithDoctor().find(s => s.specialty === specialty);
      this.assignedDoctor.set(
        match ? { doctorId: match.doctorId, doctorName: match.doctorName } : null
      );
    } else {
      this.doctorsBySpecialty.set([]);
      this.service.getDoctorsBySpecialty(specialty).subscribe(
        docs => this.doctorsBySpecialty.set(docs)
      );
    }
  }

  onDoctorChange(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    const doc = this.doctorsBySpecialty().find(d => d.doctorId === doctorId);
    this.selectedDoctorName.set(doc?.doctorName ?? '');
  }

  goToScheduleStep(): void {
    const docId = this.effectiveDoctorId();
    this.selectedDate.set('');
    this.selectedTime.set('');
    this.availableSlots.set([]);
    this.service.getAvailableDates(docId).subscribe(
      d => this.availableDates.set(d)
    );
    this.step.set(3);
  }

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.selectedTime.set('');
    this.service.getAvailableSlots(this.effectiveDoctorId(), date).subscribe(
      s => this.availableSlots.set(s)
    );
  }

  formatDate(d: string): string {
    const dt = new Date(d + 'T12:00:00');
    return `${this.dayNames[dt.getDay()]} ${dt.getDate()} de ${this.monthNames[dt.getMonth()]}`;
  }

  confirm(): void {
    if (!this.selectedDate() || !this.selectedTime() || !this.patientId() || !this.effectiveDoctorId()) {
      return;
    }
    this.isLoading.set(true);
    this.errorMessage.set('');
 
    const data: NewAppointment = {
      patientId: this.patientId()!,
      doctorId:  this.effectiveDoctorId(),
      date:      this.selectedDate(),
      time:      this.selectedTime()
    };
 
    this.service.addAppointment(data).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.success.set(true);
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