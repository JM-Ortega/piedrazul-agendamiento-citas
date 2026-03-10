import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { switchMap, of } from 'rxjs';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { Doctor } from '../../models/doctor.model';

@Component({
  selector: 'app-new-appointment-scheduler',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './new-appointment-scheduler.component.html'
})
export class NewAppointmentSchedulerComponent {
  private service = inject(NuevaCitaService);
  private router = inject(Router);

  documentId = signal('');
  foundPatient = signal<any>(null);
  notFound = signal(false);

  searchPatient(): void {
    this.service.getPatientByDocument(this.documentId()).subscribe({
      next: (patient) => {
        this.foundPatient.set(patient);
        this.notFound.set(!patient);
      },
      error: () => {
        this.foundPatient.set(null);
        this.notFound.set(true);
      },
    });
  }

  selectedDoctorId = signal('');
  doctors = toSignal(this.service.getDoctors(), { initialValue: [] });

  selectedDoctor = computed<Doctor | undefined>(() =>
    this.doctors().find(d => d.id === this.selectedDoctorId())
  );

  onDoctorChange(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    this.selectedDate.set('');
    this.selectedTime.set('');
  }

  dayNames = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  monthNames = [
    'Enero',
    'Febrero',
    'Marzo',
    'Abril',
    'Mayo',
    'Junio',
    'Julio',
    'Agosto',
    'Septiembre',
    'Octubre',
    'Noviembre',
    'Diciembre',
  ];

  formatDate(d: string): string {
    const dt = new Date(d + 'T12:00:00');
    return `${this.dayNames[dt.getDay()]} ${dt.getDate()} de ${this.monthNames[dt.getMonth()]}`;
  }

  selectedDate = signal('');
  selectedTime = signal('');

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.selectedTime.set('');
  }

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

  success = signal(false);
  isLoading = signal(false);
  errorMessage = signal('');

  confirm(): void {
    const patient = this.foundPatient();
    if (!patient || !this.selectedDoctorId() || !this.selectedDate() || !this.selectedTime())
    {return;}

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.service.addAppointment({
      patientId: patient.id,
      doctorId: this.selectedDoctorId(),
      date: this.selectedDate(),
      time: this.selectedTime(),
    }).subscribe({
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

}
