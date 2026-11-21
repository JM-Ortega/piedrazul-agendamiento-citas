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
import { PatientAppointmentService } from '../../../../core/services/patient.service';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { BookingSchedulingComponent } from '../scheduling/bookingScheduling.component';
import { BookingPatientSearchComponent } from '../searchPatient/bookingPatientSearch.component';
import { BookingPatientRegisterComponent } from '../registerPatient/bookingPatientRegister.component';
import { SpecialtyDoctor } from '../../models/dtos/specialtyDoctor.dto';
import { BookingContext } from '../../models/types/bookingContext.type';
import { BookingStateService } from '../../services/bookingState.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { BookingConfirmComponent } from '../confirmAppointment/bookingConfirm.component';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';

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
  templateUrl: './appointmentBooking.component.html',
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

  /**
   * Activa el modo reagendamiento con el id de la cita original. Se usa en
   * contexto `scheduler`: se salta la búsqueda de paciente y al confirmar
   * se llama al endpoint de reschedule.
   */
  @Input() set rescheduleAppointmentId(value: string) {
    this.state.rescheduleAppointmentId.set(value || null);
  }

  /**
   * Documento del paciente precargado: al entrar desde el contexto `doctor`
   * o al reagendar.
   */
  @Input() set documentNumber(value: string) {
    if (value) {
      this.pendingDocumentNumber = value;
    }
  }
  private pendingDocumentNumber = '';

  /**
   * Especialidad y médico a preseleccionar en contexto `doctor` o al
   * reagendar. El médico se busca por id y, si no llega id, por nombre
   * (la tabla de citas del agendador solo trae `doctorName`).
   * El usuario puede cambiar ambos libremente.
   */
  @Input() prefillSpecialty = '';
  @Input() prefillDoctorId = '';
  @Input() prefillDoctorName = '';

  goBack = output<void>();

  patientSubStep: 'search' | 'register' = 'search';

  get isPatientStep(): boolean {
    return (
      this.state.hasPatientLookupStep() &&
      this.state.step() === this.state.patientLookupStep()
    );
  }

  /** Médico/especialidad se precargan al agendar como médico o al reagendar. */
  private get shouldApplyDoctorPrefill(): boolean {
    return this.state.isDoctorContext() || this.state.isReschedule();
  }

  ngOnInit(): void {
    this.state.initContext(this.context);
    if (this.state.isDoctorContext() || this.state.isReschedule()) {
      this.initWithKnownPatient();
    } else if (this.state.isPatientContext()) {
      this.initPatientContext();
    }
  }

  private initPatientContext(): void {
    this.state.step.set(this.state.schedulingStep());
    this.loadDoctors();
  }

  /**
   * Inicializa el flujo cuando el paciente ya es conocido: un médico que
   * agenda, o el agendador que reagenda. Arranca directo en el paso de
   * agendamiento y, si llega `documentNumber`, primero busca al paciente
   * para tener su id disponible (afecta la disponibilidad de médicos y
   * alimenta el resumen de confirmación).
   */
  private initWithKnownPatient(): void {
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

  /**
   * Se dispara cuando el documento ingresado resulta pertenecer a un
   * paciente ya existente. Devuelve al paso de búsqueda con ese documento
   * precargado.
   *
   * @param doc - Documento que ya existe en el sistema.
   */
  onExistingDocumentConfirmed(doc: string): void {
    this.state.lastSearchedDocument.set(doc);
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
    this.state.resetSearchState();
  }

  // Eventos de BookingScheduling
  onSchedulingAdvance(): void {
    this.state.step.set(this.state.confirmStep());
  }

  onSchedulingBack(): void {
    if (this.state.hasPatientLookupStep()) {
      this.state.resetDoctorState();
      this.patientSubStep = this.state.notFound() ? 'register' : 'search';
      this.state.step.set(this.state.patientLookupStep()!);
      return;
    }
    // doctor / patient / reagendamiento: no hay paso previo, se sale del flujo
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

  private loadDoctors(): void {
    this.state.noDoctorsFound.set(false);
    this.state.errorMessageDoctors.set('');
    this.state.globalErrorMessage.set('');

    const patientId = this.state.resolvePatientId() || null;

    this.citaService
      .getSpecialtiesWithActiveDoctors(patientId, this.state.schedulingOrigin())
      .subscribe({
        next: (docs) => {
          this.state.doctors.set(docs);
          if (this.shouldApplyDoctorPrefill) {
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
   * Preselecciona médico y especialidad (contexto `doctor` o reagendamiento)
   * a partir de `prefillDoctorId` —o `prefillDoctorName` si no hay id— y
   * `prefillSpecialty`, sin restringir el resto de médicos/especialidades
   * ya cargados: el usuario puede cambiar ambos.
   *
   * @param docs - Lista de médicos ya cargada.
   */
  private applyDoctorPrefill(docs: SpecialtyDoctor[]): void {
    if (!this.prefillDoctorId && !this.prefillDoctorName) return;

    const normalize = (s: string) => s.trim().toLocaleLowerCase('es');
    const match = this.prefillDoctorId
      ? docs.find((d) => d.id === this.prefillDoctorId)
      : docs.find(
          (d) => normalize(d.name) === normalize(this.prefillDoctorName)
        );
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
