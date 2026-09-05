import { computed, Injectable, signal } from '@angular/core';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { PatientSuggestion } from '../models/dtos/patient-suggestion.dto';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';
import { BookingContext } from '../models/types/bookingContext.type';
import { toIsoDateString } from '../../../shared/helpers/transform-date-local';
import { formatLongDateEs } from '../../../shared/helpers/date-format';

/** Especialidad fija con la que se agenda cuando el contexto es `patient`. */
export const PATIENT_DEFAULT_SPECIALTY = 'TERAPIA_NEURAL';

/**
 * Servicio de estado compartido para el flujo de agendamiento de citas.
 * Única fuente de verdad para los componentes hermanos del flujo:
 * patient-search, patient-register, booking-scheduling y confirm.
 *
 * El flujo ya no depende de un "modo" de agendamiento: el comportamiento
 * (qué inputs se muestran, qué petición trae los médicos, si la
 * especialidad es editable) depende únicamente de `context`.
 */
@Injectable()
export class BookingStateService {
  context = signal<BookingContext>('patient');

  readonly isSchedulerContext = computed(() => this.context() === 'scheduler');
  readonly isDoctorContext = computed(() => this.context() === 'doctor');
  readonly isPatientContext = computed(() => this.context() === 'patient');

  step = signal<number>(1);

  readonly patientLookupStep = computed(() =>
    this.isSchedulerContext() ? 1 : null
  );
  readonly schedulingStep = computed(() => (this.isSchedulerContext() ? 2 : 1));
  readonly confirmStep = computed(() => (this.isSchedulerContext() ? 3 : 2));

  readonly stepLabels = computed(() => {
    if (this.isSchedulerContext())
      return ['1. Paciente', '2. Agendamiento', '3. Confirmar'];
    return ['1. Agendamiento', '2. Confirmar'];
  });

  readonly successMessage = computed(() => {
    if (this.isSchedulerContext() || this.isDoctorContext())
      return 'La cita fue registrada exitosamente.';
    return 'Su cita fue registrada exitosamente.';
  });

  patientSnapshot = signal<Partial<Patient> | null>(null);
  isNewPatient = signal<boolean>(false);

  // Agendador: resultado de búsqueda por documento
  foundPatient = signal<Patient | null>(null);
  notFound = signal<boolean>(false);
  patientId = signal<string | null>(null);

  searchQuery = signal<string>('');
  searchSuggestions = signal<PatientSuggestion[]>([]);
  searchLoading = signal<boolean>(false);
  searchError = signal<string>('');

  // Agendador: formulario para registrar paciente nuevo
  patientForm = signal<Omit<Patient, 'id'>>({
    identificationType: '',
    identification: '',
    firstName: '',
    lastName: '',
    phone: '',
    sex: '',
    birthDate: '',
    email: '',
    guardianPhone: '',
  });

  /**
   * Lista de médicos disponibles para el contexto actual.
   * - `patient`: viene de `getDoctors`, `specialty` de cada entrada se ignora.
   * - `doctor` / `scheduler`: viene de `getSpecialtiesWithDoctor`, cada
   *   entrada trae las especialidades propias de ese médico.
   */
  doctors = signal<SpecialtyDoctor[]>([]);
  selectedDoctorId = signal<string>('');
  selectedDoctorName = signal<string>('');
  selectedSpecialty = signal<string>('');

  noDoctorsFound = signal<boolean>(false);
  errorMessageDoctors = signal<string>('');
  globalErrorMessage = signal<string>('');

  /** Especialidades seleccionables para el médico actualmente elegido (doctor/scheduler). */
  readonly specialtyOptionsForSelectedDoctor = computed(() => {
    const doctor = this.doctors().find((d) => d.id === this.selectedDoctorId());
    return doctor?.specialty ?? [];
  });

  readonly selectedDoctor = computed<SpecialtyDoctor | null>(
    () => this.doctors().find((d) => d.id === this.selectedDoctorId()) ?? null
  );

  /** `true` cuando ya hay suficiente información de médico/especialidad para mostrar el calendario. */
  readonly doctorSpecialtyComplete = computed(() => {
    if (this.isPatientContext()) return !!this.selectedDoctorId();
    return !!this.selectedDoctorId() && !!this.selectedSpecialty();
  });

  // Estado de horario
  selectedDate = signal<Date | null>(null);
  selectedTime = signal<string>('');
  availableSlots = signal<string[]>([]);

  isLoading = signal<boolean>(false);
  errorMessage = signal<string>('');
  success = signal<boolean>(false);

  readonly confirmFirstName = computed(() => {
    if (this.isSchedulerContext())
      return this.foundPatient()?.firstName ?? this.patientForm().firstName;
    if (this.isDoctorContext()) return this.foundPatient()?.firstName ?? '';
    return this.patientSnapshot()?.firstName ?? '';
  });

