import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideCalendarRange,
  LucideCircleAlert,
  LucideClock,
  LucideDynamicIcon,
  LucideInfo,
  LucideStethoscope,
  type LucideIcon,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { InputComponent } from '../../../../design-system/atoms/input/input.component';
import { SelectComponent } from '../../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../../design-system/molecules/datepicker/datepicker.component';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { ToSelectOptionsPipe } from '../../../../shared/pipes/ToSelectOptionsPipe';
import { DoctorFormData } from '../../models/interfaces/DoctorFormData';

export interface SpecialtyOption {
  name: string;
  icon: LucideIcon;
  colorClass: string;
}

@Component({
  selector: 'app-create-user-doctor-form',
  templateUrl: './create-user-doctor-form.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    LucideCalendarRange,
    LucideCircleAlert,
    LucideInfo,
    LucideStethoscope,
    LucideDynamicIcon,
    LucideClock,
    FormatoPipe,
    InputComponent,
    ButtonComponent,
    SelectComponent,
    ToSelectOptionsPipe,
    DatepickerComponent,
  ],
})
export class CreateUserDoctorFormComponent {
  @Input() data!: DoctorFormData;
  @Input() specialtyOptions: SpecialtyOption[] = [];
  @Input() loadingSpecialties = false;
  @Input() timeOptions: string[] = [];
  @Input() maxInterval = 300;
  @Input() daysOfWeek: { value: number; label: string }[] = [];
  @Input() errors: Partial<Record<string, string>> = {};

  @Output() dataChange = new EventEmitter<Partial<DoctorFormData>>();
  @Output() fieldBlurred = new EventEmitter<string>();
  private cdr = inject(ChangeDetectorRef);

  isSpecialtySelected(name: string): boolean {
    return this.data.specialty.includes(name);
  }

  toggleSpecialty(name: string): void {
    const updated = this.data.specialty.includes(name)
      ? this.data.specialty.filter((s) => s !== name)
      : [...this.data.specialty, name];
    this.dataChange.emit({ specialty: updated });
  }

  isWorkDaySelected(day: number): boolean {
    return this.data.workDays.includes(day);
  }

  toggleWorkDay(day: number): void {
    const updated = this.data.workDays.includes(day)
      ? this.data.workDays.filter((d) => d !== day)
      : [...this.data.workDays, day];
    this.dataChange.emit({ workDays: updated });
  }

  emit(field: keyof DoctorFormData, value: unknown): void {
    this.dataChange.emit({ [field]: value });
  }
  onDateChange(field: 'laborStart' | 'laborEnd', date: Date | null): void {
    this.emit(field, date ? this.toIsoDateString(date) : '');
    this.fieldBlurred.emit(field);
    this.fieldBlurred.emit(field === 'laborStart' ? 'laborEnd' : 'laborStart');
  }

  private toIsoDateString(date: Date): string {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }
}
