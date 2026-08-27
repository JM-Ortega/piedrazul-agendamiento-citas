import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  signal,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCheckCircle,
  LucideClock,
  LucidePencil,
  LucidePower,
  LucidePowerOff,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

/**
 * Tarjeta de resumen de un doctor: muestra especialidades, horario y
 * estado (activo/inactivo).
 */
@Component({
  selector: 'app-doctor-card',
  templateUrl: './doctorCard.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideCheckCircle,
    LucideClock,
    LucidePencil,
    LucidePower,
    LucidePowerOff,
    ButtonComponent,
  ],
})
export class DoctorCardComponent {
  readonly DAY_LABELS = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  // ── Inputs ────────────────────────────────────────────────────────────────
  doctor = input.required<Doctor>();
  isEditing = input<boolean>(false);
  isSaved = input<boolean>(false);

  // ── Outputs ───────────────────────────────────────────────────────────────
  edit = output<Doctor>();
  toggleModal = output<Doctor>();

  // ── Estado ────────────────────────────────────────────────────────────────
  hoveredButton = signal(false);
  hoveredStatus = signal(false);
  private formatoPipe = new FormatoPipe();

  // ── Eventos ───────────────────────────────────────────────────────────────
  /** Emite el doctor actual para que el padre abra el modo edición. */
  handleEdit(): void {
    this.edit.emit(this.doctor());
  }

  // ── Horario ───────────────────────────────────────────────────────────────
  /** Convierte índices de día (0-6) a sus etiquetas abreviadas, en orden. */
  getWorkDayLabels(workdays: number[]): string {
    if (!workdays?.length) return '';
    return this.DAY_LABELS.filter((_, i) => workdays.includes(i)).join(', ');
  }

  /** Índices de día que tienen un horario personalizado (daySchedules). */
  getDayScheduleKeys(): number[] {
    return Object.keys(this.doctor().daySchedules ?? {}).map(Number);
  }

  /** True si algún día tiene un horario distinto al horario general del doctor. */
  hasRealDayOverrides(): boolean {
    const doc = this.doctor();
    return this.getDayScheduleKeys().some((day) => {
      const ds = doc.daySchedules![day];
      return ds.startTime !== doc.startTime || ds.endTime !== doc.endTime;
    });
  }

  /**
   * Horario a mostrar en la tarjeta: si no hay horarios por día, el
   * horario general; si los hay, el más frecuente entre los días.
   */
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

  /** Formatea y une la lista de especialidades para mostrar en texto. */
  formattedSpecialties(specialties: string[]): string {
    return specialties.map((s) => this.formatoPipe.transform(s)).join(', ');
  }
}
