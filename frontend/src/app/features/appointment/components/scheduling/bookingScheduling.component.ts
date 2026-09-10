import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { BookingStateService } from '../../services/bookingState.service';
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
  templateUrl: './bookingScheduling.component.html',
})
export class BookingSchedulingComponent implements OnInit {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  ngOnInit(): void {
    window.scrollTo(0, 0);
  }

  noSlotsAvailable = false;
  errorMessageSlots = '';
  globalErrorMessageSlots = signal('');
  loadingSlots = signal(false);

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

  /** Fechas disponibles del médico elegido, parseadas y ordenadas. */
  private readonly parsedAvailableDates = computed(() =>
    (this.state.selectedDoctor()?.availableDates ?? [])
      .map((d) => parseLocalDateString(d))
      .sort((a, b) => a.getTime() - b.getTime())
  );

  private readonly availableDatesSet = computed(
    () => new Set(this.state.selectedDoctor()?.availableDates ?? [])
  );

  readonly dateFilter = computed(() => {
    const availableDates = this.availableDatesSet();
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

  /** Al elegir fecha, pide los horarios disponibles de esa fecha puntual. */
  onDateSelected(date: Date | null): void {
    this.state.selectDate(date);
    this.resetSlotState();

    if (!date) return;

    const doctorId = this.state.selectedDoctorId();
    const dateStr = this.state.formatLocalDate(date);

    this.loadingSlots.set(true);
    this.citaService.getAvailableSlots(doctorId, dateStr).subscribe({
      next: (slots) => {
        this.loadingSlots.set(false);
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
          this.errorMessageSlots =
            'No hay horarios disponibles para esta fecha.';
        }
      },
      error: (err: AppError) => {
        this.loadingSlots.set(false);
        this.state.availableSlots.set([]);
        this.globalErrorMessageSlots.set(err.message);
      },
    });
  }

  goToConfirm(): void {
    this.advance.emit();
  }

  goBack(): void {
    this.back.emit();
  }

  private resetSlotState(): void {
    this.noSlotsAvailable = false;
    this.errorMessageSlots = '';
    this.globalErrorMessageSlots.set('');
    this.loadingSlots.set(false);
  }
}
