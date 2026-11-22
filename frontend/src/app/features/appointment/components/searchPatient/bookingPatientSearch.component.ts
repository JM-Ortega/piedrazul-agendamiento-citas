import {
  ChangeDetectionStrategy,
  Component,
  Input,
  inject,
  output,
  OnInit,
} from '@angular/core';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import {
  LucideCalendar,
  LucideCheckCircle,
  LucideClock,
  LucideStethoscope,
} from '@lucide/angular';
import { SearchSuggestionsComponent } from '../../../../designSystem/organisms/searchSuggestions/searchSuggestions.component';
import { PatientAppointmentService } from '../../../../core/services/patient.service';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { PatientSuggestion } from '../../models/dtos/patientSuggestion.dto';
import { BookingStateService } from '../../services/bookingState.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { formatLongDateEs } from '../../../../shared/helpers/dateFormat';
import { calcAge } from '../../../../shared/helpers/patientValidation';

const MIN_SUGGESTION_CHARS = 3;
const MIN_DOC_LENGTH = 6;
const MAX_DOC_LENGTH = 20;
/** Máximo de citas agendadas que se muestran del paciente encontrado. */
const SCHEDULED_APPOINTMENTS_LIMIT = 5;

/**
 * Localizar un paciente existente mediante autocompletado por número de
 * documento y mostrar su información básica junto con sus citas agendadas.
 */
@Component({
  selector: 'app-booking-patient-search',
  standalone: true,
  imports: [
    LucideCalendar,
    LucideCheckCircle,
    LucideClock,
    LucideStethoscope,
    FormatoPipe,
    ButtonComponent,
    SearchSuggestionsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bookingPatientSearch.component.html',
})
export class BookingPatientSearchComponent implements OnInit {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);
  private patientAppointmentService = inject(PatientAppointmentService);
  formatLongDateEs = formatLongDateEs;
  calculateAge = calcAge;

  readonly minSuggestionChars = MIN_SUGGESTION_CHARS;
  readonly minDocLength = MIN_DOC_LENGTH;
  readonly maxDocLength = MAX_DOC_LENGTH;
  readonly minLengthErrorMessage = `El documento debe tener al menos ${MIN_DOC_LENGTH} caracteres alfanuméricos.`;

  ngOnInit(): void {
    window.scrollTo(0, 0);
  }

  @Input() prefillDocument = '';

  patientConfirmed = output<void>();
  patientMissing = output<void>();

  suggestionsFn = (query: string) =>
    this.citaService.getPatientSuggestionsByDocument(query);

  exactSearchFn = (query: string) =>
    this.citaService.getPatientByDocument(query);

  suggestionToQuery = (s: PatientSuggestion) => s.identification;

  isNotFoundError = (err: { errorCode?: string }) =>
    err.errorCode === 'PATIENT_NOT_FOUND';

  onFound(patient: Patient): void {
    this.state.foundPatient.set(patient);
    this.state.patientId.set(patient.id);
    this.state.notFound.set(false);
    this.state.lastSearchedDocument.set(patient.identification);
    this.loadScheduledAppointments(patient.id);
  }

  onNotFound(identification: string): void {
    this.state.foundPatient.set(null);
    this.state.notFound.set(true);
    this.state.patientId.set(null);
    this.state.resetFoundPatientAppointments();
    this.state.resetPatientForm();
    this.state.patientForm.update((f) => ({ ...f, identification }));
    this.state.lastSearchedDocument.set(identification);
    this.patientMissing.emit();
  }

  /** Oculta la tarjeta de resultado apenas el usuario vuelve a escribir en el buscador. */
  onQueryChanged(): void {
    this.state.foundPatient.set(null);
    this.state.patientId.set(null);
    this.state.resetFoundPatientAppointments();
  }

  confirmPatient(): void {
    this.patientConfirmed.emit();
  }

  /**
   * Carga las citas en estado `AGENDADA` del paciente encontrado y las deja
   * en el estado del flujo para mostrarlas en la tarjeta del paciente.
   *
   * @param patientId - Id del paciente encontrado.
   */
  private loadScheduledAppointments(patientId: string): void {
    this.state.resetFoundPatientAppointments();
    this.state.foundPatientAppointmentsLoading.set(true);

    this.patientAppointmentService
      .getScheduledAppointments(patientId, SCHEDULED_APPOINTMENTS_LIMIT)
      .subscribe({
        next: (page) => {
          if (this.state.patientId() !== patientId) return;
          this.state.foundPatientAppointments.set(page.content);
          this.state.foundPatientAppointmentsTotal.set(page.totalElements);
          this.state.foundPatientAppointmentsLoading.set(false);
        },
        error: (err: AppError) => {
          if (this.state.patientId() !== patientId) return;
          this.state.foundPatientAppointmentsLoading.set(false);
          this.state.foundPatientAppointmentsError.set(
            'No se pudieron cargar las citas del paciente: ' + err.message
          );
        },
      });
  }
}