  readonly confirmLastName = computed(() => {
    if (this.isSchedulerContext())
      return this.foundPatient()?.lastName ?? this.patientForm().lastName;
    if (this.isDoctorContext()) return this.foundPatient()?.lastName ?? '';
    return this.patientSnapshot()?.lastName ?? '';
  });

  readonly confirmDocumentType = computed(() => {
    if (this.isSchedulerContext())
      return (
        this.foundPatient()?.identificationType ??
        this.patientForm().identificationType
      );
    if (this.isDoctorContext())
      return this.foundPatient()?.identificationType ?? '';
    return this.patientSnapshot()?.identificationType ?? '';
  });

  readonly confirmDocument = computed(() => {
    if (this.isSchedulerContext())
      return (
        this.foundPatient()?.identification ?? this.patientForm().identification
      );
    if (this.isDoctorContext())
      return this.foundPatient()?.identification ?? '';
    return this.patientSnapshot()?.identification ?? '';
  });

  readonly confirmPhone = computed(() => {
    if (this.isSchedulerContext())
      return this.foundPatient()?.phone ?? this.patientForm().phone;
    if (this.isDoctorContext()) return this.foundPatient()?.phone ?? '';
    return this.patientSnapshot()?.phone ?? '';
  });

  readonly confirmGender = computed(() => {
    if (this.isSchedulerContext())
      return this.foundPatient()?.sex ?? this.patientForm().sex;
    if (this.isDoctorContext()) return this.foundPatient()?.sex ?? '';
    return this.patientSnapshot()?.sex ?? '';
  });

  readonly confirmBirthDate = computed(() => {
    if (this.isSchedulerContext())
      return this.foundPatient()?.birthDate ?? this.patientForm().birthDate;
    if (this.isDoctorContext()) return this.foundPatient()?.birthDate ?? '';
    return this.patientSnapshot()?.birthDate ?? '';
  });

  readonly confirmDate = computed(() => {
    const date = this.selectedDate();
    if (!date) return '';
    return formatLongDateEs(date);
  });

  readonly canGoToConfirmStep = computed(
    () => !!this.selectedDate() && !!this.selectedTime()
  );

  formatLocalDate(date: Date): string {
    return toIsoDateString(date);
  }

  resolvePatientId(): string {
    if (this.isSchedulerContext() || this.isDoctorContext())
      return this.patientId() ?? '';
    return this.patientSnapshot()?.id ?? '';
  }

  /**
   * Inicializa el contexto del flujo. Para `patient` fija de una vez la
   * especialidad, ya que en ese contexto no hay selector de especialidad.
   */
  initContext(context: BookingContext): void {
    this.context.set(context);
    if (context === 'patient') {
      this.selectedSpecialty.set(PATIENT_DEFAULT_SPECIALTY);
    }
  }

  /**
   * Selecciona un médico y limpia en cascada todo lo que dependía de la
   * selección anterior (especialidad —salvo en `patient`—, fecha, hora y slots).
   */
  selectDoctor(doctorId: string): void {
    this.selectedDoctorId.set(doctorId);
    const doctor = this.doctors().find((d) => d.id === doctorId);
    this.selectedDoctorName.set(doctor?.name ?? '');
    this.selectedSpecialty.set(
      this.isPatientContext() ? PATIENT_DEFAULT_SPECIALTY : ''
    );
    this.resetScheduleState();
  }

  /** Selecciona una especialidad (doctor/scheduler) y limpia fecha, hora y slots. */
  selectSpecialty(specialty: string): void {
    this.selectedSpecialty.set(specialty);
    this.resetScheduleState();
  }

  /** Selecciona una fecha y limpia la hora previamente elegida. */
  selectDate(date: Date | null): void {
    this.selectedDate.set(date);
    this.selectedTime.set('');
    this.availableSlots.set([]);
  }

  resetSearchState(): void {
    this.searchQuery.set('');
    this.searchSuggestions.set([]);
    this.searchLoading.set(false);
    this.searchError.set('');
    this.foundPatient.set(null);
    this.notFound.set(false);
    this.patientId.set(null);
  }

  /** Limpia toda la selección de médico/especialidad/horario (usado al volver a buscar paciente). */
  resetDoctorState(): void {
    this.doctors.set([]);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
    if (!this.isPatientContext()) {
      this.selectedSpecialty.set('');
    }
    this.noDoctorsFound.set(false);
    this.errorMessageDoctors.set('');
    this.resetScheduleState();
  }

  resetScheduleState(): void {
    this.selectedDate.set(null);
    this.selectedTime.set('');
    this.availableSlots.set([]);
  }

  resetPatientForm(): void {
    this.patientForm.set({
      identification: '',
      identificationType: '',
      firstName: '',
      lastName: '',
      phone: '',
      sex: '',
      birthDate: '',
      email: '',
      guardianPhone: '',
    });
  }
}
