import {
  ChangeDetectionStrategy,
  Component,
  inject,
  output,
} from '@angular/core';
import {
  LucideCheckCircle,
  LucideStethoscope,
  LucideUserSearch,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { ErroresPipe } from '../../../../shared/pipes/erroresPipe';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { NewAppointment } from '../../models/dtos/newAppointment.dto';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

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
    ButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-confirm.component.html',
})
export class BookingConfirmComponent {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  confirmed = output<void>();
  back = output<void>();

  /**
   * Envía la cita al backend. Todos los errores muestran
   * el mensaje que resuelve el interceptor.
   */
  confirm(): void {
    const date = this.state.selectedDate();
    if (!date || !this.state.selectedTime() || !this.state.selectedDoctorId())
      return;

    this.state.isLoading.set(true);
    this.state.errorMessage.set('');

    const data = this.buildPayload(date);

    this.citaService.addAppointment(data).subscribe({
      next: () => {
        this.state.isLoading.set(false);
        this.state.success.set(true);
        this.confirmed.emit();
      },
      error: (err: AppError) => {
        this.state.isLoading.set(false);
        this.state.errorMessage.set(err.message);
      },
    });
  }

  goBack(): void {
    this.state.errorMessage.set('');
    this.back.emit();
  }

  private buildPayload(date: Date): NewAppointment {
    const base = {
      doctorId: this.state.selectedDoctorId(),
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
