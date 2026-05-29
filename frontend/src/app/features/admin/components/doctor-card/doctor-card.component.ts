import { Component, input, output } from '@angular/core';
import {
  Calendar,
  CheckCircle,
  Clock,
  LucideAngularModule,
  Pencil,
  Power,
  PowerOff,
} from 'lucide-angular';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
@Component({
  selector: 'app-doctor-card',
  templateUrl: './doctor-card.component.html',
  standalone: true,
  imports: [LucideAngularModule, FormatoPipe],
})
export class DoctorCardComponent {
  readonly Clock = Clock;
  readonly Calendar = Calendar;
  readonly Pencil = Pencil;
  readonly Power = Power;
  readonly PowerOff = PowerOff;
  readonly CheckCircle = CheckCircle;
  readonly DAY_LABELS = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];

  doctor = input.required<Doctor>();
  isEditing = input<boolean>(false);
  isSaved = input<boolean>(false);

  edit = output<Doctor>();
  toggleModal = output<Doctor>();

  getWorkDayLabels(workdays: number[]): string {
    if (!workdays?.length) return '';
    return this.DAY_LABELS.filter((_, i) => workdays.includes(i)).join(', ');
  }

  getDayScheduleKeys(): number[] {
    return Object.keys(this.doctor().daySchedules ?? {}).map(Number);
  }

  hasRealDayOverrides(): boolean {
    const doc = this.doctor();
    return this.getDayScheduleKeys().some((day) => {
      const ds = doc.daySchedules![day];
      return ds.startTime !== doc.startTime || ds.endTime !== doc.endTime;
    });
  }
}
