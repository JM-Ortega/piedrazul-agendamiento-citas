import {
  ChangeDetectionStrategy,
  Component,
  inject,
  output,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideUserSearch } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { BookingStateService } from '../../services/booking-state.service';

/**
 * Permite al usuario elegir una especialidad y,
 * si el modo es 'specialty-doctor', también un médico de esa especialidad.
 */
@Component({
  selector: 'app-booking-specialty-selector',
  standalone: true,
  imports: [FormsModule, LucideUserSearch, FormatoPipe, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-specialty-selector.component.html',
})
export class BookingSpecialtySelectorComponent {
  protected state = inject(BookingStateService);

  advance = output<void>();
  back = output<void>();
  specialtyChanged = output<string>();

  onSpecialtyChange(specialty: string): void {
    this.state.selectedSpecialty.set(specialty);
    this.state.selectedDoctorId.set('');
    this.state.selectedDoctorName.set('');
    this.state.assignedDoctor.set(null);

    if (!specialty) return;

    if (this.state.bookingMode() === 'specialty') {
      const match = this.state
        .specialtiesWithDoctor()
        .find((s) => s.specialty === specialty);
      this.state.assignedDoctor.set(match ?? null);
    } else {
      this.state.doctorsBySpecialty.set([]);
      this.specialtyChanged.emit(specialty);
    }
  }

  onDoctorChange(doctorId: string): void {
    this.state.selectedDoctorId.set(doctorId);
    const doc = this.state.doctorsBySpecialty().find((d) => d.id === doctorId);
    this.state.selectedDoctorName.set(doc?.name ?? '');
  }

  goToSchedule(): void {
    this.state.resetScheduleState();
    this.advance.emit();
  }

  goBack(): void {
    this.back.emit();
  }
}
