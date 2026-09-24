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
import { PatientQuickResult } from '../../../shared/models/dtos/patientQuickResult.dto';
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
  ],
  templateUrl: './patientQuickSearch.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PatientQuickSearchComponent {
  private schedulerService = inject(SchedulerService);

  selectedPatient = input<PatientQuickResult | null>(null);

  patientSelected = output<PatientQuickResult>();
  cleared = output<void>();

  results = signal<PatientQuickResult[]>([]);
  loading = signal(false);
  showDropdown = false;

  onSearchChange(term: string): void {
    if (!term) {
      this.results.set([]);
      this.showDropdown = false;
      return;
    }
    this.showDropdown = true;
    this.loading.set(true);
    this.schedulerService.searchPatients(term).subscribe({
      next: (page) => {
        this.results.set(page.content);
        this.loading.set(false);
      },
      error: () => {
        this.results.set([]);
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
