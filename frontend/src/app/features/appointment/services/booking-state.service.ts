import { computed, Injectable, signal } from '@angular/core';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { PatientSuggestion } from '../models/dtos/patient-suggestion.dto';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';
import { BookingContext } from '../models/types/bookingContext.type';
import { BookingMode } from '../models/types/bookingMode.type';
import { toIsoDateString } from '../../../shared/helpers/transform-date-local';

/**
 * Servicio de estado compartido para el flujo de agendamiento de citas.
 * Actúa como la única fuente de verdad para todos los componentes
 * hermanos del flujo: patient-search, patient-register, specialty-selector,
 * schedule-selector y confirm.
 */
@Injectable()
export class BookingStateService {
  context = signal<BookingContext>('patient');

  readonly isSchedulerContext = computed(() => this.context() === 'scheduler');

  readonly isDoctorContext = computed(() => this.context() === 'doctor');

  bookingMode = signal<BookingMode>(null);
  step = signal<number>(1);

  readonly patientLookupStep = computed(() =>
    this.isSchedulerContext() ? 1 : null
  );
  readonly specialtyStep = computed(() => (this.isSchedulerContext() ? 2 : 1));
  readonly scheduleStep = computed(() => (this.isSchedulerContext() ? 3 : 2));
  readonly confirmStep = computed(() => (this.isSchedulerContext() ? 4 : 3));

  readonly stepLabels = computed(() => {
    if (this.isSchedulerContext())
      return ['1. Paciente', '2. Especialidad', '3. Horario', '4. Confirmar'];
    return ['1. Especialidad', '2. Horario', '3. Confirmar'];
  });

  readonly modeSelectionLabel = computed(() =>
    this.isSchedulerContext()
      ? '¿Cómo desea agendar la cita?'
      : '¿Cómo desea agendar su cita?'
  );

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

  specialtiesWithDoctor = signal<SpecialtyDoctor[]>([]);
  doctorsBySpecialty = signal<SpecialtyDoctor[]>([]);
  selectedSpecialty = signal<string>('');
  assignedDoctor = signal<SpecialtyDoctor | null>(null);
  selectedDoctorId = signal<string>('');
  selectedDoctorName = signal<string>('');

  noSpecialtyAvailable = signal<boolean>(false);
  errorMessageSpecialty = signal<string>('');
  noDoctorsFound = signal<boolean>(false);
  errorMessageDoctors = signal<string>('');
  globalErrorMessage = signal<string>('');

  readonly uniqueSpecialties = computed(() => [
    ...new Set(
      this.specialtiesWithDoctor()
        .flatMap((s) => s.specialty)
        .filter(
          (specialty): specialty is string =>
            typeof specialty === 'string' && specialty.trim() !== ''
        )
    ),
  ]);

  readonly effectiveDoctor = computed<SpecialtyDoctor | null>(() => {
    if (this.isDoctorContext()) {
      return (
        this.doctorsBySpecialty().find(
          (d) => d.id === this.selectedDoctorId()
        ) ?? null
      );
    }
    return this.bookingMode() === 'specialty'
      ? this.assignedDoctor()
      : (this.doctorsBySpecialty().find(
          (d) => d.id === this.selectedDoctorId()
        ) ?? null);
  });

  readonly effectiveDoctorId = computed(() => this.effectiveDoctor()?.id ?? '');

  //Estado de horario
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

  readonly confirmDoctorName = computed(() => {
    if (this.isDoctorContext()) return this.selectedDoctorName();
    return this.bookingMode() === 'specialty'
      ? (this.assignedDoctor()?.name ?? '')
      : this.selectedDoctorName();
  });

  readonly confirmDate = computed(() => {
    const d = this.selectedDate();
    if (!d) return '';
    const days = [
      'Domingo',
      'Lunes',
      'Martes',
      'Miércoles',
      'Jueves',
      'Viernes',
      'Sábado',
    ];
    const months = [
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
    return `${days[d.getDay()]} ${d.getDate()} de ${months[d.getMonth()]} de ${d.getFullYear()}`;
  });

  readonly canGoToScheduleStep = computed(() => {
    if (this.isDoctorContext()) {
      return !!this.selectedSpecialty() && !!this.selectedDoctorId();
    }
    return this.bookingMode() === 'specialty'
      ? !!this.selectedSpecialty() && !!this.assignedDoctor()
      : !!this.selectedSpecialty() && !!this.selectedDoctorId();
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

  resetSearchState(): void {
    this.searchQuery.set('');
    this.searchSuggestions.set([]);
    this.searchLoading.set(false);
    this.searchError.set('');
    this.foundPatient.set(null);
    this.notFound.set(false);
    this.patientId.set(null);
  }

  resetSpecialtyState(): void {
    this.selectedSpecialty.set('');
    this.assignedDoctor.set(null);
    this.selectedDoctorId.set('');
    this.selectedDoctorName.set('');
    this.doctorsBySpecialty.set([]);
    this.specialtiesWithDoctor.set([]);
    this.noSpecialtyAvailable.set(false);
    this.errorMessageSpecialty.set('');
    this.noDoctorsFound.set(false);
    this.errorMessageDoctors.set('');
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
