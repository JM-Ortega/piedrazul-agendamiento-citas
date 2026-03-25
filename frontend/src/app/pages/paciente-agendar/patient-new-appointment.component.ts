import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatNativeDateModule } from '@angular/material/core';
import { LucideAngularModule, CheckCircle, Stethoscope, UserSearch } from 'lucide-angular';
import { FormsModule } from '@angular/forms';

import { AppService } from '../../services/app.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { CalendarService } from '../../services/calendar.service';
import { PatientAppointmentService } from '../../services/PatientApointment.service';
import { NewAppointment } from '../../models/DTOs/newAppointment';
import { SpecialtyDoctor } from '../../models/DTOs/specialty-doctor';

type BookingMode = 'specialty' | 'specialty-doctor' | null;

@Component({
  selector: 'app-patient-new-appointment',
  standalone: true,
  imports: [
    CommonModule,
    LucideAngularModule,
    MatDatepickerModule,
    MatInputModule,
    MatFormFieldModule,
    MatNativeDateModule,
    FormsModule
  ],
  templateUrl: './patient-new-appointment.component.html'
})
export class PatientNewAppointmentComponent {
  private appService            = inject(AppService);
  private citaService           = inject(NuevaCitaService);
  private calendarService       = inject(CalendarService);
  private appointmentService    = inject(PatientAppointmentService);
  private router                = inject(Router);

  readonly CheckCircle  = CheckCircle;
  readonly Stethoscope  = Stethoscope;
  readonly UserSearch   = UserSearch;

  bookingMode = signal<BookingMode>(null);

  step         = signal(1);
  isLoading    = signal(false);
  errorMessage = signal('');
  success      = signal(false);
  errorMessageSlots = signal('');
  noSlotsAvailable = signal(false);

  specialtiesWithDoctor = signal<SpecialtyDoctor[]>([]);
  doctorsBySpecialty    = signal<SpecialtyDoctor[]>([]);
  selectedSpecialty     = signal('');
  assignedDoctor        = signal<SpecialtyDoctor | null>(null);
  selectedDoctorId      = signal('');
  selectedDoctorName    = signal('');
  noDoctorsFound        = signal(false);
  noSpecialtyAvailable  = signal(false);

  readonly uniqueSpecialties = computed(() =>
    [...new Set(this.specialtiesWithDoctor().map(s => s.specialty))]
  );

  readonly effectiveDoctor = computed<SpecialtyDoctor | null>(() =>
    this.bookingMode() === 'specialty'
      ? this.assignedDoctor()
      : (this.doctorsBySpecialty().find(d => d.id === this.selectedDoctorId()) ?? null)
  );

  readonly effectiveDoctorId = computed(() => this.effectiveDoctor()?.id ?? '');

  selectedDate   = signal<Date | null>(null);
  selectedTime   = signal('');
  availableSlots = signal<string[]>([]);

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
  readonly confirmDocumentType = computed(() => this.currentPatient()?.documentType ?? '');

  readonly canGoToStep2 = computed(() =>
    this.bookingMode() === 'specialty'
      ? !!this.selectedSpecialty() && !!this.assignedDoctor()
      : !!this.selectedSpecialty() && !!this.selectedDoctorId()
  );

  readonly canGoToStep3 = computed(() =>
    !!this.selectedDate() && !!this.selectedTime()
  );

  readonly confirmDoctorName = computed(() =>
    this.bookingMode() === 'specialty'
      ? (this.assignedDoctor()?.name ?? '')
      : this.selectedDoctorName()
  );

  readonly confirmDate = computed(() => {
    const d = this.selectedDate();
    if (!d) return '';
    const days   = ['Dom','Lun','Mar','Mié','Jue','Vie','Sáb'];
    const months = ['Enero','Febrero','Marzo','Abril','Mayo','Junio',
                    'Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'];
    return `${days[d.getDay()]} ${d.getDate()} de ${months[d.getMonth()]} de ${d.getFullYear()}`;
  });

  readonly currentPatient = computed(() => this.appService.currentPatient());

  selectMode(mode: BookingMode): void {
    this.bookingMode.set(mode);
    this.noSpecialtyAvailable.set(false);

    if (mode === 'specialty') {
      this.citaService.getSpecialtiesWithDoctor().subscribe({
        next: data => this.specialtiesWithDoctor.set(data),
        error: () => this.noSpecialtyAvailable.set(true)
      });
    } else {
      this.citaService.getSpecialties().subscribe({
        next: specs => this.specialtiesWithDoctor.set(
          specs.map(s => ({ specialty: s, id: '', name: '', laborEnd: null, workdays: [] }))
        ),
        error: () => this.noSpecialtyAvailable.set(true)
      });
    }
  }

