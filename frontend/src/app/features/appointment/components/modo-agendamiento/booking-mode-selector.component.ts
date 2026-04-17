import { Component, inject, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LucideAngularModule, Stethoscope, UserSearch } from 'lucide-angular';
import { BookingStateService } from '../../booking-state.service';
import { BookingMode } from '../../../../models/types/bookingMode.type';

/**
 * BookingModeSelectorComponent
 *
 * Responsabilidad única: mostrar las dos tarjetas de selección de modo
 * (Por Especialidad / Por Especialidad y Médico) y notificar la elección.
 *
 * No contiene lógica de negocio; delega todo al servicio de estado.
 */
@Component({
  selector: 'app-booking-mode-selector',
  standalone: true,
  imports: [CommonModule, LucideAngularModule],
  templateUrl: './booking-mode-selector.component.html',
})
export class BookingModeSelectorComponent {

  readonly Stethoscope = Stethoscope;
  readonly UserSearch  = UserSearch;

  protected state = inject(BookingStateService);

  /** Notifica al orquestador el modo elegido para que inicie la carga de datos. */
  modeSelected = output<BookingMode>();

  selectMode(mode: BookingMode): void {
    this.state.bookingMode.set(mode);
    this.modeSelected.emit(mode);
  }
}