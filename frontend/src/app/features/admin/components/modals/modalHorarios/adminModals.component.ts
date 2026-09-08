import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import { LucideCircleAlert, LucideSave } from '@lucide/angular';

import { ConfirmModalComponent } from '../../../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { Doctor } from '../../../../../shared/models/interfaces/doctor.model';

@Component({
  selector: 'app-admin-modals',
  templateUrl: './adminModals.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideCircleAlert, LucideSave, ConfirmModalComponent],
})
export class AdminModalsComponent {
  // Modal: confirmar toggle
  showConfirmModal = input<boolean>(false);
  doctorToToggle = input<Doctor | null>(null);
  confirmToggle = output<void>();
  closeToggleModal = output<void>();

  // Modal: error al guardar
  showErrorModal = input<boolean>(false);
  errorGuardado = input<string>('');
  closeErrorModal = output<void>();
}
