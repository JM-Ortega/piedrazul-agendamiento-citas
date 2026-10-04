import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  HostListener,
  inject,
  input,
  OnDestroy,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideCalendarOff,
  LucideCalendarRange,
  LucideChevronDown,
  LucideClock,
  LucideCoffee,
  LucideDynamicIcon,
  LucideInfo,
  LucidePlus,
  LucideTrash2,
  LucideTriangleAlert,
  LucideX,
} from '@lucide/angular';
import { finalize } from 'rxjs';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import { InputComponent } from '../../../../designSystem/atoms/input/input.component';
import { TooltipDirective } from '../../../../designSystem/atoms/tooltip/tooltip.directive';
import { DatepickerComponent } from '../../../../designSystem/molecules/datepicker/datepicker.component';
import { ConfirmModalComponent } from '../../../../designSystem/organisms/confirmModal/confirmModal.component';
import {
  formatDateRangeEs,
  formatDayMonthEs,
  formatShortDateEs,
  formatWeekdayRangeEs,
  getMonthShort,
} from '../../../../shared/helpers/dateFormat';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../../shared/helpers/transformDateLocal';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { TimeOffDto } from '../../models/dtos/timeOff.dto';
import { AdminService } from '../../service/admin.service';

const DAY_MS = 86_400_000;
/** Duración de la animación de salida del panel (debe coincidir con el CSS). */
const CLOSE_ANIMATION_MS = 220;

type RemoveMode = 'delete' | 'trim';

/** Descanso con los datos ya calculados que necesita la vista. */
interface TimeOffItem extends TimeOffDto {
  label: string;
  month: string;
  day: string;
  days: number;
  isOngoing: boolean;
  removeMode: RemoveMode;
  takenDays: number;
  remainingDays: number;
  progress: number;
}

interface PendingRemoval {
  item: TimeOffItem;
  mode: RemoveMode;
}

/**
 * Panel lateral para consultar, programar y eliminar/recortar los
 * descansos de un doctor. Las acciones se aplican de inmediato contra el
 * backend (no dependen del botón Guardar del formulario de horario).
 */
@Component({
  selector: 'app-doctor-time-off-drawer',
  templateUrl: './doctorTimeOffDrawer.component.html',
  styleUrl: './doctorTimeOffDrawer.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    ButtonComponent,
    InputComponent,
    DatepickerComponent,
    ConfirmModalComponent,
    TooltipDirective,
    LucideDynamicIcon,
    LucideCoffee,
    LucideX,
    LucideInfo,
    LucidePlus,
    LucideTrash2,
    LucideChevronDown,
    LucideCalendarRange,
    LucideCalendarOff,
    LucideTriangleAlert,
  ],
})
export class DoctorTimeOffDrawerComponent implements OnInit, OnDestroy {
  private adminService = inject(AdminService);
  private document = inject(DOCUMENT);
  private formatoPipe = new FormatoPipe();
  private previousBodyOverflow = '';
  private closeTimer: ReturnType<typeof setTimeout> | null = null;

  readonly REASON_MAX_LENGTH = 150;
  readonly Clock = LucideClock;
  readonly Trash = LucideTrash2;

  // ── Inputs / Outputs ──────────────────────────────────────────────────────
  doctor = input.required<Doctor>();
  /** Emite la lista actualizada tras crear/eliminar, para refrescar la tarjeta. */
  timeOffsChange = output<TimeOffDto[]>();
  /** Mensaje de éxito para mostrar en el toast del padre. */
  notify = output<string>();
  /** Se emite cuando terminó la animación de cierre. */
  closed = output<void>();

  private panel = viewChild<ElementRef<HTMLElement>>('panel');

  // ── Estado ────────────────────────────────────────────────────────────────
  readonly today = startOfDay(new Date());
  timeOffs = signal<TimeOffDto[]>([]);
  loadingList = signal(true);
  listError = signal('');
  closing = signal(false);
  historyOpen = signal(false);

  startDate = signal<Date | null>(null);
  endDate = signal<Date | null>(null);
  reason = signal('');
  submitted = signal(false);
  creating = signal(false);
  serverError = signal('');

  pendingRemoval = signal<PendingRemoval | null>(null);
  processingId = signal<string | null>(null);