  onSpecialtyChange(specialty: string): void {
    this.selectedSpecialty.set(specialty);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
    this.assignedDoctor.set(null);
    this.noDoctorsFound.set(false);
    this.noSpecialtyAvailable.set(false);

    if (!specialty) return;

    if (this.bookingMode() === 'specialty') {
      const match = this.specialtiesWithDoctor().find(s => s.specialty === specialty);
      this.assignedDoctor.set(match ?? null);
      if (!match) this.noSpecialtyAvailable.set(true);
    } else {
      this.doctorsBySpecialty.set([]);
      this.citaService.getDoctorsBySpecialty(specialty).subscribe({
        next: docs => {
          this.doctorsBySpecialty.set(docs);
          this.noDoctorsFound.set(docs.length === 0);
        },
        error: () => this.noDoctorsFound.set(true)
      });
    }
  }

  onDoctorChange(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    const doc = this.doctorsBySpecialty().find(d => d.id === doctorId);
    this.selectedDoctorName.set(doc?.name ?? '');
  }

  resetStep1(): void {
    this.selectedSpecialty.set('');
    this.assignedDoctor.set(null);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
    this.doctorsBySpecialty.set([]);
    this.noDoctorsFound.set(false);
    this.noSpecialtyAvailable.set(false);
  }

  goToScheduleStep(): void {
    this.selectedDate.set(null);
    this.selectedTime.set('');
    this.availableSlots.set([]);
    this.step.set(2);
  }

  onDateSelected(date: Date | null): void {
    this.selectedDate.set(date);
    this.selectedTime.set('');
    this.availableSlots.set([]);
    this.errorMessageSlots.set('');
    this.noSlotsAvailable.set(false);
    if (!date) return;

    const dateStr = date.toISOString().slice(0, 10);
    this.citaService.getAvailableSlots(this.effectiveDoctorId(), dateStr)
      .subscribe({
        next: (slots) => {
          this.availableSlots.set(slots);

          if (slots.length === 0) {
            this.noSlotsAvailable.set(true);
            this.errorMessageSlots.set(' No hay horarios disponibles para esta fecha.');
          }
        },
        error: (err) =>{
          this.availableSlots.set([]);
          this.noSlotsAvailable.set(true);

          switch (err.status) {
            case 404:
              this.errorMessageSlots.set(' No hay horarios disponibles para esta fecha.');
              break;
            case 0:
              this.errorMessageSlots.set(' No se pudo conectar con el servidor. Intente más tarde.');
              break;
            default:
              this.errorMessageSlots.set(' Error al obtener los horarios.');
              break;
          }
        }
      });
  }

  confirm(): void {
    const date      = this.selectedDate();
    const patientId = this.currentPatient()?.id;

    if (!date || !this.selectedTime() || !this.effectiveDoctorId() || !patientId) return;

    this.isLoading.set(true);
    this.errorMessage.set('');

    const data: NewAppointment = {
      patientId,
      doctorId: this.effectiveDoctorId(),
      specialty: this.selectedSpecialty(),
      documentType: this.currentPatient()?.documentType,
      documentNumber: this.currentPatient()?.documentNumber,
      firstName: this.currentPatient()?.firstName,
      lastName: this.currentPatient()?.lastName,
      phone: this.currentPatient()?.phone,
      date: date.toISOString().slice(0, 10),
      startTime: this.selectedTime(),
      schedulingOrigin: 'AUTONOMO' as const,
      gender: this.currentPatient()?.gender,
      birthDate: this.currentPatient()?.birthDate
    };

    this.citaService.addAppointment(data).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.success.set(true);
        this.appointmentService.getAppointmentsByPatient(patientId).subscribe(
          appts => this.appointmentService.appointments.set(appts)
        );
        setTimeout(() => this.router.navigate(['/paciente']), 3000);
      },
      error: (err) => {
        this.isLoading.set(false);
        switch (err.status) {
          case 0:
            this.errorMessage.set(' No se pudo conectar con el servidor. Intente más tarde.');
            break;
          case 409:
            this.errorMessage.set(' El horario ya fue tomado por otro usuario.');
            break;
          case 500:
            this.errorMessage.set(' No puede registrar más de una cita para la misma especialidad.');
            break;
          default:
            this.errorMessage.set('Ocurrió un error al registrar la cita.');
            break;
        }
      }
    });
  }

  goBackSchedule(): void {
    this.errorMessage.set('');
    this.success.set(false);
    this.isLoading.set(false);
    this.step.set(2);
  }

  goBack(): void {
    this.router.navigate(['/paciente']);
  }
}