import { Component, inject, Input, OnInit, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BookingStateService } from '../../booking-state.service';
import { BookingModeSelectorComponent } from '../modo-agendamiento/booking-mode-selector.component';
import { BookingPatientLookupComponent } from '../busqueda-registro-paciente/booking-patient-lookup.component';
import { BookingSpecialtySelectorComponent } from '../../../appointment/components/seleccion-especialidad/booking-specialty-selector.component';
import { BookingScheduleSelectorComponent } from '../../../appointment/components/seleccion-horario/booking-schedule-selector.component';
import { BookingConfirmComponent } from '../confirmacion/booking-confirm.component';
import { NuevaCitaService } from '../../../../services/nuevaCita.service';
import { PatientSnapshot } from '../../../../models/interfaces/patientSnapshot.model';
import { AppointmentConfirmedEvent } from '../../../../models/interfaces/appointmentConfirmedEvent.model';
import { BookingContext } from '../../../../models/types/bookingContext.type';
import { BookingMode } from '../../../../models/types/bookingMode.type';

/**
 * AppointmentBookingComponent — Orquestador
 *
 * Responsabilidad: coordinar el flujo de agendamiento componiendo los
 * componentes hermanos en el orden correcto según el contexto y step actual.
 *
 * NO contiene lógica de negocio ni de presentación propia: delega todo
 * a los componentes hermanos y al BookingStateService.
 *
 * Se provee BookingStateService a nivel de este componente para que cada
 * instancia del flujo tenga su propio estado aislado.
 *
 * Contextos soportados (extensible a nuevos roles):
 *   - 'patient':    3 steps (Especialidad → Horario → Confirmar)
 *   - 'scheduler':  4 steps (Paciente → Especialidad → Horario → Confirmar)
 */
@Component({
  selector: 'app-appointment-booking',
  standalone: true,
  providers: [BookingStateService],          // Estado aislado por instancia
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

  // ── Inputs ────────────────────────────────────────────────────────────────

  /** Define el rol que inicia el flujo. */
  @Input() context: BookingContext = 'patient';

  /**
   * Datos del paciente autenticado.
   * Solo relevante cuando context === 'patient'.
   */
  @Input() set patientData(value: PatientSnapshot | null) {
    this.state.patientSnapshot.set(value);
  }

  // ── Outputs ───────────────────────────────────────────────────────────────

  /** La cita fue registrada exitosamente. */
  appointmentConfirmed = output<AppointmentConfirmedEvent>();

  /** El usuario pulsó "Volver al inicio" en el modal de éxito. */
  goBack = output<void>();

  // ────────────────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.state.context.set(this.context);
  }

  // ── Manejadores de eventos de componentes hermanos ────────────────────────

  /**
   * El selector de modo emitió una elección.
   * Para el paciente, cargamos las especialidades de inmediato.
   * Para el agendador, la carga se hace al avanzar desde el step de paciente.
   */
  onModeSelected(mode: BookingMode): void {
    if (!this.state.isSchedulerContext()) {
      this._loadSpecialtiesForMode(mode);
    }
  }

  /**
   * El lookup de paciente avanzó: ir al step de especialidad
   * y cargar las listas correspondientes.
   */
  onPatientLookupAdvance(): void {
    this._loadSpecialtiesForMode(this.state.bookingMode());
    this.state.step.set(this.state.specialtyStep());
  }

  /** Desde patient-lookup se pidió volver al selector de modo. */
  onPatientLookupChangeMode(): void {
    this.state.step.set(1);
  }

  /**
   * El selector de especialidad cambió la especialidad seleccionada
   * en modo 'specialty-doctor': cargar los médicos disponibles.
   */
  onSpecialtyChanged(specialty: string): void {
    this._loadDoctorsBySpecialty(specialty);
  }

  /** Selector de especialidad avanzó: ir al step de horario. */
  onSpecialtyAdvance(): void {
    this.state.step.set(this.state.scheduleStep());
  }

  /** Selector de especialidad retrocedió. */
  onSpecialtyBack(): void {
    if (this.state.isSchedulerContext()) {
      this.state.step.set(this.state.patientLookupStep()!);
    } else {
      this.state.bookingMode.set(null);
    }
  }

  /** Selector de horario avanzó: ir al step de confirmación. */
  onScheduleAdvance(): void {
    this.state.step.set(this.state.confirmStep());
  }

  /** Selector de horario retrocedió: volver a especialidad. */
  onScheduleBack(): void {
    this.state.step.set(this.state.specialtyStep());
  }

  /** Confirmación retrocedió: volver a horario. */
  onConfirmBack(): void {
    this.state.step.set(this.state.scheduleStep());
  }

  /** La cita fue confirmada con éxito. */
  onConfirmed(event: AppointmentConfirmedEvent): void {
    this.appointmentConfirmed.emit(event);
    setTimeout(() => this.goBack.emit(), 3000);
  }

  /** El usuario pulsó "Volver al inicio" en el modal. */
  onSuccessGoBack(): void {
    this.goBack.emit();
  }

  // ── Carga de datos (lógica de red centralizada en el orquestador) ─────────

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