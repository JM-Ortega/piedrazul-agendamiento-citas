import {
  Component,
  inject,
  output,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideCheckCircle, LucideSearch } from '@lucide/angular';
import {
  debounceTime,
  distinctUntilChanged,
  of,
  Subject,
  switchMap,
} from 'rxjs';
import { filter, tap, catchError } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { PatientSuggestion } from '../../models/dtos/patient-suggestion.dto';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

const MIN_CHARS = 3;
const MAX_DOC_LENGTH = 12;
const MIN_DOC_LENGTH = 6;

/**
 * Localizar un paciente existente mediante
 * autocompletado por número de documento.
 */
@Component({
  selector: 'app-booking-patient-search',
  standalone: true,
  imports: [FormsModule, LucideCheckCircle, LucideSearch, FormatoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-patient-search.component.html',
})
export class BookingPatientSearchComponent {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  patientConfirmed = output<void>();
  patientMissing = output<void>();
  changeMode = output<void>();
  showSuggestions = signal(false);

  docInputWarning = signal('');
  private docWarnTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly searchInput$ = new Subject<string>();

  constructor() {
    this.searchInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        // si no cumple los caracteres mínimos, no pasa al switchMap
        filter((query) => query.trim().length >= MIN_CHARS),
        // Activar loader antes de lanzar la petición
        tap(() => {
          this.state.searchLoading.set(true);
          this.state.searchError.set('');
        }),
        switchMap((query) =>
          this.citaService.getPatientSuggestionsByDocument(query.trim()).pipe(
            catchError(() => {
              this.state.searchError.set('Error al cargar sugerencias');
              return of([] as PatientSuggestion[]);
            })
          )
        ),
        takeUntilDestroyed()
      )
      .subscribe({
        next: (suggestions) => {
          this.state.searchLoading.set(false);
          this.state.searchSuggestions.set(suggestions);
          this.showSuggestions.set(suggestions.length > 0);
        },
      });
  }

  handleDocInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const raw = el.value;
    // 1. Limpieza de caracteres especiales
    let clean = raw.replace(/[^a-zA-Z0-9]/g, '');
    if (clean !== raw) {
      el.value = clean;
      this.flashWarning(
        'Solo se permiten letras y números, sin caracteres especiales'
      );
    }
    // 2. Control de longitud máxima
    if (clean.length > MAX_DOC_LENGTH) {
      clean = clean.slice(0, MAX_DOC_LENGTH);
      el.value = clean;
      this.flashWarning(`Solo se permiten máximo ${MAX_DOC_LENGTH} caracteres`);
    }
    // 3. Actualización de Estados y Signals
    this.state.searchQuery.set(clean);
    this.state.searchError.set('');
    this.clearResult();
    // 4. Lógica de sugerencias
    if (clean.trim().length < MIN_CHARS) {
      this.state.searchSuggestions.set([]);
      this.showSuggestions.set(false);
    }
    this.searchInput$.next(clean);
  }

  private flashWarning(text: string): void {
    this.docInputWarning.set(text);
    if (this.docWarnTimer) clearTimeout(this.docWarnTimer);
    this.docWarnTimer = setTimeout(() => this.docInputWarning.set(''), 3000);
  }

  onSearchExact(): void {
    const query = this.state.searchQuery().trim();
    if (query.length < MIN_DOC_LENGTH) {
      this.state.searchError.set(
        `El documento debe tener al menos ${MIN_DOC_LENGTH} caracteres alfanuméricos.`
      );
      return;
    }
    this.showSuggestions.set(false);
    this.state.searchSuggestions.set([]);
    this.loadPatientByDocument(query);
  }

  selectSuggestion(suggestion: PatientSuggestion): void {
    this.showSuggestions.set(false);
    this.state.searchSuggestions.set([]);
    this.state.searchQuery.set(suggestion.documentNumber);
    this.loadPatientByDocument(suggestion.documentNumber);
  }

  closeSuggestions(): void {
    setTimeout(() => this.showSuggestions.set(false), 150);
  }

  confirmPatient(): void {
    this.patientConfirmed.emit();
  }

  onChangeMode(): void {
    this.state.resetSearchState();
    this.changeMode.emit();
  }

  private loadPatientByDocument(documentNumber: string): void {
    this.state.searchLoading.set(true);
    this.state.searchError.set('');
    this.citaService.getPatientByDocument(documentNumber).subscribe({
      next: (patient: Patient | null) => {
        this.state.searchLoading.set(false);
        if (patient) {
          this.state.foundPatient.set(patient);
          this.state.patientId.set(patient.id);
          this.state.notFound.set(false);
        } else {
          this.handleNotFound(documentNumber);
        }
      },
      error: (err) => {
        this.state.searchLoading.set(false);
        if (err.status === 404) {
          this.handleNotFound(documentNumber);
        } else if (err.status === 0) {
          this.state.searchError.set(
            'No se pudo conectar con el servidor. Intente más tarde.'
          );
        } else {
          this.state.searchError.set('Error al buscar el paciente.');
        }
      },
    });
  }

  private handleNotFound(documentNumber: string): void {
    this.state.foundPatient.set(null);
    this.state.notFound.set(true);
    this.state.patientId.set(null);
    this.state.patientForm.update((f) => ({ ...f, documentNumber }));
    this.patientMissing.emit();
  }

  private clearResult(): void {
    this.state.foundPatient.set(null);
    this.state.notFound.set(false);
    this.state.patientId.set(null);
    this.state.resetPatientForm();
  }
}
