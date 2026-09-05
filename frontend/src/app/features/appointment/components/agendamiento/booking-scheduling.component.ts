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
import { BookingStateService } from '../../services/booking-state.service';
import { CalendarService } from '../../services/calendar.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

/**
 * Pantalla única de agendamiento: reemplaza a los antiguos
 * booking-specialty-selector y booking-schedule-selector.
 *
 * Muestra, según el contexto, el selector de médico (y de especialidad
 * cuando aplica), y a medida que se completa cada dato va revelando el
 * calendario y luego las horas disponibles en la misma pantalla.
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
  private calendarService = inject(CalendarService);

  ngOnInit(): void {
    window.scrollTo(0, 0);
  }

  // Estado local: errores y disponibilidad de slots
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

  readonly dateFilter = computed(() => {
    const doctor = this.state.selectedDoctor();
    if (!doctor) return () => false;
    return this.calendarService.buildDateFilter(
      doctor,
      this.state.isSchedulerContext() || this.state.isDoctorContext()
    );
  });

  readonly minDate = computed(() => {
    const doctor = this.state.selectedDoctor();
    return this.calendarService.getMinDate(
      doctor,
      this.state.isSchedulerContext() || this.state.isDoctorContext()
    );
  });

  readonly maxDate = computed(() => {
    const doctor = this.state.selectedDoctor();
    if (!doctor) return this.calendarService.getMinDate(doctor);
    return this.calendarService.getMaxDate(doctor);
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

    this.citaService
      .getAvailableSlots(this.state.selectedDoctorId(), dateStr)
      .subscribe({
        next: (slots) => {
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
  }
}
