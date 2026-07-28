import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideCalendarRange,
  LucideCircleAlert,
  LucideCreditCard,
  LucideDynamicIcon,
  LucideInfo,
  LucidePhone,
  LucideStethoscope,
  type LucideIcon,
} from '@lucide/angular';
import { ButtonComponent } from "../../../../design-system/atoms/button/button.component";
import { InputComponent } from '../../../../design-system/atoms/input/input.component';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

export interface DoctorFormData {
  documentType: string;
  phone: string;
  specialty: string[];
  laborStart: string;
  laborEnd: string;
  interval: number;
  workDays: number[];
  startTime: string;
  endTime: string;
}

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
    LucideCreditCard,
    LucideInfo,
    LucidePhone,
    LucideStethoscope,
    LucideDynamicIcon,
    FormatoPipe,
    InputComponent,
    ButtonComponent,
  ],
})
export class CreateUserDoctorFormComponent {
  @Input() data!: DoctorFormData;
  @Input() specialtyOptions: SpecialtyOption[] = [];
  @Input() documentTypes: string[] = [];
  @Input() loadingSpecialties = false;
  @Input() loadingDocumentTypes = false;
  @Input() timeOptions: string[] = [];
  @Input() maxInterval = 300;
  @Input() daysOfWeek: { value: number; label: string }[] = [];
  @Input() errors: Partial<Record<string, string>> = {};

  @Output() dataChange = new EventEmitter<Partial<DoctorFormData>>();
  @Output() fieldBlurred = new EventEmitter<string>();

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
}
