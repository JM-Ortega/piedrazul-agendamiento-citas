import { CommonModule } from '@angular/common';
import { Component, inject, output } from '@angular/core';
import { LucideAngularModule, Stethoscope, UserSearch } from 'lucide-angular';
import { BookingMode } from '../../models/types/bookingMode.type';
import { BookingStateService } from '../../services/booking-state.service';

/**
 * Mostrar las dos tarjetas de selección de modo
 * (Por Especialidad / Por Especialidad y Médico) y notificar la elección.
 */
@Component({
  selector: 'app-booking-mode-selector',
  standalone: true,
  imports: [CommonModule, LucideAngularModule],
  templateUrl: './booking-mode-selector.component.html',
})
export class BookingModeSelectorComponent {
  readonly Stethoscope = Stethoscope;
  readonly UserSearch = UserSearch;

  protected state = inject(BookingStateService);

  modeSelected = output<BookingMode>();

  selectMode(mode: BookingMode): void {
    this.state.bookingMode.set(mode);
    this.modeSelected.emit(mode);
  }
}