  // ── Reglas de fechas (mismas que valida el backend) ──────────────────────
  /** Último día de la ventana de agendamiento: hoy + N semanas. */
  windowEnd = computed(() =>
    addDays(this.today, (this.doctor().bookingWindowWeeks ?? 0) * 7)
  );
  /** El descanso debe empezar después del último día de la ventana. */
  minStart = computed(() => addDays(this.windowEnd(), 1));
  /** No puede pasar de la fecha de fin de vinculación. */
  maxEnd = computed(() => {
    const laborEnd = this.doctor().laborEnd;
    return laborEnd ? parseLocalDateString(laborEnd) : null;
  });
  hasRoom = computed(() => {
    const max = this.maxEnd();
    return !!max && this.minStart() <= max;
  });
  isActive = computed(() => this.doctor().status !== false);

  minStartLabel = computed(() => formatShortDateEs(this.minStart()));
  maxEndLabel = computed(() => {
    const max = this.maxEnd();
    return max ? formatShortDateEs(max) : '—';
  });
  windowEndLabel = computed(() => formatShortDateEs(this.windowEnd()));
  specialties = computed(() =>
    (this.doctor().specialty ?? [])
      .map((s) => this.formatoPipe.transform(s))
      .join(', ')
  );

  // ── Listas ────────────────────────────────────────────────────────────────
  private items = computed(() => this.timeOffs().map((t) => this.toItem(t)));
  activeItems = computed(() =>
    this.items().filter((t) => t.status !== 'FINALIZADO')
  );
  historyItems = computed(() =>
    this.items().filter((t) => t.status === 'FINALIZADO')
  );

  // ── Validación del formulario ─────────────────────────────────────────────
  startError = computed(() => {
    const start = this.startDate();
    if (!start) {
      return this.submitted() ? 'Seleccione la fecha de inicio.' : '';
    }
    if (start < this.minStart()) {
      return `Debe ser desde el ${this.minStartLabel()}.`;
    }
    const max = this.maxEnd();
    if (max && start > max) return `Debe ser hasta el ${this.maxEndLabel()}.`;
    return '';
  });

  endError = computed(() => {
    const end = this.endDate();
    const start = this.startDate();
    if (!end) return this.submitted() ? 'Seleccione la fecha de fin.' : '';
    if (start && end < start) return 'No puede ser anterior al inicio.';
    const max = this.maxEnd();
    if (max && end > max) {
      return `No puede pasar del ${this.maxEndLabel()}.`;
    }
    return '';
  });

  reasonError = computed(() =>
    this.submitted() && !this.reason().trim() ? 'El motivo es obligatorio.' : ''
  );

  /** Cruce con otro descanso del doctor (incluye los históricos). */
  overlapError = computed(() => {
    const start = this.startDate();
    const end = this.endDate();
    if (!start || !end || end < start) return '';
    const hit = this.timeOffs().find(
      (t) =>
        parseLocalDateString(t.startDate) <= end &&
        parseLocalDateString(t.endDate) >= start
    );
    return hit
      ? `Se cruza con el descanso del ${formatDateRangeEs(hit.startDate, hit.endDate)}.`
      : '';
  });

  /** Error general sobre el rango: cruce local o error devuelto por el backend. */
  formAlert = computed(() => this.overlapError() || this.serverError());

  /** Resumen del rango elegido: total de días y días de atención. */
  rangeSummary = computed(() => {
    const start = this.startDate();
    const end = this.endDate();
    if (!start || !end || end < start) return null;
    const workdays = this.doctor().workdays ?? [];
    const days = diffDays(start, end) + 1;
    let attention = 0;
    for (let i = 0; i < days; i++) {
      if (workdays.includes(addDays(start, i).getDay())) attention++;
    }
    return { days, attention, label: formatWeekdayRangeEs(start, end) };
  });

  pendingIcon = computed(() =>
    this.pendingRemoval()?.mode === 'trim' ? this.Clock : this.Trash
  );
  yesterdayLabel = computed(() => formatDayMonthEs(addDays(this.today, -1)));

  constructor() {
    afterNextRender(() => this.panel()?.nativeElement.focus());
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    const body = this.document.body;
    this.previousBodyOverflow = body.style.overflow;
    body.style.overflow = 'hidden';
    this.loadTimeOffs();
  }

  ngOnDestroy(): void {
    this.document.body.style.overflow = this.previousBodyOverflow;
    if (this.closeTimer) clearTimeout(this.closeTimer);
  }

  @HostListener('document:keydown.escape', ['$event'])
  onEscape(event: Event): void {
    // Si el calendario del datepicker está abierto, Escape solo lo cierra a él.
    if (event.defaultPrevented || this.pendingRemoval()) return;
    this.close();
  }

