import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { Doctor } from '../../models/doctor.model';
import { Patient } from '../../models/patient.model';
import { NewAppointment } from '../../models/newAppointment.model';
import { LucideAngularModule } from 'lucide-angular';

@Component({
  selector: 'app-new-appointment-scheduler',
  imports: [CommonModule, LucideAngularModule, FormsModule],
  templateUrl: './new-appointment-scheduler.component.html'
})
export class NewAppointmentSchedulerComponent {
  private service = inject(NuevaCitaService);
  private router = inject(Router);

  documentError = false;
  firstNameError = false;
  lastNameError = false;
  phoneError = false;
  genderError = false;
  birthDateError = false;
  emailError = false;

  step = 1;

  success = false;
  isLoading = false;
  errorMessage = '';

  patientId: string | null = null;
  documentId = '';
  foundPatient: Patient | null = null;
  notFound = false;

  patientForm: Omit<Patient, 'id'> = {
    documentId: '',
    firstName: '',
    lastName: '',
    phone: '',
    gender: '',
    birthDate: '',
    email: ''
  };

  doctors: Doctor[] = [];
  selectedDoctorId = '';

  selectedDate = '';
  selectedTime = '';

  availableDates: string[] = [];
  availableSlots: string[] = [];

  loadDoctors(): void {
    this.service.getDoctors().subscribe({
      next: (data) => this.doctors = data
    });
  }

  searchPatient(): void {
    this.documentError = false;

    if (!/^[0-9]+$/.test(this.documentId)) {
      this.documentError = true;
      return;
    }

    this.service.getPatientByDocument(this.documentId).subscribe({
      next: (patient) => {
        this.foundPatient = patient;
        this.notFound = !patient;
        if (patient) {
          this.patientId = patient.id;
        }
        if (!patient) {
          this.patientForm.documentId = this.documentId;
        }
      },
      error: () => {
        this.foundPatient = null;
        this.notFound = true;
      }
    });
  }

  onDoctorChange(): void {
    this.selectedDate = '';
    this.selectedTime = '';
    this.service.getAvailableDates(this.selectedDoctorId)
      .subscribe(d => this.availableDates = d);
  }

  selectDate(date: string): void {
    this.selectedDate = date;
    this.selectedTime = '';
    this.service.getAvailableSlots(this.selectedDoctorId, date)
      .subscribe(s => this.availableSlots = s);
  }

  dayNames = ['Dom','Lun','Mar','Mié','Jue','Vie','Sáb'];
  monthNames = [
    'Enero','Febrero','Marzo','Abril','Mayo','Junio',
    'Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'
  ];

  formatDate(d: string): string {
    const dt = new Date(d + 'T12:00:00');
    return `${this.dayNames[dt.getDay()]} ${dt.getDate()} de ${this.monthNames[dt.getMonth()]}`;
  }

  confirm(): void {
    if (!this.selectedDoctorId || !this.selectedDate || !this.selectedTime || !this.patientId) {
      return;
    }
    this.isLoading = true;
    this.errorMessage = '';
    this.createAppointment(this.patientId);
  }

  private createAppointment(patientId: string): void {
    const data: NewAppointment = {
      patientId: patientId,
      doctorId: this.selectedDoctorId,
      date: this.selectedDate,
      time: this.selectedTime
    };
    this.service.addAppointment(data).subscribe({
      next: () => {
        this.isLoading = false;
        this.success = true;
        setTimeout(() => {
          this.router.navigate(['/agendador']);
        }, 1500);
      },
      error: (err) => {
        this.isLoading = false;
        if (err.status === 409) {
          this.errorMessage = 'El horario ya fue tomado por otro usuario.';
        } else {
          this.errorMessage = 'Ocurrió un error al registrar la cita.';
        }
      }
    });
  }

  goToDoctorStep() {
    if (this.foundPatient) {
      this.patientId = this.foundPatient.id;
      this.step = 2;
      return;
    }
    this.firstNameError = !this.patientForm.firstName;
    this.lastNameError = !this.patientForm.lastName;
    this.phoneError = !this.patientForm.phone;
    this.genderError = !this.patientForm.gender;
    const phoneValid = this.validatePhone();
    const birthDateValid = this.validateBirthDate();
    const emailValid = this.validateEmail();

    if (
      this.firstNameError ||
      this.lastNameError ||
      this.phoneError ||
      this.genderError ||
      !phoneValid ||
      !birthDateValid ||
      !emailValid
    ) {return;}

    const newPatient: Omit<Patient,'id'> = {
      ...this.patientForm,
      documentId: this.documentId,
      birthDate: this.patientForm.birthDate || undefined,
      email: this.patientForm.email || undefined
    };

    this.isLoading = true;
    this.service.addPatient(newPatient).subscribe({
      next: (patient) => {
        this.isLoading = false;
        this.patientId = patient.id;
        this.step = 2;
      },
      error: () => {
        this.isLoading = false;
        this.errorMessage = 'No se pudo registrar el paciente.';
      }
    });
  }

  validatePhone(): boolean {
    const phone = this.patientForm.phone;
    if (!phone) {
      return true;
    }
    const phoneRegex = /^[0-9]{7,15}$/;
    if (!phoneRegex.test(phone)) {
      this.phoneError = true;
      return false;
    }
    this.phoneError = false;
    return true;
  }

  validateBirthDate(): boolean {
    const birthDate = this.patientForm.birthDate;
    if (!birthDate) {
      return true;
    }

    const today = new Date();
    const inputDate = new Date(birthDate);

    today.setHours(0,0,0,0);
    inputDate.setHours(0,0,0,0);

    if (inputDate >= today) {
      this.birthDateError = true;
      return false;
    }
    this.birthDateError = false;
    return true;
  }

  validateEmail(): boolean {
    const email = this.patientForm.email;
    if (!email) {
      return true;
    }
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(email)) {
      this.emailError = true;
      return false;
    }
    this.emailError = false;
    return true;
  }

/*
  selectedDoctor = computed<Doctor | undefined>(() =>
    this.doctors().find(d => d.id === this.selectedDoctorId())
  );

  availableDates = toSignal(
    toObservable(this.selectedDoctorId).pipe(
      switchMap((doctorId) => doctorId ? this.service.getAvailableDates(doctorId) : of([]))
    ),{ initialValue: [] }
  );

  availableSlots = toSignal(
    toObservable(computed(() => ({
        doctorId: this.selectedDoctorId(),
        date: this.selectedDate()
      }))
    ).pipe(
      switchMap(({ doctorId, date }) => doctorId && date
          ? this.service.getAvailableSlots(doctorId, date)
          : of([])
      )
    ),
    { initialValue: [] }
  );

  
    */
}
