import {
  Component,
  inject,
  output,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideAngularModule, UserSearch } from 'lucide-angular';
import { BookingStateService } from '../../services/booking-state.service';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';

/**
 * Permite al usuario elegir una especialidad y,
 * si el modo es 'specialty-doctor', también un médico de esa especialidad.
 */
@Component({
  selector: 'app-booking-specialty-selector',
  standalone: true,
  imports: [FormsModule, LucideAngularModule, FormatoPipe],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './booking-specialty-selector.component.html',
})
export class BookingSpecialtySelectorComponent {
  readonly UserSearch = UserSearch;

  protected state = inject(BookingStateService);

  advance = output<void>();
  back = output<void>();

  readonly filteredSpecialties = computed(() => {
    const specialties = this.state.uniqueSpecialties();
    return specialties;
  });

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

  specialtyChanged = output<string>();

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
    this.state.resetSpecialtyState();
    this.back.emit();
  }
}
