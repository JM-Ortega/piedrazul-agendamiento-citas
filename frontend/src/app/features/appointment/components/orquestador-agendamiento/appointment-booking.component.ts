import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  Input,
  OnInit,
  output,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timer } from 'rxjs';
import { PatientAppointmentService } from '../../../../core/services/patientAppointment.service';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { BookingSpecialtySelectorComponent } from '../../../appointment/components/seleccion-especialidad/booking-specialty-selector.component';
import { BookingScheduleSelectorComponent } from '../../../appointment/components/seleccion-horario/booking-schedule-selector.component';
import { BookingPatientSearchComponent } from '../../components/busqueda-paciente/booking-patient-search.component';
import { BookingPatientRegisterComponent } from '../../components/registro-paciente/booking-patient-register.component';
import { SpecialtyDoctor } from '../../models/dtos/specialty-doctor.dto';
import { BookingContext } from '../../models/types/bookingContext.type';
import { BookingMode } from '../../models/types/bookingMode.type';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { BookingConfirmComponent } from '../confirmacion/booking-confirm.component';
import { BookingModeSelectorComponent } from '../modo-agendamiento/booking-mode-selector.component';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

/**
 * Coordina el flujo de agendamiento componiendo los
 * componentes hermanos en el orden correcto según el contexto y step actual.
 */
