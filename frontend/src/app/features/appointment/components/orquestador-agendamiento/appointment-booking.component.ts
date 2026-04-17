import { Component, inject, Input, OnInit, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BookingStateService } from '../../booking-state.service';
import { BookingModeSelectorComponent } from '../modo-agendamiento/booking-mode-selector.component';
import { BookingPatientLookupComponent } from '../busqueda-registro-paciente/booking-patient-lookup.component';
import { BookingSpecialtySelectorComponent } from '../../../appointment/components/seleccion-especialidad/booking-specialty-selector.component';
import { BookingScheduleSelectorComponent } from '../../../appointment/components/seleccion-horario/booking-schedule-selector.component';
import { BookingConfirmComponent } from '../confirmacion/booking-confirm.component';
import { NuevaCitaService } from '../../../../services/nuevaCita.service';
import { Patient } from '../../../../models/interfaces/patient.model';
import { AppointmentConfirmedEvent } from '../../../../models/interfaces/appointmentConfirmedEvent.model';
import { BookingContext } from '../../../../models/types/bookingContext.type';
import { BookingMode } from '../../../../models/types/bookingMode.type';

/**
 * Coordina el flujo de agendamiento componiendo los
 * componentes hermanos en el orden correcto según el contexto y step actual.
 * Se provee BookingStateService a nivel de este componente para que cada
 * instancia del flujo tenga su propio estado aislado.
 *
 * Contextos soportados:
 *   - 'patient':    3 steps (Especialidad → Horario → Confirmar)
 *   - 'scheduler':  4 steps (Paciente → Especialidad → Horario → Confirmar)
 */
@Component({
  selector: 'app-appointment-booking',
  standalone: true,
  providers: [BookingStateService],
  imports: [
    CommonModule,
    BookingModeSelectorComponent,
    BookingPatientLookupComponent,
    BookingSpecialtySelectorComponent,
    BookingScheduleSelectorComponent,
    BookingConfirmComponent,
  ],
  templateUrl: './appointment-booking.component.html',
})
export class AppointmentBookingComponent implements OnInit {

  protected state     = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  @Input() context: BookingContext = 'patient';

  //Datos del paciente autenticado
  @Input() set patientData(value: Partial<Patient> | null) { this.state.patientSnapshot.set(value); }

  //Outputs
  appointmentConfirmed = output<AppointmentConfirmedEvent>();
  goBack = output<void>();

  ngOnInit(): void {
    this.state.context.set(this.context);
  }

  onModeSelected(mode: BookingMode): void {
    if (!this.state.isSchedulerContext()) {
      this._loadSpecialtiesForMode(mode);
    }
  }

  onPatientLookupAdvance(): void {
    this._loadSpecialtiesForMode(this.state.bookingMode());
    this.state.step.set(this.state.specialtyStep());
  }

  onPatientLookupChangeMode(): void {
    this.state.step.set(1);
  }

  onSpecialtyChanged(specialty: string): void {
    this._loadDoctorsBySpecialty(specialty);
  }

  onSpecialtyAdvance(): void {
    this.state.step.set(this.state.scheduleStep());
  }

  onSpecialtyBack(): void {
    if (this.state.isSchedulerContext()) {
      this.state.step.set(this.state.patientLookupStep()!);
    } else {
      this.state.bookingMode.set(null);
    }
  }

  onScheduleAdvance(): void {
    this.state.step.set(this.state.confirmStep());
  }

  onScheduleBack(): void {
    this.state.step.set(this.state.specialtyStep());
  }

  onConfirmBack(): void {
    this.state.step.set(this.state.scheduleStep());
  }

  onConfirmed(event: AppointmentConfirmedEvent): void {
    this.appointmentConfirmed.emit(event);
    setTimeout(() => this.goBack.emit(), 3000);
  }

  onSuccessGoBack(): void {
    this.goBack.emit();
  }

  // Carga de datos
  private _loadSpecialtiesForMode(mode: BookingMode): void {
    this.state.noSpecialtyAvailable.set(false);
    this.state.errorMessageSpecialty.set('');

    if (mode === 'specialty') {
      this._loadSpecialtiesWithDoctor();
    } else if (mode === 'specialty-doctor') {
      this._loadSpecialties();
    }
  }

  private _loadSpecialtiesWithDoctor(): void {
    this.citaService.getSpecialtiesWithDoctor().subscribe({
      next: (data) => this.state.specialtiesWithDoctor.set(data),
      error: (err) => {
        this.state.noSpecialtyAvailable.set(true);
        switch (err.status) {
          case 409:
            this.state.errorMessageSpecialty.set('No hay médicos disponibles para ninguna especialidad. Intente más tarde.');
            break;
          case 0:
            this.state.errorMessageSpecialty.set('No se pudo conectar con el servidor. Intente más tarde.');
            break;
          default:
            this.state.errorMessageSpecialty.set('Error al obtener las especialidades.');
        }
      },
    });
  }

  private _loadSpecialties(): void {
    this.citaService.getSpecialties().subscribe({
      next: (specs) => {
        if (!specs || specs.length === 0) {
          this.state.noSpecialtyAvailable.set(true);
          this.state.errorMessageSpecialty.set('No hay especialidades disponibles.');
          return;
        }
        this.state.specialtiesWithDoctor.set(
          specs.map(s => ({ specialty: s, id: '', name: '', laborEnd: null, workdays: [] }))
        );
      },
      error: (err) => {
        this.state.noSpecialtyAvailable.set(true);
        if (err.status === 0) {
          this.state.errorMessageSpecialty.set('No se pudo conectar con el servidor. Intente más tarde.');
          return;
        }
        this.state.errorMessageSpecialty.set('Error al obtener las especialidades.');
      },
    });
  }

  private _loadDoctorsBySpecialty(specialty: string): void {
    this.state.noDoctorsFound.set(false);
    this.state.errorMessageDoctors.set('');
    this.state.doctorsBySpecialty.set([]);

    this.citaService.getDoctorsBySpecialty(specialty).subscribe({
      next: (docs) => {
        this.state.doctorsBySpecialty.set(docs);
        this.state.noDoctorsFound.set(docs.length === 0);
        if (docs.length === 0) {
          this.state.errorMessageDoctors.set('No hay médicos disponibles para esta especialidad.');
        }
      },
      error: (err) => {
        this.state.noDoctorsFound.set(true);
        switch (err.status) {
          case 404:
            this.state.errorMessageDoctors.set('No hay médicos disponibles para esta especialidad.');
            break;
          case 0:
            this.state.errorMessageDoctors.set('No se pudo conectar con el servidor. Intente más tarde.');
            break;
          default:
            this.state.errorMessageDoctors.set('Error al obtener los médicos.');
        }
      },
    });
  }
}