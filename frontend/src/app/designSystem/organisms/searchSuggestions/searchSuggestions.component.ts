import {
  ChangeDetectionStrategy,
  Component,
  ContentChild,
  Input,
  TemplateRef,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideSearch } from '@lucide/angular';
import {
  debounceTime,
  distinctUntilChanged,
  of,
  Subject,
  switchMap,
} from 'rxjs';
import { catchError, filter, tap } from 'rxjs/operators';
import { Observable } from 'rxjs';
import { ButtonComponent } from '../../atoms/button/button.component';
import { AppError } from '../../../shared/models/interfaces/apiError.model';

/**
 * Búsqueda genérica con autocompletado. Recibe las funciones de
 * búsqueda y deja que el consumidor decida cómo se ve cada sugerencia
 * (vía `<ng-template #suggestionItem let-item>`) y cómo maneja
 * el resultado (vía los outputs `resultFound` / `resultNotFound`).
 */
@Component({
  selector: 'app-search-suggestions',
  standalone: true,
  imports: [CommonModule, FormsModule, LucideSearch, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './searchSuggestions.component.html',
})
export class SearchSuggestionsComponent<TSuggestion, TResult> {
  @Input() label = '';
  @Input() placeholder = '';
  @Input() searchButtonLabel = 'Buscar';
  @Input() inputMode = 'text';

  @Input() minSuggestionChars = 3;
  @Input() minSearchLength = 3;
  @Input() maxLength = 20;

  @Input() sanitizePattern = /[^a-zA-Z0-9]/g;
  @Input() sanitizeWarningMessage =
    'Solo se permiten letras y números, sin caracteres especiales';
  @Input() minLengthErrorMessage = '';

  @Input() suggestionsFn!: (query: string) => Observable<TSuggestion[]>;
  @Input() exactSearchFn!: (query: string) => Observable<TResult | null>;
  @Input() suggestionToQuery!: (suggestion: TSuggestion) => string;
  @Input() isNotFoundError: (err: AppError) => boolean = (err) =>
    !!err.errorCode?.includes('NOT_FOUND');

  /** Si llega un valor, precarga el query y dispara la búsqueda exacta. */
  @Input() set prefillQuery(value: string) {
    if (!value) return;
    this.searchQuery.set(value);
    this.onSearchExact();
  }

  @ContentChild('suggestionItem')
  suggestionItemTemplate?: TemplateRef<{ $implicit: TSuggestion }>;

  resultFound = output<TResult>();
  resultNotFound = output<string>();
  queryChanged = output<string>();

  searchQuery = signal('');
  searchLoading = signal(false);
  searchError = signal('');
  globalErrorMessage = signal('');
  searchSuggestions = signal<TSuggestion[]>([]);
  showSuggestions = signal(false);
  inputWarning = signal('');

  private warnTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly searchInput$ = new Subject<string>();

  constructor() {
    this.searchInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        filter((query) => query.trim().length >= this.minSuggestionChars),
        tap(() => {
          this.searchLoading.set(true);
          this.searchError.set('');
          this.globalErrorMessage.set('');
        }),
        switchMap((query) =>
          this.suggestionsFn(query.trim()).pipe(
            catchError((err: AppError) => {
              this.searchLoading.set(false);
              if (this.isNotFoundError(err)) {
                this.searchError.set(err.message);
              } else {
                this.globalErrorMessage.set(err.message);
              }
              return of([] as TSuggestion[]);
            })
          )
        ),
        takeUntilDestroyed()
      )
      .subscribe((suggestions) => {
        this.searchLoading.set(false);
        this.searchSuggestions.set(suggestions);
        this.showSuggestions.set(suggestions.length > 0);
      });
  }

  handleInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const raw = el.value;
    let clean = raw.replace(this.sanitizePattern, '');
    if (clean !== raw) {
      el.value = clean;
      this.flashWarning(this.sanitizeWarningMessage);
    }
    if (clean.length > this.maxLength) {
      clean = clean.slice(0, this.maxLength);
      el.value = clean;
      this.flashWarning(`Solo se permiten máximo ${this.maxLength} caracteres`);
    }
    this.searchQuery.set(clean);
    this.searchError.set('');
    this.queryChanged.emit(clean);
    if (clean.trim().length < this.minSuggestionChars) {
      this.searchSuggestions.set([]);
      this.showSuggestions.set(false);
    }
    this.searchInput$.next(clean);
  }

  onSearchExact(): void {
    const query = this.searchQuery().trim();
    if (query.length < this.minSearchLength) {
      this.searchError.set(
        this.minLengthErrorMessage ||
          `Debe tener al menos ${this.minSearchLength} caracteres.`
      );
      return;
    }
    this.showSuggestions.set(false);
    this.searchSuggestions.set([]);
    this.runExactSearch(query);
  }

  selectSuggestion(suggestion: TSuggestion): void {
    this.showSuggestions.set(false);
    this.searchSuggestions.set([]);
    const query = this.suggestionToQuery(suggestion);
    this.searchQuery.set(query);
    this.runExactSearch(query);
  }

  closeSuggestions(): void {
    setTimeout(() => this.showSuggestions.set(false), 150);
  }

  openSuggestionsIfAny(): void {
    this.showSuggestions.set(this.searchSuggestions().length > 0);
  }

  private runExactSearch(query: string): void {
    this.searchLoading.set(true);
    this.searchError.set('');
    this.globalErrorMessage.set('');
    this.exactSearchFn(query).subscribe({
      next: (result) => {
        this.searchLoading.set(false);
        if (result) {
          this.resultFound.emit(result);
        } else {
          this.resultNotFound.emit(query);
        }
      },
      error: (err: AppError) => {
        this.searchLoading.set(false);
        if (this.isNotFoundError(err)) {
          this.resultNotFound.emit(query);
        } else {
          this.globalErrorMessage.set(err.message);
        }
      },
    });
  }

  private flashWarning(text: string): void {
    this.inputWarning.set(text);
    if (this.warnTimer) clearTimeout(this.warnTimer);
    this.warnTimer = setTimeout(() => this.inputWarning.set(''), 3000);
  }
}
