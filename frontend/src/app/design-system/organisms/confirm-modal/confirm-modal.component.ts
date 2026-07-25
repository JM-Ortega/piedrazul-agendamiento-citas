import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ButtonComponent, ButtonVariant } from '../../atoms/button.component';

@Component({
  selector: 'app-confirm-modal',
  standalone: true,
  imports: [ButtonComponent],
  templateUrl: './confirm-modal.component.html',
})
export class ConfirmModalComponent {
  @Input() visible = false;
  @Input() title = '¿Está seguro?';
  @Input() message = 'Esta acción no se puede deshacer.';
  @Input() confirmLabel = 'Confirmar';
  @Input() cancelLabel = 'Volver';
  @Input() confirmVariant: ButtonVariant = 'danger';
  @Input() iconBg = 'bg-red-100';

  @Output() confirmed = new EventEmitter<void>();
  @Output() dismissed = new EventEmitter<void>();

  onDismiss(): void {
    this.dismissed.emit();
  }

  onConfirm(): void {
    this.confirmed.emit();
  }
}
