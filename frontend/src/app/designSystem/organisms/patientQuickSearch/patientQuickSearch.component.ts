import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import {
  LucideArrowRight,
  LucideFileText,
  LucidePhone,
  LucideUser,
  LucideX,
} from '@lucide/angular';
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
    LucidePhone,
    LucideFileText,
  ],
  templateUrl: './patientQuickSearch.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PatientQuickSearchComponent {
  results = input<PatientQuickResult[]>([]);
  loading = input(false);
  selectedPatient = input<PatientQuickResult | null>(null);

  searchTermChanged = output<string>();
  patientSelected = output<PatientQuickResult>();
  cleared = output<void>();

  showDropdown = false;

  onSearchChange(term: string): void {
    this.showDropdown = term.length > 0;
    this.searchTermChanged.emit(term);
  }

  select(patient: PatientQuickResult): void {
    this.showDropdown = false;
    this.patientSelected.emit(patient);
  }

  clear(): void {
    this.cleared.emit();
  }
}
