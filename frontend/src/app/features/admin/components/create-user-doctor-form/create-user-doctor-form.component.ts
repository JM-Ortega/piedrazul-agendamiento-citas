import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  Activity,
  Bone,
  Building2,
  Calendar,
  CalendarRange,
  CircleAlert,
  CreditCard,
  Heart,
  Info,
  LucideAngularModule,
  LucideIconData,
  Phone,
  Stethoscope,
  Zap,
} from 'lucide-angular';
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
  icon: LucideIconData;
  colorClass: string;
}

@Component({
  selector: 'app-create-user-doctor-form',
  templateUrl: './create-user-doctor-form.component.html',
  standalone: true,
  imports: [FormsModule, LucideAngularModule, FormatoPipe],
})
export class CreateUserDoctorFormComponent {
  readonly Stethoscope = Stethoscope;
  readonly CreditCard = CreditCard;
  readonly Phone = Phone;
  readonly CalendarRange = CalendarRange;
  readonly Info = Info;
  readonly Calendar = Calendar;
  readonly CircleAlert = CircleAlert;
  readonly Activity = Activity;
  readonly Bone = Bone;
  readonly Heart = Heart;
  readonly Zap = Zap;
  readonly Building2 = Building2;

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