  // ── Datos ─────────────────────────────────────────────────────────────────
  private loadTimeOffs(emitChange = false): void {
    this.loadingList.set(true);
    this.adminService.getTimeOffs(this.doctor().id).subscribe({
      next: (list) => {
        this.timeOffs.set(list);
        this.listError.set('');
        this.loadingList.set(false);
        if (emitChange) this.timeOffsChange.emit(list);
      },
      error: (err: AppError) => {
        this.listError.set(err.message);
        this.loadingList.set(false);
      },
    });
  }

  // ── Formulario ────────────────────────────────────────────────────────────
  onStartChange(date: Date | null): void {
    this.startDate.set(date);
    this.serverError.set('');
  }

  onEndChange(date: Date | null): void {
    this.endDate.set(date);
    this.serverError.set('');
  }

  onReasonChange(value: string | number | boolean | null): void {
    this.reason.set(typeof value === 'string' ? value : '');
    this.serverError.set('');
  }

  onSubmit(): void {
    this.submitted.set(true);
    this.serverError.set('');
    const start = this.startDate();
    const end = this.endDate();
    const reason = this.reason().trim();
    if (
      !start ||
      !end ||
      !reason ||
      this.startError() ||
      this.endError() ||
      this.overlapError() ||
      this.creating()
    ) {
      return;
    }

    this.creating.set(true);
    this.adminService
      .createTimeOff({
        doctorId: this.doctor().id,
        startDate: toIsoDateString(start),
        endDate: toIsoDateString(end),
        reason,
      })
      .pipe(finalize(() => this.creating.set(false)))
      .subscribe({
        next: () => {
          this.resetForm();
          this.notify.emit('Descanso programado correctamente.');
          this.loadTimeOffs(true);
        },
        error: (err: AppError) => this.serverError.set(err.message),
      });
  }

  private resetForm(): void {
    this.startDate.set(null);
    this.endDate.set(null);
    this.reason.set('');
    this.submitted.set(false);
  }

  // ── Eliminar / recortar ───────────────────────────────────────────────────
  requestRemoval(item: TimeOffItem): void {
    this.pendingRemoval.set({ item, mode: item.removeMode });
  }

  cancelRemoval(): void {
    this.pendingRemoval.set(null);
  }

  confirmRemoval(): void {
    const pending = this.pendingRemoval();
    if (!pending || this.processingId()) return;
    this.pendingRemoval.set(null);
    this.processingId.set(pending.item.id);
    this.adminService
      .deleteTimeOff(pending.item.id)
      .pipe(finalize(() => this.processingId.set(null)))
      .subscribe({
        next: () => {
          this.notify.emit(
            pending.mode === 'trim'
              ? 'Descanso terminado. Desde hoy se puede agendar.'
              : 'Descanso eliminado.'
          );
          this.loadTimeOffs(true);
        },
        error: (err: AppError) => this.listError.set(err.message),
      });
  }

  // ── Cierre ────────────────────────────────────────────────────────────────
  close(): void {
    if (this.closing()) return;
    this.closing.set(true);
    this.closeTimer = setTimeout(() => this.closed.emit(), CLOSE_ANIMATION_MS);
  }

  toggleHistory(): void {
    this.historyOpen.update((open) => !open);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  private toItem(t: TimeOffDto): TimeOffItem {
    const start = parseLocalDateString(t.startDate);
    const end = parseLocalDateString(t.endDate);
    const days = diffDays(start, end) + 1;
    const removeMode: RemoveMode = start >= this.today ? 'delete' : 'trim';
    const takenDays =
      removeMode === 'trim'
        ? Math.min(days, Math.max(0, diffDays(start, this.today)))
        : 0;
    return {
      ...t,
      label: formatDateRangeEs(t.startDate, t.endDate),
      month: getMonthShort(start).toUpperCase(),
      day: String(start.getDate()).padStart(2, '0'),
      days,
      isOngoing: t.status === 'EN_CURSO',
      removeMode,
      takenDays,
      remainingDays: Math.max(0, days - takenDays),
      progress: Math.round((takenDays / days) * 100),
    };
  }
}

// ── Utilidades de fecha (hora local) ────────────────────────────────────────
function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
}

function diffDays(from: Date, to: Date): number {
  return Math.round(
    (startOfDay(to).getTime() - startOfDay(from).getTime()) / DAY_MS
  );
}
