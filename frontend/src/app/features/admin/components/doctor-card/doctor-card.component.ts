import {
  Component,
  input,
  output,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCheckCircle,
  LucideClock,
  LucidePencil,
  LucidePower,
  LucidePowerOff,
} from '@lucide/angular';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-doctor-card',
  templateUrl: './doctor-card.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [
    LucideCalendar,
    LucideCheckCircle,
    LucideClock,
    LucidePencil,
    LucidePower,
    LucidePowerOff,
    FormatoPipe,
  ],
})
export class DoctorCardComponent {
  readonly DAY_LABELS = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];

  doctor = input.required<Doctor>();
  isEditing = input<boolean>(false);
  isSaved = input<boolean>(false);

  edit = output<Doctor>();
  toggleModal = output<Doctor>();

  hoveredButton = signal(false);
  hoveredStatus = signal(false);

  handleEdit(): void {
    this.edit.emit(this.doctor());
  }

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
  getDisplaySchedule(): { startTime: string; endTime: string } {
    const doc = this.doctor();
    const keys = this.getDayScheduleKeys();
    if (!keys.length) {
      return { startTime: doc.startTime ?? '', endTime: doc.endTime ?? '' };
    }

    const freq = new Map<
      string,
      { count: number; startTime: string; endTime: string }
    >();
    keys.forEach((day) => {
      const ds = doc.daySchedules![day];
      const key = `${ds.startTime}-${ds.endTime}`;
      if (freq.has(key)) {
        freq.get(key)!.count++;
      } else {
        freq.set(key, {
          count: 1,
          startTime: ds.startTime,
          endTime: ds.endTime,
        });
      }
    });

    let best = { count: 0, startTime: '', endTime: '' };
    freq.forEach((val) => {
      if (val.count > best.count) best = val;
    });

    return { startTime: best.startTime, endTime: best.endTime };
  }
}
