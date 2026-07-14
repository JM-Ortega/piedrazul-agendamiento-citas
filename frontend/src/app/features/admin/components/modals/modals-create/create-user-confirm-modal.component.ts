import {
  Component,
  EventEmitter,
  Input,
  Output,
  ChangeDetectionStrategy,
} from '@angular/core';
import {
  Calendar,
  CircleAlert,
  LucideAngularModule,
  Stethoscope,
  UserPlus,
} from 'lucide-angular';
import { FormatoPipe } from '../../../../../shared/pipes/formatoPipe';
import { FormErrors } from '../../../models/interfaces/FormErrors';
import { UserForm } from '../../../models/interfaces/UserForm';

@Component({
  selector: 'app-create-user-confirm-modal',
  templateUrl: './create-user-confirm-modal.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [LucideAngularModule, FormatoPipe],
})
export class CreateUserConfirmModalComponent {
  readonly UserPlus = UserPlus;
  readonly CircleAlert = CircleAlert;
  readonly Stethoscope = Stethoscope;
  readonly Calendar = Calendar;
  @Input() userForm!: UserForm;

  @Input() errors: FormErrors = {};

  @Input() hasDoctorRole = false;
  @Input() hasSchedulerRole = false;
  @Input() dayLabels: Record<number, string> = {};

  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();
}
