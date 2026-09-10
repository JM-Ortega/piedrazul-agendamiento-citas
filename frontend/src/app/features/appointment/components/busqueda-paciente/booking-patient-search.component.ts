import {
  ChangeDetectionStrategy,
  Component,
  Input,
  inject,
  output,
  OnInit,
} from '@angular/core';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { LucideCheckCircle } from '@lucide/angular';
import { SearchSuggestionsComponent } from '../../../../design-system/organisms/searchSuggestions/searchSuggestions.component';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { PatientSuggestion } from '../../models/dtos/patientSuggestion.dto';
import { BookingStateService } from '../../services/bookingState.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { calcAge } from '../../../../shared/helpers/patient-validation';

const MIN_SUGGESTION_CHARS = 3;
const MIN_DOC_LENGTH = 6;
const MAX_DOC_LENGTH = 20;

/** Localizar un paciente existente mediante autocompletado por número de documento. */
@Component({
  selector: 'app-booking-patient-search',
  standalone: true,
  imports: [
    LucideCheckCircle,
    FormatoPipe,
    ButtonComponent,
    SearchSuggestionsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-patient-search.component.html',
})
export class BookingPatientSearchComponent implements OnInit {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);
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
  }

  onNotFound(identification: string): void {
    this.state.foundPatient.set(null);
    this.state.notFound.set(true);
    this.state.patientId.set(null);
    this.state.resetPatientForm();
    this.state.patientForm.update((f) => ({ ...f, identification }));
    this.state.lastSearchedDocument.set(identification);
    this.patientMissing.emit();
  }

  /** Oculta la tarjeta de resultado apenas el usuario vuelve a escribir en el buscador. */
  onQueryChanged(): void {
    this.state.foundPatient.set(null);
    this.state.patientId.set(null);
  }

  confirmPatient(): void {
    this.patientConfirmed.emit();
  }
}
