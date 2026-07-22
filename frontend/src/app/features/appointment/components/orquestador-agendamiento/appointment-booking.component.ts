import { CommonModule } from '@angular/common';
import {
  Component,
  DestroyRef,
  inject,
  Input,
  OnInit,
  output,
  ChangeDetectionStrategy,
} from '@angular/core';
import { forkJoin, timer } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DoctorService } from '../../../../core/services/doctor.service';
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
import { PatientAppointmentService } from '../../../../core/services/patientAppointment.service';
import { mapHttpError } from '../../../../shared/helpers/http-errors';

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
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './appointment-booking.component.html',
})
export class AppointmentBookingComponent implements OnInit {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);
  private destroyRef = inject(DestroyRef);
  private doctorService = inject(DoctorService);
  private patientAppointmentService = inject(PatientAppointmentService);

  @Input() context: BookingContext = 'patient';

  //Datos del paciente autenticado
  @Input() set patientData(value: Partial<Patient> | null) {
    this.state.patientSnapshot.set(value);
  }

  @Input() set isNewPatient(value: boolean) {
    this.state.isNewPatient.set(value);
  }

  @Input() set documentNumber(value: string) {
    if (value) {
      this.pendingDocumentNumber = value;
    }
  }
  private pendingDocumentNumber = '';

  // Outputs
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

  private initDoctorContext(): void {
    this.state.bookingMode.set('specialty-doctor');
    this.state.step.set(this.state.specialtyStep());

    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        this.state.doctorSnapshot.set(doctor);
        const cleanSpecialty = doctor.specialty.replace(/[[\]]/g, '');
        this.state.selectedSpecialty.set(cleanSpecialty);

        const doctors$ = this.citaService.getDoctorsBySpecialty(cleanSpecialty);

        if (this.pendingDocumentNumber) {
          const patient$ = this.citaService.getPatientByDocument(
            this.pendingDocumentNumber
          );
          forkJoin({ doctors: doctors$, patient: patient$ }).subscribe({
            next: ({ doctors, patient }) => {
              this.state.foundPatient.set(patient);
              this.state.patientId.set(patient?.id ?? null);
              this.loadSpecialtiesForMode('specialty-doctor');
              this.applyDoctorsPreselection(doctors, doctor.id, doctor.name);
              this.state.step.set(this.state.specialtyStep());
            },
            error: () => {
              this.loadSpecialtiesForMode('specialty-doctor');
              this.state.step.set(this.state.specialtyStep());
            },
          });
        } else {
          this.loadSpecialtiesForMode('specialty-doctor');
          doctors$.subscribe({
            next: (docs) => {
              this.applyDoctorsPreselection(docs, doctor.id, doctor.name);
              this.state.step.set(this.state.specialtyStep());
            },
            error: () => this.state.step.set(this.state.specialtyStep()),
          });
        }
      },
      error: () => {
        this.loadSpecialtiesForMode('specialty-doctor');
      },
    });
  }

  onModeSelected(mode: BookingMode): void {
    if (!this.state.isSchedulerContext()) {
      this.loadSpecialtiesForMode(mode);
    }
  }

  // Eventos de BookingPatientSearch
  onPatientConfirmed(): void {
    const patientId = this.state.patientId();
    if (patientId) {
      this.patientAppointmentService.hasAppointments(patientId).subscribe({
        next: (isNew) => {
          this.state.isNewPatient.set(isNew);
          this.loadSpecialtiesForMode(this.state.bookingMode());
          this.state.step.set(this.state.specialtyStep());
        },
        error: () => {
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
    this.state.bookingMode.set(null);
    this.state.step.set(1);
  }

  // Eventos de BookingPatientRegister
  onRegisterAdvance(): void {
    this.state.isNewPatient.set(true);
    this.loadSpecialtiesForMode(this.state.bookingMode());
    this.state.step.set(this.state.specialtyStep());
  }

  onRegisterGoBack(): void {
    this.patientSubStep = 'search';
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
    if (this.state.isDoctorContext()) {
      this.restoreDoctorPreselection();
    }
  }

  onConfirmBack(): void {
    this.state.step.set(this.state.scheduleStep());
  }

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

    if (mode === 'specialty') {
      this.loadSpecialtiesWithDoctor();
    } else if (mode === 'specialty-doctor') {
      this.loadSpecialties();
    }
  }

  private loadSpecialtiesWithDoctor(): void {
    const patientId = this.resolvePatientIdForSpecialties() || null;

    this.citaService.getSpecialtiesWithDoctor(patientId).subscribe({
      next: (data) => {
        this.state.specialtiesWithDoctor.set(data);
      },
      error: (err) => {
        this.state.noSpecialtyAvailable.set(true);
        this.state.errorMessageSpecialty.set(
          err.status === 409
            ? 'No hay médicos disponibles para ninguna especialidad. Intente más tarde.'
            : mapHttpError(err, 'Error al obtener las especialidades.')
        );
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

  private traerEspecialidades(patientId: string | null): void {
    this.citaService.getSpecialties(patientId).subscribe({
      next: (specs) => {
        if (!specs || specs.length === 0) {
          this.state.noSpecialtyAvailable.set(true);
          this.state.errorMessageSpecialty.set(
            'No hay especialidades disponibles.'
          );
          return;
        }
        this.state.specialtiesWithDoctor.set(
          specs.map((s) => ({
            specialty: s,
            id: '',
            name: '',
            laborStart: null,
            laborEnd: null,
            workdays: [],
          }))
        );
      },
      error: (err) => {
        this.state.noSpecialtyAvailable.set(true);
        this.state.errorMessageSpecialty.set(
          mapHttpError(err, 'Error al obtener las especialidades.')
        );
      },
    });
  }

  private loadDoctorsBySpecialty(specialty: string): void {
    this.state.noDoctorsFound.set(false);
    this.state.errorMessageDoctors.set('');
    this.state.doctorsBySpecialty.set([]);

    this.citaService.getDoctorsBySpecialty(specialty).subscribe({
      next: (docs) => {
        this.state.doctorsBySpecialty.set(docs);
        this.state.noDoctorsFound.set(docs.length === 0);
        if (docs.length === 0) {
          this.state.errorMessageDoctors.set(
            'No hay médicos disponibles para esta especialidad.'
          );
        }
      },
      error: (err) => {
        this.state.noDoctorsFound.set(true);
        this.state.errorMessageDoctors.set(
          err.status === 404
            ? 'No hay médicos disponibles para esta especialidad.'
            : mapHttpError(err, 'Error al obtener los médicos.')
        );
      },
    });
  }

  private applyDoctorsPreselection(
    docs: SpecialtyDoctor[],
    doctorId: string,
    doctorName: string
  ): void {
    this.state.doctorsBySpecialty.set(docs);
    this.state.noDoctorsFound.set(docs.length === 0);
    if (docs.length === 0) {
      this.state.errorMessageDoctors.set(
        'No hay médicos disponibles para esta especialidad.'
      );
      return;
    }
    const self = docs.find((d) => d.id === doctorId);
    this.state.selectedDoctorId.set(self?.id ?? doctorId);
    this.state.selectedDoctorName.set(self?.name ?? doctorName);
  }

  private restoreDoctorPreselection(): void {
    const doctor = this.state.doctorSnapshot();
    if (!doctor) return;

    const cleanSpecialty = doctor.specialty.replace(/[[\]]/g, '');

    if (
      this.state.selectedSpecialty() === cleanSpecialty &&
      this.state.selectedDoctorId()
    )
      return;

    this.state.selectedSpecialty.set(cleanSpecialty);
    this.citaService.getDoctorsBySpecialty(cleanSpecialty).subscribe({
      next: (docs) =>
        this.applyDoctorsPreselection(docs, doctor.id, doctor.name),
      error: () => {
        this.state.selectedDoctorId.set(doctor.id);
        this.state.selectedDoctorName.set(doctor.name);
      },
    });
  }

  private resolvePatientIdForSpecialties(): string {
    if (this.state.isSchedulerContext()) {
      return this.state.patientId() ?? '';
    }
    return this.state.patientId() ?? this.state.patientSnapshot()?.id ?? '';
  }
}
