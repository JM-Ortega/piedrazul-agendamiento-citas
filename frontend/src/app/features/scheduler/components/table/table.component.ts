import {
  Component,
  EventEmitter,
  HostListener,
  Input,
  Output,
  signal,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCalendarSync,
  LucideChevronDown,
  LucideClipboardList,
  LucideClock,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import {
  APPOINTMENT_STATUS_CLASSES,
  APPOINTMENT_STATUS_LABELS,
} from '../../../../shared/helpers/appointmentStatus';
import { formatLongDateEs } from '../../../../shared/helpers/dateFormat';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

export type AppointmentTableView = 'today' | 'all';
export type AppointmentAction = 'CANCELAR' | 'REAGENDAR';

/** Ancho del menú (w-44 = 11rem = 176px) y alto aproximado (2 opciones). */
const MENU_WIDTH = 176;
const MENU_HEIGHT_ESTIMATE = 110;
const MENU_GAP = 8;

interface MenuPosition {
  left: number;
  top: number | null;
  bottom: number | null;
}

@Component({
  selector: 'app-appointment-table',
  standalone: true,
  imports: [
    LucideCalendar,
    LucideCalendarSync,
    LucideChevronDown,
    LucideClipboardList,
    LucideClock,
    LucideUser,
    LucideX,
    FormatoPipe,
    ButtonComponent,
  ],
  templateUrl: './table.component.html',
})
export class AppointmentTableComponent {
  @Input() view: AppointmentTableView = 'all';
  @Input() results: AppointmentsPatient[] = [];

  @Output() cancelRequested = new EventEmitter<string>();
  /** Emite la cita completa: el reagendamiento necesita paciente, médico y especialidad. */
  @Output() rescheduleRequested = new EventEmitter<AppointmentsPatient>();

  /** Id de la cita cuyo menú de acciones está abierto (null = ninguno). */
  readonly openActionsId = signal<string | null>(null);

  /**
   * Posición del menú en coordenadas de la ventana. Se usa `position: fixed`
   * porque la tabla vive dentro de un contenedor `overflow-x-auto`, que
   * recortaría un menú `absolute` en las últimas filas.
   */
  readonly menuPosition = signal<MenuPosition>({
    left: 0,
    top: 0,
    bottom: null,
  });

  formatDate(dateStr: string): string {
    return formatLongDateEs(dateStr);
  }

  statusLabel(s: string): string {
    return (
      APPOINTMENT_STATUS_LABELS[s as AppointmentsPatient['appointmentState']] ??
      s
    );
  }

  statusColor(s: string): string {
    return (
      APPOINTMENT_STATUS_CLASSES[
        s as AppointmentsPatient['appointmentState']
      ] ?? ''
    );
  }

  toggleActions(id: string, trigger: HTMLElement): void {
    if (this.openActionsId() === id) {
      this.closeActions();
      return;
    }

    const rect = trigger.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const openUp = spaceBelow < MENU_HEIGHT_ESTIMATE + MENU_GAP;

    this.menuPosition.set({
      // Alineado al borde derecho del botón, sin salirse por la izquierda.
      left: Math.max(MENU_GAP, rect.right - MENU_WIDTH),
      top: openUp ? null : rect.bottom + MENU_GAP,
      bottom: openUp ? window.innerHeight - rect.top + MENU_GAP : null,
    });
    this.openActionsId.set(id);
  }

  selectAction(apt: AppointmentsPatient, action: AppointmentAction): void {
    this.closeActions();
    if (action === 'CANCELAR') {
      this.cancelRequested.emit(apt.idAppointment);
    } else {
      this.rescheduleRequested.emit(apt);
    }
  }

  closeActions(): void {
    this.openActionsId.set(null);
  }

  /** Cierra el menú al hacer clic fuera del botón o del propio menú. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.openActionsId()) return;
    const target = event.target as HTMLElement | null;
    if (!target?.closest('[data-appointment-actions]')) {
      this.closeActions();
    }
  }

  /** Un menú `fixed` quedaría desalineado al hacer scroll o redimensionar. */
  @HostListener('window:scroll')
  @HostListener('window:resize')
  @HostListener('document:keydown.escape')
  onViewportChange(): void {
    if (this.openActionsId()) this.closeActions();
  }
}
