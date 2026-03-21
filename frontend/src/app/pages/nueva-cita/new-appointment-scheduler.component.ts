import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { Doctor } from '../../models/doctor.model';
import { Patient } from '../../models/patient.model';
import { NewAppointment } from './DTO/newAppointment.model';
import { LucideAngularModule, Search, CheckCircle, User } from 'lucide-angular';

@Component({
  selector: 'app-new-appointment-scheduler',
  imports: [CommonModule, LucideAngularModule, FormsModule],
  templateUrl: './new-appointment-scheduler.component.html'
})
export class NewAppointmentSchedulerComponent implements OnInit {
  private service = inject(NuevaCitaService);
  private router = inject(Router);

  // ── Estado de UI ──────────────────────────────────────────────
  step = signal(1);
  isLoading = signal(false);
  success = signal(false);
  errorMessage = signal('');

  // ── Errores de validación ─────────────────────────────────────
  documentError = signal(false);
  firstNameError = signal(false);
  lastNameError = signal(false);
  phoneError = signal(false);
  genderError = signal(false);
  birthDateError = signal(false);
  emailError = signal(false);

  // ── Paciente ──────────────────────────────────────────────────
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

  // ── Médico y horario ──────────────────────────────────────────
  // Se consume directamente desde el servicio: this.service.doctors()
  readonly doctors = this.service.doctors;
  selectedDoctorId = signal('');
  selectedDate = signal('');
  selectedTime = signal('');
  availableDates = signal<string[]>([]);
  availableSlots = signal<string[]>([]);

  // ── Computed: habilitar el botón "Revisar Cita" ───────────────
  readonly canGoToStep3 = computed(
    () =>
      !!this.selectedDoctorId() &&
      !!this.selectedDate() &&
      !!this.selectedTime()
  );

  // ── Computed: valores de confirmación (paso 3) ─────────────────
  // Devuelven el dato del paciente encontrado o, si es nuevo, el del formulario
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

  // ── Formato de fechas ─────────────────────────────────────────
  readonly dayNames = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  readonly monthNames = [
    'Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
    'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'
  ];

  ngOnInit(): void {
    this.loadDoctors();
  }

  loadDoctors(): void {
    this.service.getDoctors().subscribe();
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

  // Paso 1 → 2 
  goToDoctorStep(): void {
    if (this.patientId()) {
      this.step.set(2);
      return;
    }

    if (this.foundPatient()) {
      this.patientId.set(this.foundPatient()!.id);
      this.step.set(2);
      return;
    }

    // Validar formulario de nuevo paciente
    const f = this.patientForm();
    this.firstNameError.set(!f.firstName);
    this.lastNameError.set(!f.lastName);
    this.phoneError.set(!f.phone);
    this.genderError.set(!f.gender);

    const phoneValid = this.validatePhone();
    const birthDateValid = this.validateBirthDate();
    const emailValid = this.validateEmail();

    if (
      this.firstNameError() ||
      this.lastNameError() ||
      this.phoneError() ||
      this.genderError() ||
      !phoneValid ||
      !birthDateValid ||
      !emailValid
    ) { return; }

    const newPatient: Omit<Patient, 'id'> = {
      ...f,
      documentId: this.documentId(),
      birthDate: f.birthDate || undefined,
      email: f.email || undefined
    };

    this.isLoading.set(true);
    this.service.addPatient(newPatient).subscribe({
      next: (id) => {
        this.isLoading.set(false);
        this.patientId.set(id);
        this.step.set(2);
      },
      error: () => {
        this.isLoading.set(false);
        this.errorMessage.set('No se pudo registrar el paciente.');
      }
    });
  }

  // Cambio de médico 
  onDoctorChange(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    this.selectedDate.set('');
    this.selectedTime.set('');
    this.availableSlots.set([]);
    this.service.getAvailableDates(doctorId).subscribe(
      d => this.availableDates.set(d)
    );
  }

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.selectedTime.set('');
    this.service.getAvailableSlots(this.selectedDoctorId(), date).subscribe(
      s => this.availableSlots.set(s)
    );
  }

  formatDate(d: string): string {
    const dt = new Date(d + 'T12:00:00');
    return `${this.dayNames[dt.getDay()]} ${dt.getDate()} de ${this.monthNames[dt.getMonth()]}`;
  }

  // ── Confirmación ──────────────────────────────────────────────
  confirm(): void {
    if (!this.selectedDoctorId() || !this.selectedDate() || !this.selectedTime() || !this.patientId()) {
      return;
    }
    this.isLoading.set(true);
    this.errorMessage.set('');
    this.createAppointment(this.patientId()!);
  }

  private createAppointment(patientId: string): void {
    const data: NewAppointment = {
      patientId,
      doctorId: this.selectedDoctorId(),
      date: this.selectedDate(),
      time: this.selectedTime()
    };

    this.service.addAppointment(data).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.success.set(true);
        setTimeout(() => {
          this.router.navigate(['/agendador']);
        }, 1500);
      },
      error: (err) => {
        this.isLoading.set(false);
        if (err.status === 409) {
          this.errorMessage.set('El horario ya fue tomado por otro usuario.');
        } else {
          this.errorMessage.set('Ocurrió un error al registrar la cita.');
        }
      }
    });
  }

  // ── Validaciones ──────────────────────────────────────────────
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

  // ── Helper para [(ngModel)] con patientForm signal ────────────
  // Permite que los inputs del formulario lean y escriban en el signal de forma limpia
  getPatientField<K extends keyof Omit<Patient, 'id'>>(key: K): Omit<Patient, 'id'>[K] {
    return this.patientForm()[key];
  }

  setPatientField<K extends keyof Omit<Patient, 'id'>>(key: K, value: Omit<Patient, 'id'>[K]): void {
    this.patientForm.update(f => ({ ...f, [key]: value }));
  }
}