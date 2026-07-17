import {
  Component,
  input,
  output,
  ChangeDetectionStrategy,
} from '@angular/core';
import { LucidePowerOff, LucideSave, LucideX } from '@lucide/angular';
import { Doctor } from '../../../../../shared/models/interfaces/doctor.model';

@Component({
  selector: 'app-admin-modals',
  templateUrl: './admin-modals.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideX, LucideSave, LucidePowerOff],
})
export class AdminModalsComponent {
  // Modal: confirmar toggle
  showConfirmModal = input<boolean>(false);
  doctorToToggle = input<Doctor | null>(null);
  confirmToggle = output<void>();
  closeToggleModal = output<void>();

  // Modal: forzar deshabilitación
  showForceModal = input<boolean>(false);
  forceModalMessage = input<string>('');
  confirmForceDisable = output<void>();
  cancelForceDisable = output<void>();

  // Modal: error al guardar
  showErrorModal = input<boolean>(false);
  errorGuardado = input<string>('');
  closeErrorModal = output<void>();
}
