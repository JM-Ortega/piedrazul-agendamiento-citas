import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  LucideArrowRight,
  LucideFileText,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { SchedulerService } from '../../../core/services/scheduler.service';
import { PaginatedState } from '../../../shared/helpers/paginatedState';
import { PatientQuickResult } from '../../../shared/models/dtos/patientQuickResult.dto';
import { PaginationComponent } from '../../molecules/pagination/pagination.component';
import { SearchInputComponent } from '../../molecules/searchInput/searchInput.component';

@Component({
  selector: 'app-patient-quick-search',
  standalone: true,
  imports: [
    SearchInputComponent,
    LucideUser,
    LucideX,
    LucideArrowRight,
    LucideFileText,
    PaginationComponent,
  ],
  templateUrl: './patientQuickSearch.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PatientQuickSearchComponent {
  private schedulerService = inject(SchedulerService);

  selectedPatient = input<PatientQuickResult | null>(null);

  patientSelected = output<PatientQuickResult>();
  cleared = output<void>();
  loading = signal(false);
  showDropdown = false;

  private readonly searchState = new PaginatedState<PatientQuickResult>();
  readonly results = this.searchState.content;
  readonly pagination = this.searchState.pagination;
  private currentTerm = '';

  onSearchChange(term: string): void {
    this.currentTerm = term;
    if (!term) {
      this.searchState.clear();
      this.showDropdown = false;
      return;
    }
    this.showDropdown = true;
    this.loadPage(0);
  }

  onPageChange(page: number): void {
    this.loadPage(page);
  }

  private loadPage(page: number): void {
    this.loading.set(true);
    this.schedulerService.searchPatients(this.currentTerm, page, 7).subscribe({
      next: (res) => {
        this.searchState.set(res);
        this.loading.set(false);
      },
      error: () => {
        this.searchState.clear();
        this.loading.set(false);
      },
    });
  }

  select(patient: PatientQuickResult): void {
    this.showDropdown = false;
    this.patientSelected.emit(patient);
  }

  clear(): void {
    this.cleared.emit();
  }
}