@Component({
  selector: 'app-appointment-booking',
  standalone: true,
  providers: [BookingStateService],
  imports: [
    CommonModule,
    BookingModeSelectorComponent,
    BookingPatientSearchComponent,
    BookingPatientRegisterComponent,
    BookingSpecialtySelectorComponent,
    BookingScheduleSelectorComponent,
    BookingConfirmComponent,
    ButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './appointment-booking.component.html',
})
export class AppointmentBookingComponent implements OnInit {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);
  private destroyRef = inject(DestroyRef);
  private patientAppointmentService = inject(PatientAppointmentService);

  @Input() context: BookingContext = 'patient';

  @Input() set patientData(value: Partial<Patient> | null) {
    this.state.patientSnapshot.set(value);
  }

  @Input() set isNewPatient(value: boolean) {
    this.state.isNewPatient.set(value);
  }

  /** Documento precargado al entrar desde el contexto `doctor`. */
  @Input() set documentNumber(value: string) {
    if (value) {
      this.pendingDocumentNumber = value;
    }
  }
  private pendingDocumentNumber = '';

  /**
   * Especialidad e id del médico a preseleccionar en contexto `doctor`,
   * usados al reagendar desde una cita previa.
   */
  @Input() prefillSpecialty = '';
  @Input() prefillDoctorId = '';

  /** Documento a precargar en el buscador tras confirmar un "documento ya existe". */
  pendingSearchDocument = '';

  goBack = output<void>();

  patientSubStep: 'search' | 'register' = 'search';

  get isPatientStep(): boolean {
    return (
      this.state.isSchedulerContext() &&
      this.state.step() === this.state.patientLookupStep()
    );
  }

  ngOnInit(): void {
    this.state.context.set(this.context);
    if (this.state.isDoctorContext()) {
      this.initDoctorContext();
    }
  }

  /**
   * Inicializa el flujo cuando un médico agenda una cita.
   * En el paso de especialidad carga TODAS las especialidades
   * disponibles y, si además llegaronm `prefillSpecialty`/`prefillDoctorId`,
   * se preselecciona esa combinación.
   */
  private initDoctorContext(): void {
    this.state.bookingMode.set('specialty-doctor');
    this.state.step.set(this.state.specialtyStep());

    if (!this.pendingDocumentNumber) {
      this.finishDoctorInit();
      return;
    }

    this.citaService
      .getPatientByDocument(this.pendingDocumentNumber)
      .subscribe({
        next: (patient) => {
          this.state.foundPatient.set(patient);
          this.state.patientId.set(patient?.id ?? null);
          this.finishDoctorInit();
        },
        error: (err: AppError) => {
          this.state.globalErrorMessage.set(err.message);
        },
      });
  }

  private finishDoctorInit(): void {
    this.loadSpecialtiesForMode('specialty-doctor');
    this.applyPrefillSelection();
    this.state.step.set(this.state.specialtyStep());
  }

  /**
   * Si llegó especialidad + id de médico de una cita previa, selecciona esa
   * especialidad y carga sus médicos para preseleccionar el médico indicado
   * sin restringir el resto de especialidades ya cargadas.
   */
  private applyPrefillSelection(): void {
    if (!this.prefillSpecialty || !this.prefillDoctorId) return;
    this.loadDoctorsBySpecialty(this.prefillSpecialty, this.prefillDoctorId);
  }

  onModeSelected(mode: BookingMode): void {
    if (!this.state.isSchedulerContext()) {
      this.loadSpecialtiesForMode(mode);
    }
  }

  /**
   * Al confirmar un paciente encontrado, determina si es paciente nuevo
   * antes de avanzar, para que el paso de especialidad pueda restringir
   * opciones según corresponda.
   */
  onPatientConfirmed(): void {
    const patientId = this.state.patientId();
    if (patientId) {
      this.patientAppointmentService.hasAppointments(patientId).subscribe({
        next: (isNew) => {
          this.state.isNewPatient.set(isNew);
          this.loadSpecialtiesForMode(this.state.bookingMode());
          this.state.step.set(this.state.specialtyStep());
        },
        error: (err: AppError) => {
          this.state.globalErrorMessage.set(err.message);
          this.state.isNewPatient.set(false);
          this.loadSpecialtiesForMode(this.state.bookingMode());
          this.state.step.set(this.state.specialtyStep());
        },
      });
    } else {
      this.loadSpecialtiesForMode(this.state.bookingMode());
      this.state.step.set(this.state.specialtyStep());
    }
  }

  onPatientMissing(): void {
    this.patientSubStep = 'register';
  }

  onSearchChangeMode(): void {
    this.patientSubStep = 'search';
    this.pendingSearchDocument = '';
    this.state.bookingMode.set(null);
    this.state.step.set(1);
  }

  /**
   * Se dispara cuando el documento ingresado resulta pertenecer a un
   * paciente ya existente. Devuelve al paso de búsqueda con ese documento
   * precargado.
   *
   * @param doc - Documento que ya existe en el sistema.
   */
  onExistingDocumentConfirmed(doc: string): void {
    this.pendingSearchDocument = doc;
    this.patientSubStep = 'search';
  }

  // Eventos de BookingPatientRegister
  onRegisterAdvance(): void {
    this.state.isNewPatient.set(true);
    this.loadSpecialtiesForMode(this.state.bookingMode());
    this.state.step.set(this.state.specialtyStep());
  }

  onRegisterGoBack(): void {
    this.patientSubStep = 'search';
    this.pendingSearchDocument = '';
    this.state.notFound.set(false);
    this.state.searchQuery.set('');
    this.state.searchSuggestions.set([]);
    this.state.searchError.set('');
  }

  // Eventos de BookingSpecialtySelector
  onSpecialtyChanged(specialty: string): void {
    this.loadDoctorsBySpecialty(specialty);
  }

  onSpecialtyAdvance(): void {
    this.state.step.set(this.state.scheduleStep());
  }

  onSpecialtyBack(): void {
    this.state.resetSpecialtyState();
    if (this.state.isSchedulerContext()) {
      this.patientSubStep = this.state.notFound() ? 'register' : 'search';
      this.state.step.set(this.state.patientLookupStep()!);
      return;
    }
    if (this.state.isDoctorContext()) {
      this.goBack.emit();
      return;
    }
    this.state.bookingMode.set(null);
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

  /** Tras confirmar la cita, espera 3s para mostrar el mensaje de éxito y cierra el flujo. */
  onConfirmed(): void {
    timer(3000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.goBack.emit());
  }

  onSuccessGoBack(): void {
    this.goBack.emit();
  }

  // Carga de datos
  private loadSpecialtiesForMode(mode: BookingMode): void {
    this.state.noSpecialtyAvailable.set(false);
    this.state.errorMessageSpecialty.set('');
    this.state.globalErrorMessage.set('');

    if (mode === 'specialty') {
      this.loadSpecialtiesWithDoctor();
    } else if (mode === 'specialty-doctor') {
      this.loadSpecialties();
    }
  }

  /**
   * Carga especialidades con su médico ya asignado (modo `specialty`).
   */
  private loadSpecialtiesWithDoctor(): void {
    const patientId = this.resolvePatientIdForSpecialties() || null;

    this.citaService.getSpecialtiesWithDoctor(patientId).subscribe({
      next: (data) => {
        this.state.specialtiesWithDoctor.set(data);
      },
      error: (err: AppError) => {
        if (
          err.errorCode === 'NO_ACTIVE_DOCTORS' ||
          err.errorCode === 'NO_AVAILABLE_DOCTORS'
        ) {
          this.state.noSpecialtyAvailable.set(true);
          this.state.errorMessageSpecialty.set(err.message);
        } else {
          this.state.globalErrorMessage.set(err.message);
        }
      },
    });
  }

  private loadSpecialties(): void {
    const patientId = this.resolvePatientIdForSpecialties();

    if (!patientId) {
      this.traerEspecialidades(null);
      return;
    }
    this.traerEspecialidades(patientId);
  }

  /**
   * Carga especialidades sin médico asignado (modo `specialty-doctor`,
   * usado tanto por el agendador/paciente sin médico elegido como por el
   * contexto `doctor`).
   *
   * @param patientId - Id del paciente para filtrar especialidades, o `null` si no aplica.
   */
  private traerEspecialidades(patientId: string | null): void {
    this.citaService.getSpecialties(patientId).subscribe({
      next: (specs) => {
        this.state.specialtiesWithDoctor.set(
          specs.map((s) => ({
            specialty: [s],
            id: '',
            name: '',
            laborStart: null,
            laborEnd: null,
            bookingWindowWeeks: null,
            workdays: [],
          }))
        );
        if (this.prefillSpecialty && specs.includes(this.prefillSpecialty)) {
          this.state.selectedSpecialty.set(this.prefillSpecialty);
        }
      },
      error: (err: AppError) => {
        if (
          err.errorCode === 'NO_ACTIVE_DOCTORS' ||
          err.errorCode === 'NO_AVAILABLE_DOCTORS'
        ) {
          this.state.noSpecialtyAvailable.set(true);
          this.state.errorMessageSpecialty.set(err.message);
        } else {
          this.state.globalErrorMessage.set(err.message);
        }
      },
    });
  }

  /**
   * Carga los médicos disponibles para una especialidad elegida. Si se
   * indica `preselectDoctorId`, intenta preseleccionar ese médico
   * una vez cargada la lista (usado al reagendar desde una cita previa).
   *
   * @param specialty - Especialidad seleccionada por el usuario.
   * @param preselectDoctorId - Id del médico a preseleccionar tras cargar, si aplica.
   */
  private loadDoctorsBySpecialty(
    specialty: string,
    preselectDoctorId?: string
  ): void {
    this.state.noDoctorsFound.set(false);
    this.state.errorMessageDoctors.set('');
    this.state.doctorsBySpecialty.set([]);

    this.citaService.getDoctorsBySpecialty(specialty).subscribe({
      next: (docs) => {
        this.state.doctorsBySpecialty.set(docs);
        if (preselectDoctorId) {
          this.preselectDoctorFromList(docs, preselectDoctorId);
        }
      },
      error: (err: AppError) => {
        if (
          err.errorCode === 'NO_ACTIVE_DOCTORS' ||
          err.errorCode === 'NO_AVAILABLE_DOCTORS'
        ) {
          this.state.noDoctorsFound.set(true);
          this.state.errorMessageDoctors.set(err.message);
        } else {
          this.state.globalErrorMessage.set(err.message);
        }
      },
    });
  }

  /**
   * Preselecciona un médico dentro de una lista disponible utilizando su identificador.
   *
   * Si el médico especificado existe en la lista, actualiza el estado local con su ID y nombre.
   * De lo contrario, establece un mensaje de error indicando que el médico ya no está disponible.
   *
   * @param docs - Lista de médicos disponibles para la especialidad (`SpecialtyDoctor[]`).
   * @param doctorId - Identificador único del médico a buscar (`string`).
   */
  private preselectDoctorFromList(
    docs: SpecialtyDoctor[],
    doctorId: string
  ): void {
    const match = docs.find((d) => d.id === doctorId);
    if (!match) {
      this.state.errorMessageDoctors.set(
        'El médico de la cita anterior ya no está disponible para esta especialidad. Seleccione uno nuevo.'
      );
      return;
    }
    this.state.selectedDoctorId.set(match.id);
    this.state.selectedDoctorName.set(match.name);
  }

  /**
   * Obtiene el ID del paciente según el contexto actual del proceso de agendamiento.
   *
   * - En contexto de agendador (`isSchedulerContext`): Retorna directamente el ID del paciente en el estado.
   * - En otros contextos: Intenta obtener el ID del estado principal o, en su defecto,
   *   del snapshot del paciente (`patientSnapshot`).
   *
   * @returns {string} El ID del paciente identificado o una cadena vacía si no existe.
   */
  private resolvePatientIdForSpecialties(): string {
    if (this.state.isSchedulerContext()) {
      return this.state.patientId() ?? '';
    }
    return this.state.patientId() ?? this.state.patientSnapshot()?.id ?? '';
  }
}
