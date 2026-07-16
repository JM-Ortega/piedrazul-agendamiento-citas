import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideCalendar, LucideCircleAlert, LucideStethoscope],
})
export class CreateUserRolesComponent {
  @Input() selectedRoles: Role[] = [];
  @Input() errorRoles?: string;
  @Output() roleToggled = new EventEmitter<Role>();
}
