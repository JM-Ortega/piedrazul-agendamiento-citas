import {
  Component,
  inject,
  output,
  ChangeDetectionStrategy,
} from '@angular/core';
import { LucideStethoscope, LucideUserSearch } from '@lucide/angular';
import { BookingMode } from '../../models/types/bookingMode.type';
import { BookingStateService } from '../../services/booking-state.service';

/**
 * Mostrar las dos tarjetas de selección de modo
 * (Por Especialidad / Por Especialidad y Médico) y notificar la elección.
 */
@Component({
  selector: 'app-booking-mode-selector',
  standalone: true,
  imports: [LucideStethoscope, LucideUserSearch],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './booking-mode-selector.component.html',
})
export class BookingModeSelectorComponent {
  protected state = inject(BookingStateService);

  modeSelected = output<BookingMode>();

  selectMode(mode: BookingMode): void {
    this.state.bookingMode.set(mode);
    this.modeSelected.emit(mode);
  }
}
