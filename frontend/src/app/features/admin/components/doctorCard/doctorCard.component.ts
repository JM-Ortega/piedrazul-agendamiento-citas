import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCheckCircle,
  LucideClock,
  LucideCoffee,
  LucidePencil,
  LucidePower,
  LucidePowerOff,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import { TooltipDirective } from '../../../../designSystem/atoms/tooltip/tooltip.directive';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { getMonthShort } from '../../../../shared/helpers/dateFormat';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { TimeOffDto } from '../../models/dtos/timeOff.dto';

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
    LucideCoffee,
    LucidePencil,
    LucidePower,
    LucidePowerOff,
    ButtonComponent,
    TooltipDirective,
  ],
})
export class DoctorCardComponent {
  readonly DAY_LABELS = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  // ── Inputs ────────────────────────────────────────────────────────────────
  doctor = input.required<Doctor>();
  isEditing = input<boolean>(false);
  isSaved = input<boolean>(false);
  /** Descansos del doctor (para el chip de descanso en curso / próximo). */
  timeOffs = input<TimeOffDto[]>([]);

  // ── Outputs ───────────────────────────────────────────────────────────────
  edit = output<Doctor>();
  toggleModal = output<Doctor>();
  timeOff = output<Doctor>();

  // ── Descansos ─────────────────────────────────────────────────────────────
  private scheduledTimeOffs = computed(() =>
    this.timeOffs()
      .filter((t) => t.status === 'PROGRAMADO')
      .sort((a, b) => a.startDate.localeCompare(b.startDate))
  );

  /** Descanso a destacar: el que está en curso, o si no, el próximo programado. */
  featuredTimeOff = computed<TimeOffDto | null>(
    () =>
      this.timeOffs().find((t) => t.status === 'EN_CURSO') ??
      this.scheduledTimeOffs()[0] ??
      null
  );

  /** Cuántos descansos programados hay además del que se muestra. */
  extraScheduledTimeOffs = computed(() => {
    const featured = this.featuredTimeOff();
    const scheduled = this.scheduledTimeOffs().length;
    return featured?.status === 'PROGRAMADO' ? scheduled - 1 : scheduled;
  });

  // ── Estado ────────────────────────────────────────────────────────────────
  private formatoPipe = new FormatoPipe();

  // ── Eventos ───────────────────────────────────────────────────────────────
  /** Emite el doctor actual para que el padre abra el modo edición. */
  handleEdit(): void {
    this.edit.emit(this.doctor());
  }
  /** Emite el doctor actual para que el padre abra el modal de habilitar/deshabilitar. */
  handleToggle(): void {
    this.toggleModal.emit(this.doctor());
  }
  /** Emite el doctor actual para que el padre abra el panel de descansos. */
  handleTimeOff(): void {
    this.timeOff.emit(this.doctor());
  }

  /** Texto del chip: fecha de fin si está en curso, o el rango si está programado. */
  timeOffLabel(timeOff: TimeOffDto): string {
    const [, sm, sd] = timeOff.startDate.split('-');
    const [, em, ed] = timeOff.endDate.split('-');
    const endText = `${ed} ${getMonthShort(timeOff.endDate)}`;
    if (timeOff.status === 'EN_CURSO') return `En descanso hasta el ${endText}`;
    if (timeOff.startDate === timeOff.endDate) {
      return `Descanso programado: ${endText}`;
    }
    const startText =
      sm === em ? sd : `${sd} ${getMonthShort(timeOff.startDate)}`;
    return `Descanso programado: ${startText} – ${endText}`;
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

  /** True si el doctor no tiene ningún dato de horario configurado. */
  hasNoSchedule(): boolean {
    const schedule = this.getDisplaySchedule();
    return (
      !schedule.startTime &&
      !schedule.endTime &&
      !this.doctor().workdays?.length
    );
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
