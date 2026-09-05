import {
  ChangeDetectionStrategy,
  Component,
  Input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideCheckCircle } from '@lucide/angular';
import { ButtonComponent } from '../../../../../design-system/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../../design-system/atoms/select/select.component';
import { formatLongDateEs } from '../../../../../shared/helpers/date-format';
import { calcAge } from '../../../../../shared/helpers/patient-validation';
import { Patient } from '../../../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../../../shared/pipes/formatoPipe';

/** Pantalla completa: paciente encontrado + selección del tipo de atención. */
@Component({
  selector: 'app-unscheduled-patient-attend',
  standalone: true,
  imports: [
    FormsModule,
    LucideCheckCircle,
    FormatoPipe,
    ButtonComponent,
    SelectComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './unscheduledPatientAttend.component.html',
})
export class UnscheduledPatientAttendComponent {
  @Input({ required: true }) patient!: Patient;
  @Input() specialtyOptions: SelectOption[] = [];

  goBack = output<void>();
  advance = output<string>();

  selectedSpecialty = signal('');

  formatLongDateEs = formatLongDateEs;
  calculateAge = calcAge;

  onSpecialtyChange(value: string): void {
    this.selectedSpecialty.set(value);
  }

  onGoBack(): void {
    this.goBack.emit();
  }

  onAdvance(): void {
    if (!this.selectedSpecialty()) return;
    this.advance.emit(this.selectedSpecialty());
  }
}
