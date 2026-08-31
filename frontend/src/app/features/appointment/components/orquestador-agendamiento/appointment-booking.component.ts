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
import { BookingSchedulingComponent } from '../../../appointment/components/agendamiento/booking-scheduling.component';
import { BookingPatientSearchComponent } from '../../components/busqueda-paciente/booking-patient-search.component';
import { BookingPatientRegisterComponent } from '../../components/registro-paciente/booking-patient-register.component';
import { SpecialtyDoctor } from '../../models/dtos/specialty-doctor.dto';
import { BookingContext } from '../../models/types/bookingContext.type';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { BookingConfirmComponent } from '../confirmacion/booking-confirm.component';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

/**
 * Coordina el flujo de agendamiento componiendo los
 * componentes hermanos en el orden correcto según el contexto y step actual.
 *
 * Ya no existe selección de "modo" de agendamiento: el comportamiento
 * (qué inputs se muestran, qué petición trae los médicos) depende
 * únicamente de `context`.
 */
@Component({
  selector: 'app-appointment-booking',
  standalone: true,
  providers: [BookingStateService],
  imports: [
    CommonModule,
    BookingPatientSearchComponent,
    BookingPatientRegisterComponent,
    BookingSchedulingComponent,
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
   * Especialidad e id del médico a preseleccionar en contexto `doctor`
   * (tanto para el médico en sesión por defecto como al reagendar desde
   * una cita previa). El usuario puede cambiar ambos libremente.
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
    this.state.initContext(this.context);
    if (this.state.isDoctorContext()) {
      this.initDoctorContext();
    } else if (this.state.isPatientContext()) {
      this.initPatientContext();
    }
  }

  private initPatientContext(): void {
    this.state.step.set(this.state.schedulingStep());
    this.loadDoctors();
  }

  /**
   * Inicializa el flujo cuando un médico agenda una cita. Si llega
   * `documentNumber`, primero busca al paciente para tener su id
   * disponible (por si afecta la disponibilidad de médicos).
   */
  private initDoctorContext(): void {
    this.state.step.set(this.state.schedulingStep());

    if (!this.pendingDocumentNumber) {
      this.loadDoctors();
      return;
    }

    this.citaService
      .getPatientByDocument(this.pendingDocumentNumber)
      .subscribe({
        next: (patient) => {
          this.state.foundPatient.set(patient);
          this.state.patientId.set(patient?.id ?? null);
          this.loadDoctors();
        },
        error: (err: AppError) => {
          this.state.globalErrorMessage.set(err.message);
          this.loadDoctors();
        },
      });
  }

  /**
   * Al confirmar un paciente encontrado, determina si es paciente nuevo
   * antes de avanzar, y carga los médicos para el paso de agendamiento.
   */
  onPatientConfirmed(): void {
    const patientId = this.state.patientId();
    if (patientId) {
      this.patientAppointmentService.hasAppointments(patientId).subscribe({
        next: (isNew) => {
          this.state.isNewPatient.set(isNew);
          this.loadDoctors();
          this.state.step.set(this.state.schedulingStep());
        },
        error: (err: AppError) => {
          this.state.globalErrorMessage.set(err.message);
          this.state.isNewPatient.set(false);
          this.loadDoctors();
          this.state.step.set(this.state.schedulingStep());
        },
      });
    } else {
      this.loadDoctors();
      this.state.step.set(this.state.schedulingStep());
    }
  }

  onPatientMissing(): void {
    this.patientSubStep = 'register';
  }

  onSearchChangeMode(): void {
    this.patientSubStep = 'search';
    this.pendingSearchDocument = '';
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
    this.loadDoctors();
    this.state.step.set(this.state.schedulingStep());
  }

  onRegisterGoBack(): void {
    this.patientSubStep = 'search';
    this.pendingSearchDocument = '';
    this.state.notFound.set(false);
    this.state.searchQuery.set('');
    this.state.searchSuggestions.set([]);
    this.state.searchError.set('');
  }

  // Eventos de BookingScheduling
  onSchedulingAdvance(): void {
    this.state.step.set(this.state.confirmStep());
  }

  onSchedulingBack(): void {
    if (this.state.isSchedulerContext()) {
      this.state.resetDoctorState();
      this.patientSubStep = this.state.notFound() ? 'register' : 'search';
      this.state.step.set(this.state.patientLookupStep()!);
      return;
    }
    // doctor / patient: no hay paso previo dentro del flujo, se sale de él
    this.goBack.emit();
  }

  onConfirmBack(): void {
    this.state.step.set(this.state.schedulingStep());
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
  /**
   * Carga la lista de médicos para el contexto actual:
   * - `patient`: `getDoctors`, sin filtrar por especialidad.
   * - `doctor` / `scheduler`: `getSpecialtiesWithDoctor`, cada médico trae
   *   sus propias especialidades.
   *
   * En contexto `doctor`, si llegó `prefillDoctorId`, intenta preseleccionar
   * ese médico (y su especialidad) una vez cargada la lista.
   */
  private loadDoctors(): void {
    this.state.noDoctorsFound.set(false);
    this.state.errorMessageDoctors.set('');
    this.state.globalErrorMessage.set('');

    const patientId = this.state.resolvePatientId() || null;
    const request$ = this.state.isPatientContext()
      ? this.citaService.getDoctors(patientId)
      : this.citaService.getSpecialtiesWithDoctor(patientId);

    request$.subscribe({
      next: (docs) => {
        this.state.doctors.set(docs);
        if (this.state.isDoctorContext()) {
          this.applyDoctorPrefill(docs);
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
   * Preselecciona médico y especialidad en contexto `doctor` a partir de
   * `prefillDoctorId`/`prefillSpecialty`, sin restringir el resto de
   * médicos/especialidades ya cargados: el usuario puede cambiar ambos.
   *
   * @param docs - Lista de médicos ya cargada.
   */
  private applyDoctorPrefill(docs: SpecialtyDoctor[]): void {
    if (!this.prefillDoctorId) return;

    const match = docs.find((d) => d.id === this.prefillDoctorId);
    if (!match) {
      this.state.errorMessageDoctors.set(
        'El médico de la cita anterior ya no está disponible. Seleccione uno nuevo.'
      );
      return;
    }

    this.state.selectedDoctorId.set(match.id);
    this.state.selectedDoctorName.set(match.name);
    this.state.selectedSpecialty.set(
      this.prefillSpecialty && match.specialty.includes(this.prefillSpecialty)
        ? this.prefillSpecialty
        : (match.specialty[0] ?? '')
    );
  }
}
