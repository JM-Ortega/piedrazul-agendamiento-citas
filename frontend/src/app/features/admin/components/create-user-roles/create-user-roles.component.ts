import {
  Component,
  EventEmitter,
  Input,
  Output,
  ChangeDetectionStrategy,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCircleAlert,
  LucideStethoscope,
} from '@lucide/angular';

type Role = 'doctor' | 'scheduler';

@Component({
  selector: 'app-create-user-roles',
  templateUrl: './create-user-roles.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [LucideCalendar, LucideCircleAlert, LucideStethoscope],
})
export class CreateUserRolesComponent {
  @Input() selectedRoles: Role[] = [];
  @Input() errorRoles?: string;
  @Output() roleToggled = new EventEmitter<Role>();
}
