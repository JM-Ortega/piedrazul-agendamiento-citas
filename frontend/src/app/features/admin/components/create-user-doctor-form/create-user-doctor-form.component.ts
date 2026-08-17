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
import { toggleInArray } from '../../../../shared/helpers/array-utils';
import { toIsoDateString } from '../../../../shared/helpers/transform-date-local';
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
    this.dataChange.emit({
      specialty: toggleInArray(this.data.specialty, name),
    });
  }

  isWorkDaySelected(day: number): boolean {
    return this.data.workDays.includes(day);
  }

  toggleWorkDay(day: number): void {
    this.dataChange.emit({ workDays: toggleInArray(this.data.workDays, day) });
  }

  emit(field: keyof DoctorFormData, value: unknown): void {
    this.dataChange.emit({ [field]: value });
  }
  onDateChange(field: 'laborStart' | 'laborEnd', date: Date | null): void {
    this.emit(field, date ? toIsoDateString(date) : '');
    this.fieldBlurred.emit(field);
    this.fieldBlurred.emit(field === 'laborStart' ? 'laborEnd' : 'laborStart');
  }

  onBookingWindowWeeksChange(value: string | number | boolean | null): void {
    this.emit('bookingWindowWeeks', value === null ? null : Number(value));
    this.fieldBlurred.emit('bookingWindowWeeks');
  }

  get bookingWindowWeeksValue(): number | null {
    return this.data.bookingWindowWeeks || null;
  }
  onIntervalChange(value: string | number | boolean | null): void {
    this.emit(
      'interval',
      value === null || value === '' ? null : Number(value)
    );
    this.fieldBlurred.emit('interval');
  }

  get intervalValue(): number | null {
    return this.data.interval ?? null;
  }
}
