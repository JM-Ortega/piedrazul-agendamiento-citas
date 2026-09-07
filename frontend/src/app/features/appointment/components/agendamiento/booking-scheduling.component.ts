import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatNativeDateModule, MAT_DATE_LOCALE } from '@angular/material/core';
import { DateAdapter } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { LucideCalendar } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { CustomDateAdapter } from '../../../../design-system/molecules/datepicker/customDateAdapter';
import {
  SelectComponent,
  SelectOption,
} from '../../../../design-system/atoms/select/select.component';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { parseLocalDateString } from '../../../../shared/helpers/transform-date-local';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

/**
 * Pantalla única de agendamiento. Dependiendo del contexto:
 * - `MANUAL`: agendador/médico selecciona especialidad, médico, fecha y hora.
 * - `AUTONOMO`: paciente selecciona médico, fecha y hora.
 */
@Component({
  selector: 'app-booking-scheduling',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    LucideCalendar,
    MatDatepickerModule,
    MatNativeDateModule,
    ButtonComponent,
    SelectComponent,
  ],
  providers: [
    { provide: MAT_DATE_LOCALE, useValue: 'es-CO' },
    { provide: DateAdapter, useClass: CustomDateAdapter },
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-scheduling.component.html',
})
export class BookingSchedulingComponent implements OnInit {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  /**
   * Carga las fechas disponibles del médico apenas `selectedDoctorId` tiene
   * un valor, sin importar si se seleccionó manualmente en el `<app-select>`
   * o llegó precargado (contexto `doctor` vía `prefillDoctorId`).
   */
  constructor() {
    effect(() => {
      const doctorId = this.state.selectedDoctorId();
      if (doctorId) {
        this.loadAvailableDateSlots(doctorId);
      }
    });
  }

  ngOnInit(): void {
    window.scrollTo(0, 0);
  }

  noSlotsAvailable = false;
  errorMessageSlots = '';
  globalErrorMessageSlots = signal('');

  advance = output<void>();
  back = output<void>();

  doctorOptions = computed<SelectOption[]>(() =>
    this.state.doctors().map((d) => ({ value: d.id, label: d.name }))
  );

  specialtyOptions = computed<SelectOption[]>(() => {
    const formatoPipe = new FormatoPipe();
    return this.state
      .specialtyOptionsForSelectedDoctor()
      .map((s) => ({ value: s, label: formatoPipe.transform(s) }));
  });

  private readonly parsedAvailableDates = computed(() =>
    this.state
      .availableDateSlots()
      .map((d) => parseLocalDateString(d.date))
      .sort((a, b) => a.getTime() - b.getTime())
  );

  readonly dateFilter = computed(() => {
    const availableDates = this.state.availableDatesSet();
    return (date: Date | null): boolean => {
      if (!date) return false;
      return availableDates.has(this.state.formatLocalDate(date));
    };
  });

  readonly minDate = computed(
    () => this.parsedAvailableDates()[0] ?? new Date()
  );

  readonly maxDate = computed(() => {
    const dates = this.parsedAvailableDates();
    return dates.length ? dates[dates.length - 1] : new Date();
  });

  readonly startAt = computed(
    () => this.state.selectedDate() ?? this.minDate()
  );

  onDoctorChange(doctorId: string): void {
    this.state.selectDoctor(doctorId);
    this.resetSlotState();
  }

  onSpecialtyChange(specialty: string): void {
    this.state.selectSpecialty(specialty);
    this.resetSlotState();
  }

  onDateSelected(date: Date | null): void {
    this.state.selectDate(date);
    this.resetSlotState();

    if (!date) return;

    const dateStr = this.state.formatLocalDate(date);
    let slots = this.state.slotsForDate(dateStr);

    if (this.state.isSchedulerContext() || this.state.isDoctorContext()) {
      const today = this.state.formatLocalDate(new Date());
      if (dateStr === today) {
        const cutoff = new Date(Date.now() + 10 * 60 * 1000);
        const cutoffStr = `${String(cutoff.getHours()).padStart(2, '0')}:${String(cutoff.getMinutes()).padStart(2, '0')}`;
        slots = slots.filter((s) => s >= cutoffStr);
      }
    }

    this.state.availableSlots.set(slots);
    if (!slots || slots.length === 0) {
      this.noSlotsAvailable = true;
      this.errorMessageSlots = 'No hay horarios disponibles para esta fecha.';
    }
  }

  goToConfirm(): void {
    this.advance.emit();
  }

  goBack(): void {
    this.back.emit();
  }

  private loadAvailableDateSlots(doctorId: string): void {
    if (!doctorId) return;
    this.citaService.getAvailableDateSlots(doctorId).subscribe({
      next: (slots) => this.state.availableDateSlots.set(slots),
      error: (err: AppError) => {
        this.state.availableDateSlots.set([]);
        this.globalErrorMessageSlots.set(err.message);
      },
    });
  }

  private resetSlotState(): void {
    this.noSlotsAvailable = false;
    this.errorMessageSlots = '';
    this.globalErrorMessageSlots.set('');
  }
}
