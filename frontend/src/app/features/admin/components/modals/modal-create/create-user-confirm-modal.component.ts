import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import {
  LucideCalendar,
  LucideStethoscope,
  LucideUserPlus,
  LucideX,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../../design-system/atoms/button/button.component';
import { FormatoPipe } from '../../../../../shared/pipes/formatoPipe';
import { FormErrors } from '../../../models/interfaces/FormErrors';
import { UserForm } from '../../../models/interfaces/UserForm';

@Component({
  selector: 'app-create-user-confirm-modal',
  templateUrl: './create-user-confirm-modal.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideStethoscope,
    LucideUserPlus,
    FormatoPipe,
    ButtonComponent,
    LucideX,
  ],
})
export class CreateUserConfirmModalComponent {
  @Input() userForm!: UserForm;

  @Input() errors: FormErrors = {};

  @Input() hasDoctorRole = false;
  @Input() hasSchedulerRole = false;
  @Input() dayLabels: Record<number, string> = {};

  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();
}
