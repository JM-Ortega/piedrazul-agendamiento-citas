import { Component, inject, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideAngularModule, UserSearch } from 'lucide-angular';
import { BookingStateService } from '../../booking-state.service';

/**
 * BookingSpecialtySelectorComponent
 *
 * Responsabilidad única: permitir al usuario elegir una especialidad y,
 * si el modo es 'specialty-doctor', también un médico de esa especialidad.
 *
 * Lee las listas de especialidades/médicos desde BookingStateService
 * (ya precargadas por el orquestador) y escribe la selección de vuelta
 * en el mismo servicio.
 */
@Component({
  selector: 'app-booking-specialty-selector',
  standalone: true,
  imports: [CommonModule, FormsModule, LucideAngularModule],
  templateUrl: './booking-specialty-selector.component.html',
})
export class BookingSpecialtySelectorComponent {

  readonly UserSearch = UserSearch;

  protected state = inject(BookingStateService);

  /** Avanzar al step de horario. */
  advance = output<void>();

  /** Retroceder: al step de paciente (agendador) o al selector de modo (paciente). */
  back = output<void>();

  onSpecialtyChange(specialty: string): void {
    this.state.selectedSpecialty.set(specialty);
    this.state.selectedDoctorId.set('');
    this.state.selectedDoctorName.set('');
    this.state.assignedDoctor.set(null);

    if (!specialty) return;

    if (this.state.bookingMode() === 'specialty') {
      const match = this.state.specialtiesWithDoctor().find(s => s.specialty === specialty);
      this.state.assignedDoctor.set(match ?? null);
    } else {
      this.state.doctorsBySpecialty.set([]);
      // La carga de médicos se hace en el orquestador para mantener
      // la lógica de red fuera de este componente de presentación.
      // Notificamos vía el advance para que el orquestador decida.
      // NOTA: para la carga reactiva de médicos al cambiar especialidad
      // usamos un output específico que el orquestador escucha.
      this.specialtyChanged.emit(specialty);
    }
  }

  /** Notifica al orquestador que cambió la especialidad (para cargar médicos). */
  specialtyChanged = output<string>();

  onDoctorChange(doctorId: string): void {
    this.state.selectedDoctorId.set(doctorId);
    const doc = this.state.doctorsBySpecialty().find(d => d.id === doctorId);
    this.state.selectedDoctorName.set(doc?.name ?? '');
  }

  goToSchedule(): void {
    this.state.resetScheduleState();
    this.advance.emit();
  }

  goBack(): void {
    this.state.resetSpecialtyState();
    this.back.emit();
  }
}