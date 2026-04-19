import { Injectable, signal, computed } from '@angular/core';
import { Patient } from '../../models/interfaces/patient.model';
import { SpecialtyDoctor } from '../../models/dtos/specialty-doctor.dto';
import { BookingMode } from '../../models/types/bookingMode.type';
import { BookingContext } from '../../models/types/bookingContext.type';
import { PatientSuggestion } from '../../models/dtos/patient-suggestion.dto';
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
 
  bookingMode = signal<BookingMode>(null);
  step        = signal<number>(1);
 
  readonly patientLookupStep = computed(() => this.isSchedulerContext() ? 1 : null);
  readonly specialtyStep     = computed(() => this.isSchedulerContext() ? 2 : 1);
  readonly scheduleStep      = computed(() => this.isSchedulerContext() ? 3 : 2);
  readonly confirmStep       = computed(() => this.isSchedulerContext() ? 4 : 3);
 
  readonly stepLabels = computed(() =>
    this.isSchedulerContext()
      ? ['1. Paciente', '2. Especialidad', '3. Horario', '4. Confirmar']
      : ['1. Especialidad', '2. Horario', '3. Confirmar']
  );
 
  readonly modeSelectionLabel = computed(() =>
    this.isSchedulerContext()
      ? '¿Cómo desea agendar la cita?'
      : '¿Cómo desea agendar su cita?'
  );
 
  readonly successMessage = computed(() =>
    this.isSchedulerContext()
      ? 'La cita fue registrada exitosamente.'
      : 'Su cita fue registrada exitosamente.'
  );
 
  // Datos del paciente autenticado.
  patientSnapshot = signal<Partial<Patient> | null>(null);
 
  // Agendador: resultado de búsqueda por documento
  foundPatient = signal<Patient | null>(null);
  notFound     = signal<boolean>(false);
  patientId    = signal<string | null>(null);
 
  searchQuery = signal<string>('');
  searchSuggestions = signal<PatientSuggestion[]>([]);
  searchLoading = signal<boolean>(false);
  searchError = signal<string>('');
  
  // Agendador: formulario para registrar paciente nuevo
  patientForm = signal<Omit<Patient, 'id'>>({
    documentType:   '',
    documentNumber: '',
    firstName:      '',
    lastName:       '',
    phone:          '',
    gender:         '',
    birthDate:      '',
    email:          '',
    guardianPhone:  '',
  });
 
  //Estado de especialidad y médico
  specialtiesWithDoctor = signal<SpecialtyDoctor[]>([]);
  doctorsBySpecialty    = signal<SpecialtyDoctor[]>([]);
  selectedSpecialty     = signal<string>('');
  assignedDoctor        = signal<SpecialtyDoctor | null>(null);
  selectedDoctorId      = signal<string>('');
  selectedDoctorName    = signal<string>('');

  noSpecialtyAvailable   = signal<boolean>(false);
  errorMessageSpecialty  = signal<string>('');
  noDoctorsFound         = signal<boolean>(false);
  errorMessageDoctors    = signal<string>('');  
 
  readonly uniqueSpecialties = computed(() =>
    [...new Set(this.specialtiesWithDoctor().map(s => s.specialty))]
  );
 
  readonly effectiveDoctor = computed<SpecialtyDoctor | null>(() =>
    this.bookingMode() === 'specialty'
      ? this.assignedDoctor()
      : (this.doctorsBySpecialty().find(d => d.id === this.selectedDoctorId()) ?? null)
  );
 
  readonly effectiveDoctorId = computed(() => this.effectiveDoctor()?.id ?? '');
 
  //Estado de horario
  selectedDate   = signal<Date | null>(null);
  selectedTime   = signal<string>('');
  availableSlots = signal<string[]>([]);
 
  isLoading    = signal<boolean>(false);
  errorMessage = signal<string>('');
  success      = signal<boolean>(false);
  
  readonly confirmFirstName = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.firstName ?? this.patientForm().firstName)
      : (this.patientSnapshot()?.firstName ?? '')
  );
 
  readonly confirmLastName = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.lastName ?? this.patientForm().lastName)
      : (this.patientSnapshot()?.lastName ?? '')
  );
 
  readonly confirmDocumentType = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.documentType ?? this.patientForm().documentType)
      : (this.patientSnapshot()?.documentType ?? '')
  );
 
  readonly confirmDocument = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.documentNumber ?? this.patientForm().documentNumber)
      : (this.patientSnapshot()?.documentNumber ?? '')
  );
 
  readonly confirmPhone = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.phone ?? this.patientForm().phone)
      : (this.patientSnapshot()?.phone ?? '')
  );
 
  readonly confirmGender = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.gender ?? this.patientForm().gender)
      : (this.patientSnapshot()?.gender ?? '')
  );
 
  readonly confirmBirthDate = computed(() =>
    this.isSchedulerContext()
      ? (this.foundPatient()?.birthDate ?? this.patientForm().birthDate)
      : (this.patientSnapshot()?.birthDate ?? '')
  );
 
  readonly confirmDoctorName = computed(() =>
    this.bookingMode() === 'specialty'
      ? (this.assignedDoctor()?.name ?? '')
      : this.selectedDoctorName()
  );
 
  readonly confirmDate = computed(() => {
    const d = this.selectedDate();
    if (!d) return '';
    const days   = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
    const months = ['Enero','Febrero','Marzo','Abril','Mayo','Junio',
                    'Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'];
    return `${days[d.getDay()]} ${d.getDate()} de ${months[d.getMonth()]} de ${d.getFullYear()}`;
  });
  
  readonly canGoToScheduleStep = computed(() =>
    this.bookingMode() === 'specialty'
      ? !!this.selectedSpecialty() && !!this.assignedDoctor()
      : !!this.selectedSpecialty() && !!this.selectedDoctorId()
  );
 
  readonly canGoToConfirmStep = computed(() =>
    !!this.selectedDate() && !!this.selectedTime()
  );
  
  formatLocalDate(date: Date): string {
    return date.getFullYear() + '-' +
      String(date.getMonth() + 1).padStart(2, '0') + '-' +
      String(date.getDate()).padStart(2, '0');
  }
 
  resolvePatientId(): string {
    return this.isSchedulerContext()
      ? (this.patientId() ?? '')
      : (this.patientSnapshot()?.id ?? '');
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
      documentNumber: '',
      documentType:   '',
      firstName:      '',
      lastName:       '',
      phone:          '',
      gender:         '',
      birthDate:      '',
      email:          '',
      guardianPhone:  '',
    });
  }
}