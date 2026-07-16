import {
  Component,
  inject,
  output,
  ChangeDetectionStrategy,
} from '@angular/core';
import {
  LucideCheckCircle,
  LucideStethoscope,
  LucideUserSearch,
} from '@lucide/angular';
import { NewAppointment } from '../../models/dtos/newAppointment.dto';
import { AppointmentConfirmedEvent } from '../../models/interfaces/appointmentConfirmedEvent.model';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { ErroresPipe } from '../../../../shared/pipes/erroresPipe';

/**
 * Mostrar el resumen completo de la cita a confirmar
 * y ejecutar la llamada al backend para registrarla.
 */
@Component({
  selector: 'app-booking-confirm',
  standalone: true,
  imports: [
    LucideCheckCircle,
    LucideStethoscope,
    LucideUserSearch,
    FormatoPipe,
    ErroresPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-confirm.component.html',
})
export class BookingConfirmComponent {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  confirmed = output<AppointmentConfirmedEvent>();

  back = output<void>();

  confirm(): void {
    const date = this.state.selectedDate();
    if (!date || !this.state.selectedTime() || !this.state.effectiveDoctorId())
      return;

    this.state.isLoading.set(true);
    this.state.errorMessage.set('');

    const data = this.buildPayload(date);

    this.citaService.addAppointment(data).subscribe({
      next: () => {
        this.state.isLoading.set(false);
        this.state.success.set(true);
        this.confirmed.emit({ patientId: this.state.resolvePatientId() });
      },
      error: (err) => {
        this.state.isLoading.set(false);

        if (err.status === 0) {
          this.state.errorMessage.set(
            'No se pudo conectar con el servidor. Intente más tarde.'
          );
          return;
        }

        const errorCode = err.error?.errorCode;
        const detail = err.error?.detail;
        switch (errorCode) {
          case 'PATIENT_TIME_CONFLICT':
          case 'PATIENT_SPECIALTY_CONFLICT':
            this.state.errorMessage.set(detail);
            break;
          default:
            this.state.errorMessage.set(
              detail || 'Error inesperado al registrar la cita.'
            );
        }
      },
    });
  }

  goBack(): void {
    this.state.errorMessage.set('');
    this.back.emit();
  }

  private buildPayload(date: Date): NewAppointment {
    const base = {
      doctorId: this.state.effectiveDoctorId(),
      specialty: this.state.selectedSpecialty(),
      date: this.state.formatLocalDate(date),
      startTime: this.state.selectedTime(),
      documentType: this.state.confirmDocumentType(),
      documentNumber: this.state.confirmDocument(),
      firstName: this.state.confirmFirstName(),
      lastName: this.state.confirmLastName(),
      phone: this.state.confirmPhone(),
      gender: this.state.confirmGender(),
      birthDate: this.state.confirmBirthDate(),
    };

    if (this.state.isSchedulerContext() || this.state.isDoctorContext()) {
      const f = this.state.patientForm();
      return {
        ...base,
        schedulingOrigin: 'MANUAL',
        ...(this.state.patientId()
          ? { patientId: this.state.patientId()! }
          : {
              email: f.email || undefined,
              guardianPhone: f.guardianPhone || undefined,
            }),
      };
    }

    return {
      ...base,
      patientId: this.state.patientSnapshot()?.id,
      email: this.state.patientSnapshot()?.email,
      schedulingOrigin: 'AUTONOMO',
    };
  }
}
