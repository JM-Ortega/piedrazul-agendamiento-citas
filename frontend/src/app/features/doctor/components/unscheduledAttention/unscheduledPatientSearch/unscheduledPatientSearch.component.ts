import {
  ChangeDetectionStrategy,
  Component,
  Input,
  inject,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideCheckCircle } from '@lucide/angular';
import { SearchSuggestionsComponent } from '../../../../../design-system/organisms/searchSuggestions/searchSuggestions.component';
import { ButtonComponent } from '../../../../../design-system/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../../design-system/atoms/select/select.component';
import { DoctorService } from '../../../../../core/services/doctor.service';
import { formatLongDateEs } from '../../../../../shared/helpers/date-format';
import { calcAge } from '../../../../../shared/helpers/patient-validation';
import { Patient } from '../../../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../../../shared/pipes/formatoPipe';
import { PatientSuggestion } from '../../../../appointment/models/dtos/patient-suggestion.dto';

const MIN_SUGGESTION_CHARS = 3;
const MIN_DOC_LENGTH = 6;
const MAX_DOC_LENGTH = 20;

export interface UnscheduledAttendanceStart {
  patient: Patient;
  specialty: string;
}

/**
 * Búsqueda de un paciente por documento para el flujo de atención sin cita
 * previa. Si se encuentra, muestra su información y el selector de tipo de
 * atención debajo del buscador; emite `found` solo cuando el usuario elige
 * una especialidad y confirma con "Continuar".
 */
@Component({
  selector: 'app-unscheduled-patient-search',
  standalone: true,
  imports: [
    FormsModule,
    SearchSuggestionsComponent,
    LucideCheckCircle,
    FormatoPipe,
    ButtonComponent,
    SelectComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './unscheduledPatientSearch.component.html',
})
export class UnscheduledPatientSearchComponent {
  private doctorService = inject(DoctorService);

  @Input() prefillDocument = '';
  @Input() specialtyOptions: SelectOption[] = [];

  found = output<UnscheduledAttendanceStart>();
  notFound = output<string>();

  readonly minSuggestionChars = MIN_SUGGESTION_CHARS;
  readonly minDocLength = MIN_DOC_LENGTH;
  readonly maxDocLength = MAX_DOC_LENGTH;
  readonly minLengthErrorMessage = `El documento debe tener al menos ${MIN_DOC_LENGTH} caracteres alfanuméricos.`;

  foundPatient = signal<Patient | null>(null);
  selectedSpecialty = signal('');

  formatLongDateEs = formatLongDateEs;
  calculateAge = calcAge;

  suggestionsFn = (query: string) =>
    this.doctorService.getPatientSuggestionsByDocument(query);
  exactSearchFn = (query: string) =>
    this.doctorService.getPatientByDocument(query);
  suggestionToQuery = (s: PatientSuggestion) => s.identification;
  isNotFoundError = (err: { errorCode?: string }) =>
    err.errorCode === 'PATIENT_NOT_FOUND';

  onResultFound(patient: Patient): void {
    this.foundPatient.set(patient);
    this.selectedSpecialty.set('');
  }

  onResultNotFound(identification: string): void {
    this.foundPatient.set(null);
    this.selectedSpecialty.set('');
    this.notFound.emit(identification);
  }

  /** Oculta la tarjeta de resultado apenas el usuario vuelve a escribir. */
  onQueryChanged(): void {
    this.foundPatient.set(null);
    this.selectedSpecialty.set('');
  }

  onSpecialtyChange(value: string): void {
    this.selectedSpecialty.set(value);
  }

  onContinue(): void {
    const patient = this.foundPatient();
    const specialty = this.selectedSpecialty();
    if (!patient || !specialty) return;
    this.found.emit({ patient, specialty });
  }
}
