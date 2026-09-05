import {
  ChangeDetectionStrategy,
  Component,
  Input,
  inject,
  output,
} from '@angular/core';
import { SearchSuggestionsComponent } from '../../../../../design-system/organisms/searchSuggestions/searchSuggestions.component';
import { DoctorService } from '../../../../../core/services/doctor.service';
import { Patient } from '../../../../../shared/models/interfaces/patient.model';
import { PatientSuggestion } from '../../../../appointment/models/dtos/patient-suggestion.dto';

const MIN_SUGGESTION_CHARS = 3;
const MIN_DOC_LENGTH = 6;
const MAX_DOC_LENGTH = 20;

/** Búsqueda de un paciente por documento para el flujo de atención sin cita previa. */
@Component({
  selector: 'app-unscheduled-patient-search',
  standalone: true,
  imports: [SearchSuggestionsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './unscheduledPatientSearch.component.html',
})
export class UnscheduledPatientSearchComponent {
  private doctorService = inject(DoctorService);

  @Input() prefillDocument = '';

  found = output<Patient>();
  notFound = output<string>();

  readonly minSuggestionChars = MIN_SUGGESTION_CHARS;
  readonly minDocLength = MIN_DOC_LENGTH;
  readonly maxDocLength = MAX_DOC_LENGTH;
  readonly minLengthErrorMessage = `El documento debe tener al menos ${MIN_DOC_LENGTH} caracteres alfanuméricos.`;

  suggestionsFn = (query: string) =>
    this.doctorService.getPatientSuggestionsByDocument(query);
  exactSearchFn = (query: string) =>
    this.doctorService.getPatientByDocument(query);
  suggestionToQuery = (s: PatientSuggestion) => s.identification;
  isNotFoundError = (err: { errorCode?: string }) =>
    err.errorCode === 'PATIENT_NOT_FOUND';

  onFound(patient: Patient): void {
    this.found.emit(patient);
  }

  onNotFound(identification: string): void {
    this.notFound.emit(identification);
  }
}
