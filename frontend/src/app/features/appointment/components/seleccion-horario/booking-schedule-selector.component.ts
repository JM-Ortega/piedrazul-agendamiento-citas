import { CommonModule } from '@angular/common';
import {
  Component,
  computed,
  inject,
  output,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatNativeDateModule } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { LucideUserSearch, LucideCalendar } from '@lucide/angular';
import { BookingStateService } from '../../services/booking-state.service';
import { CalendarService } from '../../services/calendar.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

/**
 * Permite al usuario elegir una fecha y hora
 * disponible para la cita, dado el médico ya seleccionado.
 *
 * Carga los slots disponibles llamando al servicio cuando cambia la fecha.
 * Escribe la selección en BookingStateService.
 */
@Component({
  selector: 'app-booking-schedule-selector',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    LucideUserSearch,
    LucideCalendar,
    MatDatepickerModule,
    MatInputModule,
    MatFormFieldModule,
    MatNativeDateModule,
    FormatoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-schedule-selector.component.html',
})
export class BookingScheduleSelectorComponent {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);
  private calendarService = inject(CalendarService);

  // Estado local: errores y disponibilidad de slots
  noSlotsAvailable = false;
  errorMessageSlots = '';

  advance = output<void>();
  back = output<void>();

  readonly dateFilter = computed(() => {
    const doctor = this.state.effectiveDoctor();
    if (!doctor) return () => false;
    return this.calendarService.buildDateFilter(
      doctor,
      this.state.isSchedulerContext() || this.state.isDoctorContext()
    );
  });

  readonly minDate = computed(() =>
    this.calendarService.getMinDate(
      this.state.isSchedulerContext() || this.state.isDoctorContext()
    )
  );

  readonly maxDate = computed(() => {
    const doctor = this.state.effectiveDoctor();
    if (!doctor) return this.calendarService.getMinDate();
    return this.calendarService.getMaxDate(doctor);
  });

  onDateSelected(date: Date | null): void {
    this.state.selectedDate.set(date);
    this.state.selectedTime.set('');
    this.state.availableSlots.set([]);
    this.noSlotsAvailable = false;
    this.errorMessageSlots = '';

    if (!date) return;

    const dateStr = this.state.formatLocalDate(date);

    this.citaService
      .getAvailableSlots(this.state.effectiveDoctorId(), dateStr)
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
        error: (err) => {
          this.state.availableSlots.set([]);
          this.noSlotsAvailable = true;

          if (err.status === 0) {
            this.errorMessageSlots =
              'No se pudo conectar con el servidor. Intente más tarde.';
            return;
          }
          const detail = err.error?.detail;
          this.errorMessageSlots =
            detail || 'Error al cargar los horarios disponibles.';
        },
      });
  }

  goToConfirm(): void {
    this.advance.emit();
  }

  goBack(): void {
    this.back.emit();
  }
}
