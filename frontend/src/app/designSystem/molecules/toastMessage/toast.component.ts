import { Component, Input } from '@angular/core';
import { LucideCheck, LucideAlertCircle } from '@lucide/angular';

export type ToastType = 'success' | 'error';

@Component({
  selector: 'app-toast',
  standalone: true,
  imports: [LucideCheck, LucideAlertCircle],
  templateUrl: './toast.component.html',
})
export class ToastComponent {
  @Input() message = '';
  @Input() type: ToastType | null = null;
}
